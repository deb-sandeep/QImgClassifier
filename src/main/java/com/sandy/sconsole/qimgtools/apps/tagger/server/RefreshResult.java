package com.sandy.sconsole.qimgtools.apps.tagger.server;

import com.sandy.sconsole.qimgtools.core.server.TopicDependency;

import java.util.List;

/**
 * The server's answer for one {@link RefreshItem}.
 *
 * @param itemId            the question's id, as sent
 * @param status            {@code APPLIED} or {@code REJECTED}
 * @param autoTaggingNeeded the question's flag after the refresh
 * @param message           why it was rejected; null otherwise
 * @param topicDependencies its dependencies after the refresh
 */
public record RefreshResult( int itemId, String status,
                             Boolean autoTaggingNeeded, String message,
                             List<TopicDependency> topicDependencies ) {

    public static final String APPLIED  = "APPLIED" ;
    public static final String REJECTED = "REJECTED" ;

    /** A rejection made here, for a request that got no per-item answer. */
    public static RefreshResult failed( int itemId, String message ) {
        return new RefreshResult( itemId, REJECTED, null, message, null ) ;
    }

    public boolean applied() {
        return APPLIED.equals( status ) ;
    }
}
