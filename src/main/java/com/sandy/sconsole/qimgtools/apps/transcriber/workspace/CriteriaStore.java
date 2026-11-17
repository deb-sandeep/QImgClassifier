package com.sandy.sconsole.qimgtools.apps.transcriber.workspace;

import com.sandy.sconsole.qimgtools.apps.transcriber.model.TranscriptionCriteria;
import com.sandy.sconsole.qimgtools.core.workspace.JsonFiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The transcriber's search criteria as last saved, so they survive a
 * restart. A JSON object:
 * <pre>
 * { "syllabusNames" : [ "IIT Chemistry" ], "topicIds" : [ 126 ],
 *   "batchSize" : 50, "savedAt" : "2026-11-17T09:30:00Z" }
 * </pre>
 * A missing, unreadable or empty file reads as no saved criteria; the next
 * save replaces it. Thread-safe.
 */
@Slf4j
public final class CriteriaStore {

    private final File file ;

    public CriteriaStore( File file ) {
        this.file = file ;
    }

    /**
     * The saved criteria, keeping only the syllabuses in {@code known} and
     * bringing the batch size into 1-{@code maxBatch}; null if none are
     * saved, or nothing valid is left of them.
     */
    public TranscriptionCriteria load( List<String> known, int maxBatch ) {

        ObjectNode root ;
        synchronized( JsonFiles.lockFor( file ) ) {
            try {
                if( !file.exists() ) return null ;
                root = JsonFiles.readObject( file ) ;
            }
            catch( IOException e ) {
                log.warn( "Could not read the saved criteria {} - using the " +
                          "configured ones", file, e ) ;
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

        int size = Math.min( Math.max(
                root.path( "batchSize" ).asInt( maxBatch ), 1 ), maxBatch ) ;
        TranscriptionCriteria criteria =
                new TranscriptionCriteria( names, topicIds, size ) ;

        if( !criteria.isValid() ) {
            log.warn( "The saved criteria {} name no syllabus or topic - " +
                      "using the configured ones", file ) ;
            return null ;
        }
        log.info( "Saved criteria: {}, {} per load", criteria.describe(),
                  size ) ;
        return criteria ;
    }

    /** Save {@code criteria}, replacing those saved before. */
    public void save( TranscriptionCriteria criteria ) throws IOException {

        ObjectNode root = JsonFiles.MAPPER.createObjectNode() ;
        criteria.syllabusNames().forEach( root.putArray( "syllabusNames" )::add ) ;
        criteria.topicIds().forEach( root.putArray( "topicIds" )::add ) ;
        root.put( "batchSize", criteria.batchSize() ) ;
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
