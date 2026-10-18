package com.sandy.sconsole.ai;

import com.sandy.sconsole.ai.config.ModelInfo;

/**
 * Follows one job through the hub. Calls come on the hub's worker threads,
 * one at a time per job, in this order: {@link #started} once, then per
 * attempt {@link #attemptStarted}, {@link #check} (if a reply came) and
 * {@link #attemptFinished}, and finally exactly one of {@link #succeeded},
 * {@link #failed} or {@link #dropped}. Implementations must be thread-safe
 * across jobs. An exception thrown by a callback is logged and otherwise
 * ignored ({@link #check} throwing rejects the reply).
 *
 * @param <P> the app's payload type
 * @param <R> the app's result type
 */
public interface JobListener<P,R> {

    /** The job's first attempt is about to be made. */
    default void started( JobContext<P> job ) {}

    /** {@code attempt} is about to be sent. */
    default void attemptStarted( JobContext<P> job, Attempt attempt ) {}

    /**
     * Judge {@code reply}: accept it as the result, reject it (the attempt
     * fails as a bad reply), or escalate to another root.
     */
    ReplyCheck<R> check( JobContext<P> job, Attempt attempt, AIReply reply ) ;

    /** {@code result}'s attempt ended, whatever the outcome. */
    default void attemptFinished( JobContext<P> job, AttemptResult result ) {}

    /**
     * The job has its result.
     *
     * @param model       the model that gave it
     * @param provisional true if it is an escalated reply kept because no
     *                    model of the escalation root gave one
     */
    void succeeded( JobContext<P> job, R value, ModelInfo model,
                    boolean provisional ) ;

    /**
     * The job failed for good: no retry or fallback is left, or it was
     * stopped after it started.
     *
     * @param reason the last failure's readable reason
     * @param kind   the last failure's kind
     */
    void failed( JobContext<P> job, String reason, FailureKind kind ) ;

    /**
     * The job ended without starting: it was stopped while waiting, or
     * cancelled.
     */
    default void dropped( JobContext<P> job ) {}
}
