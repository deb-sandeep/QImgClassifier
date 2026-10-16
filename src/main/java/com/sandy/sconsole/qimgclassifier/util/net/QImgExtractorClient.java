package com.sandy.sconsole.qimgclassifier.util.net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Client for QImgExtractor's remote command API (see
 * {@code sconsole/QImgExtractor/doc/remote-command-api.md}). Asks the running
 * QImgExtractor to open the project an image belongs to and scroll to the
 * image, so it can be repaired there.
 */
@Slf4j
@RequiredArgsConstructor
public class QImgExtractorClient {

    private static final Duration TIMEOUT = Duration.ofSeconds( 5 ) ;

    /**
     * Base URL of the remote command API, e.g. {@code http://127.0.0.1:8082}.
     */
    private final String baseUrl ;

    private final HttpClient   httpClient =
            HttpClient.newBuilder().connectTimeout( TIMEOUT ).build() ;
    private final ObjectMapper mapper = new ObjectMapper() ;

    /**
     * Fire-and-forget request to show {@code imgFile} in QImgExtractor.
     * Completes with null when the request was accepted, or with a
     * human-readable error message otherwise. Never completes exceptionally.
     */
    public CompletableFuture<String> showQuestionImage( File imgFile ) {

        String imgName = imgFile.getName() ;
        URI uri = URI.create( baseUrl + "/api/remote/show-question-image?imgName="
                + URLEncoder.encode( imgName, StandardCharsets.UTF_8 ) ) ;
        HttpRequest request = HttpRequest.newBuilder( uri )
                .timeout( TIMEOUT )
                .POST( HttpRequest.BodyPublishers.noBody() )
                .build() ;

        return httpClient.sendAsync( request, HttpResponse.BodyHandlers.ofString() )
                .thenApply( r -> {
                    if( r.statusCode() == 202 ) {
                        log.debug( "QImgExtractor accepted {}", imgName ) ;
                        return (String) null ;
                    }
                    String msg = errorMessage( r.body() ) ;
                    log.warn( "QImgExtractor rejected {} - HTTP {}: {}",
                              imgName, r.statusCode(), msg ) ;
                    return "HTTP " + r.statusCode() + ": " + msg ;
                } )
                .exceptionally( e -> {
                    Throwable cause = e.getCause() != null ? e.getCause() : e ;
                    log.warn( "Could not reach QImgExtractor for {}",
                              imgName, cause ) ;
                    if( cause instanceof ConnectException ) {
                        return "QImgExtractor is not running " +
                               "(nothing listening on " + baseUrl + ")." ;
                    }
                    String msg = cause.getMessage() ;
                    return cause.getClass().getSimpleName() +
                           ( msg != null ? ": " + msg : "" ) ;
                } ) ;
    }

    private String errorMessage( String body ) {

        try {
            JsonNode node = mapper.readTree( body ) ;
            if( node.hasNonNull( "message" ) ) {
                return node.get( "message" ).asText() ;
            }
        }
        catch( Exception ignored ) {
            // Not our JSON format (e.g. Spring's default error body) - show it
            // raw.
        }
        return body == null || body.isBlank() ? "(no response body)" : body ;
    }
}
