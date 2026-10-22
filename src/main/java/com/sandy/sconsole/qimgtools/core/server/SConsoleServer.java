package com.sandy.sconsole.qimgtools.core.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * Calls to the SConsole server's REST APIs. Every response comes in the
 * server's envelope ({@code data} + {@code executionResult}); a response that
 * is not HTTP 2xx with status {@code OK} is a {@link ServerException}
 * carrying the server's message. Thread-safe.
 */
@Slf4j
public class SConsoleServer {

    /** A request the server refused, or could not be asked. */
    public static class ServerException extends Exception {

        /** The envelope's {@code data} of a refusal, if it had one. */
        private final transient JsonNode data ;

        public ServerException( String message ) {
            this( message, null ) ;
        }

        public ServerException( String message, JsonNode data ) {
            super( message ) ;
            this.data = data ;
        }

        /**
         * The {@code data} the server sent with its refusal (e.g. an
         * import's errors); null if none.
         */
        public JsonNode data() {
            return data ;
        }
    }

    /** Server field names are camelCase; fields not modelled are ignored. */
    private final ObjectMapper mapper = new ObjectMapper()
            .configure( DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false ) ;

    /** e.g. {@code http://localhost:8080}, without a trailing slash. */
    private final String     baseUrl ;
    private final Duration   requestTimeout ;
    private final HttpClient httpClient ;

    public SConsoleServer( String baseUrl, Duration connectTimeout,
                           Duration requestTimeout ) {

        this.baseUrl        = baseUrl.replaceAll( "/+$", "" ) ;
        this.requestTimeout = requestTimeout ;
        this.httpClient     = HttpClient.newBuilder()
                                        .connectTimeout( connectTimeout )
                                        .build() ;
    }

    /** The server's base URL, without a trailing slash. */
    public String baseUrl() {
        return baseUrl ;
    }

    /** The mapper replies are read with, also for building request bodies. */
    public ObjectMapper mapper() {
        return mapper ;
    }

    /**
     * GET {@code path} (from the base URL, with any query) and return the
     * envelope's {@code data} as {@code type}.
     */
    public <T> T get( String path, TypeReference<T> type )
            throws ServerException {

        return call( HttpRequest.newBuilder( URI.create( baseUrl + path ) )
                                .timeout( requestTimeout )
                                .GET()
                                .build(), type ) ;
    }

    /** POST {@code body} as JSON to {@code path}; see {@link #get}. */
    public <T> T post( String path, JsonNode body, TypeReference<T> type )
            throws ServerException {

        return call( HttpRequest.newBuilder( URI.create( baseUrl + path ) )
                                .timeout( requestTimeout )
                                .header( "Content-Type", "application/json" )
                                .POST( HttpRequest.BodyPublishers.ofString(
                                        body.toString() ) )
                                .build(), type ) ;
    }

    /** {@code s} encoded for a query parameter value. */
    public static String encode( String s ) {
        return URLEncoder.encode( s, StandardCharsets.UTF_8 ).replace( "+", "%20" ) ;
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
            JsonNode data = root == null ? null : root.get( "data" ) ;
            log.warn( "{} - HTTP {}: {}", what, response.statusCode(), message ) ;
            throw new ServerException( message,
                                       data == null || data.isNull() ? null
                                                                     : data ) ;
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
}
