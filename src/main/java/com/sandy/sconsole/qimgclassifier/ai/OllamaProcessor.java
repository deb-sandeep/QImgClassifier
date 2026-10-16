package com.sandy.sconsole.qimgclassifier.ai;

import com.sandy.sconsole.qimgclassifier.model.AIAgent;
import com.sandy.sconsole.qimgclassifier.model.TokenUsage;
import com.sandy.sconsole.qimgclassifier.util.QClassifierConfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Gemma served by a local Ollama instance ({@code /api/generate}). */
@Slf4j
public class OllamaProcessor implements AIProcessor {

    /**
     * Context window requested from Ollama. The classifier prompt (instructions
     * + syllabus reference + topic list) plus the encoded image runs to several
     * thousand tokens; Ollama's default num_ctx (4096) would silently truncate
     * it. The Chemistry prompt (largest) is ~5.5k text tokens plus the encoded
     * image.
     */
    private static final int NUM_CTX = 12288 ;

    private final ObjectMapper mapper = new ObjectMapper() ;
    private final HttpClient   httpClient ;

    /**
     * Full URL of Ollama's generate endpoint, e.g.
     * {@code http://host:11434/api/generate}.
     */
    private final String serverUrl ;

    /** Model, parallelism, retries and timeouts ({@code gemma.*}). */
    private final QClassifierConfig.Target settings ;

    public OllamaProcessor( String serverUrl, QClassifierConfig.Target settings ) {

        this.serverUrl  = serverUrl ;
        this.settings   = settings ;
        this.httpClient = HttpClient.newBuilder()
                                    .connectTimeout( settings.getConnectTimeout() )
                                    .build() ;
    }

    @Override public AIAgent getAgent() { return AIAgent.GEMMA ; }

    @Override public QClassifierConfig.Target getSettings() { return settings ; }

    /**
     * Non-streaming {@code /api/generate} call with the image attached; the
     * model stays loaded for 5 hours. Thinking is switched on or off per
     * {@code gemma.reasoning}.
     */
    @Override
    public Reply complete( String model, String prompt, String imageBase64,
                           String mimeType )
            throws ProcessorException {

        try {
            ObjectNode payload = mapper.createObjectNode() ;
            payload.put( "model", model ) ;
            payload.put( "prompt", prompt ) ;
            payload.put( "stream", false ) ;
            payload.set( "images", mapper.createArrayNode().add( imageBase64 ) ) ;
            payload.put( "keep_alive", "5h" ) ;
            payload.put( "think", settings.isReasoning() ) ;
            payload.set( "options",
                         mapper.createObjectNode().put( "num_ctx", NUM_CTX ) ) ;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri( URI.create( serverUrl ) )
                    .header( "Content-Type", "application/json" )
                    .timeout( settings.getRequestTimeout() )
                    .POST( HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString( payload ) ) )
                    .build() ;

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString() ) ;
            if( response.statusCode() == 429 ) {
                log.warn( "Gemma server rate limit hit: {}", response.body() ) ;
                throw RateLimitException.from(
                        "the Gemma server is overloaded (rate limited)", response ) ;
            }
            if( response.statusCode() != 200 ) {
                log.error( "Error invoking Gemma server (status {}): {}",
                           response.statusCode(), response.body() ) ;
                throw new ProcessorException( describe( response.statusCode() ),
                                              response.body() ) ;
            }

            JsonNode body = mapper.readTree( response.body() ) ;
            int promptTokens = body.path( "prompt_eval_count" ).asInt() ;

            if( promptTokens >= NUM_CTX ) {
                log.warn( "Prompt used {} tokens - at or beyond num_ctx {}; " +
                          "input may be truncated", promptTokens, NUM_CTX ) ;
            }
            String reply = body.path( "response" ).asText() ;
            TokenUsage usage = new TokenUsage(
                    promptTokens, body.path( "eval_count" ).asInt(),
                    null, null, null ) ;

            if( reply.isBlank() ) {
                log.error( "Gemma {} returned no answer: {}", model, response.body() ) ;
                throw new ProcessorException( "the model returned no answer",
                                              response.body(), usage ) ;
            }
            return new Reply( reply, usage ) ;
        }
        catch( ProcessorException e ) {
            throw e ;
        }
        catch( Exception e ) {
            if( !( e instanceof InterruptedException ) ) {
                log.error( "Error invoking Gemma server", e ) ;
            }
            throw ProcessorException.of( e, "the Gemma server" ) ;
        }
    }

    /** Readable reason for an error status from Ollama. */
    private static String describe( int status ) {
        return switch( status ) {
            case 404 -> "model not installed on the Gemma server" ;
            case 400 -> "the Gemma server rejected the request (HTTP 400)" ;
            default  -> "Gemma server error (HTTP " + status + ")" ;
        } ;
    }
}
