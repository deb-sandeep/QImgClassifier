package com.sandy.sconsole.qimgtools.apps.topicreviewer.workspace;

import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewOpinions;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewVerdict;
import com.sandy.sconsole.qimgtools.core.server.ServerQuestion;
import com.sandy.sconsole.qimgtools.core.model.Topic;
import com.sandy.sconsole.qimgtools.core.workspace.JsonFiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The JSON form of a question's server association and of the AI's
 * opinions, shared by {@link ReviewAuditLog} and {@link PendingVerdicts}.
 */
@Slf4j
final class ReviewJson {

    private ReviewJson() {}

    /** {@code q}'s association: topicId, topicName, actor, model, confidence. */
    static ObjectNode server( ServerQuestion q ) {
        return JsonFiles.MAPPER.createObjectNode()
                .put( "topicId", q.getTopicId() )
                .put( "topicName", q.getTopicName() )
                .put( "actor", q.getTopicAssocActor() )
                .put( "model", q.getTopicAssocAiModel() )
                .put( "confidence", q.getTopicAssocConfidence() ) ;
    }

    /**
     * Put {@code o} into {@code node}: the reviewer's verdict as
     * {@code verdict}, and the tie-breaker's as {@code tieBreaker} or why it
     * gave none as {@code tieBreakerFailure}, when there is one.
     */
    static void putOpinions( ObjectNode node, ReviewOpinions o ) {

        node.set( "verdict", verdict( o.reviewer() ) ) ;
        if( o.tieBreaker() != null ) {
            node.set( "tieBreaker", verdict( o.tieBreaker() ) ) ;
        }
        if( o.tieBreakerFailure() != null ) {
            node.put( "tieBreakerFailure", o.tieBreakerFailure() ) ;
        }
    }

    /**
     * The opinions written by {@link #putOpinions} into {@code node}; null if
     * the reviewer's verdict can't be read.
     */
    static ReviewOpinions readOpinions( JsonNode node ) {

        JsonNode reviewer = node.path( "verdict" ) ;
        if( !reviewer.isObject() ) return null ;
        ReviewVerdict r = readVerdict( reviewer ) ;
        if( r == null ) return null ;

        JsonNode      tb = node.path( "tieBreaker" ) ;
        ReviewVerdict t  = tb.isObject() ? readVerdict( tb ) : null ;

        return new ReviewOpinions( r, t, node.path( "tieBreakerFailure" )
                                             .asText( null ) ) ;
    }

    /**
     * {@code v} as model, topics (id, topicName, confidence) and the id of
     * the topic chosen from them.
     */
    static ObjectNode verdict( ReviewVerdict v ) {

        ObjectNode node = JsonFiles.MAPPER.createObjectNode() ;
        node.put( "model", v.model() ) ;
        node.put( "chosenTopicId", v.chosen().getId() ) ;

        ArrayNode list = node.putArray( "topics" ) ;
        for( Topic t : v.topics() ) {
            list.addObject()
                .put( "id", t.getId() )
                .put( "topicName", t.getTopicName() )
                .put( "confidence",
                      Math.round( t.getMatchProbability() * 100 ) ) ;
        }
        return node ;
    }

    /**
     * The verdict written by {@link #verdict}, its topic chosen afresh with
     * the topic sequence as it is now; null if it has no topics or the
     * sequence can't be read (logged).
     */
    static ReviewVerdict readVerdict( JsonNode node ) {

        List<Topic> topics = new ArrayList<>() ;
        for( JsonNode t : node.path( "topics" ) ) {
            Topic topic = new Topic() ;
            topic.setId( t.path( "id" ).asInt() ) ;
            topic.setTopicName( t.path( "topicName" ).asText( null ) ) ;
            topic.setMatchProbability( t.path( "confidence" ).asInt() / 100f ) ;
            topics.add( topic ) ;
        }
        if( topics.isEmpty() ) return null ;
        try {
            return ReviewVerdict.of( node.path( "model" ).asText( null ),
                                     topics ) ;
        }
        catch( IOException e ) {
            log.error( "Not reusing a stored verdict: {}", e.getMessage() ) ;
            return null ;
        }
    }
}
