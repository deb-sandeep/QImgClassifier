package com.sandy.sconsole.qimgtools.apps.transcriber.model;

/**
 * The server's answer to one {@link SaveItem}.
 *
 * @param questionId the question's server id
 * @param status     APPLIED or REJECTED
 * @param message    why it was rejected; null otherwise
 */
public record SaveResult( int questionId, Status status, String message ) {

    public enum Status { APPLIED, REJECTED }

    /** A result for an item whose request failed as a whole. */
    public static SaveResult failed( int questionId, String message ) {
        return new SaveResult( questionId, Status.REJECTED, message ) ;
    }

    public boolean accepted() {
        return status == Status.APPLIED ;
    }
}
