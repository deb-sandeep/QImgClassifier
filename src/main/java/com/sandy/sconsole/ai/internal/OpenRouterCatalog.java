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
 * first use. Currently only whether a model supports prompt caching, taken to
 * be true when the model has a non-zero {@code input_cache_read} price.
 * <p>
 * If the list cannot be fetched every model is reported as not supporting
 * caching, and the fetch is retried after {@link #RETRY_AFTER}. Thread-safe.
 */
final class OpenRouterCatalog {

    private static final Logger log = LoggerFactory.getLogger( "ai.engine" ) ;

    private static final Duration TIMEOUT     = Duration.ofSeconds( 20 ) ;
    private static final Duration RETRY_AFTER = Duration.ofMinutes( 10 ) ;

    private final HttpClient   httpClient ;
    private final ObjectMapper mapper ;

    /** OpenRouter's model list, e.g. {@code .../api/v1/models}. */
    private final String modelsUrl ;

    /** Model id -> supports prompt caching; null until fetched. */
    private Map<String,Boolean> cachingByModel ;

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

        if( cachingByModel == null ) fetch() ;
        return cachingByModel != null && cachingByModel.getOrDefault( model, false ) ;
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

            Map<String,Boolean> map = new HashMap<>() ;
            for( JsonNode m : mapper.readTree( response.body() ).path( "data" ) ) {
                double cacheRead = m.path( "pricing" ).path( "input_cache_read" )
                                    .asDouble( 0 ) ;
                map.put( m.path( "id" ).asText(), cacheRead > 0 ) ;
            }
            cachingByModel = map ;
            log.info( "OpenRouter catalogue: {} models, {} with prompt caching",
                      map.size(), map.values().stream().filter( b -> b ).count() ) ;
        }
        catch( InterruptedException e ) {
            Thread.currentThread().interrupt() ;
        }
        catch( Exception e ) {
            lastFailure = System.currentTimeMillis() ;
            log.warn( "Could not fetch the OpenRouter model list - prompt caching " +
                      "off until it can be read: {}", e.toString() ) ;
        }
    }
}
