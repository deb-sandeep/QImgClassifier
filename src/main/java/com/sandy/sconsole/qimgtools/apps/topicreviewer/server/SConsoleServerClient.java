package com.sandy.sconsole.qimgtools.apps.topicreviewer.server;

import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewDecision;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewResult;
import com.sandy.sconsole.qimgtools.core.model.Topic;
import com.sandy.sconsole.qimgtools.core.server.SConsoleServer;
import com.sandy.sconsole.qimgtools.core.server.SConsoleServer.ServerException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Client for the SConsole server's master APIs that the topic reviewer uses
 * (see {@code docs/required-server-apis.md} and the server's
 * {@code doc/feature-enhancements/2026-10-16/question-topic-review/}
 * {@code api-changes.md}): the topic list, the topic review queue, and
 * confirming reviews. Calls go through {@link SConsoleServer}, which
 * turns a failed one into a {@link ServerException}. Thread-safe.
 */
@Slf4j
public class SConsoleServerClient {

    /** Most questions one {@link #pending} call may ask for. */
    public static final int MAX_LIMIT = 500 ;

    /** Most decisions one {@link #review} call may carry. */
    public static final int MAX_REVIEWS = 100 ;

    private final SConsoleServer server ;

    public SConsoleServerClient( SConsoleServer server ) {
        this.server = server ;
    }

    /** The server this client talks to. */
    public SConsoleServer server() {
        return server ;
    }

    /** The topics of syllabus {@code syllabusName}, e.g. "IIT Physics". */
    public List<Topic> topics( String syllabusName ) throws ServerException {

        List<ServerTopic> topics = server.get(
                "/Master/Topic/All?syllabusName=" +
                SConsoleServer.encode( syllabusName ),
                new TypeReference<List<ServerTopic>>() {} ) ;
        return topics.stream().map( ServerTopic::toTopic ).toList() ;
    }

    /**
     * The first {@code limit} (1-{@value #MAX_LIMIT}) questions, by id, whose
     * topic association awaits review. The server uses {@code topicIds} if
     * any are given, else {@code syllabusNames}.
     *
     * @param syllabusNames e.g. "IIT Physics"
     */
    public PendingResult pending( List<String> syllabusNames,
                                  List<Integer> topicIds, int limit )
            throws ServerException {

        StringBuilder path = new StringBuilder(
                "/Master/Question/TopicReview/Pending?limit=" + limit ) ;
        if( !syllabusNames.isEmpty() ) {
            path.append( "&syllabusNames=" )
                .append( SConsoleServer.encode(
                        String.join( ",", syllabusNames ) ) ) ;
        }
        if( !topicIds.isEmpty() ) {
            path.append( "&topicIds=" )
                .append( topicIds.stream()
                                 .map( String::valueOf )
                                 .collect( Collectors.joining( "," ) ) ) ;
        }
        return server.get( path.toString(),
                           new TypeReference<PendingResult>() {} ) ;
    }

    /**
     * Confirm {@code decisions} (1-{@value #MAX_REVIEWS}): each question gets
     * its decision's topic and provenance. Items are independent; a rejected
     * one doesn't affect the others.
     *
     * @return the server's result for each decision, in the same order
     */
    public List<ReviewResult> review( List<ReviewDecision> decisions )
            throws ServerException {

        ObjectNode body    = server.mapper().createObjectNode() ;
        ArrayNode  reviews = body.putArray( "reviews" ) ;

        for( ReviewDecision d : decisions ) {
            ObjectNode r = reviews.addObject()
                                  .put( "questionId", d.item().id() )
                                  .put( "topicId", d.topic().getId() )
                                  .put( "topicAssocActor", d.actor() ) ;
            if( d.model() != null ) r.put( "topicAssocAiModel", d.model() ) ;
            r.put( "topicAssocConfidence", d.confidence() ) ;
        }

        JsonNode data = server.post( "/Master/Question/TopicReview", body,
                                     new TypeReference<JsonNode>() {} ) ;
        List<ReviewResult> results = server.mapper().convertValue(
                data.path( "results" ),
                new TypeReference<List<ReviewResult>>() {} ) ;

        if( !matches( results, decisions ) ) {
            log.error( "Review reply has {} results for {} items: {}",
                       results == null ? 0 : results.size(),
                       decisions.size(), data ) ;
            throw new ServerException( "unexpected reply from the server" ) ;
        }
        return results ;
    }

    /** True if there is one result per decision, in the same order. */
    private static boolean matches( List<ReviewResult> results,
                                    List<ReviewDecision> decisions ) {

        if( results == null || results.size() != decisions.size() ) {
            return false ;
        }
        for( int i = 0 ; i < results.size() ; i++ ) {
            ReviewResult r = results.get( i ) ;
            if( r == null || r.status() == null
                || r.questionId() != decisions.get( i ).item().id() ) {
                return false ;
            }
        }
        return true ;
    }
}
