package com.sandy.sconsole.qimgclassifier.ai;

/**
 * One AI backend that can classify a question image. {@link AIInvoker}
 * builds the prompt, spreads tasks across processors and parses the reply;
 * a processor only makes a single request and returns the model's raw text.
 * Implementations must be thread-safe: up to {@link #getMaxParallel()}
 * requests run concurrently.
 */
public interface AIProcessor {

    /**
     * Short label used in logs and failure reasons, e.g. "gemma" or
     * "openrouter".
     */
    String getName() ;

    /**
     * How many requests this processor may have in flight at once (at least 1).
     */
    int getMaxParallel() ;

    /**
     * Send one classification request.
     *
     * @param prompt      the complete prompt text
     * @param imageBase64 the question image, base64 encoded
     * @param mimeType    the image's media type, e.g. "image/png"
     * @return the model's text reply (expected to be the JSON topic array)
     * @throws ProcessorException with a short reason when the request fails
     */
    String complete( String prompt, String imageBase64, String mimeType )
            throws ProcessorException ;

    /**
     * A failed request; the message is a short reason shown in the Result
     * column.
     */
    class ProcessorException extends Exception {

        public ProcessorException( String reason ) {
            super( reason ) ;
        }

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
}
