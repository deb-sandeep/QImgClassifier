package com.sandy.sconsole.qimgclassifier.ai;

import com.sandy.sconsole.qimgclassifier.model.AIAgent;
import com.sandy.sconsole.qimgclassifier.model.TokenUsage;
import com.sandy.sconsole.qimgclassifier.util.QClassifierConfig;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * One AI backend that can classify a question image. {@link AIInvoker}
 * builds the prompt, spreads tasks across processors and parses the reply;
 * a processor only makes a single request and returns the model's raw text.
 * Implementations must be thread-safe: up to {@link #getMaxParallel()}
 * requests run concurrently.
 */
public interface AIProcessor {

    /** Which backend this is. */
    AIAgent getAgent() ;

    /** Model, parallelism, retries, timeouts and rate limits of this target. */
    QClassifierConfig.Target getSettings() ;

    /** The primary model, e.g. {@code gemma4:latest}. */
    default String getModel() { return getSettings().getModel() ; }

    /** The primary model followed by the backup models, in order. */
    default List<String> getModels() {

        List<String> models = new ArrayList<>() ;
        models.add( getModel() ) ;
        models.addAll( getSettings().getBackupModels() ) ;
        return models ;
    }

    /**
     * Model for a question's {@code n}-th attempt on this processor (1-based):
     * the primary model first, then the backup models in order on retries,
     * wrapping round to the primary when retries outnumber the backups.
     */
    default String modelForAttempt( int n ) {

        List<String> backups = getSettings().getBackupModels() ;
        int index = ( n - 1 ) % ( backups.size() + 1 ) ;

        return index == 0 ? getModel() : backups.get( index - 1 ) ;
    }

    /** Short label used in logs and failure reasons, e.g. "gemma". */
    default String getName() { return getAgent().getLabel() ; }

    /**
     * How many requests this processor may have in flight at once (at least 1).
     */
    default int getMaxParallel() { return getSettings().getMaxParallel() ; }

    /**
     * Retries a question may get on this processor after a failed attempt on
     * it.
     */
    default int getMaxRetries() { return getSettings().getMaxRetries() ; }

    /**
     * A model's reply: its text and the tokens the request used ({@code usage}
     * is null if the backend did not report them).
     */
    record Reply( String text, TokenUsage usage ) {}

    /**
     * Send one classification request.
     *
     * @param model       the model to ask (see {@link #modelForAttempt})
     * @param prompt      the complete prompt text
     * @param imageBase64 the question image, base64 encoded
     * @param mimeType    the image's media type, e.g. "image/png"
     * @return the model's text reply (expected to be the JSON topic array) and
     *         the tokens used
     * @throws ProcessorException with a short reason when the request fails
     */
    Reply complete( String model, String prompt, String imageBase64,
                    String mimeType ) throws ProcessorException ;

    /**
     * A failed request. The message is a short, readable reason shown in the
     * Result column; the backend's raw reply goes in {@link #getDetail()}
     * (saved with the transcript) and the log.
     */
    class ProcessorException extends Exception {

        /**
         * Raw response body from the backend, for the transcript; may be null.
         */
        private final String detail ;

        /**
         * Tokens the failed request still used (e.g. a reply that broke off);
         * null if none were reported.
         */
        private final TokenUsage usage ;

        public ProcessorException( String reason ) {
            this( reason, null ) ;
        }

        public ProcessorException( String reason, String detail ) {
            this( reason, detail, null ) ;
        }

        public ProcessorException( String reason, String detail,
                                   TokenUsage usage ) {
            super( reason ) ;
            this.detail = detail ;
            this.usage  = usage ;
        }

        public String getDetail() { return detail ; }

        public TokenUsage getUsage() { return usage ; }

        /**
         * Wrap an unexpected failure talking to {@code backend} (e.g.
         * "OpenRouter") with a readable reason: timeouts and connection
         * failures by name, anything else as "ExceptionType: message".
         * Restores the thread's interrupt flag if {@code e} is an
         * {@link InterruptedException}.
         */
        public static ProcessorException of( Exception e, String backend ) {

            if( e instanceof ProcessorException pe ) return pe ;
            if( e instanceof InterruptedException ) {
                Thread.currentThread().interrupt() ;
                return new ProcessorException( "interrupted" ) ;
            }
            String reason ;
            if( e instanceof HttpConnectTimeoutException ) {
                reason = "could not connect to " + backend + " (timed out)" ;
            }
            else if( e instanceof HttpTimeoutException ) {
                reason = backend + " took too long to reply (timed out)" ;
            }
            else if( e instanceof ConnectException ) {
                reason = "could not connect to " + backend ;
            }
            else {
                reason = e.getClass().getSimpleName() +
                         ( e.getMessage() != null ? ": " + e.getMessage() : "" ) ;
            }
            return new ProcessorException( reason, String.valueOf( e ) ) ;
        }
    }

    /** What a rate limit applies to. */
    enum RateLimitScope {

        /**
         * Only the model asked (e.g. its upstream provider's shared pool is
         * full); the processor's other models can still be used.
         */
        MODEL,

        /**
         * Every model of the processor (e.g. the account's own requests per
         * minute or day).
         */
        PROCESSOR
    }

    /**
     * The backend refused the request because a rate limit was hit (HTTP 429).
     * The invoker then stops sending to the model, or to the whole processor,
     * for a while.
     */
    class RateLimitException extends ProcessorException {

        /** How long the backend asked us to wait; null if it didn't say. */
        private final Duration retryAfter ;

        private final RateLimitScope scope ;

        /** A limit on the model asked ({@link RateLimitScope#MODEL}). */
        public RateLimitException( String reason, Duration retryAfter,
                                   String detail ) {
            this( reason, retryAfter, detail, RateLimitScope.MODEL ) ;
        }

        public RateLimitException( String reason, Duration retryAfter,
                                   String detail, RateLimitScope scope ) {
            super( reason, detail ) ;
            this.retryAfter = retryAfter ;
            this.scope      = scope ;
        }

        public Duration getRetryAfter() { return retryAfter ; }

        public RateLimitScope getScope() { return scope ; }

        /**
         * From a 429 response: honours a {@code Retry-After} header given in
         * seconds (the HTTP-date form is ignored).
         */
        public static RateLimitException from( String reason,
                                               HttpResponse<?> response ) {

            Duration retryAfter = response.headers()
                    .firstValue( "Retry-After" )
                    .map( String::trim )
                    .filter( v -> v.matches( "\\d+" ) )
                    .map( v -> Duration.ofSeconds( Long.parseLong( v ) ) )
                    .orElse( null ) ;
            return new RateLimitException( reason, retryAfter,
                                           String.valueOf( response.body() ) ) ;
        }
    }
}
