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

    /** The regression settled it: its tags are staged, to be saved. */
    ACCEPTED( "Accepted" ),

    /**
     * The regression didn't settle it: the user is to resolve its undecided
     * tags or confirm its tags. Save leaves it out meanwhile.
     */
    PARTIALLY_UNDECIDED( "Partially undecided" ),

    /** Handed to the user: its text could not be read. */
    NEEDS_HUMAN( "Needs human" ),

    /**
     * The model says no tag fits: held out for the user to tag by hand, and
     * not sent to the models again in this cycle.
     */
    ESCALATED( "Escalated" ),

    /** The user has decided; not sent to the server yet. */
    DECIDED( "Decided" ),

    /** Noted for later: stays pending on the server. */
    DEFERRED( "Deferred" ),

    /** The decision is being sent to the server. */
    SAVING ( "Saving" ),

    /** The server applied the tags. */
    SAVED  ( "Saved" ),

    /** The server refused the save, or could not be reached. */
    ERROR  ( "Error" ) ;

    private final String label ;

    /** True for the state the automation settled: tags staged to save. */
    public boolean isAccepted() {
        return this == ACCEPTED ;
    }

    /** True if the user may decide (or decide again) on the question. */
    public boolean isDecidable() {
        return isAccepted() || this == PARTIALLY_UNDECIDED
               || this == NEEDS_HUMAN || this == ESCALATED || this == DECIDED
               || this == DEFERRED || this == ERROR ;
    }

    /**
     * True if the LLM's opinion may be asked for: it has been read, and isn't
     * being saved or saved. Asking changes nothing about where it stands.
     */
    public boolean canAskLlm() {
        return isDecidable() ;
    }

    @Override
    public String toString() { return label ; }
}
