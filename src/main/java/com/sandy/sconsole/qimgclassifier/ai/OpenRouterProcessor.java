package com.sandy.sconsole.qimgclassifier.ai;

import com.sandy.sconsole.qimgclassifier.model.AIAgent;
import com.sandy.sconsole.qimgclassifier.model.TokenUsage;
import com.sandy.sconsole.qimgclassifier.util.QClassifierConfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
     * temperature 0, with reasoning disabled unless
     * {@code openrouter.reasoning} is set. Provider errors that OpenRouter
     * reports inside a 200 response are surfaced as failures too.
     */
    @Override
    public Reply complete( String model, String prompt, String imageBase64,
                           String mimeType )
            throws ProcessorException {

        try {
            ObjectNode payload = mapper.createObjectNode() ;
            payload.put( "model", model ) ;
            payload.put( "temperature", 0 ) ;

            // Reasoning only adds output tokens and time: the answer is a
            // short topic list. Off unless openrouter.reasoning is set.
            if( !settings.isReasoning() ) {
                payload.putObject( "reasoning" ).put( "enabled", false ) ;
            }

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
            JsonNode body  = parse( response.body() ) ;
            JsonNode error = body.path( "error" ) ;

            if( response.statusCode() == 429 || error.path( "code" ).asInt() == 429 ) {
                log.warn( "OpenRouter {} rate limit hit (status {}): {}", model,
                          response.statusCode(), response.body() ) ;
                throw rateLimit( model, error, response ) ;
            }
            if( response.statusCode() != 200 ) {
                log.error( "Error invoking OpenRouter {} (status {}): {}",
                           model, response.statusCode(), response.body() ) ;
                throw new ProcessorException(
                        describe( response.statusCode(), error ), response.body() ) ;
            }
            // OpenRouter can report upstream provider errors inside a 200
            // response.
            TokenUsage usage = usage( body.path( "usage" ) ) ;
            if( !error.isMissingNode() ) {
                log.error( "OpenRouter {} returned an error: {}", model, error ) ;
                throw new ProcessorException(
                        describe( error.path( "code" ).asInt(), error ),
                        response.body(), usage ) ;
            }

            JsonNode choice = body.path( "choices" ).path( 0 ) ;
            JsonNode reply  = choice.path( "message" ).path( "content" ) ;

            if( reply.isMissingNode() || reply.isNull() || reply.asText().isBlank() ) {
                log.error( "OpenRouter {} returned no content: {}",
                           model, response.body() ) ;
                throw new ProcessorException( noAnswer( choice, body ),
                                              response.body(), usage ) ;
            }
            return new Reply( reply.asText(), usage ) ;
        }
        catch( ProcessorException e ) {
            throw e ;
        }
        catch( Exception e ) {
            if( !( e instanceof InterruptedException ) ) {
                log.error( "Error invoking OpenRouter {}", model, e ) ;
            }
            throw ProcessorException.of( e, "OpenRouter" ) ;
        }
    }

    /** The response body as JSON; a missing node if it is not JSON. */
    private JsonNode parse( String body ) {

        try {
            return body == null ? MissingNode.getInstance() : mapper.readTree( body ) ;
        }
        catch( Exception e ) {
            return MissingNode.getInstance() ;
        }
    }

    /**
     * A 429, sorted by what it limits. The account's own limits on free
     * models (per minute, per day) and OpenRouter's own limits apply to every
     * model; a limit reported by the model's upstream provider applies to
     * that model only.
     */
    private static RateLimitException rateLimit( String model, JsonNode error,
                                                 HttpResponse<String> response ) {

        String   message    = error.path( "message" ).asText( "" ) ;
        String   provider   = provider( error ) ;
        Duration retryAfter = retryAfter( error, response ) ;

        String         reason ;
        RateLimitScope scope = RateLimitScope.PROCESSOR ;

        if( message.contains( "per-day" ) ) {
            reason = "OpenRouter daily request limit reached" ;
        }
        else if( message.contains( "per-min" ) ) {
            reason = "OpenRouter requests-per-minute limit reached" ;
        }
        else if( provider != null || error.path( "metadata" )
                                          .path( "limit_source" )
                                          .asText( "" ).startsWith( "upstream" ) ) {
            reason = model + " is busy at its provider" +
                     ( provider != null ? " (" + provider + ")" : "" ) ;
            scope  = RateLimitScope.MODEL ;
        }
        else {
            reason = "OpenRouter rate limit reached" ;
        }
        return new RateLimitException( reason, retryAfter, response.body(), scope ) ;
    }

    /**
     * How long to wait after a 429: the {@code Retry-After} header (seconds),
     * else the {@code X-RateLimit-Reset} time (epoch ms) OpenRouter puts in the
     * error's metadata; null if neither is given.
     */
    private static Duration retryAfter( JsonNode error,
                                        HttpResponse<String> response ) {

        Duration header = RateLimitException.from( "", response ).getRetryAfter() ;
        if( header != null ) return header ;

        JsonNode reset = error.path( "metadata" ).path( "headers" )
                              .path( "X-RateLimit-Reset" ) ;
        if( !reset.asText( "" ).matches( "\\d+" ) ) return null ;

        long ms = Long.parseLong( reset.asText() ) - System.currentTimeMillis() ;
        return ms > 0 ? Duration.ofMillis( ms ) : null ;
    }

    /** Readable reason for an error status (HTTP, or the error's own code). */
    private static String describe( int code, JsonNode error ) {

        String provider = provider( error ) ;
        String upstream = "upstream model error" +
                          ( provider != null ? " (" + provider + ")" : "" ) ;

        return switch( code ) {
            case 400 -> error.path( "message" ).asText( "" )
                             .toLowerCase().contains( "reasoning" )
                        ? "the model can't answer without reasoning " +
                          "(set openrouter.reasoning = true)"
                        : "OpenRouter rejected the request (HTTP 400)" ;
            case 401 -> "OpenRouter rejected the API key" ;
            case 402 -> "OpenRouter credits used up" ;
            case 403 -> "request refused by OpenRouter's moderation" ;
            case 404 -> "model not available on OpenRouter" ;
            case 408 -> "OpenRouter request timed out" ;
            case 502 -> upstream ;
            case 503 -> "no provider is serving the model right now" ;
            default  -> code >= 500 ? "OpenRouter server error (HTTP " + code + ")"
                      : error.isMissingNode() ? "OpenRouter error (HTTP " + code + ")"
                      : upstream ;
        } ;
    }

    /**
     * Readable reason for a reply without an answer, from its finish reason.
     */
    private static String noAnswer( JsonNode choice, JsonNode body ) {

        JsonNode refusal = choice.path( "message" ).path( "refusal" ) ;
        if( !refusal.isMissingNode() && !refusal.isNull() ) {
            return "the model refused to answer" ;
        }
        String provider = body.path( "provider" ).asText( null ) ;
        return switch( choice.path( "finish_reason" ).asText( "" ) ) {
            case "error"          -> "upstream model error - the reply broke off" +
                                     ( provider != null ? " (" + provider + ")" : "" ) ;
            case "length"         -> "the reply hit the length limit before " +
                                     "the answer" ;
            case "content_filter" -> "the reply was blocked by a content filter" ;
            default               -> "the model returned no answer" ;
        } ;
    }

    /** The upstream provider named in an error's metadata; null if none. */
    private static String provider( JsonNode error ) {
        String name = error.path( "metadata" ).path( "provider_name" ).asText( "" ) ;
        return name.isBlank() || name.equals( "null" ) ? null : name ;
    }

    /**
     * The tokens a request used, from the response's {@code usage}; null if it
     * has none.
     */
    private static TokenUsage usage( JsonNode usage ) {

        if( !usage.has( "prompt_tokens" ) ) return null ;
        JsonNode reasoning = usage.path( "completion_tokens_details" )
                                  .path( "reasoning_tokens" ) ;
        JsonNode cached    = usage.path( "prompt_tokens_details" )
                                  .path( "cached_tokens" ) ;
        JsonNode cost      = usage.path( "cost" ) ;

        return new TokenUsage(
                usage.path( "prompt_tokens" ).asInt(),
                usage.path( "completion_tokens" ).asInt(),
                reasoning.isNumber() ? reasoning.asInt() : null,
                cached.isNumber() ? cached.asInt() : null,
                cost.isNumber() ? cost.asDouble() : null ) ;
    }
}
