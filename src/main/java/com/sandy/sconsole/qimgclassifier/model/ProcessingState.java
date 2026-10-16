package com.sandy.sconsole.qimgclassifier.model;

/** Lifecycle of the batch classification pass. */
public enum ProcessingState {

    /** No batch thread active. */
    STOPPED,

    /** Batch thread active, spawning and draining tasks. */
    RUNNING,

    /**
     * Stop requested - no new tasks spawn, but in-flight tasks are still
     * finishing.
     */
    STOPPING
}
