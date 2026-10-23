package com.sandy.sconsole.qimgtools.apps.tagger.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Why a question is handed to the user instead of being settled. */
@Getter
@RequiredArgsConstructor
public enum HandOffReason {

    /** Both tiers gave the same answer, and it isn't the key's. */
    KEY_SUSPECT( "key suspect" ),

    /** Neither tier matched the key (or tier 2 gave no usable reply). */
    UNSOLVED( "unsolved" ),

    /** Tier 1's replies could not be used, even after a resend. */
    INVALID_OUTPUT( "invalid output" ),

    /** The accepted tags drop a hand tag already on it. */
    EXISTING_DIFFERS( "existing tags differ" ),

    /**
     * The accepted tier gave no concept tag, its proposed concept gap
     * having been rejected (or settled on a setup tag).
     */
    NO_CONCEPT( "no concept tag" ) ;

    private final String label ;

    @Override
    public String toString() { return label ; }
}
