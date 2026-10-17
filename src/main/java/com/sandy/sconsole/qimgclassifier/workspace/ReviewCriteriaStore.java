package com.sandy.sconsole.qimgclassifier.workspace;

import com.sandy.sconsole.qimgclassifier.model.ReviewCriteria;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The topic reviewer's search criteria as last saved, so they survive a
 * restart. A JSON object:
 * <pre>
 * { "syllabusNames" : [ "IIT Chemistry" ], "topicIds" : [ 126 ],
 *   "limit" : 100, "savedAt" : "2026-10-17T09:30:00Z" }
 * </pre>
 * A missing, unreadable or empty file reads as no saved criteria; the next
 * save replaces it. Thread-safe.
 */
@Slf4j
public final class ReviewCriteriaStore {

    private final File file ;

    public ReviewCriteriaStore( File file ) {
        this.file = file ;
    }

    /**
     * The saved criteria, keeping only the syllabuses in {@code known} and
     * bringing the limit into 1-{@code maxLimit}; null if none are saved, or
     * nothing valid is left of them.
     */
    public ReviewCriteria load( List<String> known, int maxLimit ) {

        ObjectNode root ;
        synchronized( JsonFiles.lockFor( file ) ) {
            try {
                if( !file.exists() ) return null ;
                root = JsonFiles.readObject( file ) ;
            }
            catch( IOException e ) {
                log.warn( "Could not read the saved review criteria {} - " +
                          "using the configured ones", file, e ) ;
                return null ;
            }
        }

        List<String> names = new ArrayList<>() ;
        for( JsonNode n : root.path( "syllabusNames" ) ) {
            if( known.contains( n.asText() ) ) names.add( n.asText() ) ;
        }

        List<Integer> topicIds = new ArrayList<>() ;
        for( JsonNode n : root.path( "topicIds" ) ) {
            if( n.canConvertToInt() ) topicIds.add( n.asInt() ) ;
        }

        int limit = Math.min( Math.max( root.path( "limit" ).asInt( maxLimit ),
                                        1 ), maxLimit ) ;
        ReviewCriteria criteria = new ReviewCriteria( names, topicIds, limit ) ;

        if( !criteria.isValid() ) {
            log.warn( "The saved review criteria {} name no syllabus or " +
                      "topic - using the configured ones", file ) ;
            return null ;
        }
        log.info( "Saved review criteria: {}, {} per fetch",
                  criteria.describe(), limit ) ;
        return criteria ;
    }

    /** Save {@code criteria}, replacing those saved before. */
    public void save( ReviewCriteria criteria ) throws IOException {

        ObjectNode root = JsonFiles.MAPPER.createObjectNode() ;
        criteria.syllabusNames().forEach( root.putArray( "syllabusNames" )::add ) ;
        criteria.topicIds().forEach( root.putArray( "topicIds" )::add ) ;
        root.put( "limit", criteria.limit() ) ;
        root.put( "savedAt", Instant.now().toString() ) ;

        File dir = file.getParentFile() ;
        if( dir != null && !dir.isDirectory() && !dir.mkdirs() ) {
            throw new IOException( "Could not create " + dir ) ;
        }
        synchronized( JsonFiles.lockFor( file ) ) {
            JsonFiles.writeAtomically( file, root ) ;
        }
    }
}
