package com.sandy.sconsole.qimgtools.apps.transcriber.workspace;

import com.sandy.sconsole.qimgtools.core.workspace.JsonFiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;

/**
 * A JSON object file of entries by key, held in memory and written whole
 * on each change. A file that can't be read is left alone: the store
 * starts empty and is never written, so its contents aren't lost, and each
 * change throws. Thread-safe.
 */
@Slf4j
abstract class JsonStore {

    private final File       file ;
    private final ObjectNode root ;
    private final boolean    readable ;

    JsonStore( File file, String what ) {

        ObjectNode root     = null ;
        boolean    readable = true ;

        this.file = file ;
        synchronized( JsonFiles.lockFor( file ) ) {
            try {
                root = JsonFiles.readObject( file ) ;
            }
            catch( IOException e ) {
                log.error( "Could not read the {} {} - starting without " +
                           "them, and leaving the file as it is", what, file,
                           e ) ;
                readable = false ;
            }
        }
        this.root     = root != null ? root
                                     : JsonFiles.MAPPER.createObjectNode() ;
        this.readable = readable ;
    }

    /** The entry under {@code key}; null if there is none. */
    synchronized JsonNode entry( String key ) {
        JsonNode n = root.get( key ) ;
        return n == null ? null : n.deepCopy() ;
    }

    /** The number of entries. */
    synchronized int size() {
        return root.size() ;
    }

    /** Put {@code node} under {@code key}, and write the file. */
    synchronized void put( String key, ObjectNode node ) throws IOException {
        root.set( key, node ) ;
        write() ;
    }

    /**
     * Drop the entries under {@code keys}, and write the file if any was
     * there.
     */
    synchronized void remove( Iterable<String> keys ) throws IOException {

        boolean changed = false ;
        for( String key : keys ) changed |= root.remove( key ) != null ;
        if( changed ) write() ;
    }

    private void write() throws IOException {

        if( !readable ) {
            throw new IOException( file + " could not be read at startup, " +
                                   "so it is not overwritten" ) ;
        }
        File dir = file.getParentFile() ;
        if( dir != null && !dir.isDirectory() && !dir.mkdirs() ) {
            throw new IOException( "Could not create " + dir ) ;
        }
        synchronized( JsonFiles.lockFor( file ) ) {
            JsonFiles.writeAtomically( file, root ) ;
        }
    }
}
