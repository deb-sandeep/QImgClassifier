package com.sandy.sconsole.qimgtools.apps.tagger.workspace;

import com.sandy.sconsole.qimgtools.apps.tagger.model.Scope;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagMode;
import com.sandy.sconsole.qimgtools.core.workspace.JsonFiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.time.Instant;

/**
 * The scope last chosen, kept across restarts in the workspace:
 * <pre>
 * { "topicId", "batchSize", "mode" : "EXISTING_ONLY" | "REVIEW_GAPS",
 *   "ownAfterId", "depAfterId", "savedAt" }
 * </pre>
 * The cursors are null until a Load moves them. Thread-safe.
 */
@Slf4j
public final class ScopeStore {

    private final File file ;

    public ScopeStore( File file ) {
        this.file = file ;
    }

    /** The saved scope; null if there is none, or it can't be read. */
    public synchronized Scope load( int maxBatch ) {

        if( !file.isFile() ) return null ;
        try {
            JsonNode n = JsonFiles.MAPPER.readTree( file ) ;
            if( !n.hasNonNull( "topicId" ) ) return null ;

            int batch = Math.max( 1, Math.min( maxBatch,
                                  n.path( "batchSize" ).asInt( 50 ) ) ) ;
            return new Scope( n.get( "topicId" ).asInt(), batch,
                              TagMode.valueOf( n.path( "mode" ).asText(
                                      TagMode.EXISTING_ONLY.name() ) ),
                              intOrNull( n.get( "ownAfterId" ) ),
                              intOrNull( n.get( "depAfterId" ) ) ) ;
        }
        catch( IOException | IllegalArgumentException e ) {
            log.error( "Could not read the saved scope {}", file, e ) ;
            return null ;
        }
    }

    public synchronized void save( Scope s ) throws IOException {

        ObjectNode n = JsonFiles.MAPPER.createObjectNode() ;
        n.put( "topicId", s.topicId() ) ;
        n.put( "batchSize", s.batchSize() ) ;
        n.put( "mode", s.mode().name() ) ;
        n.put( "ownAfterId", s.ownAfterId() ) ;
        n.put( "depAfterId", s.depAfterId() ) ;
        n.put( "savedAt", Instant.now().toString() ) ;

        File dir = file.getParentFile() ;
        if( dir != null && !dir.isDirectory() && !dir.mkdirs() ) {
            throw new IOException( "Could not create " + dir ) ;
        }
        synchronized( JsonFiles.lockFor( file ) ) {
            JsonFiles.writeAtomically( file, n ) ;
        }
    }

    private static Integer intOrNull( JsonNode n ) {
        return n == null || n.isNull() ? null : n.asInt() ;
    }
}
