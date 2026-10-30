package com.sandy.sconsole.qimgtools.apps.nxrclassifier.model;

import com.sandy.sconsole.nxr.NXRResult;

/**
 * A label to save on the server for one question.
 *
 * @param item       the question
 * @param value      the label saved
 * @param by         whose label it is
 * @param classifier the classifier's result; null if it hasn't run
 * @param ai         the AI model's reply; null if none was asked or answered
 */
public record NXRUpdate( NXRItem item, NXRValue value, By by,
                         NXRResult classifier, NXRReply ai ) {

    /** Whose label is saved. */
    public enum By {

        CLASSIFIER, AI, USER ;

        /** "classifier", "ai" or "user", for the logs and the audit. */
        public String wire() {
            return name().toLowerCase() ;
        }
    }

    /** The classifier's label; null if it gave none. */
    public NXRValue suggested() {
        return classifier == null ? null : NXRValue.of( classifier.value() ) ;
    }

    /** "R (classifier)", "X (ai)" or "X (user)". */
    public String describe() {
        return value.name() + " (" + by.wire() + ")" ;
    }
}
