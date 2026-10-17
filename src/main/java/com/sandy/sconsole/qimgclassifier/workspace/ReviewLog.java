package com.sandy.sconsole.qimgclassifier.workspace;

import com.sandy.sconsole.qimgclassifier.model.ReviewDecision;
import com.sandy.sconsole.qimgclassifier.model.ReviewResult;
import com.sandy.sconsole.qimgclassifier.model.ReviewVerdict;
import com.sandy.sconsole.qimgclassifier.model.Topic;
import com.sandy.sconsole.qimgclassifier.model.ServerQuestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The topic reviewer's record of each question it has looked at, in a JSON
 * object keyed by the question's numeric server id:
 * <pre>
 * "5012" : {
 *   "questionId" : "C/SCA/74", "sourceId" : "...",
 *   "server"     : { "topicId", "topicName", "actor", "model", "confidence" },
 *   "verdict"    : { "model", "time", "topics" : [ { "id", "topicName",
 *                                                    "confidence" } ] },
 *   "decision"   : { "topicId", "topicName", "actor" : "AI" | "User",
 *                    "model", "confidence",
 *                    "status" : "APPLIED" | "UNCHANGED",
 *                    "topicReviewNeeded", "time" }
 * }
 * </pre>
 * {@code server} is the association the model's verdict was compared with;
 * {@code decision} is the user's, once the server has taken it, with the
 * provenance sent and the server's answer. A decision made by the user
 * against the model is sent as User, so this file is where the model's
 * verdict on it is kept.
 * <p>
 * Read once when constructed and rewritten (atomically) on every change. A
 * file that cannot be parsed reads as empty but is never overwritten: every
 * write then throws. Thread-safe.
 */
@Slf4j
public final class ReviewLog {

    private final File       file ;
    private final ObjectNode root ;

    /** True if the file exists but could not be read; it is then left alone. */
    private final boolean unreadable ;

    public ReviewLog( File file ) {

        ObjectNode loaded ;
        boolean    failed = false ;

        this.file = file ;
        try {
            loaded = JsonFiles.readObject( file ) ;
        }
        catch( IOException e ) {
            log.error( "Could not read the review log {} - it will not be " +
                       "written to", file, e ) ;
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

        List<Topic> topics = new ArrayList<>() ;
        for( JsonNode t : verdict.path( "topics" ) ) {
            Topic topic = new Topic() ;
            topic.setId( t.path( "id" ).asInt() ) ;
            topic.setTopicName( t.path( "topicName" ).asText( null ) ) ;
            topic.setMatchProbability( t.path( "confidence" ).asInt() / 100f ) ;
            topics.add( topic ) ;
        }
        if( topics.isEmpty() ) return null ;
        return new ReviewVerdict( verdict.path( "model" ).asText( null ),
                                  topics ) ;
    }

    /**
     * Store the model's verdict on {@code q}, replacing any earlier one and
     * dropping an earlier decision.
     */
    public synchronized void saveVerdict( ServerQuestion q,
                                          ReviewVerdict v )
            throws IOException {

        ObjectNode entry = root.putObject( key( q ) ) ;
        entry.put( "questionId", q.getQuestionId() ) ;
        entry.put( "sourceId", q.getSourceId() ) ;

        ObjectNode server = entry.putObject( "server" ) ;
        server.put( "topicId", q.getTopicId() ) ;
        server.put( "topicName", q.getTopicName() ) ;
        server.put( "actor", q.getTopicAssocActor() ) ;
        server.put( "model", q.getTopicAssocAiModel() ) ;
        server.put( "confidence", q.getTopicAssocConfidence() ) ;

        ObjectNode verdict = entry.putObject( "verdict" ) ;
        verdict.put( "model", v.model() ) ;
        verdict.put( "time", Instant.now().toString() ) ;

        ArrayNode list = verdict.putArray( "topics" ) ;
        for( Topic t : v.topics() ) {
            list.addObject()
                .put( "id", t.getId() )
                .put( "topicName", t.getTopicName() )
                .put( "confidence", Math.round( t.getMatchProbability() * 100 ) ) ;
        }
        save() ;
    }

    /**
     * Store the user's decision {@code d} on question {@code q} (as fetched)
     * and the server's answer {@code r}.
     */
    public synchronized void saveDecision( ServerQuestion q, ReviewDecision d,
                                           ReviewResult r ) throws IOException {

        JsonNode existing = root.get( key( q ) ) ;
        ObjectNode entry  = existing instanceof ObjectNode o
                          ? o : root.putObject( key( q ) ) ;

        if( !entry.has( "questionId" ) ) {
            entry.put( "questionId", q.getQuestionId() ) ;
            entry.put( "sourceId", q.getSourceId() ) ;
        }
        entry.putObject( "decision" )
             .put( "topicId", d.topic().getId() )
             .put( "topicName", d.topic().getTopicName() )
             .put( "actor", d.actor() )
             .put( "model", d.model() )
             .put( "confidence", d.confidence() )
             .put( "status", r.status().name() )
             .put( "topicReviewNeeded", r.topicReviewNeeded() )
             .put( "time", Instant.now().toString() ) ;
        save() ;
    }

    private static String key( ServerQuestion q ) {
        return String.valueOf( q.getId() ) ;
    }

    /** Under {@code this}: rewrite the file. */
    private void save() throws IOException {

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
