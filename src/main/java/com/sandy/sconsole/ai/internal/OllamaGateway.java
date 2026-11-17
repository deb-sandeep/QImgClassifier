package com.sandy.sconsole.ai.internal;

import com.sandy.sconsole.ai.AIReply;
import com.sandy.sconsole.ai.AIRequest;
import com.sandy.sconsole.ai.FailureKind;
import com.sandy.sconsole.ai.TokenLogprob;
import com.sandy.sconsole.ai.TokenUsage;
import com.sandy.sconsole.ai.config.GatewayInfo;
import com.sandy.sconsole.ai.config.ModelInfo;
import com.sandy.sconsole.ai.config.Sampler;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Models served by an Ollama server ({@code /api/generate}). */
final class OllamaGateway extends Gateway {

    private final Logger       log ;
    private final ObjectMapper mapper = new ObjectMapper() ;

    OllamaGateway( GatewayInfo info ) {
        super( info ) ;
        this.log = LoggerFactory.getLogger( "ai.gateway." + info.name() ) ;
    }

    /**
     * A non-streaming {@code /api/generate} call with the images attached.
     * How long the model stays loaded is the server's own setting (no
     * {@code keep_alive} is sent). Thinking is switched on or off per the
     * model's {@code reasoning}; the context window is the model's
     * {@code contextTokens} when set, else the server's own (a server left
     * at Ollama's default, 4096, would silently cut a long prompt; a
     * {@code num_ctx} other than the loaded model's makes Ollama reload
     * it), and the reply's length its {@code maxOutputTokens}
     * ({@code num_predict}) when set; a reply cut off there fails as
     * {@link FailureKind#TRUNCATED}. A model with {@code structuredOutput}
     * is sent the request's schema as {@code format}, which holds its reply
     * to that JSON: no prose around it. A model's {@code sampler} settings
     * go in {@code options}, overriding its Modelfile's; with
     * {@code logprobs} set, the reply's tokens come back with their log
     * probabilities and that many alternatives ({@link AIReply#tokens}).
     */
    @Override
    AIReply complete( ModelInfo model, AIRequest request, Call call )
            throws GatewayException {

        String id = model.id() ;
        try {
            ObjectNode payload = mapper.createObjectNode() ;
            payload.put( "model", id ) ;
            payload.put( "prompt", request.prompt() ) ;
            payload.put( "stream", false ) ;

            ArrayNode images = payload.putArray( "images" ) ;
            request.images().forEach( i -> images.add( i.base64() ) ) ;
            payload.put( "think", model.reasoning() ) ;

            AIRequest.ResponseSchema schema = request.responseSchema() ;
            if( schema != null && model.structuredOutput() ) {
                payload.set( "format", schema.schema() ) ;
            }
            if( model.logprobs() != null ) {
                payload.put( "logprobs", true ) ;
                payload.put( "top_logprobs", model.logprobs() ) ;
            }
            ObjectNode options = mapper.createObjectNode() ;
            if( model.contextTokens() != null ) {
                options.put( "num_ctx", model.contextTokens() ) ;
            }
            if( model.maxOutputTokens() != null ) {
                options.put( "num_predict", model.maxOutputTokens() ) ;
            }
            putSampler( options, model.sampler() ) ;
            if( !options.isEmpty() ) payload.set( "options", options ) ;

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri( URI.create( info.url() ) )
                    .header( "Content-Type", "application/json" )
                    .timeout( model.requestTimeout() )
                    .POST( HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString( payload ) ) )
                    .build() ;

            HttpResponse<String> response =
                    send( httpRequest, model.requestTimeout(), call ) ;
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
            if( "length".equals( body.path( "done_reason" ).asText() ) ) {
                log.error( "{} reply was cut off: {}", id, response.body() ) ;
                throw new GatewayException( FailureKind.TRUNCATED,
                        "the reply was cut off at the length limit",
                        response.body(), usage ) ;
            }
            String thinking = body.path( "thinking" ).asText( "" ) ;
            return new AIReply( reply, thinking.isBlank() ? null : thinking,
                                usage,
                                model.logprobs() == null
                                ? null : tokens( body.path( "logprobs" ) ) ) ;
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

    /** The sampler settings as Ollama options. */
    private static void putSampler( ObjectNode options, Sampler sampler ) {

        if( sampler == null ) return ;
        if( sampler.temperature() != null ) {
            options.put( "temperature", sampler.temperature() ) ;
        }
        if( sampler.topK() != null ) options.put( "top_k", sampler.topK() ) ;
        if( sampler.topP() != null ) options.put( "top_p", sampler.topP() ) ;
        if( sampler.presencePenalty() != null ) {
            options.put( "presence_penalty", sampler.presencePenalty() ) ;
        }
        if( sampler.frequencyPenalty() != null ) {
            options.put( "frequency_penalty", sampler.frequencyPenalty() ) ;
        }
        if( sampler.repeatPenalty() != null ) {
            options.put( "repeat_penalty", sampler.repeatPenalty() ) ;
        }
    }

    /**
     * Ollama's {@code logprobs} array, each entry
     * {@code {token, logprob, bytes, top_logprobs}}; null if absent.
     */
    private static List<TokenLogprob> tokens( JsonNode logprobs ) {

        if( !logprobs.isArray() ) return null ;

        List<TokenLogprob> tokens = new ArrayList<>( logprobs.size() ) ;
        for( JsonNode t : logprobs ) {
            List<TokenLogprob.Alternative> top = new ArrayList<>() ;
            for( JsonNode a : t.path( "top_logprobs" ) ) {
                top.add( new TokenLogprob.Alternative(
                        a.path( "token" ).asText(),
                        a.path( "logprob" ).asDouble(),
                        bytes( a ) ) ) ;
            }
            tokens.add( new TokenLogprob( t.path( "token" ).asText(),
                                          t.path( "logprob" ).asDouble(),
                                          bytes( t ), top ) ) ;
        }
        return tokens ;
    }

    /** A token's {@code bytes} (an array of 0–255), else its text's UTF-8. */
    private static byte[] bytes( JsonNode token ) {

        JsonNode b = token.path( "bytes" ) ;
        if( !b.isArray() ) {
            return token.path( "token" ).asText()
                        .getBytes( StandardCharsets.UTF_8 ) ;
        }
        byte[] bytes = new byte[b.size()] ;
        for( int i = 0 ; i < bytes.length ; i++ ) {
            bytes[i] = (byte)b.get( i ).asInt() ;
        }
        return bytes ;
    }

    /**
     * The failure for an error status from Ollama, with the server's own
     * message when it gives one (e.g. {@code prediction aborted, token
     * repeat limit reached}, a failure of this request only).
     */
    private GatewayException failure( int status, String body ) {

        String server = "the " + info.name() + " server" ;
        String error  = errorText( body ) ;

        if( status >= 500 && error != null ) {
            return new GatewayException( FailureKind.SERVER_ERROR,
                    server + " error (HTTP " + status + "): " + error, body ) ;
        }
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

    /** The {@code error} of an Ollama error body; null if there is none. */
    private String errorText( String body ) {

        try {
            String error = mapper.readTree( body ).path( "error" ).asText( "" ) ;
            return error.isBlank() ? null : error.trim() ;
        }
        catch( Exception e ) {
            return null ;
        }
    }
}
