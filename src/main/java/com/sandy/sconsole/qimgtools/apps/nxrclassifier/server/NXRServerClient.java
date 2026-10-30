package com.sandy.sconsole.qimgtools.apps.nxrclassifier.server;

import com.sandy.sconsole.qimgtools.apps.nxrclassifier.model.NXRSaveResult;
import com.sandy.sconsole.qimgtools.apps.nxrclassifier.model.NXRUpdate;
import com.sandy.sconsole.qimgtools.core.server.PendingResult;
import com.sandy.sconsole.qimgtools.core.server.SConsoleServer;
import com.sandy.sconsole.qimgtools.core.server.SConsoleServer.ServerException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Client for the SConsole server's NXR APIs (the server's
 * {@code doc/feature-enhancements/2026-10-30/nxr-classification/}
 * {@code api-changes.md}): the questions with no label yet, and labelling a
 * batch of them. Calls go through {@link SConsoleServer}, which turns a
 * failed one into a {@link ServerException}. Thread-safe.
 */
@Slf4j
public class NXRServerClient {

    /** Most questions one {@link #pending} call may ask for. */
    public static final int MAX_BATCH = 100 ;

    /** Most labels one {@link #update} call may carry. */
    public static final int MAX_UPDATES = 500 ;

    private final SConsoleServer server ;

    public NXRServerClient( SConsoleServer server ) {
        this.server = server ;
    }

    /**
     * The first {@code batchSize} (1-{@value #MAX_BATCH}) questions, by id,
     * with no label, and how many there are in all. There is no cursor: a
     * question stays first until it is labelled.
     */
    public PendingResult pending( int batchSize ) throws ServerException {
        return server.get( "/Master/Question/NXR/Pending?batchSize=" +
                           batchSize,
                           new TypeReference<PendingResult>() {} ) ;
    }

    /**
     * Save {@code updates} (1-{@value #MAX_UPDATES}). Items are independent;
     * a rejected one doesn't affect the others.
     *
     * @return the server's result for each update, in the same order
     */
    public List<NXRSaveResult> update( List<NXRUpdate> updates )
            throws ServerException {

        ObjectNode body  = server.mapper().createObjectNode() ;
        ArrayNode  items = body.putArray( "updates" ) ;

        for( NXRUpdate u : updates ) {
            items.addObject()
                 .put( "questionId", u.item().id() )
                 .put( "nxrValue", u.value().name() ) ;
        }

        JsonNode data = server.post( "/Master/Question/NXR", body,
                                     new TypeReference<JsonNode>() {} ) ;
        List<NXRSaveResult> results = server.mapper().convertValue(
                data.path( "results" ),
                new TypeReference<List<NXRSaveResult>>() {} ) ;

        if( !matches( results, updates ) ) {
            log.error( "NXR reply has {} results for {} items: {}",
                       results == null ? 0 : results.size(),
                       updates.size(), data ) ;
            throw new ServerException( "unexpected reply from the server" ) ;
        }
        return results ;
    }

    /** True if there is one result per update, in the same order. */
    private static boolean matches( List<NXRSaveResult> results,
                                    List<NXRUpdate> updates ) {

        if( results == null || results.size() != updates.size() ) {
            return false ;
        }
        for( int i = 0 ; i < results.size() ; i++ ) {
            NXRSaveResult r = results.get( i ) ;
            if( r == null || r.status() == null
                || r.questionId() != updates.get( i ).item().id() ) {
                return false ;
            }
        }
        return true ;
    }
}
