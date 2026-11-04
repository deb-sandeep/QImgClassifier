package com.sandy.sconsole.qimgtools.apps.tagger.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Where a loaded question stands in the tagger. */
@Getter
@RequiredArgsConstructor
public enum TaggerStatus {

    /** Its text is being read and scored. */
    READING( "Reading" ),

    /** Loaded; not sent to the models yet. */
    PENDING( "Pending" ),

    /** The question's images are not in the question bank. */
    NO_IMAGES( "No local images" ),

    QUEUED ( "Queued" ),

    /** A request to a model is in flight, or about to be. */
    RUNNING( "Running" ),

    /** The regression's or the model's tags are staged, to be saved. */
    ACCEPTED( "Accepted" ),

    /** Handed to the user: OCR failed, or the model's replies were no use. */
    NEEDS_HUMAN( "Needs human" ),

    /** The user has decided; not sent to the server yet. */
    DECIDED( "Decided" ),

    /** Noted for later: stays pending on the server. */
    DEFERRED( "Deferred" ),

    /** The decision is being sent to the server. */
    SAVING ( "Saving" ),

    /** The server applied the tags. */
    SAVED  ( "Saved" ),

    /** The models gave no usable answer (infrastructure). */
    FAILED ( "Failed" ),

    /** The server refused the save, or could not be reached. */
    ERROR  ( "Error" ) ;

    private final String label ;

    /** True for the state the automation settled: tags staged to save. */
    public boolean isAccepted() {
        return this == ACCEPTED ;
    }

    /** True while a model is working on the question. */
    public boolean isRunning() {
        return this == QUEUED || this == RUNNING ;
    }

    /**
     * True if the user may decide (or decide again) on the question: it has
     * the model's reply, or none could be had.
     */
    public boolean isDecidable() {
        return isAccepted() || this == NEEDS_HUMAN || this == DECIDED
               || this == DEFERRED || this == ERROR ;
    }

    /**
     * True if the question may be sent to the models: not yet sent, failed,
     * or judged but not decided (to judge it again).
     */
    public boolean isRunnable() {
        return this == PENDING || this == FAILED || isAccepted()
               || this == NEEDS_HUMAN || this == DEFERRED ;
    }

    @Override
    public String toString() { return label ; }
}
