package com.sandy.sconsole.ai;

/**
 * How an {@link Attempt} ended.
 *
 * @param attempt   the attempt
 * @param elapsedMs from sending to the end
 * @param outcome   how it ended
 * @param response  the raw reply, or the raw error body when the gateway
 *                  sent one; null when there was nothing
 * @param failure   a short readable reason; null on success
 * @param kind      why it failed, for fallback; null on success
 * @param usage     the tokens it used; null if the gateway did not say
 */
public record AttemptResult( Attempt attempt, long elapsedMs, Outcome outcome,
                             String response, String failure, FailureKind kind,
                             TokenUsage usage ) {

    /** How an attempt ended. */
    public enum Outcome {

        /** A reply came and the app's {@link ReplyCheck} took it. */
        OK,

        /** It failed, and used one of the model's retries. */
        FAILED,

        /** A rate limit refused it; handed back without using a retry. */
        RATE_LIMITED,

        /** The gateway could not be reached; handed back. */
        UNREACHABLE
    }

    public boolean isSuccess() { return outcome == Outcome.OK ; }
}
