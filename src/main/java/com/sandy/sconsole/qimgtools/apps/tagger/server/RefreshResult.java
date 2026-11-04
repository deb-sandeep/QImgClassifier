package com.sandy.sconsole.qimgtools.apps.tagger.server;

/**
 * The server's answer for one {@link RefreshItem}.
 *
 * @param itemId  the question's id, as sent
 * @param status  {@code APPLIED} or {@code REJECTED}
 * @param message why it was rejected; null otherwise
 */
public record RefreshResult( int itemId, String status, String message ) {

    public static final String APPLIED  = "APPLIED" ;
    public static final String REJECTED = "REJECTED" ;

    /** A rejection made here, for a request that got no per-item answer. */
    public static RefreshResult failed( int itemId, String message ) {
        return new RefreshResult( itemId, REJECTED, message ) ;
    }

    public boolean applied() {
        return APPLIED.equals( status ) ;
    }
}
