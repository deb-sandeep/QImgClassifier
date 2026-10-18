package com.sandy.sconsole.qimgtools.core.scheduler;

import com.sandy.sconsole.ai.AIHub;
import com.sandy.sconsole.ai.AIJob;
import com.sandy.sconsole.ai.AIReply;
import com.sandy.sconsole.ai.AIRequest;
import com.sandy.sconsole.ai.Attempt;
import com.sandy.sconsole.ai.AttemptResult;
import com.sandy.sconsole.ai.FailureKind;
import com.sandy.sconsole.ai.HubStatus;
import com.sandy.sconsole.ai.JobContext;
import com.sandy.sconsole.ai.JobHandle;
import com.sandy.sconsole.ai.JobListener;
import com.sandy.sconsole.ai.Priority;
import com.sandy.sconsole.ai.ReplyCheck;
import com.sandy.sconsole.ai.config.ModelInfo;
import com.sandy.sconsole.qimgtools.core.ai.TopicPrompter;
import com.sandy.sconsole.qimgtools.core.model.AIAgent;
import com.sandy.sconsole.qimgtools.core.model.AttemptRequest;
import com.sandy.sconsole.qimgtools.core.model.AttemptTranscript;
import com.sandy.sconsole.qimgtools.core.model.QTask;
import com.sandy.sconsole.qimgtools.core.model.RateLimitNotice;
import com.sandy.sconsole.qimgtools.core.model.Topic;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Sends questions to the app's AI models through the {@link AIHub}: each
 * question becomes a hub job on the roots {@link TaskOutcomes#roots} names,
 * asking the {@link TopicPrompter}'s question with the question's image. The
 * hub binds each job to a model when one of the model's slots is free, as
 * the app's routing policy decides, and handles retries, guarded fallbacks
 * and rate limits (see {@code AIHub}). A reply is read as a topic list; one
 * that isn't fails the attempt as a bad reply. A topic list the outcomes
 * want a second opinion on is escalated to the root they name.
 * <p>
 * Batches are counted here, so the app can wait for one to finish. Every
 * question carries a {@code keepGoing} check: once it turns false, a
 * question that has not started is dropped, and a started one ends with
 * what it has (a reply awaiting a second opinion, else its last failure).
 * Thread-safe.
 */
@Slf4j
public class TaskScheduler {

    /** Longest single wait in {@link #awaitBatch}. */
    private static final long MAX_WAIT_MS = 1000 ;

    private final AIHub         hub ;
    private final TopicPrompter prompter ;
    private final TaskOutcomes  outcomes ;

    /** Guards {@link #active}, {@link #held} and the batch count. */
    private final Object lock = new Object() ;

    /** Tasks queued or running, with their entry. */
    private final Map<QTask,Entry> active = new HashMap<>() ;

    /**
     * Tasks taken out of scheduling during manual association, with the
     * entry to put back on release; the value is null when nothing was
     * queued.
     */
    private final Map<QTask,Entry> held = new HashMap<>() ;

    /** Batch jobs queued or running; the batch is done at 0. */
    private int batchOutstanding ;

    /**
     * How many questions are waiting (including those between attempts),
     * how many have a request in flight, and how many have finished.
     */
    public record Counts( int queued, int running, int processed ) {}

    /** One question's job, and the hub job's payload. */
    private final class Entry {

        final QTask           task ;
        final BooleanSupplier keepGoing ;
        final boolean         batch ;
        final AttemptListener attempts ;

        /** Set once submitted; callbacks may come before it is. */
        volatile JobHandle handle ;

        /** Taken off the batch count by a hold; guarded by lock. */
        boolean countedOut ;

        /** The image, prepared on the first attempt. */
        AIRequest.Image image ;

        /** The request of the attempt in flight, for its transcript. */
        AttemptRequest request ;

        Entry( QTask task, BooleanSupplier keepGoing, boolean batch ) {
            this.task      = task ;
            this.keepGoing = keepGoing ;
            this.batch     = batch ;
            this.attempts  = outcomes.attemptListener( task ) ;
        }
    }

    public TaskScheduler( AIHub hub, TopicPrompter prompter,
                          TaskOutcomes outcomes ) {
        this.hub      = hub ;
        this.prompter = prompter ;
        this.outcomes = outcomes ;
    }

    /**
     * Queue a batch's tasks behind everything queued, in order. Tasks already
     * queued or running are skipped; a held task is queued when it is
     * released.
     */
    public void submitBatch( List<QTask> tasks, BooleanSupplier keepGoing ) {

        synchronized( lock ) {
            for( QTask task : tasks ) {
                if( active.containsKey( task ) ) continue ;

                Entry e = new Entry( task, keepGoing, true ) ;
                if( held.containsKey( task ) ) {
                    held.put( task, e ) ;
                    continue ;
                }
                active.put( task, e ) ;
                batchOutstanding++ ;
                submit( e, Priority.NORMAL ) ;
            }
        }
    }

    /**
     * Queue one task (outside any batch) ahead of the others waiting. Ignored
     * if the task is already queued, running or held.
     */
    public void submitFirst( QTask task, BooleanSupplier keepGoing ) {

        synchronized( lock ) {
            if( active.containsKey( task ) || held.containsKey( task ) ) {
                log.debug( "{} is already scheduled - not queueing it again",
                           task ) ;
                return ;
            }
            Entry e = new Entry( task, keepGoing, false ) ;
            active.put( task, e ) ;
            submit( e, Priority.FRONT ) ;
        }
    }

    /** Block until every batch job has finished or been dropped. */
    public void awaitBatch() throws InterruptedException {
        synchronized( lock ) {
            while( batchOutstanding > 0 ) lock.wait( MAX_WAIT_MS ) ;
        }
    }

    /** True while a batch job is queued or running. */
    public boolean hasBatchWork() {
        synchronized( lock ) {
            return batchOutstanding > 0 ;
        }
    }

    public Counts counts() {
        HubStatus.Jobs j = hub.status().jobs() ;
        return new Counts( j.waiting(), j.running(), j.done() ) ;
    }

    /**
     * The gateways and models held back by a limit right now, with why and
     * until when.
     */
    public List<RateLimitNotice> rateLimitNotices() {
        return hub.status().notices().stream()
                  .map( n -> new RateLimitNotice(
                          AIAgent.of( n.gatewayType() ), n.model(), n.reason(),
                          n.until(), n.daily() ) )
                  .toList() ;
    }

    /**
     * Epoch ms from which a model with waiting questions may send; now or
     * earlier if one may now, or if none waits.
     */
    public long nextRequestAt() {
        long at = hub.status().nextSendAt() ;
        return at == Long.MAX_VALUE ? System.currentTimeMillis() : at ;
    }

    /**
     * Take a task out of scheduling while it is manually associated.
     *
     * @return false if the task is running or between attempts
     */
    public boolean hold( QTask task ) {

        synchronized( lock ) {
            Entry e = active.get( task ) ;
            if( e != null ) {
                if( e.handle == null || !e.handle.hold() ) return false ;
                active.remove( task ) ;
                if( e.batch ) {
                    batchOutstanding-- ;
                    e.countedOut = true ;
                    lock.notifyAll() ;
                }
            }
            held.put( task, e ) ;
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
            Entry e = held.remove( task ) ;
            if( e == null ) return false ;

            if( associated || !e.keepGoing.getAsBoolean() ) {
                if( e.handle != null ) e.handle.cancel() ;
                return false ;
            }
            active.put( task, e ) ;
            if( e.batch ) {
                batchOutstanding++ ;
                e.countedOut = false ;
            }
            if( e.handle != null ) e.handle.release( true ) ;
            else submit( e, Priority.FRONT ) ;
            return true ;
        }
    }

    /**
     * The question of a hub job this scheduler submitted, for a routing
     * policy; null for any other job.
     */
    public static QTask taskOf( JobContext<?> job ) {
        return job.payload() instanceof Entry e ? e.task : null ;
    }

    /** Stop the hub, interrupting requests in flight. */
    public void shutdown() {
        hub.shutdown() ;
    }

    // ---- hub jobs ------------------------------------------------------

    /** Under lock: hand {@code e} to the hub. */
    private void submit( Entry e, Priority priority ) {

        AIJob<Entry,List<Topic>> job = new AIJob<>(
                e, outcomes.roots( e.task ), model -> request( e ),
                e.keepGoing, new Listener() ) ;
        e.handle = hub.submit( job, priority ) ;
    }

    /** The question's request; the image is prepared once. */
    private AIRequest request( Entry e ) throws Exception {

        if( e.image == null ) e.image = prompter.prepareImage( e.task ) ;
        return new AIRequest( prompter.prompt( e.task.getSubject() ),
                              List.of( e.image ) ) ;
    }

    /** The job's end: off the active tasks and the batch count. */
    private void ended( Entry e ) {

        synchronized( lock ) {
            if( active.get( e.task ) == e ) active.remove( e.task ) ;
            if( e.batch && !e.countedOut ) {
                batchOutstanding-- ;
                e.countedOut = true ;
            }
            lock.notifyAll() ;
        }
    }

    private static AIAgent agentOf( ModelInfo model ) {
        return AIAgent.of( model.gateway().type() ) ;
    }

    /** Follows one question through the hub. */
    private final class Listener implements JobListener<Entry,List<Topic>> {

        @Override
        public void started( JobContext<Entry> job ) {
            outcomes.started( job.payload().task ) ;
        }

        @Override
        public void attemptStarted( JobContext<Entry> job, Attempt attempt ) {

            Entry   e     = job.payload() ;
            AIAgent agent = agentOf( attempt.model() ) ;
            String  mime  = attempt.request().images().isEmpty() ? null
                          : attempt.request().images().get( 0 ).mimeType() ;

            e.attempts.attemptStarted( agent, attempt.model().id(),
                                       attempt.number() ) ;
            e.request = new AttemptRequest(
                    e.task, agent, attempt.model().id(), attempt.number(),
                    mime, attempt.startedAt(), attempt.request().prompt() ) ;
            e.attempts.requestSent( e.request ) ;
        }

        @Override
        public ReplyCheck<List<Topic>> check( JobContext<Entry> job,
                                              Attempt attempt, AIReply reply ) {

            QTask       task = job.payload().task ;
            List<Topic> topics ;
            try {
                topics = prompter.parseTopics( reply.text(), task ) ;
            }
            catch( JsonProcessingException ex ) {
                log.error( "[{}] Error parsing the reply of {} (attempt {}): {}",
                           task, attempt.model().tag(), attempt.number(),
                           reply.text(), ex ) ;
                return ReplyCheck.reject( "the model's reply was not a topic " +
                                          "list" ) ;
            }

            String root = outcomes.secondOpinionRoot(
                    task, topics, agentOf( attempt.model() ) ) ;
            return root == null
                   ? ReplyCheck.accept( topics )
                   : ReplyCheck.escalate( topics, root, "the top topic is not " +
                                          "confident enough" ) ;
        }

        @Override
        public void attemptFinished( JobContext<Entry> job,
                                     AttemptResult result ) {

            AttemptTranscript.Outcome outcome = switch( result.outcome() ) {
                case OK           -> AttemptTranscript.Outcome.OK ;
                case FAILED       -> AttemptTranscript.Outcome.FAILED ;
                case RATE_LIMITED -> AttemptTranscript.Outcome.RATE_LIMITED ;
                case UNREACHABLE  -> AttemptTranscript.Outcome.UNREACHABLE ;
            } ;
            Entry e = job.payload() ;
            e.attempts.attemptFinished( new AttemptTranscript(
                    e.request, result.elapsedMs(), result.response(),
                    result.failure(), outcome, result.usage() ) ) ;
        }

        @Override
        public void succeeded( JobContext<Entry> job, List<Topic> topics,
                               ModelInfo model, boolean provisional ) {

            Entry e = job.payload() ;
            try {
                outcomes.succeeded( e.task, topics, agentOf( model ), model.id(),
                                    job.attempts() ) ;
            }
            finally {
                ended( e ) ;
            }
        }

        @Override
        public void failed( JobContext<Entry> job, String reason,
                            FailureKind kind ) {

            Entry e = job.payload() ;
            try {
                outcomes.failed( e.task, job.attempts(), reason ) ;
            }
            finally {
                ended( e ) ;
            }
        }

        @Override
        public void dropped( JobContext<Entry> job ) {
            ended( job.payload() ) ;   // never started: stays queued in the UI
        }
    }
}
