package com.sandy.sconsole.qimgtools.core.workspace;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.time.Instant;

/**
 * How many loads an app's unattended run may make, as last chosen, so the
 * choice survives a restart. A JSON object:
 * <pre>
 * { "maxLoads" : 25, "savedAt" : "2026-11-24T09:30:00Z" }
 * </pre>
 * A missing or unreadable file reads as the default; a failed save is only
 * logged. Thread-safe.
 */
@Slf4j
public final class UnattendedLoads {

    private final File file ;

    public UnattendedLoads( File file ) {
        this.file = file ;
    }

    /**
     * The number last saved, brought into 1-{@code max}; {@code dflt} if
     * none is saved or the file can't be read.
     */
    public int load( int dflt, int max ) {

        synchronized( JsonFiles.lockFor( file ) ) {
            try {
                if( !file.exists() ) return dflt ;
                ObjectNode root = JsonFiles.readObject( file ) ;
                int n = root.path( "maxLoads" ).asInt( dflt ) ;
                return Math.min( Math.max( n, 1 ), max ) ;
            }
            catch( IOException e ) {
                log.warn( "Could not read {} - using {} loads", file, dflt,
                          e ) ;
                return dflt ;
            }
        }
    }

    /** Save {@code maxLoads}, replacing the number saved before. */
    public void save( int maxLoads ) {

        ObjectNode root = JsonFiles.MAPPER.createObjectNode() ;
        root.put( "maxLoads", maxLoads ) ;
        root.put( "savedAt", Instant.now().toString() ) ;

        File dir = file.getParentFile() ;
        synchronized( JsonFiles.lockFor( file ) ) {
            try {
                if( dir != null && !dir.isDirectory() && !dir.mkdirs() ) {
                    throw new IOException( "Could not create " + dir ) ;
                }
                JsonFiles.writeAtomically( file, root ) ;
            }
            catch( IOException e ) {
                log.warn( "Could not save the unattended loads to {}", file,
                          e ) ;
            }
        }
    }
}
