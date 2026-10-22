package com.sandy.sconsole.qimgtools.apps.topicreviewer.workspace;

import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewDecision;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewResult;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewOpinions;
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
 * The topic reviewer's audit trail: every verdict the model gives and every
 * decision the server takes, one JSON object per line (JSON Lines), only
 * ever appended to. The reviewer never reads it back; it is there to look
 * into what was done. Lines:
 * <pre>
 * { "time", "event" : "verdict", "serverId", "questionId", "sourceId",
 *   "server"  : { "topicId", "topicName", "actor", "model", "confidence" },
 *   "verdict" : { "model", "chosenTopicId",
 *                 "topics" : [ { "id", "topicName", "confidence" } ] },
 *   "tieBreaker" : { ... as verdict ... }, "tieBreakerFailure" }
 * { "time", "event" : "decision", "serverId", "questionId", "sourceId",
 *   "decision" : { "topicId", "topicName", "actor" : "AI" | "User",
 *                  "model", "confidence" },
 *   "status" : "APPLIED" | "UNCHANGED", "topicReviewNeeded" }
 * </pre>
 * {@code server} is the association the verdict was compared with;
 * {@code tieBreaker} (or why it gave none) is there only if the review model
 * differed from it. A decision made by the user against the models is sent
 * as User, so the verdict line before it is where their opinions are kept.
 * Thread-safe.
 */
public final class ReviewAuditLog {

    /** One line per entry. */
    private static final ObjectWriter WRITER =
            JsonFiles.MAPPER.writer()
                            .without( SerializationFeature.INDENT_OUTPUT ) ;

    private final File file ;

    public ReviewAuditLog( File file ) {
        this.file = file ;
    }

    /** Record the AI's opinions {@code o} on question {@code q}. */
    public void verdict( ServerQuestion q, ReviewOpinions o )
            throws IOException {

        ObjectNode line = entry( "verdict", q ) ;
        line.set( "server", ReviewJson.server( q ) ) ;
        ReviewJson.putOpinions( line, o ) ;
        append( line ) ;
    }

    /**
     * Record the user's decision {@code d} on question {@code q} (as fetched)
     * and the server's answer {@code r}.
     */
    public void decision( ServerQuestion q, ReviewDecision d, ReviewResult r )
            throws IOException {

        ObjectNode line = entry( "decision", q ) ;
        line.putObject( "decision" )
            .put( "topicId", d.topic().getId() )
            .put( "topicName", d.topic().getTopicName() )
            .put( "actor", d.actor() )
            .put( "model", d.model() )
            .put( "confidence", d.confidence() ) ;
        line.put( "status", r.status().name() ) ;
        line.put( "topicReviewNeeded", r.topicReviewNeeded() ) ;
        append( line ) ;
    }

    private static ObjectNode entry( String event, ServerQuestion q ) {

        ObjectNode line = JsonFiles.MAPPER.createObjectNode() ;
        line.put( "time", Instant.now().toString() ) ;
        line.put( "event", event ) ;
        line.put( "serverId", q.getId() ) ;
        line.put( "questionId", q.getQuestionId() ) ;
        line.put( "sourceId", q.getSourceId() ) ;
        return line ;
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
