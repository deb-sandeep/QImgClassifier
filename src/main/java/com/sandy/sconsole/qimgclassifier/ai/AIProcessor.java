package com.sandy.sconsole.qimgclassifier.ai;

import com.sandy.sconsole.qimgclassifier.model.AIAgent;
import com.sandy.sconsole.qimgclassifier.util.QClassifierConfig;

import java.net.http.HttpResponse;
import java.time.Duration;
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
     * Send one classification request.
     *
     * @param model       the model to ask (see {@link #modelForAttempt})
     * @param prompt      the complete prompt text
     * @param imageBase64 the question image, base64 encoded
     * @param mimeType    the image's media type, e.g. "image/png"
     * @return the model's text reply (expected to be the JSON topic array)
     * @throws ProcessorException with a short reason when the request fails
     */
    String complete( String model, String prompt, String imageBase64,
                     String mimeType ) throws ProcessorException ;

    /**
     * A failed request; the message is a short reason shown in the Result
     * column.
     */
    class ProcessorException extends Exception {

        /**
         * Raw response body from the backend, for the transcript; may be null.
         */
        private final String detail ;

        public ProcessorException( String reason ) {
            this( reason, null ) ;
        }

        public ProcessorException( String reason, String detail ) {
            super( reason ) ;
            this.detail = detail ;
        }

        public String getDetail() { return detail ; }

        /**
         * Wrap an unexpected failure as "ExceptionType: message". Restores the
         * thread's interrupt flag if {@code e} is an
         * {@link InterruptedException}.
         */
        public static ProcessorException of( Exception e ) {

            if( e instanceof ProcessorException pe ) return pe ;
            if( e instanceof InterruptedException ) {
                Thread.currentThread().interrupt() ;
                return new ProcessorException( "interrupted" ) ;
            }
            return new ProcessorException( e.getClass().getSimpleName() +
                    ( e.getMessage() != null ? ": " + e.getMessage() : "" ) ) ;
        }
    }

    /**
     * The backend refused the request because a rate limit was hit (HTTP 429).
     * The invoker then stops routing to this processor for a while.
     */
    class RateLimitException extends ProcessorException {

        /** How long the backend asked us to wait; null if it didn't say. */
        private final Duration retryAfter ;

        public RateLimitException( String reason, Duration retryAfter,
                                   String detail ) {
            super( reason, detail ) ;
            this.retryAfter = retryAfter ;
        }

        public Duration getRetryAfter() { return retryAfter ; }

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
