package com.sandy.sconsole.qimgtools.apps.topicreviewer.workspace;

import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewVerdict;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ServerQuestion;
import com.sandy.sconsole.qimgtools.core.model.Topic;
import com.sandy.sconsole.qimgtools.core.workspace.JsonFiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * The JSON form of a question's server association and of a verdict, shared
 * by {@link ReviewAuditLog} and {@link PendingVerdicts}.
 */
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

    /** {@code v} as model, time and topics (id, topicName, confidence). */
    static ObjectNode verdict( ReviewVerdict v ) {

        ObjectNode node = JsonFiles.MAPPER.createObjectNode() ;
        node.put( "model", v.model() ) ;

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

    /** The verdict written by {@link #verdict}; null if it has no topics. */
    static ReviewVerdict readVerdict( JsonNode node ) {

        List<Topic> topics = new ArrayList<>() ;
        for( JsonNode t : node.path( "topics" ) ) {
            Topic topic = new Topic() ;
            topic.setId( t.path( "id" ).asInt() ) ;
            topic.setTopicName( t.path( "topicName" ).asText( null ) ) ;
            topic.setMatchProbability( t.path( "confidence" ).asInt() / 100f ) ;
            topics.add( topic ) ;
        }
        return topics.isEmpty()
               ? null
               : new ReviewVerdict( node.path( "model" ).asText( null ),
                                    topics ) ;
    }
}
