package com.sandy.sconsole.qimgclassifier.classifier;

import com.sandy.sconsole.qimgclassifier.model.QTask;
import com.sandy.sconsole.qimgclassifier.workspace.QuestionTopicAssociator;
import com.sandy.sconsole.qimgclassifier.workspace.WorkspaceTopicMap;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;

/**
 * Discovers and classifies the questions of one source folder.
 * <p>
 * {@link #discover()} reports every question and returns the unclassified
 * ones; {@link #process} then feeds them to the shared executor, never more
 * than {@code maxInFlight} at a time. While {@code process} runs, a queued
 * task can be {@linkplain #hold held} out of scheduling (during manual
 * association) and {@linkplain #release released} back to the front of the
 * queue.
 */
@Slf4j
public class QSourceClassifier {

    @Getter
    private final File               srcDir ;
    private final QuestionClassifier classifier ;
    private final ExecutorService    executor ;
    private final BooleanSupplier    keepGoing ;
    private final int                maxInFlight ;

    /** Guards {@link #queue}, {@link #inFlight} and {@link #held}. */
    private final Object      lock     = new Object() ;

    /**
     * Tasks waiting to be dispatched by the running {@link #process}; null when
     * it isn't running.
     */
    private Deque<QTask>      queue ;

    /** Tasks dispatched to the executor and not yet finished. */
    private final Set<QTask>  inFlight = new HashSet<>() ;

    /**
     * Tasks taken out of scheduling while they are being manually associated.
     */
    private final Set<QTask>  held     = new HashSet<>() ;

    /**
     * @param executor    pool the classification tasks run on (shared across
     *         sources)
     * @param keepGoing   false once the batch should stop; checked before each
     *         dispatch and retry
     * @param maxInFlight most tasks of this source in the executor at once
     */
    public QSourceClassifier( File srcDir, QuestionClassifier classifier,
                              ExecutorService executor,
                              BooleanSupplier keepGoing, int maxInFlight ) {
        this.srcDir      = srcDir ;
        this.classifier  = classifier ;
        this.executor    = executor ;
        this.keepGoing   = keepGoing ;
        this.maxInFlight = Math.max( 1, maxInFlight ) ;
    }

    public String getSourceName() { return srcDir.getName() ; }

    /**
     * Fire {@code taskDiscovered} for every question of this source (part
     * images are folded into their parent question).
     *
     * @return the tasks with no stored classification, in question order
     */
    public List<QTask> discover() {

        Map<String,List<File>> groups = classifier.questionImages( srcDir ) ;
        if( groups == null ) {
            log.error( "No question-images folder in source {}", srcDir.getName() ) ;
            return List.of() ;
        }

        Map<String,String>  stored      =
                WorkspaceTopicMap.associatedSummaries( srcDir ) ;
        Map<String,Integer> aiTopIds    =
                WorkspaceTopicMap.topTopicIds( srcDir ) ;
        Map<String,Integer> assignedIds =
                QuestionTopicAssociator.assignedTopicIds( srcDir ) ;
        ClassificationListener listener = classifier.getListener() ;

        List<QTask> pending = new ArrayList<>() ;
        for( Map.Entry<String,List<File>> e : groups.entrySet() ) {
            String qId  = e.getKey() ;
            QTask  task = new QTask( srcDir, qId, e.getValue() ) ;
            String storedResult = stored.get( qId ) ;
            if( storedResult != null ) {
                storedResult += QuestionTopicAssociator.indicator(
                        assignedIds.get( qId ), aiTopIds.get( qId ) ) ;
            }
            listener.taskDiscovered( task, storedResult ) ;
            if( storedResult == null ) pending.add( task ) ;
        }
        log.debug( "Source {} - {} of {} questions pending",
                   srcDir.getName(), pending.size(), groups.size() ) ;
        return pending ;
    }

    /**
     * Classify the still-unclassified tasks of {@code pending}, keeping at most
     * {@code maxInFlight} in the executor at any time. A new task is only
     * submitted once an earlier one completes and {@code keepGoing} is still
     * true - so "Stop" takes effect after the currently-running tasks finish
     * rather than after the whole source's backlog drains. Blocks until done.
     */
    public void process( List<QTask> pending ) {

        Set<String> classified = WorkspaceTopicMap.classifiedQuestionIds( srcDir ) ;
        synchronized( lock ) {
            queue = new ArrayDeque<>() ;
            for( QTask t : pending ) {
                if( !classified.contains( t.getQuestionId() ) && !held.contains( t ) ) {
                    queue.add( t ) ;
                }
            }
        }

        ExecutorCompletionService<Boolean> completion =
                new ExecutorCompletionService<>( executor ) ;
        int running = 0 ;
        try {
            while( true ) {
                QTask next ;
                while( running < maxInFlight && keepGoing.getAsBoolean()
                       && ( next = nextTask() ) != null ) {
                    completion.submit( dispatchCallable( next ) ) ;
                    running++ ;
                }
                if( running == 0 ) {
                    // Done unless a task was put back (manual association
                    // cancelled) meanwhile.
                    synchronized( lock ) {
                        if( !keepGoing.getAsBoolean() || queue.isEmpty() ) break ;
                    }
                    continue ;
                }
                try {
                    completion.take().get() ;
                }
                catch( InterruptedException e ) {
                    Thread.currentThread().interrupt() ;
                    break ;
                }
                catch( Exception e ) {
                    log.error( "Error awaiting a classification in source {}",
                               srcDir.getName(), e ) ;
                }
                running-- ;
            }
        }
        finally {
            synchronized( lock ) { queue = null ; }
        }

        // Covers a source with nothing pending that was never listed.
        classifier.markProcessedIfComplete( srcDir ) ;
    }

    /**
     * Take a queued task out of scheduling while it is manually associated.
     *
     * @return false if the task has already been dispatched for classification
     */
    public boolean hold( QTask task ) {

        synchronized( lock ) {
            if( inFlight.contains( task ) ) return false ;
            if( queue != null ) queue.remove( task ) ;
            held.add( task ) ;
            return true ;
        }
    }

    /**
     * End a {@link #hold}. A task that was not associated goes back to the
     * front of the queue.
     *
     * @return true if a running {@link #process} will pick the task up; false
     *         if it was associated, was not held, or no {@code process} is
     *         running
     */
    public boolean release( QTask task, boolean associated ) {
        synchronized( lock ) {
            if( !held.remove( task ) || associated || queue == null ) return false ;
            queue.addFirst( task ) ;
            return true ;
        }
    }

    /** Next queued task, marked in flight; null if the queue is empty. */
    private QTask nextTask() {
        synchronized( lock ) {
            QTask t = queue.poll() ;
            if( t != null ) inFlight.add( t ) ;
            return t ;
        }
    }

    /**
     * {@link QuestionClassifier#classifyCallable} that clears the in-flight
     * mark when done.
     */
    private Callable<Boolean> dispatchCallable( QTask task ) {

        Callable<Boolean> c = classifier.classifyCallable( task, keepGoing ) ;
        return () -> {
            try {
                return c.call() ;
            }
            finally {
                synchronized( lock ) { inFlight.remove( task ) ; }
            }
        } ;
    }
}
