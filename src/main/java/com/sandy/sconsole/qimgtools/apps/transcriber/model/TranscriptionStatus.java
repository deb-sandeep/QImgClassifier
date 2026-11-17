package com.sandy.sconsole.qimgtools.apps.transcriber.model;

import lombok.Getter;

/** Where a question in the transcriber's table stands. */
@Getter
public enum TranscriptionStatus {

    PENDING         ( "Pending" ),
    NO_IMAGES       ( "No local images" ),
    WAITING_PASSAGE ( "Waiting for passage" ),
    TRANSCRIBING    ( "Transcribing" ),

    /** Accepted and staged, to be saved. */
    TRANSCRIBED     ( "Transcribed" ),

    /**
     * Qwen's, kept because the model asked for a second opinion gave none;
     * saved only once the user accepts it.
     */
    UNCONFIRMED     ( "Unconfirmed" ),

    /** GLM's transcription marked faulty: no model is left to ask. */
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
