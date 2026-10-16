package com.sandy.sconsole.qimgclassifier.classifier;

import com.sandy.sconsole.qimgclassifier.model.AIAgent;
import com.sandy.sconsole.qimgclassifier.model.ProcessingState;
import com.sandy.sconsole.qimgclassifier.model.QTask;
import com.sandy.sconsole.qimgclassifier.model.TaskStatus;

/**
 * Callbacks fired by the classification pipeline as tasks move through their
 * lifecycle. Callbacks arrive on the discovery, batch and worker threads, so
 * implementations must be thread-safe (the UI marshals them onto the EDT).
 */
public interface ClassificationListener {

    /**
     * A question was found during discovery.
     *
     * @param storedResult summary of the question's stored classification (the
     *         row starts as ASSOCIATED), or null if it has none (the row starts
     *         as QUEUED)
     */
    void taskDiscovered( QTask task, String storedResult ) ;

    /** A worker picked the task up. */
    void taskStarted( QTask task ) ;

    /**
     * An attempt for the task was handed to a backend. Fires once per attempt,
     * so a retry on the other backend fires again.
     *
     * @param model   the model the request goes to
     * @param attempt 1-based attempt number
     */
    default void taskAttempt( QTask task, AIAgent agent, String model,
                              int attempt ) {}

    /** A transcript of one of the task's attempts was saved. */
    default void taskTranscriptSaved( QTask task ) {}

    /**
     * The task finished.
     *
     * @param status   DONE or FAILED
     * @param attempts model requests made
     * @param result   short summary: the top topic on success, the reason on
     *         failure
     */
    void taskFinished( QTask task, TaskStatus status, int attempts, String result ) ;

    /**
     * Progress hint: discovery or the batch moved on to source
     * {@code sourceIndex} (1-based) of {@code sourceCount}.
     */
    default void batchProgress( int sourceIndex, int sourceCount,
                                String sourceName ) {}

    /** The batch pass moved between STOPPED / RUNNING / STOPPING. */
    default void processingStateChanged( ProcessingState state ) {}

    /** Listener that ignores every event (used in headless mode). */
    ClassificationListener NOOP = new ClassificationListener() {
        @Override public void taskDiscovered( QTask task, String storedResult ) {}
        @Override public void taskStarted( QTask task ) {}
        @Override public void taskFinished( QTask task, TaskStatus status,
                                            int attempts, String result ) {}
    } ;
}
