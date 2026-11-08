package com.sandy.sconsole.qimgtools.apps.tagger.server;

import com.sandy.sconsole.qimgtools.apps.tagger.model.CatalogueTag;
import com.sandy.sconsole.qimgtools.apps.tagger.model.ServerTag;
import com.sandy.sconsole.qimgtools.core.model.Topic;
import com.sandy.sconsole.qimgtools.core.server.PendingResult;
import com.sandy.sconsole.qimgtools.core.server.SConsoleServer;
import com.sandy.sconsole.qimgtools.core.server.SConsoleServer.ServerException;
import com.sandy.sconsole.qimgtools.core.server.ServerTopic;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Client for the SConsole server's master APIs the tagger uses (see the
 * server's {@code doc/feature-enhancements/2026-11-03/tagging-workflow/}
 * {@code api-changes.md}): topics, a topic's tags, its questions pending auto
 * tagging (with an {@code afterId} cursor), the tags on a batch of questions,
 * and saving tag sets. Calls go through {@link SConsoleServer}, which turns a
 * failed one into a {@link ServerException}. Thread-safe.
 */
@Slf4j
public class TaggerServerClient {

    /** Most questions one fetch may ask for. */
    public static final int MAX_LIMIT = 500 ;

    /** Most items one {@link #refresh} call may carry. */
    public static final int MAX_REFRESHES = 500 ;

    private final SConsoleServer server ;

    public TaggerServerClient( SConsoleServer server ) {
        this.server = server ;
    }

    /** The topics of syllabus {@code syllabusName}, e.g. "IIT Physics". */
    public List<Topic> topics( String syllabusName ) throws ServerException {

        List<ServerTopic> topics = server.get(
                "/Master/Topic/All?syllabusName=" +
                SConsoleServer.encode( syllabusName ),
                new TypeReference<List<ServerTopic>>() {} ) ;
        return topics.stream().map( ServerTopic::toTopic ).toList() ;
    }

    /** Every tag of topic {@code topicId}. */
    public List<CatalogueTag> tags( int topicId ) throws ServerException {

        JsonNode data = server.get( "/Master/Tag/Topic/" + topicId,
                                    new TypeReference<JsonNode>() {} ) ;
        List<CatalogueTag> tags = new ArrayList<>() ;
        for( JsonNode t : data ) {
            JsonNode hint = t.get( "aiAssociationHint" ) ;
            tags.add( new CatalogueTag(
                    t.path( "id" ).asInt(), t.path( "tagText" ).asText(),
                    hint == null || hint.isNull() ? null : hint.asText() ) ) ;
        }
        return tags ;
    }

    /**
     * The first {@code limit} questions of topic {@code topicId} pending auto
     * tagging, by id, after {@code afterId} (null: from the first). The
     * server leaves out those awaiting topic review.
     */
    public PendingResult pending( int topicId, int limit, Integer afterId )
            throws ServerException {

        return server.get( "/Master/Question/AutoTagging/Pending?topicId=" +
                           topicId + "&limit=" + limit +
                           ( afterId == null ? "" : "&afterId=" + afterId ),
                           new TypeReference<PendingResult>() {} ) ;
    }

    /** Every tag on each of the questions {@code ids}, by question id. */
    public Map<Integer,List<ServerTag>> questionTags( List<Integer> ids )
            throws ServerException {

        Map<Integer,List<ServerTag>> tags = new HashMap<>() ;
        if( ids.isEmpty() ) return tags ;

        ObjectNode body = server.mapper().createObjectNode() ;
        ids.forEach( body.putArray( "itemIds" )::add ) ;

        JsonNode data = server.post( "/Master/TagAssociation/QUESTION/Tags",
                                     body, new TypeReference<JsonNode>() {} ) ;
        for( Iterator<String> it = data.fieldNames() ; it.hasNext() ; ) {
            String          id   = it.next() ;
            List<ServerTag> list = new ArrayList<>() ;
            for( JsonNode t : data.get( id ) ) {
                list.add( new ServerTag(
                        t.path( "id" ).asInt(), t.path( "tagText" ).asText(),
                        t.path( "explicit" ).asBoolean( false ) ) ) ;
            }
            tags.put( Integer.valueOf( id ), list ) ;
        }
        return tags ;
    }

    /**
     * Replace the explicit tags of each question {@code items} names
     * (1-{@value #MAX_REFRESHES}). Items are independent; a rejected one
     * doesn't affect the others.
     *
     * @return the server's result for each item, in the same order
     */
    public List<RefreshResult> refresh( List<RefreshItem> items )
            throws ServerException {

        ObjectNode body = server.mapper().createObjectNode() ;
        ArrayNode  list = body.putArray( "refreshes" ) ;

        for( RefreshItem item : items ) {
            ObjectNode r = list.addObject()
                               .put( "itemType", "QUESTION" )
                               .put( "itemId", item.questionId() ) ;
            item.tagIds().forEach( r.putArray( "tagIds" )::add ) ;
        }

        JsonNode data = server.post( "/Master/TagAssociation/Refresh", body,
                                     new TypeReference<JsonNode>() {} ) ;
        List<RefreshResult> results = server.mapper().convertValue(
                data.path( "results" ),
                new TypeReference<List<RefreshResult>>() {} ) ;

        if( !matches( results, items ) ) {
            log.error( "Refresh reply has {} results for {} items: {}",
                       results == null ? 0 : results.size(), items.size(),
                       data ) ;
            throw new ServerException( "unexpected reply from the server" ) ;
        }
        return results ;
    }

    /**
     * Move question {@code questionId} to topic {@code topicId}, of its
     * syllabus, as the user's choice ({@code User} / null / 100).
     */
    public void changeTopic( int questionId, int topicId )
            throws ServerException {

        ObjectNode body = server.mapper().createObjectNode()
                                .put( "topicAssocActor", "User" )
                                .putNull( "topicAssocAiModel" )
                                .put( "topicAssocConfidence", 100 ) ;
        server.post( "/Master/Question/Topic/" + questionId + "/" + topicId,
                     body, new TypeReference<JsonNode>() {} ) ;
    }

    /**
     * Delete question {@code questionId} and its image files from the bank.
     * The server refuses a question in an exam.
     */
    public void delete( int questionId ) throws ServerException {

        ObjectNode body = server.mapper().createObjectNode() ;
        body.putArray( "questionIds" ).add( questionId ) ;
        server.post( "/Master/Question/Delete", body,
                     new TypeReference<JsonNode>() {} ) ;
    }

    /** True if there is one result per item, in the same order. */
    private static boolean matches( List<RefreshResult> results,
                                    List<RefreshItem> items ) {

        if( results == null || results.size() != items.size() ) return false ;
        for( int i = 0 ; i < results.size() ; i++ ) {
            RefreshResult r = results.get( i ) ;
            if( r == null || r.status() == null
                || r.itemId() != items.get( i ).questionId() ) {
                return false ;
            }
        }
        return true ;
    }
}
