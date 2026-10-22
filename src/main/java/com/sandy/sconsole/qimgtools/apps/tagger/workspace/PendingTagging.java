package com.sandy.sconsole.qimgtools.apps.tagger.workspace;

import com.sandy.sconsole.qimgtools.apps.tagger.model.TagOpinions;
import com.sandy.sconsole.qimgtools.core.server.ServerQuestion;
import com.sandy.sconsole.qimgtools.core.workspace.JsonFiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;

/**
 * The tiers' replies on questions whose tags are not saved on the server
 * yet, so that loading them again needs no new AI call. A JSON object keyed
 * by the question's numeric server id:
 * <pre>
 * "6120" : {
 *   "questionId", "sourceId", "time",
 *   "key", "topicId", "scopeTopicId", "dependent",
 *   "tier1" : { ... see TagJson ... }, "tier2" : { ... }, "tier2Failure",
 *   "gapSettled" : true | false,
 *   "deferred" : { "note", "time" }
 * }
 * </pre>
 * The replies are reused only while the question's key and topic, and the
 * scope it was run for, are as they were. {@code gapSettled} is set once the
 * user has decided on the gap the accepted tier proposed (its reply then
 * holds the tag taken, if any); {@code deferred} once the question was put
 * off. An entry goes once its save is applied, so the file holds only the
 * unsaved backlog; the full history is in {@link TagAuditLog}.
 * <p>
 * Read once when constructed and rewritten (atomically) on every change. A
 * file that cannot be parsed reads as empty but is never overwritten: every
 * write then throws. Thread-safe.
 */
@Slf4j
public final class PendingTagging {

    /**
     * A stored entry.
     *
     * @param opinions   the tiers' replies
     * @param gapSettled true once the accepted tier's gap was decided on
     * @param deferral   the note the question was deferred with; null if it
     *                   wasn't
     */
    public record Stored( TagOpinions opinions, boolean gapSettled,
                          String deferral ) {}

    private final File       file ;
    private final ObjectNode root ;

    /** True if the file exists but could not be read; it is then left alone. */
    private final boolean unreadable ;

    public PendingTagging( File file ) {

        ObjectNode loaded ;
        boolean    failed = false ;

        this.file = file ;
        try {
            loaded = JsonFiles.readObject( file ) ;
        }
        catch( IOException e ) {
            log.error( "Could not read the pending tagging {} - it will not " +
                       "be written to", file, e ) ;
            loaded = JsonFiles.MAPPER.createObjectNode() ;
            failed = true ;
        }
        this.root       = loaded ;
        this.unreadable = failed ;
    }

    /**
     * What is stored for {@code q} run under topic {@code scopeTopicId}, if
     * stored while its key and topic were what they are now; null
     * otherwise.
     */
    public synchronized Stored stored( ServerQuestion q, int scopeTopicId ) {

        JsonNode e = root.path( key( q ) ) ;
        if( !e.isObject()
            || e.path( "scopeTopicId" ).asInt( -1 ) != scopeTopicId
            || !Objects.equals( TagJson.text( e.get( "key" ) ), q.getAnswer() )
            || q.getTopicId() == null
            || e.path( "topicId" ).asInt( -1 ) != q.getTopicId() ) {
            return null ;
        }
        TagOpinions o = TagJson.readOpinions( e ) ;
        if( o == null ) return null ;

        JsonNode d = e.get( "deferred" ) ;
        return new Stored( o, e.path( "gapSettled" ).asBoolean( false ),
                           d != null && d.isObject()
                           ? d.path( "note" ).asText( "" ) : null ) ;
    }

    /** Store the replies on {@code q}, replacing anything stored. */
    public synchronized void save( ServerQuestion q, int scopeTopicId,
                                   boolean dependent, TagOpinions o )
            throws IOException {

        ObjectNode e = root.putObject( key( q ) ) ;
        e.put( "questionId", q.getQuestionId() ) ;
        e.put( "sourceId", q.getSourceId() ) ;
        e.put( "time", Instant.now().toString() ) ;
        e.put( "key", q.getAnswer() ) ;
        e.put( "topicId", q.getTopicId() ) ;
        e.put( "scopeTopicId", scopeTopicId ) ;
        e.put( "dependent", dependent ) ;
        TagJson.putOpinions( e, o ) ;
        e.put( "gapSettled", false ) ;
        write() ;
    }

    /**
     * Note that the gap on {@code q} is decided, with {@code o} the replies
     * as they now stand (the tag taken added).
     */
    public synchronized void settleGap( ServerQuestion q, TagOpinions o )
            throws IOException {

        JsonNode n = root.get( key( q ) ) ;
        if( !( n instanceof ObjectNode e ) ) return ;
        TagJson.putOpinions( e, o ) ;
        e.put( "gapSettled", true ) ;
        write() ;
    }

    /** Note {@code q} as deferred with {@code note}; null to undo it. */
    public synchronized void defer( ServerQuestion q, String note )
            throws IOException {

        JsonNode n = root.get( key( q ) ) ;
        if( !( n instanceof ObjectNode e ) ) return ;
        if( note == null ) {
            e.remove( "deferred" ) ;
        }
        else {
            e.putObject( "deferred" ).put( "note", note )
             .put( "time", Instant.now().toString() ) ;
        }
        write() ;
    }

    /** Drop the entries of {@code questions}; one write for them all. */
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
