package com.sandy.sconsole.qimgtools.apps.nxrclassifier.model;

/**
 * The server's answer to one label of a batch
 * ({@code POST /Master/Question/NXR}).
 *
 * @param questionId the question's numeric server id
 * @param status     what the server did with it
 * @param message    why it was rejected; null otherwise
 */
public record NXRSaveResult( int questionId, Status status, String message ) {

    public enum Status {

        /** The label was set or changed. */
        APPLIED,

        /** The question already had this label. */
        UNCHANGED,

        /** Not applied: an invalid value, or an unknown question. */
        REJECTED
    }

    /** A rejection made here, for a request that got no per-item answer. */
    public static NXRSaveResult failed( int questionId, String message ) {
        return new NXRSaveResult( questionId, Status.REJECTED, message ) ;
    }

    /** True if the server holds the label sent. */
    public boolean accepted() {
        return status != Status.REJECTED ;
    }
}
