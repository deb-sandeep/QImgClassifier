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

    /**
     * It has tags to save, whoever gave them: the regression, the LLM or the
     * user. The table's From column says which.
     */
    ACCEPTED( "Accepted" ),

    /**
     * Nothing it could stand on yet: the LLM is to give it tags. Save leaves
     * it out meanwhile.
     */
    AWAITING_LLM( "Awaiting LLM" ),

    /** Handed to the user: its text could not be read. */
    NEEDS_HUMAN( "Needs human" ),

    /**
     * The model says no tag fits: held out for the user to tag by hand, and
     * not sent to the models again in this cycle.
     */
    ESCALATED( "Escalated" ),

    /** Noted for later: stays pending on the server. */
    DEFERRED( "Deferred" ),

    /** The decision is being sent to the server. */
    SAVING ( "Saving" ),

    /** The server applied the tags. */
    SAVED  ( "Saved" ),

    /** The server refused the save, or could not be reached. */
    ERROR  ( "Error" ) ;

    private final String label ;

    /** True if it has tags to save. */
    public boolean isAccepted() {
        return this == ACCEPTED ;
    }

    /** True if the user may decide (or decide again) on the question. */
    public boolean isDecidable() {
        return isAccepted() || this == AWAITING_LLM
               || this == NEEDS_HUMAN || this == ESCALATED
               || this == DEFERRED || this == ERROR ;
    }

    /** True if the LLM may be asked: it has been read, and isn't saved. */
    public boolean canAskLlm() {
        return isDecidable() ;
    }

    @Override
    public String toString() { return label ; }
}
