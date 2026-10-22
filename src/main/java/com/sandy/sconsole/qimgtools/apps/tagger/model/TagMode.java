package com.sandy.sconsole.qimgtools.apps.tagger.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Whether a run may propose tags the topic doesn't have yet. */
@Getter
@RequiredArgsConstructor
public enum TagMode {

    /** The model picks only from the offered set. */
    EXISTING_ONLY( "Existing tags only" ),

    /**
     * The model may also propose a missing tag of the topic; a proposal on a
     * correct answer pauses the run for the user.
     */
    REVIEW_GAPS( "Review gaps" ) ;

    private final String label ;

    @Override
    public String toString() { return label ; }
}
