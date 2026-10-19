package com.sandy.sconsole.ai.internal;

import com.sandy.sconsole.ai.AIReply;
import com.sandy.sconsole.ai.AIRequest;
import com.sandy.sconsole.ai.FailureKind;
import com.sandy.sconsole.ai.TokenUsage;
import com.sandy.sconsole.ai.config.GatewayInfo;
import com.sandy.sconsole.ai.config.ModelInfo;
import com.sandy.sconsole.ai.internal.GatewayException.RateLimited;
import com.sandy.sconsole.ai.internal.GatewayException.Scope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Any model on OpenRouter, via its OpenAI-compatible chat completions
 * endpoint (the gateway's {@code url}). The model id is a full OpenRouter
 * slug such as {@code google/gemini-2.5-flash}.
 */
final class OpenRouterGateway extends Gateway {

    private final Logger       log ;
    private final ObjectMapper mapper = new ObjectMapper() ;
    private final String       appName ;

    /** Which models support prompt caching. */
    private final OpenRouterCatalog catalog ;

    /** A failure's kind and readable reason. */
    private record Failure( FailureKind kind, String reason ) {}

    OpenRouterGateway( GatewayInfo info, String appName ) {

        super( info ) ;
        this.log     = LoggerFactory.getLogger( "ai.gateway." + info.name() ) ;
        this.appName = appName ;
        this.catalog = new OpenRouterCatalog(
                httpClient, mapper,
                URI.create( info.url() ).resolve( "../models" ).toString() ) ;
    }

    /**
     * One user message carrying the prompt and then the images as data
     * URLs, at temperature 0, with reasoning disabled unless the model's
     * {@code reasoning} is on. Provider errors OpenRouter reports inside a
     * 200 response are failures too.
     */
    @Override
    AIReply complete( ModelInfo model, AIRequest request )
            throws GatewayException {

        String id = model.id() ;
        try {
            ObjectNode payload = mapper.createObjectNode() ;
            payload.put( "model", id ) ;
            payload.put( "temperature", 0 ) ;

            // Off unless asked for: for a short answer reasoning only adds
            // output tokens and time.
            if( !model.reasoning() ) {
                payload.putObject( "reasoning" ).put( "enabled", false ) ;
            }

            // The prompt goes first, so a prompt shared by many requests is
            // the cacheable prefix.
            ArrayNode  content = mapper.createArrayNode() ;
            ObjectNode text    = content.addObject()
                                        .put( "type", "text" )
                                        .put( "text", request.prompt() ) ;

            if( model.promptCaching() && catalog.supportsCaching( id ) ) {
                text.putObject( "cache_control" ).put( "type", "ephemeral" ) ;
            }
            for( AIRequest.Image image : request.images() ) {
                content.addObject()
                       .put( "type", "image_url" )
                       .putObject( "image_url" )
                       .put( "url", "data:" + image.mimeType() + ";base64," +
                                    image.base64() ) ;
            }
            payload.putArray( "messages" ).addObject()
                   .put( "role", "user" )
                   .set( "content", content ) ;

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri( URI.create( info.url() ) )
                    .header( "Content-Type", "application/json" )
                    .header( "Authorization", "Bearer " + info.apiKey() )
                    .timeout( model.requestTimeout() )
                    .POST( HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString( payload ) ) ) ;
            if( appName != null ) builder.header( "X-Title", appName ) ;

            HttpResponse<String> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString() ) ;
            JsonNode body  = parse( response.body() ) ;
            JsonNode error = body.path( "error" ) ;

            if( response.statusCode() == 429
                || error.path( "code" ).asInt() == 429 ) {
                log.warn( "{} rate limit hit (status {}): {}", id,
                          response.statusCode(), response.body() ) ;
                throw rateLimit( id, error, response ) ;
            }
            if( response.statusCode() != 200 ) {
                log.error( "Error invoking {} (status {}): {}", id,
                           response.statusCode(), response.body() ) ;
                Failure f = describe( response.statusCode(), error ) ;
                throw new GatewayException( f.kind(), f.reason(),
                                            response.body() ) ;
            }

            // OpenRouter can report upstream provider errors inside a 200.
            TokenUsage usage = usage( body.path( "usage" ) ) ;
            if( !error.isMissingNode() ) {
                log.error( "{} returned an error: {}", id, error ) ;
                Failure f = describe( error.path( "code" ).asInt(), error ) ;
                throw new GatewayException( f.kind(), f.reason(),
                                            response.body(), usage ) ;
            }

            JsonNode choice = body.path( "choices" ).path( 0 ) ;
            JsonNode reply  = choice.path( "message" ).path( "content" ) ;

            if( reply.isMissingNode() || reply.isNull()
                || reply.asText().isBlank() ) {
                log.error( "{} returned no content: {}", id, response.body() ) ;
                Failure f = noAnswer( choice, body ) ;
                throw new GatewayException( f.kind(), f.reason(),
                                            response.body(), usage ) ;
            }
            return new AIReply( reply.asText(),
                                reasoning( choice.path( "message" ) ),
                                usage ) ;
        }
        catch( GatewayException e ) {
            throw e ;
        }
        catch( Exception e ) {
            if( !( e instanceof InterruptedException ) ) {
                log.error( "Error invoking {}", id, e ) ;
            }
            throw GatewayException.of( e, "OpenRouter" ) ;
        }
    }

    /** The response body as JSON; a missing node if it is not JSON. */
    private JsonNode parse( String body ) {

        try {
            return body == null ? MissingNode.getInstance()
                                : mapper.readTree( body ) ;
        }
        catch( Exception e ) {
            return MissingNode.getInstance() ;
        }
    }

    /**
     * A 429, sorted by what it limits. The account's own limits on free
     * models (per minute, per day) and OpenRouter's own limits apply to
     * every model; a limit reported by the model's upstream provider applies
     * to that model only. OpenRouter's message, and the {@code remedy_hint}
     * it sometimes adds, go with it as the explanation.
     */
    private static RateLimited rateLimit( String model, JsonNode error,
                                          HttpResponse<String> response ) {

        JsonNode metadata    = error.path( "metadata" ) ;
        String   message     = error.path( "message" ).asText( "" ) ;
        String   limitSource = metadata.path( "limit_source" ).asText( "" ) ;
        String   provider    = provider( error ) ;
        Duration retryAfter  = retryAfter( error, response ) ;

        String reason ;
        Scope  scope = Scope.GATEWAY ;

        if( message.contains( "per-day" ) || limitSource.contains( "per_day" ) ) {
            reason = "OpenRouter daily request limit reached" ;
            scope  = Scope.DAY ;
        }
        else if( message.contains( "per-min" )
                 || limitSource.contains( "per_minute" ) ) {
            reason = "OpenRouter requests-per-minute limit reached" ;
        }
        else if( provider != null || limitSource.startsWith( "upstream" ) ) {
            reason = model + " is busy at its provider" +
                     ( provider != null ? " (" + provider + ")" : "" ) ;
            scope  = Scope.MODEL ;
        }
        else {
            reason = "OpenRouter rate limit reached" ;
        }
        return new RateLimited( reason, retryAfter, response.body(), scope,
                                explanation( message, metadata ) ) ;
    }

    /**
     * OpenRouter's message and remedy hint as one text; null if it gave
     * neither.
     */
    private static String explanation( String message, JsonNode metadata ) {

        String text = message.trim() ;
        String hint = metadata.path( "remedy_hint" ).asText( "" ).trim() ;

        if( !hint.isEmpty() ) text = text.isEmpty() ? hint : text + " " + hint ;
        return text.isEmpty() ? null : text ;
    }

    /**
     * How long to wait after a 429: the {@code Retry-After} header
     * (seconds), else the {@code X-RateLimit-Reset} time, from the response
     * headers or the copy OpenRouter puts in the error's metadata; null if
     * none is given or the reset time has passed.
     */
    private static Duration retryAfter( JsonNode error,
                                        HttpResponse<String> response ) {

        Duration header = RateLimited.retryAfter( response ) ;
        if( header != null ) return header ;

        String reset = response.headers()
                .firstValue( "X-RateLimit-Reset" )
                .orElse( error.path( "metadata" ).path( "headers" )
                              .path( "X-RateLimit-Reset" ).asText( "" ) )
                .trim() ;
        if( !reset.matches( "\\d+" ) ) return null ;

        long ms = resetEpochMs( Long.parseLong( reset ) )
                  - System.currentTimeMillis() ;
        return ms > 0 ? Duration.ofMillis( ms ) : null ;
    }

    /**
     * An {@code X-RateLimit-Reset} value as epoch ms. OpenRouter sends epoch
     * ms, but its docs don't state the unit, so a value too small to be ms
     * (before 1973) is taken as epoch seconds.
     */
    private static long resetEpochMs( long value ) {
        return value < 100_000_000_000L ? value * 1000 : value ;
    }

    /** Kind and reason for an error status (HTTP, or the error's code). */
    private static Failure describe( int code, JsonNode error ) {

        String provider = provider( error ) ;
        String upstream = "upstream model error" +
                          ( provider != null ? " (" + provider + ")" : "" ) ;

        return switch( code ) {
            case 400 -> new Failure( FailureKind.OTHER,
                        error.path( "message" ).asText( "" ).toLowerCase()
                             .contains( "reasoning" )
                        ? "the model can't answer without reasoning (set " +
                          "reasoning to true for it)"
                        : "OpenRouter rejected the request (HTTP 400)" ) ;
            case 401 -> new Failure( FailureKind.OTHER,
                                     "OpenRouter rejected the API key" ) ;
            case 402 -> new Failure( FailureKind.OTHER,
                                     "OpenRouter credits used up" ) ;
            case 403 -> new Failure( FailureKind.OTHER,
                                     "request refused by OpenRouter's moderation" ) ;
            case 404 -> new Failure( FailureKind.OTHER,
                                     "model not available on OpenRouter" ) ;
            case 408 -> new Failure( FailureKind.TIMEOUT,
                                     "OpenRouter request timed out" ) ;
            case 502 -> new Failure( FailureKind.SERVER_ERROR, upstream ) ;
            case 503 -> new Failure( FailureKind.SERVER_ERROR,
                                     "no provider is serving the model right now" ) ;
            default  -> code >= 500
                      ? new Failure( FailureKind.SERVER_ERROR,
                                     "OpenRouter server error (HTTP " + code + ")" )
                      : error.isMissingNode()
                      ? new Failure( FailureKind.OTHER,
                                     "OpenRouter error (HTTP " + code + ")" )
                      : new Failure( FailureKind.SERVER_ERROR, upstream ) ;
        } ;
    }

    /** Kind and reason for a reply without an answer. */
    private static Failure noAnswer( JsonNode choice, JsonNode body ) {

        JsonNode refusal = choice.path( "message" ).path( "refusal" ) ;
        if( !refusal.isMissingNode() && !refusal.isNull() ) {
            return new Failure( FailureKind.BAD_REPLY,
                                "the model refused to answer" ) ;
        }
        String provider = body.path( "provider" ).asText( null ) ;
        return switch( choice.path( "finish_reason" ).asText( "" ) ) {
            case "error"          -> new Failure( FailureKind.SERVER_ERROR,
                    "upstream model error - the reply broke off" +
                    ( provider != null ? " (" + provider + ")" : "" ) ) ;
            case "length"         -> new Failure( FailureKind.BAD_REPLY,
                    "the reply hit the length limit before the answer" ) ;
            case "content_filter" -> new Failure( FailureKind.BAD_REPLY,
                    "the reply was blocked by a content filter" ) ;
            default               -> new Failure( FailureKind.BAD_REPLY,
                    "the model returned no answer" ) ;
        } ;
    }

    /** The upstream provider named in an error's metadata; null if none. */
    private static String provider( JsonNode error ) {
        String name = error.path( "metadata" ).path( "provider_name" ).asText( "" ) ;
        return name.isBlank() || name.equals( "null" ) ? null : name ;
    }

    /**
     * The reasoning in a reply's {@code message}: its {@code reasoning}
     * text, else the text and summaries of its {@code reasoning_details}
     * (encrypted ones are skipped); null if there is none.
     */
    private static String reasoning( JsonNode message ) {

        String text = message.path( "reasoning" ).asText( "" ) ;
        if( !text.isBlank() ) return text ;

        StringBuilder sb = new StringBuilder() ;
        for( JsonNode d : message.path( "reasoning_details" ) ) {
            String part = d.path( "text" ).asText(
                          d.path( "summary" ).asText( "" ) ) ;
            if( part.isBlank() ) continue ;
            if( sb.length() > 0 ) sb.append( "\n\n" ) ;
            sb.append( part ) ;
        }
        return sb.length() > 0 ? sb.toString() : null ;
    }

    /**
     * The tokens a request used, from the response's {@code usage}; null if
     * it has none.
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
