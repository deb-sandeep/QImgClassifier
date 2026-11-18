package com.sandy.sconsole.qimgtools.apps.tagger.workspace;

import com.sandy.sconsole.qimgtools.apps.tagger.model.TagProposal;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagReply;
import com.sandy.sconsole.qimgtools.core.server.ServerQuestion;
import com.sandy.sconsole.qimgtools.core.workspace.JsonFiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The model's replies on questions whose tags are not saved on the server
 * yet, so that loading them again needs no new AI call. A JSON object keyed
 * by the question's numeric server id:
 * <pre>
 * "6120" : {
 *   "questionId", "sourceId", "time", "topicId", "scopeTopicId", "build",
 *   "textHash", "model", "tags" : [ ... ], "confidence", "noTagFits",
 *   "framing", "otherTopic" : { "topicId", "topicName", "reason" }, "reason",
 *   "deferred" : { "note", "time" }
 * }
 * </pre>
 * The replies are reused only while the question's topic, the scope and the
 * build of the topic's files it was run for ({@code c02.v01}), and its
 * transcription ({@code textHash}, SHA-256 of the text the LLM was shown)
 * are as they were. Entries from before transcriptions (with a
 * {@code cycle}, no {@code build}) are never reused. {@code deferred} is set
 * once the question was put off. An entry goes once its save is applied, so
 * the file holds only the unsaved backlog; the full history is in
 * {@link TagAuditLog}.
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
     * @param proposal the model's reply
     * @param deferral the note the question was deferred with; null if it
     *                 wasn't
     */
    public record Stored( TagProposal proposal, String deferral ) {}

    private final File       file ;
    private final ObjectNode root ;

    /** True if the file exists but could not be read; it is then left alone. */
    private final boolean unreadable ;

    /** The pending replies in {@code file}. */
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
     * What is stored for {@code q} run under topic {@code scopeTopicId} and
     * {@code build} on the transcription hashed {@code textHash}, if stored
     * while its topic was what it is now; null otherwise. A new build's tags
     * and rules may differ, and a new transcription may read otherwise, so
     * their replies don't carry over.
     */
    public synchronized Stored stored( ServerQuestion q, int scopeTopicId,
                                       String build, String textHash ) {

        JsonNode e = root.path( key( q ) ) ;
        if( !e.isObject() || !e.path( "tags" ).isArray()
            || e.path( "scopeTopicId" ).asInt( -1 ) != scopeTopicId
            || !build.equals( e.path( "build" ).asText() )
            || !textHash.equals( e.path( "textHash" ).asText() )
            || q.getTopicId() == null
            || e.path( "topicId" ).asInt( -1 ) != q.getTopicId() ) {
            return null ;
        }

        List<String> tags = new ArrayList<>() ;
        e.path( "tags" ).forEach( t -> tags.add( t.asText() ) ) ;

        JsonNode d     = e.get( "deferred" ) ;
        JsonNode o     = e.get( "otherTopic" ) ;
        TagReply reply = new TagReply(
                tags,
                TagReply.Confidence.of( e.path( "confidence" ).asText( "" ) ),
                e.path( "noTagFits" ).asBoolean(),
                e.path( "framing" ).asText( null ),
                o == null || !o.isObject() ? null
                : new TagReply.OtherTopic( o.path( "topicId" ).asInt(),
                                           o.path( "topicName" ).asText(),
                                           o.path( "reason" ).asText( null ) ),
                e.path( "reason" ).asText( null ) ) ;

        return new Stored( new TagProposal( e.path( "model" ).asText( null ),
                                            reply ),
                           d != null && d.isObject()
                           ? d.path( "note" ).asText( "" ) : null ) ;
    }

    /**
     * Store the reply on {@code q}, given under {@code build} on the
     * transcription hashed {@code textHash}, replacing anything stored.
     */
    public synchronized void save( ServerQuestion q, int scopeTopicId,
                                   String build, String textHash,
                                   TagProposal p ) throws IOException {

        ObjectNode e = root.putObject( key( q ) ) ;
        e.put( "questionId", q.getQuestionId() ) ;
        e.put( "sourceId", q.getSourceId() ) ;
        e.put( "time", Instant.now().toString() ) ;
        e.put( "topicId", q.getTopicId() ) ;
        e.put( "scopeTopicId", scopeTopicId ) ;
        e.put( "build", build ) ;
        e.put( "textHash", textHash ) ;
        e.put( "model", p.model() ) ;
        p.reply().tags().forEach( e.putArray( "tags" )::add ) ;
        e.put( "confidence", String.valueOf( p.reply().confidence() ) ) ;
        e.put( "noTagFits", p.reply().noTagFits() ) ;
        e.put( "framing", p.reply().framing() ) ;
        otherTopic( e, p.reply().otherTopic() ) ;
        e.put( "reason", p.reply().reason() ) ;
        write() ;
    }

    /** {@code o} as {@code n}'s {@code otherTopic}: an object, or null. */
    public static void otherTopic( ObjectNode n, TagReply.OtherTopic o ) {

        if( o == null ) {
            n.putNull( "otherTopic" ) ;
            return ;
        }
        n.putObject( "otherTopic" )
         .put( "topicId", o.topicId() )
         .put( "topicName", o.topicName() )
         .put( "reason", o.reason() ) ;
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

    /**
     * The server ids of the questions held out under topic
     * {@code scopeTopicId} and {@code build}: those the model said no tag
     * fits, or would move to another topic. They are held out until tagged
     * or moved. The set is the caller's to change.
     */
    public synchronized Set<Integer> heldOut( int scopeTopicId, String build ) {

        Set<Integer> ids = new LinkedHashSet<>() ;
        for( Iterator<String> it = root.fieldNames() ; it.hasNext() ; ) {
            String   id = it.next() ;
            JsonNode e  = root.get( id ) ;

            boolean held = e.path( "noTagFits" ).asBoolean()
                           || e.path( "otherTopic" ).isObject() ;
            if( held
                && e.path( "scopeTopicId" ).asInt( -1 ) == scopeTopicId
                && build.equals( e.path( "build" ).asText() ) ) {
                ids.add( Integer.valueOf( id ) ) ;
            }
        }
        return ids ;
    }

    /** Drop the entries of the questions {@code ids}; one write for them all. */
    public synchronized void removeIds( Collection<Integer> ids )
            throws IOException {

        boolean changed = false ;
        for( Integer id : ids ) {
            changed |= root.remove( String.valueOf( id ) ) != null ;
        }
        if( changed ) write() ;
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
