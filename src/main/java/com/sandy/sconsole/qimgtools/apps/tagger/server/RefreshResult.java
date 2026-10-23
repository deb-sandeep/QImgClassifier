package com.sandy.sconsole.qimgtools.apps.tagger.server;

import com.sandy.sconsole.qimgtools.apps.tagger.model.TagClass;
import com.sandy.sconsole.qimgtools.core.server.TopicDependency;

import java.util.List;

/**
 * The server's answer for one {@link RefreshItem}.
 *
 * @param itemId                   the question's id, as sent
 * @param status                   {@code APPLIED} or {@code REJECTED}
 * @param autoSetupTaggingNeeded   the question's setup flag after the
 *                                 refresh; null if it wasn't found
 * @param autoConceptTaggingNeeded its concept flag, likewise
 * @param message                  why it was rejected; null otherwise
 * @param topicDependencies        its dependencies after the refresh
 */
public record RefreshResult( int itemId, String status,
                             Boolean autoSetupTaggingNeeded,
                             Boolean autoConceptTaggingNeeded,
                             String message,
                             List<TopicDependency> topicDependencies ) {

    public static final String APPLIED  = "APPLIED" ;
    public static final String REJECTED = "REJECTED" ;

    /** A rejection made here, for a request that got no per-item answer. */
    public static RefreshResult failed( int itemId, String message ) {
        return new RefreshResult( itemId, REJECTED, null, null, message,
                                  null ) ;
    }

    /**
     * True if the question still awaits pass {@code tagClass} after the
     * refresh (as after a close item, which clears no flag).
     */
    public boolean stillNeeded( TagClass tagClass ) {
        return Boolean.TRUE.equals( tagClass == TagClass.SETUP
                                    ? autoSetupTaggingNeeded
                                    : autoConceptTaggingNeeded ) ;
    }

    public boolean applied() {
        return APPLIED.equals( status ) ;
    }
}
