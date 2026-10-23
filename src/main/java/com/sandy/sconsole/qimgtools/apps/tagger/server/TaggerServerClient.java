package com.sandy.sconsole.qimgtools.apps.tagger.server;

import com.sandy.sconsole.qimgtools.apps.tagger.model.CatalogueTag;
import com.sandy.sconsole.qimgtools.apps.tagger.model.ServerTag;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagClass;
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
 * server's {@code doc/feature-enhancements/2026-10-22/auto-tagging-support/},
 * {@code bulk-tag-import/} and
 * {@code 2026-10-23/bi-modal-auto-tagging/} {@code api-changes.md}): topics,
 * a topic's tags, a pass' tagging queue and a topic's dependents (both with
 * an {@code afterId} cursor), the tags on a batch of questions, saving a
 * pass' tag sets with their dependencies, and importing a tag. Calls go
 * through
 * {@link SConsoleServer}, which turns a failed one into a
 * {@link ServerException}. Thread-safe.
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

    /** Every tag of topic {@code topicId}, hinted or not. */
    public List<CatalogueTag> tags( int topicId ) throws ServerException {

        JsonNode data = server.get( "/Master/Tag/Topic/" + topicId,
                                    new TypeReference<JsonNode>() {} ) ;
        List<CatalogueTag> tags = new ArrayList<>() ;
        for( JsonNode t : data ) {
            tags.add( new CatalogueTag(
                    t.path( "id" ).asInt(), t.path( "tagText" ).asText(),
                    text( t, "unabbreviatedTagText" ),
                    text( t, "description" ), text( t, "aiAssociationHint" ),
                    text( t, "color" ), t.path( "topicId" ).asInt() ) ) ;
        }
        return tags ;
    }

    /**
     * The first {@code limit} questions of topic {@code topicId}, by id,
     * awaiting pass {@code tagClass} (concept: only those in an exam),
     * after {@code afterId} (null: from the first).
     */
    public PendingResult pending( TagClass tagClass, int topicId, int limit,
                                  Integer afterId ) throws ServerException {

        return server.get( "/Master/Question/AutoTagging/Pending?tagClass=" +
                           tagClass.wire() + "&topicIds=" + topicId +
                           "&limit=" + limit + after( afterId ),
                           new TypeReference<PendingResult>() {} ) ;
    }

    /**
     * The first {@code limit} questions, by id, with an open dependency on
     * topic {@code topicId}, after {@code afterId} (null: from the first).
     */
    public PendingResult dependents( int topicId, int limit, Integer afterId )
            throws ServerException {

        return server.get( "/Master/Question/AutoTagging/Dependents?topicId=" +
                           topicId + "&limit=" + limit + after( afterId ),
                           new TypeReference<PendingResult>() {} ) ;
    }

    private static String after( Integer afterId ) {
        return afterId == null ? "" : "&afterId=" + afterId ;
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
                        t.hasNonNull( "topicId" ) ? t.get( "topicId" ).asInt()
                                                  : null,
                        t.path( "explicit" ).asBoolean( false ) ) ) ;
            }
            tags.put( Integer.valueOf( id ), list ) ;
        }
        return tags ;
    }

    /**
     * Replace the explicit tags of each question {@code items} names
     * (1-{@value #MAX_REFRESHES}), with their dependencies, for pass
     * {@code tagClass}: an applied item clears only that pass' flag. Items
     * are independent; a rejected one doesn't affect the others.
     *
     * @return the server's result for each item, in the same order
     */
    public List<RefreshResult> refresh( TagClass tagClass,
                                        List<RefreshItem> items )
            throws ServerException {

        ObjectNode body = server.mapper().createObjectNode()
                                .put( "tagClass", tagClass.wire() ) ;
        ArrayNode  list = body.putArray( "refreshes" ) ;

        for( RefreshItem item : items ) {
            ObjectNode r = list.addObject()
                               .put( "itemType", "QUESTION" )
                               .put( "itemId", item.questionId() ) ;
            item.tagIds().forEach( r.putArray( "tagIds" )::add ) ;
            if( item.forDependencyOf() != null ) {
                r.put( "forDependencyOf", item.forDependencyOf() ) ;
            }
            else if( item.dependencyTopicIds() != null ) {
                item.dependencyTopicIds()
                    .forEach( r.putArray( "dependencyTopicIds" )::add ) ;
                r.put( "dependencyAssocActor", item.dependencyActor() ) ;
                r.put( "dependencyAssocAiModel", item.dependencyModel() ) ;
            }
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

    /**
     * Import a {@code sconsole-tagset} v2 file for topic {@code topicId}:
     * with {@code dryRun} only validated. A file the server refuses is a
     * {@link ServerException} whose {@code data} holds the report, with
     * its {@code errors}.
     *
     * @return the server's report
     */
    public JsonNode importTags( JsonNode file, int topicId, boolean dryRun )
            throws ServerException {

        return server.post( "/Master/Tag/Import?dryRun=" + dryRun +
                            "&topicId=" + topicId, file,
                            new TypeReference<JsonNode>() {} ) ;
    }

    private static String text( JsonNode n, String field ) {
        JsonNode v = n.get( field ) ;
        return v == null || v.isNull() ? null : v.asText() ;
    }
}
