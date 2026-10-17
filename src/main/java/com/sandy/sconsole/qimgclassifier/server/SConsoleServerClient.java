package com.sandy.sconsole.qimgclassifier.server;

import com.sandy.sconsole.qimgclassifier.model.ReviewDecision;
import com.sandy.sconsole.qimgclassifier.model.ReviewResult;
import com.sandy.sconsole.qimgclassifier.model.Topic;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Client for the SConsole server's master APIs that the topic reviewer uses
 * (see {@code docs/required-server-apis.md} and the server's
 * {@code doc/feature-enhancements/2026-10-16/question-topic-review/}
 * {@code api-changes.md}): the topic list, the topic review queue, and
 * confirming reviews. Every response comes
 * in the server's envelope ({@code data} + {@code executionResult}); a
 * response that is not HTTP 2xx with status {@code OK} is a
 * {@link ServerException} carrying the server's message. Thread-safe.
 */
@Slf4j
public class SConsoleServerClient {

    /** Most questions one {@link #pending} call may ask for. */
    public static final int MAX_LIMIT = 100 ;

    /** Most decisions one {@link #review} call may carry. */
    public static final int MAX_REVIEWS = 100 ;

    /** A request the server refused, or could not be asked. */
    public static class ServerException extends Exception {
        public ServerException( String message ) { super( message ) ; }
    }

    /** Server field names are camelCase; fields not modelled are ignored. */
    private final ObjectMapper mapper = new ObjectMapper()
            .configure( DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false ) ;

    /** e.g. {@code http://localhost:8080}, without a trailing slash. */
    private final String     baseUrl ;
    private final Duration   requestTimeout ;
    private final HttpClient httpClient ;

    public SConsoleServerClient( String baseUrl, Duration connectTimeout,
                                 Duration requestTimeout ) {

        this.baseUrl        = baseUrl.replaceAll( "/+$", "" ) ;
        this.requestTimeout = requestTimeout ;
        this.httpClient     = HttpClient.newBuilder()
                                        .connectTimeout( connectTimeout )
                                        .build() ;
    }

    /** The topics of syllabus {@code syllabusName}, e.g. "IIT Physics". */
    public List<Topic> topics( String syllabusName ) throws ServerException {

        HttpRequest request = get( "/Master/Topic/All?syllabusName="
                                   + encode( syllabusName ) ) ;
        List<ServerTopic> topics = call( request,
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
                .append( encode( String.join( ",", syllabusNames ) ) ) ;
        }
        if( !topicIds.isEmpty() ) {
            path.append( "&topicIds=" )
                .append( topicIds.stream()
                                 .map( String::valueOf )
                                 .collect( Collectors.joining( "," ) ) ) ;
        }
        return call( get( path.toString() ),
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

        ObjectNode body    = mapper.createObjectNode() ;
        ArrayNode  reviews = body.putArray( "reviews" ) ;

        for( ReviewDecision d : decisions ) {
            ObjectNode r = reviews.addObject()
                                  .put( "questionId", d.item().id() )
                                  .put( "topicId", d.topic().getId() )
                                  .put( "topicAssocActor", d.actor() ) ;
            if( d.model() != null ) r.put( "topicAssocAiModel", d.model() ) ;
            r.put( "topicAssocConfidence", d.confidence() ) ;
        }

        JsonNode data = call( post( "/Master/Question/TopicReview", body ),
                              new TypeReference<JsonNode>() {} ) ;
        List<ReviewResult> results = mapper.convertValue(
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

    // ---- plumbing ------------------------------------------------------

    private HttpRequest get( String path ) {
        return HttpRequest.newBuilder( URI.create( baseUrl + path ) )
                          .timeout( requestTimeout )
                          .GET()
                          .build() ;
    }

    private HttpRequest post( String path, JsonNode body ) {
        return HttpRequest.newBuilder( URI.create( baseUrl + path ) )
                          .timeout( requestTimeout )
                          .header( "Content-Type", "application/json" )
                          .POST( HttpRequest.BodyPublishers.ofString(
                                  body.toString() ) )
                          .build() ;
    }

    /**
     * Send {@code request} and return the envelope's {@code data} as
     * {@code type}.
     *
     * @throws ServerException if the server could not be reached, or the reply
     *         is not a successful envelope
     */
    private <T> T call( HttpRequest request, TypeReference<T> type )
            throws ServerException {

        String what = request.method() + " " + request.uri().getPath() ;
        HttpResponse<String> response ;

        try {
            response = httpClient.send( request,
                                        HttpResponse.BodyHandlers.ofString() ) ;
        }
        catch( InterruptedException e ) {
            Thread.currentThread().interrupt() ;
            throw new ServerException( what + " was interrupted" ) ;
        }
        catch( IOException e ) {
            log.warn( "{} failed", what, e ) ;
            throw new ServerException( unreachable( e ) ) ;
        }

        JsonNode root = parse( response.body() ) ;
        JsonNode result = root == null ? null : root.path( "executionResult" ) ;
        boolean ok = response.statusCode() / 100 == 2
                     && result != null
                     && "OK".equals( result.path( "status" ).asText() ) ;

        if( !ok ) {
            String message = result != null && result.hasNonNull( "message" )
                           ? result.get( "message" ).asText()
                           : abbreviate( response.body() ) ;
            log.warn( "{} - HTTP {}: {}", what, response.statusCode(), message ) ;
            throw new ServerException( message ) ;
        }
        try {
            return mapper.convertValue( root.get( "data" ), type ) ;
        }
        catch( IllegalArgumentException e ) {
            log.error( "{} - unexpected reply: {}", what, response.body(), e ) ;
            throw new ServerException( "unexpected reply from the server" ) ;
        }
    }

    /** The body as JSON; null if it is not JSON (e.g. an HTML error page). */
    private JsonNode parse( String body ) {
        try {
            return body == null || body.isBlank() ? null : mapper.readTree( body ) ;
        }
        catch( IOException e ) {
            return null ;
        }
    }

    /** A readable reason for a request that got no reply. */
    private String unreachable( IOException e ) {

        if( e instanceof HttpConnectTimeoutException
            || e instanceof ConnectException ) {
            return "the SConsole server is not reachable at " + baseUrl ;
        }
        if( e instanceof HttpTimeoutException ) {
            return "the SConsole server took too long to reply (timed out)" ;
        }
        String msg = e.getMessage() ;
        return e.getClass().getSimpleName() + ( msg != null ? ": " + msg : "" ) ;
    }

    private static String abbreviate( String body ) {
        if( body == null || body.isBlank() ) return "(no response body)" ;
        return body.length() <= 200 ? body : body.substring( 0, 200 ) + "..." ;
    }

    private static String encode( String s ) {
        return URLEncoder.encode( s, StandardCharsets.UTF_8 ).replace( "+", "%20" ) ;
    }
}
