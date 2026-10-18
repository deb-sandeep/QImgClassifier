package com.sandy.sconsole.ai;

import java.util.List;

/**
 * What the hub tells its callbacks about a job.
 *
 * @param <P> the app's payload type
 */
public interface JobContext<P> {

    /** Unique within the hub, increasing in submission order. */
    long id() ;

    /** The app's payload, as submitted. */
    P payload() ;

    /** The roots the job may start on, as submitted. */
    List<String> roots() ;

    /**
     * Attempts made so far, not counting those handed back after a rate
     * limit.
     */
    int attempts() ;

    /**
     * The root the job was escalated to by {@link ReplyCheck#escalate};
     * null if it was not.
     */
    String escalationRoot() ;
}
