package com.sandy.sconsole.qimgclassifier.model;

/** Lifecycle state of a single question classification task. */
public enum TaskStatus {

    /**
     * Discovered without a stored classification; waiting for the batch pass.
     */
    QUEUED,

    /** Re-submitted by the user; waiting for a worker. */
    REQUEUED,

    /** A worker is classifying it. */
    RUNNING,

    /** Classified in this session. */
    DONE,

    /** Classification failed (all attempts exhausted, or an error). */
    FAILED,

    /**
     * Already had a stored classification when discovered, or was associated by
     * hand.
     */
    ASSOCIATED ;

    /**
     * True while the task still waits for, or is undergoing, classification.
     */
    public boolean isActive() {
        return this == QUEUED || this == REQUEUED || this == RUNNING ;
    }

    /**
     * True once the task has an outcome (DONE, FAILED or ASSOCIATED); such a
     * task can be re-queued.
     */
    public boolean isSettled() {
        return !isActive() ;
    }
}
