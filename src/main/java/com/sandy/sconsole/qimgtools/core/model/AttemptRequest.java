package com.sandy.sconsole.qimgtools.core.model;

import lombok.Value;

/**
 * One request sent to an AI backend: what was asked, of which model, and
 * when. Reported as soon as the request goes out, so a request still waiting
 * for its reply can be shown; {@link AttemptTranscript} adds the outcome.
 */
@Value
public class AttemptRequest {

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

    String prompt ;

    /**
     * What the app notes about the attempt beside the model (e.g. the tier
     * asked); null if nothing.
     */
    String context ;
}
