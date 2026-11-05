package com.sandy.sconsole.qimgtools.apps.tagger.workspace;

import com.sandy.sconsole.qimgtools.apps.tagger.model.TaggerSettings;
import com.sandy.sconsole.qimgtools.core.workspace.JsonFiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;

/**
 * The user's settings, kept across restarts in the workspace:
 * <pre>
 * { "autoSendToLlm", "batchSize" }
 * </pre>
 * A missing file or key reads as the default. Thread-safe.
 */
@Slf4j
public final class SettingsStore {

    private final File file ;

    public SettingsStore( File file ) {
        this.file = file ;
    }

    /**
     * The saved settings; the defaults if there are none or they can't be
     * read. A file with no batch size takes {@code oldBatch}, if any, and is
     * written with it at once, as that value goes with the old scope file's
     * next save.
     *
     * @param maxBatch the largest batch size allowed
     * @param oldBatch the batch size the scope file held; null if none
     */
    public synchronized TaggerSettings load( int maxBatch, Integer oldBatch ) {

        TaggerSettings d = TaggerSettings.DEFAULT ;
        JsonNode       n ;
        try {
            n = file.isFile() ? JsonFiles.MAPPER.readTree( file )
                              : JsonFiles.MAPPER.createObjectNode() ;
        }
        catch( IOException e ) {
            log.error( "Could not read the settings {}", file, e ) ;
            return d ;
        }

        JsonNode saved = n.get( "batchSize" ) ;
        int      batch = saved != null ? saved.asInt( d.batchSize() )
                       : oldBatch != null ? oldBatch : d.batchSize() ;

        TaggerSettings s = new TaggerSettings(
                n.path( "autoSendToLlm" ).asBoolean( d.autoSendToLlm() ),
                Math.max( 1, Math.min( maxBatch, batch ) ) ) ;

        if( saved == null ) {
            try {
                save( s ) ;
            }
            catch( IOException e ) {
                log.error( "Could not save the settings {}", file, e ) ;
            }
        }
        return s ;
    }

    public synchronized void save( TaggerSettings s ) throws IOException {

        ObjectNode n = JsonFiles.MAPPER.createObjectNode() ;
        n.put( "autoSendToLlm", s.autoSendToLlm() ) ;
        n.put( "batchSize", s.batchSize() ) ;

        File dir = file.getParentFile() ;
        if( dir != null && !dir.isDirectory() && !dir.mkdirs() ) {
            throw new IOException( "Could not create " + dir ) ;
        }
        synchronized( JsonFiles.lockFor( file ) ) {
            JsonFiles.writeAtomically( file, n ) ;
        }
    }
}
