package com.sandy.sconsole.qimgclassifier.model;

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

    /** The server refused the decision. */
    ERROR  ( "Error" ) ;

    private final String label ;

    /** True if the model has given a verdict the user can decide on. */
    public boolean isDecidable() {
        return this == AGREED || this == DIFFERS || this == ERROR ;
    }

    /** True if the question may be (re)sent to the model. */
    public boolean isRunnable() {
        return this == PENDING || this == FAILED ;
    }

    /** True once the user's decision is recorded on the server. */
    public boolean isDecided() {
        return this == KEPT || this == OVERRIDDEN || this == STILL_FLAGGED ;
    }

    @Override
    public String toString() { return label ; }
}
