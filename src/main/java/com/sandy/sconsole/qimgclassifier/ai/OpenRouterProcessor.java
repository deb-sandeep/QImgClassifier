package com.sandy.sconsole.qimgclassifier.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Any vision-capable model on OpenRouter, via its OpenAI-compatible
 * chat completions endpoint. The model is a full OpenRouter slug such as
 * {@code google/gemini-2.5-flash}.
 */
@Slf4j
@RequiredArgsConstructor
public class OpenRouterProcessor implements AIProcessor {

    private static final String   API_URL         =
            "https://openrouter.ai/api/v1/chat/completions" ;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds( 10 ) ;
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes( 3 ) ;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout( CONNECT_TIMEOUT )
            .build() ;
    private final ObjectMapper mapper = new ObjectMapper() ;

    private final String apiKey ;

    /** OpenRouter model slug, e.g. {@code google/gemini-2.5-flash}. */
    private final String model ;

    /** Concurrent requests allowed. */
    @Getter
    private final int    maxParallel ;

    @Override public String getName() { return "openrouter" ; }

    /**
     * One user message carrying the prompt and the image as a data URL, at
     * temperature 0. Provider errors that OpenRouter reports inside a 200
     * response are surfaced as failures too.
     */
    @Override
    public String complete( String prompt, String imageBase64, String mimeType )
            throws ProcessorException {

        try {
            ObjectNode payload = mapper.createObjectNode() ;
            payload.put( "model", model ) ;
            payload.put( "temperature", 0 ) ;

            ArrayNode content = mapper.createArrayNode() ;
            content.addObject()
                    .put( "type", "text" )
                    .put( "text", prompt ) ;
            content.addObject()
                    .put( "type", "image_url" )
                    .putObject( "image_url" )
                    .put( "url", "data:" + mimeType + ";base64," + imageBase64 ) ;
            payload.putArray( "messages" ).addObject()
                    .put( "role", "user" )
                    .set( "content", content ) ;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri( URI.create( API_URL ) )
                    .header( "Content-Type", "application/json" )
                    .header( "Authorization", "Bearer " + apiKey )
                    .header( "X-Title", "qclassifier" )
                    .timeout( REQUEST_TIMEOUT )
                    .POST( HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString( payload ) ) )
                    .build() ;

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString() ) ;
            if( response.statusCode() != 200 ) {
                log.error( "Error invoking OpenRouter {} (status {}): {}",
                        model, response.statusCode(), response.body() ) ;
                throw new ProcessorException(
                        "OpenRouter HTTP " + response.statusCode() ) ;
            }

            // OpenRouter can report upstream provider errors inside a 200
            // response.
            JsonNode body = mapper.readTree( response.body() ) ;
            JsonNode error = body.path( "error" ) ;
            if( !error.isMissingNode() ) {
                log.error( "OpenRouter {} returned an error: {}", model, error ) ;
                throw new ProcessorException(
                        "OpenRouter: " + error.path( "message" ).asText( "error" ) ) ;
            }
            JsonNode reply = body.path( "choices" ).path( 0 )
                                 .path( "message" ).path( "content" ) ;
            if( reply.isMissingNode() || reply.isNull() ) {
                log.error( "OpenRouter {} returned no content: {}",
                           model, response.body() ) ;
                throw new ProcessorException( "OpenRouter: empty response" ) ;
            }
            return reply.asText() ;
        }
        catch( ProcessorException e ) {
            throw e ;
        }
        catch( Exception e ) {
            if( !( e instanceof InterruptedException ) ) {
                log.error( "Error invoking OpenRouter {}", model, e ) ;
            }
            throw ProcessorException.of( e ) ;
        }
    }
}
