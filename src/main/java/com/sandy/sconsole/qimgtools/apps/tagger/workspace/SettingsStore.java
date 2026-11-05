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
 * { "autoSendToLlm" }
 * </pre>
 * A missing file or key reads as the default. Thread-safe.
 */
@Slf4j
public final class SettingsStore {

    private final File file ;

    public SettingsStore( File file ) {
        this.file = file ;
    }

    /** The saved settings; the defaults if there are none or they can't be read. */
    public synchronized TaggerSettings load() {

        TaggerSettings d = TaggerSettings.DEFAULT ;
        if( !file.isFile() ) return d ;
        try {
            JsonNode n = JsonFiles.MAPPER.readTree( file ) ;
            return new TaggerSettings(
                    n.path( "autoSendToLlm" ).asBoolean( d.autoSendToLlm() ) ) ;
        }
        catch( IOException e ) {
            log.error( "Could not read the settings {}", file, e ) ;
            return d ;
        }
    }

    public synchronized void save( TaggerSettings s ) throws IOException {

        ObjectNode n = JsonFiles.MAPPER.createObjectNode() ;
        n.put( "autoSendToLlm", s.autoSendToLlm() ) ;

        File dir = file.getParentFile() ;
        if( dir != null && !dir.isDirectory() && !dir.mkdirs() ) {
            throw new IOException( "Could not create " + dir ) ;
        }
        synchronized( JsonFiles.lockFor( file ) ) {
            JsonFiles.writeAtomically( file, n ) ;
        }
    }
}
