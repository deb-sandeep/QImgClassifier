package com.sandy.sconsole.qimgtools.apps.topicreviewer.workspace;

import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewVerdict;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ServerQuestion;
import com.sandy.sconsole.qimgtools.core.workspace.JsonFiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.Collection;

/**
 * The model's verdicts on questions whose review is not settled on the
 * server yet, so that loading them again needs no new AI call. A JSON object
 * keyed by the question's numeric server id:
 * <pre>
 * "5012" : {
 *   "questionId" : "C/SCA/74", "sourceId" : "...", "time" : "...",
 *   "server"     : { "topicId", "topicName", "actor", "model", "confidence" },
 *   "verdict"    : { "model", "topics" : [ { "id", "topicName",
 *                                            "confidence" } ] }
 * }
 * </pre>
 * {@code server} is the association the verdict was compared with; the
 * verdict is reused only while the question still has that topic. An entry
 * goes once the server has taken a decision on the question and cleared its
 * review flag, so the file holds only the unsaved backlog; the full history
 * is in {@link ReviewAuditLog}.
 * <p>
 * Read once when constructed and rewritten (atomically) on every change. A
 * file that cannot be parsed reads as empty but is never overwritten: every
 * write then throws. Thread-safe.
 */
@Slf4j
public final class PendingVerdicts {

    private final File       file ;
    private final ObjectNode root ;

    /** True if the file exists but could not be read; it is then left alone. */
    private final boolean unreadable ;

    public PendingVerdicts( File file ) {

        ObjectNode loaded ;
        boolean    failed = false ;

        this.file = file ;
        try {
            loaded = JsonFiles.readObject( file ) ;
        }
        catch( IOException e ) {
            log.error( "Could not read the pending verdicts {} - it will not " +
                       "be written to", file, e ) ;
            loaded = JsonFiles.MAPPER.createObjectNode() ;
            failed = true ;
        }
        this.root       = loaded ;
        this.unreadable = failed ;
    }

    /**
     * The model's verdict on question {@code q}, if one was stored while the
     * question's server topic was the one it has now; null otherwise.
     */
    public synchronized ReviewVerdict verdict( ServerQuestion q ) {

        JsonNode entry   = root.path( key( q ) ) ;
        JsonNode verdict = entry.path( "verdict" ) ;
        if( !verdict.isObject() ) return null ;

        JsonNode topicId = entry.path( "server" ).path( "topicId" ) ;
        if( q.getTopicId() == null || topicId.asInt( -1 ) != q.getTopicId() ) {
            return null ;
        }
        return ReviewJson.readVerdict( verdict ) ;
    }

    /** Store the model's verdict on {@code q}, replacing any earlier one. */
    public synchronized void save( ServerQuestion q, ReviewVerdict v )
            throws IOException {

        ObjectNode entry = root.putObject( key( q ) ) ;
        entry.put( "questionId", q.getQuestionId() ) ;
        entry.put( "sourceId", q.getSourceId() ) ;
        entry.put( "time", Instant.now().toString() ) ;
        entry.set( "server", ReviewJson.server( q ) ) ;
        entry.set( "verdict", ReviewJson.verdict( v ) ) ;
        write() ;
    }

    /** Drop the verdicts on {@code questions}; one write for them all. */
    public synchronized void remove( Collection<ServerQuestion> questions )
            throws IOException {

        boolean changed = false ;
        for( ServerQuestion q : questions ) {
            changed |= root.remove( key( q ) ) != null ;
        }
        if( changed ) write() ;
    }

    private static String key( ServerQuestion q ) {
        return String.valueOf( q.getId() ) ;
    }

    /** Under {@code this}: rewrite the file. */
    private void write() throws IOException {

        if( unreadable ) {
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
