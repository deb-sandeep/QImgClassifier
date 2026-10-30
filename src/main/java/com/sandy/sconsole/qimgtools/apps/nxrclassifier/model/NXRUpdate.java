package com.sandy.sconsole.qimgtools.apps.nxrclassifier.model;

import com.sandy.sconsole.nxr.NXRResult;

/**
 * A label to save on the server for one question.
 *
 * @param item       the question
 * @param value      the label saved
 * @param classifier the classifier's result; null if it hasn't run
 */
public record NXRUpdate( NXRItem item, NXRValue value, NXRResult classifier ) {

    /** The classifier's label; null if it gave none. */
    public NXRValue suggested() {
        return classifier == null ? null : NXRValue.of( classifier.value() ) ;
    }

    /** True if the label is the user's rather than the classifier's. */
    public boolean byUser() {
        return value != suggested() ;
    }

    /** "R (classifier)" or "X (user)". */
    public String describe() {
        return value.name() + ( byUser() ? " (user)" : " (classifier)" ) ;
    }
}
