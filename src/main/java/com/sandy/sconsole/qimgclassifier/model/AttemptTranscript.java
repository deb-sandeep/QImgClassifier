package com.sandy.sconsole.qimgclassifier.model;

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

    /** True if the backend refused the request because of a rate limit. */
    boolean rateLimited ;

    /** Tokens the request used; null if the backend did not report them. */
    TokenUsage usage ;

    /** True if the reply was obtained and parsed. */
    public boolean isSuccess() { return failure == null ; }

    public QTask getTask() { return request.getTask() ; }

    public AIAgent getAgent() { return request.getAgent() ; }

    public String getAiModel() { return request.getAiModel() ; }

    public int getAttempt() { return request.getAttempt() ; }

    public String getMimeType() { return request.getMimeType() ; }

    public long getStartedAt() { return request.getStartedAt() ; }

    public String getPrompt() { return request.getPrompt() ; }
}
