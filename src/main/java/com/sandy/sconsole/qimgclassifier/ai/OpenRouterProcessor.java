package com.sandy.sconsole.qimgclassifier.ai;

import com.sandy.sconsole.qimgclassifier.model.AIAgent;
import com.sandy.sconsole.qimgclassifier.util.QClassifierConfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Any vision-capable model on OpenRouter, via its OpenAI-compatible
 * chat completions endpoint. The model is a full OpenRouter slug such as
 * {@code google/gemini-2.5-flash}.
 */
@Slf4j
public class OpenRouterProcessor implements AIProcessor {

    private static final String API_URL =
            "https://openrouter.ai/api/v1/chat/completions" ;

    private final ObjectMapper mapper = new ObjectMapper() ;
    private final HttpClient   httpClient ;
    private final String       apiKey ;

    /** Model slug, parallelism, retries and timeouts ({@code openrouter.*}). */
    private final QClassifierConfig.Target settings ;

    /** Mark the prompt cacheable for models that support prompt caching. */
    private final boolean promptCaching ;

    /** Which models support prompt caching. */
    private final OpenRouterCatalog catalog ;

    /**
     * @param promptCaching ask for the prompt to be cached when the model
     *        supports it (see {@link OpenRouterCatalog})
     */
    public OpenRouterProcessor( String apiKey, QClassifierConfig.Target settings,
                                boolean promptCaching ) {

        this.apiKey        = apiKey ;
        this.settings      = settings ;
        this.promptCaching = promptCaching ;
        this.httpClient    = HttpClient.newBuilder()
                                       .connectTimeout( settings.getConnectTimeout() )
                                       .build() ;
        this.catalog       = new OpenRouterCatalog( httpClient, mapper ) ;
    }

    @Override public AIAgent getAgent() { return AIAgent.OPENROUTER ; }

    @Override public QClassifierConfig.Target getSettings() { return settings ; }

    /**
     * One user message carrying the prompt and the image as a data URL, at
     * temperature 0. Provider errors that OpenRouter reports inside a 200
     * response are surfaced as failures too.
     */
    @Override
    public String complete( String model, String prompt, String imageBase64,
                            String mimeType )
            throws ProcessorException {

        try {
            ObjectNode payload = mapper.createObjectNode() ;
            payload.put( "model", model ) ;
            payload.put( "temperature", 0 ) ;

            // The prompt is identical for every question of a subject and goes
            // first; only the image varies, so the prompt is the cacheable
            // prefix.
            ArrayNode  content = mapper.createArrayNode() ;
            ObjectNode text    = content.addObject()
                                        .put( "type", "text" )
                                        .put( "text", prompt ) ;

            if( promptCaching && catalog.supportsCaching( model ) ) {
                text.putObject( "cache_control" ).put( "type", "ephemeral" ) ;
            }
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
                    .timeout( settings.getRequestTimeout() )
                    .POST( HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString( payload ) ) )
                    .build() ;

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString() ) ;
            if( response.statusCode() == 429 ) {
                log.warn( "OpenRouter {} rate limit hit: {}", model, response.body() ) ;
                throw RateLimitException.from( "OpenRouter rate limited", response ) ;
            }
            if( response.statusCode() != 200 ) {
                log.error( "Error invoking OpenRouter {} (status {}): {}",
                        model, response.statusCode(), response.body() ) ;
                throw new ProcessorException(
                        "OpenRouter HTTP " + response.statusCode(), response.body() ) ;
            }

            // OpenRouter can report upstream provider errors inside a 200
            // response.
            JsonNode body = mapper.readTree( response.body() ) ;
            JsonNode error = body.path( "error" ) ;

            if( error.path( "code" ).asInt() == 429 ) {
                log.warn( "OpenRouter {} upstream rate limit hit: {}", model, error ) ;
                throw new RateLimitException( "OpenRouter rate limited", null,
                                              response.body() ) ;
            }
            if( !error.isMissingNode() ) {
                log.error( "OpenRouter {} returned an error: {}", model, error ) ;
                throw new ProcessorException(
                        "OpenRouter: " + error.path( "message" ).asText( "error" ),
                        response.body() ) ;
            }
            JsonNode reply = body.path( "choices" ).path( 0 )
                                 .path( "message" ).path( "content" ) ;
            if( reply.isMissingNode() || reply.isNull() ) {
                log.error( "OpenRouter {} returned no content: {}",
                           model, response.body() ) ;
                throw new ProcessorException( "OpenRouter: empty response",
                                              response.body() ) ;
            }
            logUsage( model, body.path( "usage" ) ) ;
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

    /**
     * Log token use, including how much of the prompt was read from the cache.
     */
    private void logUsage( String model, JsonNode usage ) {

        if( usage.isMissingNode() ) return ;
        JsonNode details = usage.path( "prompt_tokens_details" ) ;
        log.debug( "OpenRouter {}: {} prompt tokens ({} from cache, {} written), " +
                   "{} completion tokens", model,
                   usage.path( "prompt_tokens" ).asInt(),
                   details.path( "cached_tokens" ).asInt(),
                   details.path( "cache_write_tokens" ).asInt(),
                   usage.path( "completion_tokens" ).asInt() ) ;
    }
}
