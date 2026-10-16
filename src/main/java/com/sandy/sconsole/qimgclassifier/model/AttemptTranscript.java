package com.sandy.sconsole.qimgclassifier.model;

import lombok.Value;

/**
 * Record of one request to an AI backend and what came back: the prompt that
 * was sent and the raw reply or error. Saved per attempt so a question's
 * classification can be audited later.
 */
@Value
public class AttemptTranscript {

    QTask   task ;
    AIAgent agent ;

    /** The model the request was sent to. */
    String aiModel ;

    /** 1-based attempt number for the question, across all backends. */
    int attempt ;

    /** Media type of the image sent with the prompt. */
    String mimeType ;

    /** Epoch millisecond the request was sent. */
    long startedAt ;

    long elapsedMs ;

    String prompt ;

    /**
     * The backend's raw reply on success or an unparseable reply, else the raw
     * error body when the backend sent one; null when there was nothing.
     */
    String response ;

    /** Short failure reason; null on success. */
    String failure ;

    /** True if the backend refused the request because of a rate limit. */
    boolean rateLimited ;

    /** True if the reply was obtained and parsed. */
    public boolean isSuccess() { return failure == null ; }
}
