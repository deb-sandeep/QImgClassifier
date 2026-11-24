package com.sandy.sconsole.qimgtools.apps.transcriber.model;

import lombok.Getter;

/** Where a question in the transcriber's table stands. */
@Getter
public enum TranscriptionStatus {

    PENDING         ( "Pending" ),
    NO_IMAGES       ( "No local images" ),
    WAITING_PASSAGE ( "Waiting for passage" ),

    /** Handed to the models, no request made yet: it can be dequeued. */
    QUEUED          ( "Queued" ),

    /**
     * With a model: a request out, or one made and the job waiting to
     * retry or fall back.
     */
    TRANSCRIBING    ( "Transcribing" ),

    /**
     * Its last attempt failed this session; the job waits for a retry or
     * another model.
     */
    TX_FAILED       ( "Tx failed" ),

    /** Accepted and staged, to be saved. */
    TRANSCRIBED     ( "Transcribed" ),

    /**
     * Qwen's, kept because the model asked for a second opinion gave none;
     * saved only once the user accepts it.
     */
    UNCONFIRMED     ( "Unconfirmed" ),

    /**
     * Marked faulty with no model left to ask, or whose re-send failed:
     * never saved.
     */
    FAULTY          ( "Faulty" ),

    FAILED          ( "Failed" ),
    SAVING          ( "Saving" ),
    SAVED           ( "Saved" ),

    /** The server refused the save, or the request failed. */
    ERROR           ( "Error" ) ;

    private final String label ;

    TranscriptionStatus( String label ) {
        this.label = label ;
    }

    @Override
    public String toString() {
        return label ;
    }
}
