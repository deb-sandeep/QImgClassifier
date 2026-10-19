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
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Models served by an Ollama server ({@code /api/generate}). */
final class OllamaGateway extends Gateway {

    private final Logger       log ;
    private final ObjectMapper mapper = new ObjectMapper() ;

    OllamaGateway( GatewayInfo info ) {
        super( info ) ;
        this.log = LoggerFactory.getLogger( "ai.gateway." + info.name() ) ;
    }

    /**
     * A non-streaming {@code /api/generate} call with the images attached;
     * the model stays loaded for 5 hours. Thinking is switched on or off per
     * the model's {@code reasoning}; the context window is the model's
     * {@code contextTokens} when set (Ollama's default, 4096, would silently
     * cut a long prompt).
     */
    @Override
    AIReply complete( ModelInfo model, AIRequest request )
            throws GatewayException {

        String id = model.id() ;
        try {
            ObjectNode payload = mapper.createObjectNode() ;
            payload.put( "model", id ) ;
            payload.put( "prompt", request.prompt() ) ;
            payload.put( "stream", false ) ;

            ArrayNode images = payload.putArray( "images" ) ;
            request.images().forEach( i -> images.add( i.base64() ) ) ;
            payload.put( "keep_alive", "5h" ) ;
            payload.put( "think", model.reasoning() ) ;
            if( model.contextTokens() != null ) {
                payload.putObject( "options" )
                       .put( "num_ctx", model.contextTokens() ) ;
            }

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri( URI.create( info.url() ) )
                    .header( "Content-Type", "application/json" )
                    .timeout( model.requestTimeout() )
                    .POST( HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString( payload ) ) )
                    .build() ;

            HttpResponse<String> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofString() ) ;
            if( response.statusCode() == 429 ) {
                log.warn( "{} rate limit hit: {}", id, response.body() ) ;
                throw new RateLimited(
                        "the " + info.name() + " server is overloaded " +
                        "(rate limited)", RateLimited.retryAfter( response ),
                        response.body(), Scope.MODEL, null ) ;
            }
            if( response.statusCode() != 200 ) {
                log.error( "Error invoking {} (status {}): {}", id,
                           response.statusCode(), response.body() ) ;
                throw failure( response.statusCode(), response.body() ) ;
            }

            JsonNode body         = mapper.readTree( response.body() ) ;
            int      promptTokens = body.path( "prompt_eval_count" ).asInt() ;

            if( model.contextTokens() != null
                && promptTokens >= model.contextTokens() ) {
                log.warn( "{}: the prompt used {} tokens - at or beyond the " +
                          "context window of {}; input may be cut", id,
                          promptTokens, model.contextTokens() ) ;
            }
            String     reply = body.path( "response" ).asText() ;
            TokenUsage usage = new TokenUsage(
                    promptTokens, body.path( "eval_count" ).asInt(),
                    null, null, null ) ;

            if( reply.isBlank() ) {
                log.error( "{} returned no answer: {}", id, response.body() ) ;
                throw new GatewayException( FailureKind.BAD_REPLY,
                                            "the model returned no answer",
                                            response.body(), usage ) ;
            }
            String thinking = body.path( "thinking" ).asText( "" ) ;
            return new AIReply( reply, thinking.isBlank() ? null : thinking,
                                usage ) ;
        }
        catch( GatewayException e ) {
            throw e ;
        }
        catch( Exception e ) {
            if( !( e instanceof InterruptedException ) ) {
                log.error( "Error invoking {}", id, e ) ;
            }
            throw GatewayException.of( e, "the " + info.name() + " server" ) ;
        }
    }

    /** The failure for an error status from Ollama. */
    private GatewayException failure( int status, String body ) {

        String server = "the " + info.name() + " server" ;
        return switch( status ) {
            case 404 -> new GatewayException( FailureKind.OTHER,
                    "model not installed on " + server, body ) ;
            case 400 -> new GatewayException( FailureKind.OTHER,
                    server + " rejected the request (HTTP 400)", body ) ;
            default  -> new GatewayException(
                    status >= 500 ? FailureKind.SERVER_ERROR : FailureKind.OTHER,
                    server + " error (HTTP " + status + ")", body ) ;
        } ;
    }
}
