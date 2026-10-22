package com.sandy.sconsole.qimgtools.apps.tagger.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Where a loaded question stands in the tagger. */
@Getter
@RequiredArgsConstructor
public enum TaggerStatus {

    /** Loaded; not sent to the models yet. */
    PENDING( "Pending" ),

    /** Never sent: its type or answer key can't be checked (e.g. MMT). */
    UNSUPPORTED( "Unsupported" ),

    /** The question's images are not in the question bank. */
    NO_IMAGES( "No local images" ),

    QUEUED ( "Queued" ),

    /** A request to tier 1 is in flight, or about to be. */
    RUNNING_T1( "Running T1" ),

    /** Tier 1 missed the key; tier 2 is asked. */
    RUNNING_T2( "Running T2" ),

    /** Tier 1's answer matched the key; its tags are staged. */
    ACCEPTED_T1( "Accepted T1" ),

    /** Tier 2's answer matched the key; its tags are staged. */
    ACCEPTED_T2( "Accepted T2" ),

    /** The model proposed a new tag; the run waits for the user. */
    GAP_PROPOSED( "Gap proposed" ),

    /** Handed to the user (see {@link HandOffReason}). */
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

    /** True for the states the automation settled: tags staged to save. */
    public boolean isAccepted() {
        return this == ACCEPTED_T1 || this == ACCEPTED_T2 ;
    }

    /** True while a model is working on the question. */
    public boolean isRunning() {
        return this == QUEUED || this == RUNNING_T1 || this == RUNNING_T2 ;
    }

    /**
     * True if the user may decide (or decide again) on the question: it has
     * the tiers' replies, or none could be had.
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

    /** True if Save sends it: tags staged by the models or the user. */
    public boolean isSavable() {
        return isAccepted() || this == DECIDED || this == ERROR ;
    }

    @Override
    public String toString() { return label ; }
}
