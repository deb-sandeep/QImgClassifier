package com.sandy.sconsole.qimgtools.apps.nxrclassifier.workspace;

import com.sandy.sconsole.qimgtools.apps.nxrclassifier.model.NXRSaveResult;
import com.sandy.sconsole.qimgtools.apps.nxrclassifier.model.NXRUpdate;
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
 * The NXR classifier's audit trail: every label the server took, one JSON
 * object per line (JSON Lines), only ever appended to. The app never reads
 * it back. Lines:
 * <pre>
 * { "time", "serverId", "questionId", "sourceId", "problemType",
 *   "suggested" : "N" | "X" | "R" | null, "confidence", "method",
 *   "reason", "value" : "N" | "X" | "R", "by" : "classifier" | "user",
 *   "status" : "APPLIED" | "UNCHANGED" }
 * </pre>
 * {@code confidence}, {@code method} and {@code reason} are the
 * classifier's (null if it didn't run), so a later model can re-run only
 * its {@code MODEL} labels, and a re-run can leave the user's alone.
 * Thread-safe.
 */
public final class NXRAuditLog {

    /** One line per entry. */
    private static final ObjectWriter WRITER =
            JsonFiles.MAPPER.writer()
                            .without( SerializationFeature.INDENT_OUTPUT ) ;

    private final File file ;

    public NXRAuditLog( File file ) {
        this.file = file ;
    }

    /** Record that the server took {@code u} with answer {@code r}. */
    public void saved( NXRUpdate u, NXRSaveResult r ) throws IOException {

        ServerQuestion q    = u.item().question() ;
        ObjectNode     line = JsonFiles.MAPPER.createObjectNode() ;

        line.put( "time", Instant.now().toString() ) ;
        line.put( "serverId", q.getId() ) ;
        line.put( "questionId", q.getQuestionId() ) ;
        line.put( "sourceId", q.getSourceId() ) ;
        line.put( "problemType", q.getProblemType() ) ;
        line.put( "suggested", u.suggested() == null ? null
                                                     : u.suggested().name() ) ;
        if( u.classifier() != null ) {
            line.put( "confidence", u.classifier().confidence() ) ;
            line.put( "method", u.classifier().method().name() ) ;
            line.put( "reason", u.classifier().reason() ) ;
        }
        else {
            line.putNull( "confidence" ) ;
            line.putNull( "method" ) ;
            line.putNull( "reason" ) ;
        }
        line.put( "value", u.value().name() ) ;
        line.put( "by", u.byUser() ? "user" : "classifier" ) ;
        line.put( "status", r.status().name() ) ;
        append( line ) ;
    }

    private synchronized void append( ObjectNode line ) throws IOException {

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
