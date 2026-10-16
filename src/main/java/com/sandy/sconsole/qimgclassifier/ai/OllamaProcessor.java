package com.sandy.sconsole.qimgclassifier.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Gemma served by a local Ollama instance ({@code /api/generate}). */
@Slf4j
@RequiredArgsConstructor
public class OllamaProcessor implements AIProcessor {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds( 10 ) ;
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes( 5 ) ;

    /**
     * Context window requested from Ollama. The classifier prompt (instructions
     * + syllabus reference + topic list) plus the encoded image runs to several
     * thousand tokens; Ollama's default num_ctx (4096) would silently truncate
     * it. The Chemistry prompt (largest) is ~5.5k text tokens plus the encoded
     * image.
     */
    private static final int NUM_CTX = 12288 ;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout( CONNECT_TIMEOUT )
            .build() ;
    private final ObjectMapper mapper = new ObjectMapper() ;

    /**
     * Full URL of Ollama's generate endpoint, e.g.
     * {@code http://host:11434/api/generate}.
     */
    private final String serverUrl ;

    /** Ollama model tag, e.g. {@code gemma4:latest}. */
    private final String model ;

    /**
     * Concurrent requests allowed (match the server's
     * {@code OLLAMA_NUM_PARALLEL}).
     */
    @Getter
    private final int    maxParallel ;

    @Override public String getName() { return "gemma" ; }

    /**
     * Non-streaming {@code /api/generate} call with the image attached; the
     * model stays loaded for 5 hours.
     */
    @Override
    public String complete( String prompt, String imageBase64, String mimeType )
            throws ProcessorException {

        try {
            ObjectNode payload = mapper.createObjectNode() ;
            payload.put( "model", model ) ;
            payload.put( "prompt", prompt ) ;
            payload.put( "stream", false ) ;
            payload.set( "images", mapper.createArrayNode().add( imageBase64 ) ) ;
            payload.put( "keep_alive", "5h" ) ;
            payload.set( "options",
                         mapper.createObjectNode().put( "num_ctx", NUM_CTX ) ) ;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri( URI.create( serverUrl ) )
                    .header( "Content-Type", "application/json" )
                    .timeout( REQUEST_TIMEOUT )
                    .POST( HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString( payload ) ) )
                    .build() ;

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString() ) ;
            if( response.statusCode() != 200 ) {
                log.error( "Error invoking Gemma server (status {}): {}",
                           response.statusCode(), response.body() ) ;
                throw new ProcessorException( "HTTP " + response.statusCode() ) ;
            }

            JsonNode body = mapper.readTree( response.body() ) ;
            int promptTokens = body.path( "prompt_eval_count" ).asInt() ;
            if( promptTokens >= NUM_CTX ) {
                log.warn( "Prompt used {} tokens - at or beyond num_ctx {}; " +
                          "input may be truncated", promptTokens, NUM_CTX ) ;
            }
            return body.path( "response" ).asText() ;
        }
        catch( ProcessorException e ) {
            throw e ;
        }
        catch( Exception e ) {
            if( !( e instanceof InterruptedException ) ) {
                log.error( "Error invoking Gemma server", e ) ;
            }
            throw ProcessorException.of( e ) ;
        }
    }
}
