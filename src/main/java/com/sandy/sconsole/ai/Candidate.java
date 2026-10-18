package com.sandy.sconsole.ai;

/**
 * A waiting job a model slot may take, offered to the
 * {@link RoutingPolicy}.
 *
 * @param job       the job
 * @param via       why the job may run on the slot's model
 * @param waitedMs  how long it has waited since it last joined the waiting
 *                  jobs
 * @param order     its place among the waiting jobs: lower first (jobs
 *                  submitted at the {@link Priority#FRONT} come before
 *                  those submitted normally)
 */
public record Candidate( JobContext<?> job, Via via, long waitedMs,
                         long order ) {

    /** Why a job may run on a model. */
    public enum Via {

        /** It has not been tried yet; the model is in one of its roots. */
        ROOT,

        /**
         * It has not been tried yet, but every model of its roots is held
         * back; the model stands in for one of them, by that model's
         * fallback guard for why it is held.
         */
        STAND_IN,

        /** It was escalated; the model is in its escalation root. */
        ESCALATION,

        /** It failed on this model, which has retries left for it. */
        RETRY,

        /** A rate limit or an unreachable gateway handed it back. */
        HANDED_BACK,

        /**
         * The previous model's fallback guard names this model: for why the
         * job failed on it, or, while it is held back, for why it is held.
         */
        FALLBACK
    }

    /** True unless the job has not been tried yet (not escalated either). */
    public boolean isContinuation() {
        return via != Via.ROOT && via != Via.STAND_IN ;
    }
}
