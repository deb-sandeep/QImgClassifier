package com.sandy.sconsole.qimgtools.apps.nxrclassifier.model;

/**
 * An AI model's label for a question the local classifier left undecided.
 *
 * @param value      the label
 * @param confidence how sure the model says it is, 0-100; null if it
 *                   didn't say
 * @param reason     why, in a sentence; null if it didn't say
 * @param model      the model that gave it; null until the reply is
 *                   credited ({@link #from})
 */
public record NXRReply( NXRValue value, Integer confidence, String reason,
                        String model ) {

    /** This reply, credited to {@code model}. */
    public NXRReply from( String model ) {
        return new NXRReply( value, confidence, reason, model ) ;
    }

    /** "X  Reasoning  80%  (reason)". */
    public String describe() {

        String conf = confidence == null ? "" : "  " + confidence + "%" ;
        String why  = reason == null ? "" : "  (" + reason + ")" ;

        return value.describe() + conf + why ;
    }
}
