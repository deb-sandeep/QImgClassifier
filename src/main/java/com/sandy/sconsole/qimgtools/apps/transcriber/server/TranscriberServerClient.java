package com.sandy.sconsole.qimgtools.apps.transcriber.server;

import com.sandy.sconsole.qimgtools.apps.transcriber.model.SaveItem;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.SaveResult;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.TranscriptionCriteria;
import com.sandy.sconsole.qimgtools.core.model.Topic;
import com.sandy.sconsole.qimgtools.core.server.SConsoleServer;
import com.sandy.sconsole.qimgtools.core.server.SConsoleServer.ServerException;
import com.sandy.sconsole.qimgtools.core.server.ServerTopic;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Client for the SConsole server's transcription APIs (the server's
 * {@code doc/feature-enhancements/2026-11-17/question-transcription/}
 * {@code api-changes.md} rev 3): pending questions, an LCT group's passage,
 * storing a batch of transcriptions, and reading one back. Calls go
 * through {@link SConsoleServer}, which turns a failed one into a
 * {@link ServerException}. Thread-safe.
 */
@Slf4j
public class TranscriberServerClient {

    /** Most questions one {@link #pending} call may ask for. */
    public static final int MAX_BATCH = 100 ;

    /** Most transcriptions one {@link #save} call may carry. */
    public static final int MAX_SAVE = 100 ;

    private final SConsoleServer server ;

    public TranscriberServerClient( SConsoleServer server ) {
        this.server = server ;
    }

    /** The topics of {@code syllabusName}, e.g. "IIT Physics". */
    public List<Topic> topics( String syllabusName ) throws ServerException {

        List<ServerTopic> topics = server.get(
                "/Master/Topic/All?syllabusName=" +
                SConsoleServer.encode( syllabusName ),
                new TypeReference<List<ServerTopic>>() {} ) ;
        return topics.stream().map( ServerTopic::toTopic ).toList() ;
    }

    /**
     * The first {@code criteria.batchSize()} (1-{@value #MAX_BATCH})
     * pending questions matching {@code criteria}, by id, after
     * {@code afterId} (null: from the first), and how many match in all.
     */
    public PendingTranscriptions pending( TranscriptionCriteria criteria,
                                          Integer afterId )
            throws ServerException {

        StringBuilder path = new StringBuilder(
                "/Master/Question/Transcription/Pending?batchSize=" +
                criteria.batchSize() ) ;

        if( afterId != null ) path.append( "&afterId=" ).append( afterId ) ;
        if( !criteria.topicIds().isEmpty() ) {
            path.append( "&topicIds=" )
                .append( criteria.topicIds().stream()
                                 .map( String::valueOf )
                                 .collect( Collectors.joining( "," ) ) ) ;
        }
        else if( !criteria.syllabusNames().isEmpty() ) {
            path.append( "&syllabusNames=" )
                .append( SConsoleServer.encode(
                        String.join( ",", criteria.syllabusNames() ) ) ) ;
        }
        return server.get( path.toString(),
                           new TypeReference<PendingTranscriptions>() {} ) ;
    }

    /** LCT group {@code lctCtxId}'s stored passage (or none) and questions. */
    public LctContext lctContext( String lctCtxId ) throws ServerException {
        return server.get( "/Master/Question/Transcription/LctCtx?lctCtxId=" +
                           SConsoleServer.encode( lctCtxId ),
                           new TypeReference<LctContext>() {} ) ;
    }

    /** The stored transcription of the question with server id {@code id}. */
    public StoredTranscription transcription( int id ) throws ServerException {
        return server.get( "/Master/Question/Transcription/" + id,
                           new TypeReference<StoredTranscription>() {} ) ;
    }

    /**
     * Store {@code items} (1-{@value #MAX_SAVE}), in order: an item
     * carrying a passage stores it first, for the items after it. Items
     * are independent; a rejected one stores nothing and doesn't affect
     * the others.
     *
     * @return the server's result for each item, in the same order
     */
    public List<SaveResult> save( List<SaveItem> items )
            throws ServerException {

        ObjectNode body = server.mapper().createObjectNode() ;
        ArrayNode  list = body.putArray( "transcriptions" ) ;

        for( SaveItem item : items ) {
            ObjectNode node = list.addObject()
                                  .put( "questionId", item.questionId() )
                                  .put( "aiModel", item.aiModel() )
                                  .put( "content", item.content() ) ;
            if( item.hasPassage() ) {
                node.putObject( "lctCtx" )
                    .put( "aiModel", item.lctCtxModel() )
                    .put( "content", item.lctCtxContent() ) ;
            }
        }

        JsonNode         data    = server.post(
                "/Master/Question/Transcription", body,
                new TypeReference<JsonNode>() {} ) ;
        List<SaveResult> results = server.mapper().convertValue(
                data.path( "results" ),
                new TypeReference<List<SaveResult>>() {} ) ;

        if( !matches( results, items ) ) {
            log.error( "Transcription reply has {} results for {} items: {}",
                       results == null ? 0 : results.size(), items.size(),
                       data ) ;
            throw new ServerException( "unexpected reply from the server" ) ;
        }
        return results ;
    }

    /** True if there is one result per item, in the same order. */
    private static boolean matches( List<SaveResult> results,
                                    List<SaveItem> items ) {

        if( results == null || results.size() != items.size() ) return false ;
        for( int i = 0 ; i < results.size() ; i++ ) {
            SaveResult r = results.get( i ) ;
            if( r == null || r.status() == null
                || r.questionId() != items.get( i ).questionId() ) {
                return false ;
            }
        }
        return true ;
    }
}
