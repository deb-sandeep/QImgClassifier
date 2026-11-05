package com.sandy.sconsole.qimgtools.apps.tagger.workspace;

import com.sandy.sconsole.qimgtools.apps.tagger.model.Scope;
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
 * { "topicId", "afterId", "savedAt" }
 * </pre>
 * The cursor is null until a Load moves it. Thread-safe.
 */
@Slf4j
public final class ScopeStore {

    private final File file ;

    public ScopeStore( File file ) {
        this.file = file ;
    }

    /** The saved scope; null if there is none, or it can't be read. */
    public synchronized Scope load() {

        if( !file.isFile() ) return null ;
        try {
            JsonNode n = JsonFiles.MAPPER.readTree( file ) ;
            if( !n.hasNonNull( "topicId" ) ) return null ;

            JsonNode after = n.get( "afterId" ) ;

            return new Scope( n.get( "topicId" ).asInt(),
                              after == null || after.isNull()
                              ? null : after.asInt() ) ;
        }
        catch( IOException e ) {
            log.error( "Could not read the saved scope {}", file, e ) ;
            return null ;
        }
    }

    /**
     * The batch size a scope file written before it moved to the settings
     * still holds; null if it holds none.
     */
    public synchronized Integer oldBatchSize() {

        if( !file.isFile() ) return null ;
        try {
            JsonNode b = JsonFiles.MAPPER.readTree( file ).get( "batchSize" ) ;
            return b != null && b.isInt() ? b.asInt() : null ;
        }
        catch( IOException e ) {
            log.error( "Could not read the saved scope {}", file, e ) ;
            return null ;
        }
    }

    public synchronized void save( Scope s ) throws IOException {

        ObjectNode n = JsonFiles.MAPPER.createObjectNode() ;
        n.put( "topicId", s.topicId() ) ;
        n.put( "afterId", s.afterId() ) ;
        n.put( "savedAt", Instant.now().toString() ) ;

        File dir = file.getParentFile() ;
        if( dir != null && !dir.isDirectory() && !dir.mkdirs() ) {
            throw new IOException( "Could not create " + dir ) ;
        }
        synchronized( JsonFiles.lockFor( file ) ) {
            JsonFiles.writeAtomically( file, n ) ;
        }
    }
}
