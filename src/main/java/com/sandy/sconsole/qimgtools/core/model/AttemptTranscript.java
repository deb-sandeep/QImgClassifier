package com.sandy.sconsole.qimgtools.core.model;

import lombok.Value;

/**
 * Record of one request to an AI backend and what came back: the
 * {@link AttemptRequest} and the raw reply or error. Saved per attempt so a
 * question's classification can be audited later.
 */
@Value
public class AttemptTranscript {

    /** What was sent. */
    AttemptRequest request ;

    long elapsedMs ;

    /**
     * The backend's raw reply on success or an unparseable reply, else the raw
     * error body when the backend sent one; null when there was nothing.
     */
    String response ;

    /** Short failure reason; null on success. */
    String failure ;

    Outcome outcome ;

    /** Tokens the request used; null if the backend did not report them. */
    TokenUsage usage ;

    /** How an attempt ended. */
    public enum Outcome {

        /** The reply was obtained and parsed. */
        OK,

        /** The request or its reply failed; it used one of the retries. */
        FAILED,

        /** The backend refused it with a rate limit; handed back. */
        RATE_LIMITED,

        /** The backend could not be reached; handed back. */
        UNREACHABLE
    }

    /** True if the reply was obtained and parsed. */
    public boolean isSuccess() { return outcome == Outcome.OK ; }

    public QTask getTask() { return request.getTask() ; }

    public AIAgent getAgent() { return request.getAgent() ; }

    public String getAiModel() { return request.getAiModel() ; }

    public int getAttempt() { return request.getAttempt() ; }

    public String getMimeType() { return request.getMimeType() ; }

    public long getStartedAt() { return request.getStartedAt() ; }

    public String getPrompt() { return request.getPrompt() ; }
}
