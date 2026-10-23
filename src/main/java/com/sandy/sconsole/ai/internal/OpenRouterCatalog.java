package com.sandy.sconsole.ai.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * What OpenRouter's public model list says about each model, fetched once on
 * first use: whether it supports prompt caching, taken to be true when the
 * model has a non-zero {@code input_cache_read} price, and whether it
 * supports structured output ({@code structured_outputs} among its
 * {@code supported_parameters}).
 * <p>
 * If the list cannot be fetched every model is reported as supporting
 * neither, and the fetch is retried after {@link #RETRY_AFTER}. Thread-safe.
 */
final class OpenRouterCatalog {

    private static final Logger log = LoggerFactory.getLogger( "ai.engine" ) ;

    private static final Duration TIMEOUT     = Duration.ofSeconds( 20 ) ;
    private static final Duration RETRY_AFTER = Duration.ofMinutes( 10 ) ;

    private final HttpClient   httpClient ;
    private final ObjectMapper mapper ;

    /** OpenRouter's model list, e.g. {@code .../api/v1/models}. */
    private final String modelsUrl ;

    /** What a model supports. */
    private record Support( boolean caching, boolean structuredOutput ) {}

    /** Model id -> what it supports; null until fetched. */
    private Map<String,Support> byModel ;

    /** Epoch ms of the last failed fetch; 0 if none. */
    private long lastFailure ;

    OpenRouterCatalog( HttpClient httpClient, ObjectMapper mapper,
                       String modelsUrl ) {
        this.httpClient = httpClient ;
        this.mapper     = mapper ;
        this.modelsUrl  = modelsUrl ;
    }

    /**
     * True if OpenRouter lists {@code model} with a prompt-cache read price.
     */
    synchronized boolean supportsCaching( String model ) {
        Support s = support( model ) ;
        return s != null && s.caching() ;
    }

    /**
     * True if OpenRouter lists {@code model} as supporting structured output
     * (a JSON schema in {@code response_format}).
     */
    synchronized boolean supportsStructuredOutput( String model ) {
        Support s = support( model ) ;
        return s != null && s.structuredOutput() ;
    }

    /** What {@code model} supports; null if unknown. */
    private Support support( String model ) {

        if( byModel == null ) fetch() ;
        return byModel == null ? null : byModel.get( model ) ;
    }

    private void fetch() {

        if( System.currentTimeMillis() - lastFailure < RETRY_AFTER.toMillis() ) return ;
        try {
            HttpRequest request = HttpRequest.newBuilder( URI.create( modelsUrl ) )
                                             .timeout( TIMEOUT )
                                             .GET()
                                             .build() ;
            HttpResponse<String> response =
                    httpClient.send( request, HttpResponse.BodyHandlers.ofString() ) ;

            if( response.statusCode() != 200 ) {
                throw new IllegalStateException( "HTTP " + response.statusCode() ) ;
            }

            Map<String,Support> map = new HashMap<>() ;
            for( JsonNode m : mapper.readTree( response.body() ).path( "data" ) ) {
                double cacheRead = m.path( "pricing" ).path( "input_cache_read" )
                                    .asDouble( 0 ) ;
                boolean structured = false ;
                for( JsonNode p : m.path( "supported_parameters" ) ) {
                    if( "structured_outputs".equals( p.asText() ) ) {
                        structured = true ;
                    }
                }
                map.put( m.path( "id" ).asText(),
                         new Support( cacheRead > 0, structured ) ) ;
            }
            byModel = map ;
            log.info( "OpenRouter catalogue: {} models, {} with prompt caching, " +
                      "{} with structured output", map.size(),
                      map.values().stream().filter( Support::caching ).count(),
                      map.values().stream()
                         .filter( Support::structuredOutput ).count() ) ;
        }
        catch( InterruptedException e ) {
            Thread.currentThread().interrupt() ;
        }
        catch( Exception e ) {
            lastFailure = System.currentTimeMillis() ;
            log.warn( "Could not fetch the OpenRouter model list - prompt caching " +
                      "and structured output off until it can be read: {}",
                      e.toString() ) ;
        }
    }
}
