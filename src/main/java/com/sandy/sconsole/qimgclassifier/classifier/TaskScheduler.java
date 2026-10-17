package com.sandy.sconsole.qimgclassifier.classifier;

import com.sandy.sconsole.qimgclassifier.ai.AIInvoker;
import com.sandy.sconsole.qimgclassifier.ai.AIProcessor;
import com.sandy.sconsole.qimgclassifier.model.AIAgent;
import com.sandy.sconsole.qimgclassifier.model.QTask;
import com.sandy.sconsole.qimgclassifier.model.Subject;
import com.sandy.sconsole.qimgclassifier.model.Topic;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Feeds the questions of every source to the AI processors, with one worker
 * thread per processor slot. When a slot frees up, its worker takes the best
 * waiting question for its processor:
 * <ul>
 *   <li>Chemistry goes to OpenRouter. Gemma takes it only when OpenRouter is
 *       not configured, or while OpenRouter is held back by a rate limit
 *       (a 429, the daily limit, being unreachable; not per-minute
 *       pacing).</li>
 *   <li>Physics and Maths go to Gemma. OpenRouter takes them only when no
 *       Chemistry is waiting for it and no Gemma slot is free to take
 *       them.</li>
 *   <li>A Gemma reply whose top topic fails the association gate is sent to
 *       OpenRouter for a second opinion. OpenRouter's reply is stored instead,
 *       unless every OpenRouter attempt fails, when Gemma's is kept.</li>
 * </ul>
 * Retries come before new questions; otherwise questions run in the order they
 * were submitted, which is source-list order. Each question may make
 * {@code 1 + max.retries} attempts per processor. An HTTP 429 hands the
 * attempt back without using the budget, and pauses the model that reported
 * it (the next attempt goes to the processor's next model), or the whole
 * processor when the limit covers all its models. A backend that cannot be
 * reached is paused as a whole the same way, so a host that is down does
 * not fail every queued question in turn.
 * <p>
 * A second opinion waits for OpenRouter however long it is held back (until
 * the next 00:00 UTC after the daily limit); stopping the batch stores
 * Gemma's reply instead.
 * <p>
 * Every question carries a {@code keepGoing} check. Once it turns false, a
 * question that has not started is dropped, and a started one is finished
 * with what it has: Gemma's reply if it was waiting for a second opinion,
 * else its last failure. Thread-safe.
 */
@Slf4j
public class TaskScheduler {

    /** Longest single wait for work, so stops and rate windows are seen. */
    private static final long MAX_WAIT_MS = 1000 ;

    private final AIInvoker          aiInvoker ;
    private final TaskOutcomes       classifier ;
    private final List<AIProcessor>  processors ;

    /** Index of the OpenRouter processor in {@link #processors}; -1 if none. */
    private final int openRouter ;
    private final int gemma ;

    /**
     * Jobs per processor between taking an attempt and leaving
     * {@link #running}; guarded by lock.
     */
    private final int[] busy ;

    /** Guards {@link #queue}, {@link #running}, {@link #held} and counters. */
    private final Object lock = new Object() ;

    private final List<Job>    queue   = new ArrayList<>() ;
    private final Set<Job>     running = new HashSet<>() ;
    private final List<Thread> workers = new ArrayList<>() ;

    /**
     * Tasks taken out of scheduling during manual association, with the job
     * to put back on release; the value is null when nothing was queued.
     */
    private final Map<QTask,Job> held = new HashMap<>() ;

    /** Batch jobs queued or running; the batch is done at 0. */
    private int batchOutstanding ;

    /** Order of submission; jobs put at the front count down from 0. */
    private long backSeq ;
    private long frontSeq ;

    private volatile boolean shutdown ;

    /**
     * True while OpenRouter is held back by a rate limit, so Gemma may take
     * Chemistry; refreshed before each routing decision.
     */
    private volatile boolean openRouterHeldBack ;

    /**
     * One question's way through the scheduler. Touched under {@link #lock}
     * while queued, and only by its worker while running.
     */
    private static final class Job {

        final QTask           task ;
        final BooleanSupplier keepGoing ;
        final boolean         batch ;
        final int[]           used ;

        long seq ;
        int  attempts ;

        /** Processor the running attempt is on; -1 when there is none. */
        int runningOn = -1 ;

        /** Model the running attempt asks; chosen when it is taken. */
        String model ;

        boolean started ;
        String  lastFailure = "unknown error" ;

        AIInvoker.PreparedImage image ;

        /**
         * Gemma's reply while it waits for OpenRouter's second opinion; null
         * otherwise.
         */
        List<Topic> gemmaTopics ;

        /** The Gemma model that gave {@link #gemmaTopics}. */
        String gemmaModel ;

        Job( QTask task, BooleanSupplier keepGoing, boolean batch,
             int processorCount, long seq ) {
            this.task      = task ;
            this.keepGoing = keepGoing ;
            this.batch     = batch ;
            this.used      = new int[ processorCount ] ;
            this.seq       = seq ;
        }

        boolean awaitsSecondOpinion() { return gemmaTopics != null ; }
    }

    /** A job handed to a worker; {@code attempt} if one was counted for it. */
    private record Taken( Job job, boolean attempt ) {}

    public TaskScheduler( AIInvoker aiInvoker, TaskOutcomes classifier ) {

        this.aiInvoker  = aiInvoker ;
        this.classifier = classifier ;
        this.processors = aiInvoker.getProcessors() ;
        this.openRouter = indexOf( AIAgent.OPENROUTER ) ;
        this.gemma      = indexOf( AIAgent.GEMMA ) ;
        this.busy       = new int[ processors.size() ] ;
    }

    /** Start one worker thread per processor slot. Call once. */
    public void start() {

        for( int p = 0 ; p < processors.size() ; p++ ) {
            AIProcessor processor = processors.get( p ) ;
            int         index     = p ;

            for( int slot = 1 ; slot <= processor.getMaxParallel() ; slot++ ) {
                Thread t = new Thread( () -> work( index ),
                        "qclassifier-" + processor.getName() + "-" + slot ) ;
                t.setDaemon( true ) ;
                workers.add( t ) ;
            }
        }
        workers.forEach( Thread::start ) ;
    }

    /**
     * Queue a batch's tasks behind everything queued, in order. Tasks already
     * queued or running are skipped; a held task is queued when it is
     * released.
     */
    public void submitBatch( List<QTask> tasks, BooleanSupplier keepGoing ) {

        synchronized( lock ) {
            for( QTask task : tasks ) {
                if( isQueuedOrRunning( task ) ) continue ;

                Job job = new Job( task, keepGoing, true, processors.size(),
                                   backSeq++ ) ;
                if( held.containsKey( task ) ) {
                    held.put( task, job ) ;
                    continue ;
                }
                queue.add( job ) ;
                batchOutstanding++ ;
            }
            lock.notifyAll() ;
        }
    }

    /**
     * Queue one task (outside any batch) ahead of the others waiting at its
     * priority. Ignored if the task is already queued, running or held.
     */
    public void submitFirst( QTask task, BooleanSupplier keepGoing ) {

        synchronized( lock ) {
            if( isQueuedOrRunning( task ) || held.containsKey( task ) ) {
                log.debug( "{} is already scheduled - not queueing it again", task ) ;
                return ;
            }
            queue.add( new Job( task, keepGoing, false, processors.size(),
                                --frontSeq ) ) ;
            lock.notifyAll() ;
        }
    }

    /** Block until every batch job has finished or been dropped. */
    public void awaitBatch() throws InterruptedException {
        synchronized( lock ) {
            while( batchOutstanding > 0 ) lock.wait( MAX_WAIT_MS ) ;
        }
    }

    /**
     * Take a task out of scheduling while it is manually associated.
     *
     * @return false if the task is running or between attempts
     */
    public boolean hold( QTask task ) {

        synchronized( lock ) {
            if( running.stream().anyMatch( j -> j.task == task ) ) return false ;

            Job job = queued( task ) ;
            if( job != null ) {
                if( job.started ) return false ;
                queue.remove( job ) ;
                if( job.batch ) batchOutstanding-- ;
                lock.notifyAll() ;
            }
            held.put( task, job ) ;
            return true ;
        }
    }

    /**
     * End a {@link #hold}. A task that was not associated goes back to the
     * front of the queue, if it was queued and its batch is still running.
     *
     * @return true if the task was queued again
     */
    public boolean release( QTask task, boolean associated ) {

        synchronized( lock ) {
            Job job = held.remove( task ) ;
            if( job == null || associated || !job.keepGoing.getAsBoolean() ) {
                return false ;
            }
            job.seq = --frontSeq ;
            queue.add( job ) ;
            if( job.batch ) batchOutstanding++ ;
            lock.notifyAll() ;
            return true ;
        }
    }

    /** Stop the workers, interrupting requests in flight. */
    public void shutdown() {

        shutdown = true ;
        workers.forEach( Thread::interrupt ) ;
        for( Thread t : workers ) {
            try {
                t.join( 10_000 ) ;
            }
            catch( InterruptedException e ) {
                Thread.currentThread().interrupt() ;
                return ;
            }
        }
    }

    // ---- worker --------------------------------------------------------

    private void work( int p ) {

        while( !shutdown ) {
            Taken taken ;
            try {
                taken = take( p ) ;
            }
            catch( InterruptedException e ) {
                continue ;   // shutdown is checked by the loop
            }
            if( taken == null ) continue ;

            try {
                run( taken, p ) ;
            }
            catch( Exception e ) {
                log.error( "Error scheduling {}", taken.job().task, e ) ;
                Job job = taken.job() ;
                done( job, () -> classifier.failed( job.task, job.attempts,
                                                    e.toString() ) ) ;
            }
        }
    }

    /**
     * Wait for the best job for processor {@code p}, counting the attempt
     * against the job's budget and the processor's rate limits. A queued job
     * whose {@code keepGoing} turned false is returned first, with no attempt
     * counted, so it can be wound up.
     *
     * @return null on shutdown
     */
    private Taken take( int p ) throws InterruptedException {

        AIProcessor processor = processors.get( p ) ;
        synchronized( lock ) {
            return takeLocked( p, processor ) ;
        }
    }

    private Taken takeLocked( int p, AIProcessor processor )
            throws InterruptedException {

        while( !shutdown ) {
            Job stopped = firstStopped() ;
            if( stopped != null ) {
                queue.remove( stopped ) ;
                running.add( stopped ) ;
                return new Taken( stopped, false ) ;
            }

            long now         = System.currentTimeMillis() ;
            long availableAt = aiInvoker.availableAt( processor ) ;

            if( availableAt <= now ) {
                Job best = best( p ) ;
                if( best != null ) {
                    queue.remove( best ) ;
                    running.add( best ) ;
                    best.runningOn = p ;
                    busy[p]++ ;
                    best.used[p]++ ;
                    best.attempts++ ;
                    best.model = modelFor( processor, best.used[p] ) ;
                    aiInvoker.recordUse( processor ) ;
                    return new Taken( best, true ) ;
                }
            }
            long waitMs = availableAt > now
                        ? Math.min( MAX_WAIT_MS, availableAt - now )
                        : MAX_WAIT_MS ;
            lock.wait( Math.max( 1, waitMs ) ) ;
        }
        return null ;
    }

    /**
     * The model for a job's {@code n}-th attempt on {@code processor},
     * skipping models paused after a rate limit. If they were all paused
     * since the processor was found free, the usual model is asked anyway.
     */
    private String modelFor( AIProcessor processor, int n ) {
        String model = aiInvoker.modelFor( processor, n ) ;
        return model != null ? model : processor.modelForAttempt( n ) ;
    }

    /**
     * Make the taken job's attempt on processor {@code p}, then route it on.
     */
    private void run( Taken taken, int p ) {

        Job         job       = taken.job() ;
        AIProcessor processor = processors.get( p ) ;

        if( !job.keepGoing.getAsBoolean() ) {
            if( taken.attempt() ) {
                job.used[p]-- ;
                job.attempts-- ;
            }
            windUp( job ) ;
            return ;
        }
        if( !job.started ) {
            job.started = true ;
            classifier.started( job.task ) ;
        }
        if( job.image == null ) {
            try {
                job.image = aiInvoker.prepareImage( job.task ) ;
            }
            catch( IOException e ) {
                log.error( "Error preparing image for {}", job.task, e ) ;
                String reason = "image error: " + e.getMessage() ;

                done( job, () -> classifier.failed( job.task, 0, reason ) ) ;
                return ;
            }
        }

        String model = job.model ;
        AIInvoker.InvocationResult result ;

        try {
            result = aiInvoker.attempt( processor, model, job.task, job.image,
                                        job.attempts,
                                        classifier.attemptListener( job.task ) ) ;
        }
        catch( AIProcessor.RateLimitException e ) {
            // Not the question's fault (a rate limit, or the backend is
            // unreachable): the attempt is handed back. The next one goes to
            // another model while this one is paused, or waits if the limit
            // covers the whole processor.
            aiInvoker.suspend( processor, model, e ) ;
            job.used[p]-- ;
            job.attempts-- ;
            job.lastFailure = e.getMessage() ;
            requeue( job ) ;
            return ;
        }

        if( result.isSuccess() ) {
            succeeded( job, p, result.getTopics(), model ) ;
            return ;
        }
        job.lastFailure = result.getFailureReason() ;
        failed( job ) ;
    }

    private void succeeded( Job job, int p, List<Topic> topics, String model ) {

        if( job.awaitsSecondOpinion() ) {
            log.info( "[{}] Storing OpenRouter's second opinion", job.task ) ;
            finish( job, topics, AIAgent.OPENROUTER, model ) ;
            return ;
        }

        boolean fromGemma = processors.get( p ).getAgent() == AIAgent.GEMMA ;
        if( fromGemma && hasBudget( job, openRouter ) && mayContinue( job )
            && classifier.failsGate( job.task, topics ) ) {
            log.info( "[{}] Gemma's top topic fails the association gate - " +
                      "asking OpenRouter", job.task ) ;
            job.gemmaTopics = topics ;
            job.gemmaModel  = model ;

            requeue( job ) ;
            return ;
        }
        finish( job, topics, processors.get( p ).getAgent(), model ) ;
    }

    private void failed( Job job ) {

        refreshOpenRouterHeldBack() ;
        if( mayContinue( job ) && canRunAnywhere( job ) ) {
            log.warn( "Retrying {} (attempt {})", job.task, job.attempts + 1 ) ;
            requeue( job ) ;
            return ;
        }
        if( job.awaitsSecondOpinion() ) {
            log.warn( "[{}] OpenRouter's second opinion failed - keeping " +
                      "Gemma's reply", job.task ) ;
            finish( job, job.gemmaTopics, AIAgent.GEMMA, job.gemmaModel ) ;
            return ;
        }
        done( job, () -> classifier.failed( job.task, job.attempts,
                                            job.lastFailure ) ) ;
    }

    /** Finish a job whose {@code keepGoing} turned false. */
    private void windUp( Job job ) {

        if( job.awaitsSecondOpinion() ) {
            finish( job, job.gemmaTopics, AIAgent.GEMMA, job.gemmaModel ) ;
        }
        else if( job.started ) {
            done( job, () -> classifier.failed( job.task, job.attempts,
                                                job.lastFailure ) ) ;
        }
        else {
            done( job, () -> {} ) ;   // never started: stays queued in the UI
        }
    }

    private boolean mayContinue( Job job ) {
        return !Thread.currentThread().isInterrupted()
               && job.keepGoing.getAsBoolean() ;
    }

    /** Store {@code topics}, the reply of {@code model} on {@code agent}. */
    private void finish( Job job, List<Topic> topics, AIAgent agent,
                         String model ) {
        done( job, () -> classifier.succeeded( job.task, topics, agent, model,
                                               job.attempts ) ) ;
    }

    /**
     * Take a job out of the scheduler, then run its final {@code action} and
     * count it off its batch.
     */
    private void done( Job job, Runnable action ) {

        synchronized( lock ) { leaveRunning( job ) ; }
        try {
            action.run() ;
        }
        finally {
            synchronized( lock ) {
                if( job.batch ) batchOutstanding-- ;
                lock.notifyAll() ;
            }
        }
    }

    private void requeue( Job job ) {
        synchronized( lock ) {
            leaveRunning( job ) ;
            queue.add( job ) ;
            lock.notifyAll() ;
        }
    }

    /** Under lock: the job no longer runs, and its slot counts as free. */
    private void leaveRunning( Job job ) {

        running.remove( job ) ;
        if( job.runningOn >= 0 ) busy[job.runningOn]-- ;
        job.runningOn = -1 ;
    }

    // ---- routing -------------------------------------------------------

    /**
     * The queued job processor {@code p} should take next: lowest
     * {@link #rank}, then earliest submitted; null if none may run on it.
     */
    private Job best( int p ) {

        Job best     = null ;
        int bestRank = Integer.MAX_VALUE ;

        refreshOpenRouterHeldBack() ;

        for( Job job : queue ) {
            int rank = rank( job, p ) ;
            if( rank < 0 ) continue ;
            if( rank < bestRank || ( rank == bestRank && job.seq < best.seq ) ) {
                best     = job ;
                bestRank = rank ;
            }
        }
        return best ;
    }

    /**
     * Priority of {@code job} on processor {@code p}, lower first; -1 if it may
     * not run there. OpenRouter: Chemistry retries, new Chemistry, second
     * opinions, then other retries and new Physics / Maths, but those only
     * while no Gemma slot is free to take them. Gemma: retries, then new
     * questions.
     */
    private int rank( Job job, int p ) {

        if( !canRun( job, p ) ) return -1 ;

        boolean chemistry = job.task.getSubject() == Subject.CHEMISTRY ;
        if( p != openRouter || chemistry ) return job.started ? 0 : 1 ;
        if( job.awaitsSecondOpinion() ) return 2 ;
        if( gemmaCanTake( job ) ) return -1 ;
        return job.started ? 3 : 4 ;
    }

    /** True if Gemma has a free slot and may run {@code job} right now. */
    private boolean gemmaCanTake( Job job ) {
        return gemma >= 0
               && busy[gemma] < processors.get( gemma ).getMaxParallel()
               && canRun( job, gemma )
               && aiInvoker.availableAt( processors.get( gemma ) )
                  <= System.currentTimeMillis() ;
    }

    /**
     * True if the routing rules let {@code job} run on processor {@code p} and
     * it has attempts left there. Chemistry runs on OpenRouter, or anywhere
     * while OpenRouter is missing or held back by a rate limit.
     */
    private boolean canRun( Job job, int p ) {

        if( !hasBudget( job, p ) ) return false ;
        if( job.awaitsSecondOpinion() ) return p == openRouter ;

        boolean chemistry = job.task.getSubject() == Subject.CHEMISTRY ;
        return !chemistry || openRouter < 0 || p == openRouter
               || openRouterHeldBack ;
    }

    private void refreshOpenRouterHeldBack() {
        openRouterHeldBack = openRouter >= 0
                && aiInvoker.isHeldBack( processors.get( openRouter ) ) ;
    }

    private boolean canRunAnywhere( Job job ) {
        for( int p = 0 ; p < processors.size() ; p++ ) {
            if( canRun( job, p ) ) return true ;
        }
        return false ;
    }

    private boolean hasBudget( Job job, int p ) {
        return p >= 0 && job.used[p] <= processors.get( p ).getMaxRetries() ;
    }

    // ---- lookups (under lock) -----------------------------------------

    private Job firstStopped() {
        for( Job job : queue ) {
            if( !job.keepGoing.getAsBoolean() ) return job ;
        }
        return null ;
    }

    private Job queued( QTask task ) {
        for( Job job : queue ) {
            if( job.task == task ) return job ;
        }
        return null ;
    }

    private boolean isQueuedOrRunning( QTask task ) {
        return queued( task ) != null
               || running.stream().anyMatch( j -> j.task == task ) ;
    }

    private int indexOf( AIAgent agent ) {
        for( int p = 0 ; p < processors.size() ; p++ ) {
            if( processors.get( p ).getAgent() == agent ) return p ;
        }
        return -1 ;
    }
}
