package com.sandy.sconsole.ai;

/** Where a submitted job joins the waiting jobs. */
public enum Priority {

    /** Behind every job waiting. */
    NORMAL,

    /** Ahead of every job waiting, e.g. a job queued again by the user. */
    FRONT
}
