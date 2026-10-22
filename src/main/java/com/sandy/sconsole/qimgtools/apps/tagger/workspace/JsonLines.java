package com.sandy.sconsole.qimgtools.apps.tagger.workspace;

import com.sandy.sconsole.qimgtools.core.server.ServerQuestion;
import com.sandy.sconsole.qimgtools.core.workspace.JsonFiles;

import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * An append-only file of JSON objects, one per line (JSON Lines), never
 * read back by the tagger. Thread-safe.
 */
class JsonLines {

    /** One line per entry. */
    private static final ObjectWriter WRITER =
            JsonFiles.MAPPER.writer()
                            .without( SerializationFeature.INDENT_OUTPUT ) ;

    private final File file ;

    JsonLines( File file ) {
        this.file = file ;
    }

    /** A new line for {@code event} on question {@code q}. */
    static ObjectNode entry( String event, ServerQuestion q ) {

        ObjectNode line = JsonFiles.MAPPER.createObjectNode() ;
        line.put( "time", Instant.now().toString() ) ;
        line.put( "event", event ) ;
        line.put( "serverId", q.getId() ) ;
        line.put( "questionId", q.getQuestionId() ) ;
        line.put( "sourceId", q.getSourceId() ) ;
        return line ;
    }

    synchronized void append( ObjectNode line ) throws IOException {

        File dir = file.getParentFile() ;
        if( dir != null && !dir.isDirectory() && !dir.mkdirs() ) {
            throw new IOException( "Could not create " + dir ) ;
        }
        Files.writeString( file.toPath(),
                           WRITER.writeValueAsString( line ) + "\n",
                           StandardCharsets.UTF_8,
                           StandardOpenOption.CREATE,
                           StandardOpenOption.APPEND ) ;
    }
}
