package com.sandy.sconsole.qimgtools.apps.topicreviewer.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Where a question of the topic review stands in the reviewer. */
@Getter
@RequiredArgsConstructor
public enum ReviewStatus {

    /** Loaded; not sent to the model yet. */
    PENDING( "Pending" ),

    QUEUED ( "Queued" ),
    RUNNING( "Running" ),

    /** The model's best topic is the server's topic; awaits sign-off. */
    AGREED ( "Agreed" ),

    /** The model's best topic is another one; awaits a decision. */
    DIFFERS( "Differs" ),

    /** The model gave no usable answer. */
    FAILED ( "Failed" ),

    /** The question's images are not in the question bank. */
    NO_IMAGES( "No local images" ),

    /** The user has decided; the decision is not sent to the server yet. */
    DECIDED( "Decided" ),

    /** A decision is being sent to the server. */
    SAVING ( "Saving" ),

    /** The user kept the server's topic. */
    KEPT   ( "Kept" ),

    /** The user replaced the server's topic. */
    OVERRIDDEN( "Overridden" ),

    /**
     * The server took the decision but left the question flagged: the
     * reviewer model is not in its strong-model list, so the question comes
     * back in the next fetch.
     */
    STILL_FLAGGED( "Still flagged" ),

    /** The server refused the decision, or could not be reached. */
    ERROR  ( "Error" ) ;

    private final String label ;

    /**
     * True if the model has given a verdict the user can decide on, or
     * decide on again while the decision is unsaved.
     */
    public boolean isDecidable() {
        return this == AGREED || this == DIFFERS || this == ERROR
               || this == DECIDED ;
    }

    /**
     * True if the user may send the question to the model: not yet judged,
     * failed, or judged but not decided (to judge it again; an unsaved
     * decision is then dropped).
     */
    public boolean isRunnable() {
        return this == PENDING || this == FAILED || isDecidable() ;
    }

    /** True once the user's decision is recorded on the server. */
    public boolean isDecided() {
        return this == KEPT || this == OVERRIDDEN || this == STILL_FLAGGED ;
    }

    @Override
    public String toString() { return label ; }
}
