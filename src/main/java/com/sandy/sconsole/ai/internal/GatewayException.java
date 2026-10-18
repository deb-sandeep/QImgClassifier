package com.sandy.sconsole.ai.internal;

import com.sandy.sconsole.ai.FailureKind;
import com.sandy.sconsole.ai.TokenUsage;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * A failed request. The message is a short readable reason; the gateway's
 * raw reply goes in {@link #getDetail()}. The {@link FailureKind} decides
 * the job's fallback.
 */
class GatewayException extends Exception {

    private final FailureKind kind ;

    /** Raw response body, for the transcript; may be null. */
    private final String detail ;

    /**
     * Tokens the failed request still used (e.g. a reply that broke off);
     * null if none were reported.
     */
    private final TokenUsage usage ;

    GatewayException( FailureKind kind, String reason, String detail ) {
        this( kind, reason, detail, null ) ;
    }

    GatewayException( FailureKind kind, String reason, String detail,
                      TokenUsage usage ) {
        super( reason ) ;
        this.kind   = kind ;
        this.detail = detail ;
        this.usage  = usage ;
    }

    FailureKind getKind() { return kind ; }

    String getDetail() { return detail ; }

    TokenUsage getUsage() { return usage ; }

    /**
     * Wrap an unexpected failure talking to {@code backend} (e.g.
     * "OpenRouter") with a readable reason: timeouts and connection failures
     * by name, anything else as "ExceptionType: message". A connection
     * failure is an {@link Unreachable}. Restores the thread's interrupt
     * flag if {@code e} is an {@link InterruptedException}.
     */
    static GatewayException of( Exception e, String backend ) {

        if( e instanceof GatewayException ge ) return ge ;
        if( e instanceof InterruptedException ) {
            Thread.currentThread().interrupt() ;
            return new GatewayException( FailureKind.OTHER, "interrupted",
                                         null ) ;
        }
        String detail = String.valueOf( e ) ;

        // HttpConnectTimeoutException is an HttpTimeoutException, so it is
        // checked first.
        if( e instanceof HttpConnectTimeoutException ) {
            return new Unreachable( "could not connect to " + backend +
                                    " (timed out)", detail ) ;
        }
        if( e instanceof ConnectException ) {
            return new Unreachable( "could not connect to " + backend,
                                    detail ) ;
        }
        if( e instanceof HttpTimeoutException ) {
            return new GatewayException( FailureKind.TIMEOUT, backend +
                    " took too long to reply (timed out)", detail ) ;
        }
        String reason = e.getClass().getSimpleName() +
                ( e.getMessage() != null ? ": " + e.getMessage() : "" ) ;
        return new GatewayException( FailureKind.OTHER, reason, detail ) ;
    }

    /** What a rate limit applies to. */
    enum Scope {

        /** Only the model asked; the gateway's other models may be used. */
        MODEL,

        /** Every model of the gateway (e.g. the account's per minute). */
        GATEWAY,

        /** Every model of the gateway until its daily quota resets. */
        DAY
    }

    /**
     * A rate limit refused the request (HTTP 429). The attempt is handed
     * back, and the model or the whole gateway is held back for a while.
     */
    static class RateLimited extends GatewayException {

        /** How long the provider asked us to wait; null if it didn't say. */
        private final Duration retryAfter ;

        private final Scope scope ;

        /**
         * The provider's own explanation (e.g. OpenRouter's message and
         * remedy hint); null if it gave none.
         */
        private final String explanation ;

        RateLimited( String reason, Duration retryAfter, String detail,
                     Scope scope, String explanation ) {
            this( FailureKind.RATE_LIMITED, reason, retryAfter, detail, scope,
                  explanation ) ;
        }

        RateLimited( FailureKind kind, String reason, Duration retryAfter,
                     String detail, Scope scope, String explanation ) {
            super( kind, reason, detail ) ;
            this.retryAfter  = retryAfter ;
            this.scope       = scope ;
            this.explanation = explanation ;
        }

        Duration getRetryAfter() { return retryAfter ; }

        Scope getScope() { return scope ; }

        String getExplanation() { return explanation ; }

        /**
         * The response's {@code Retry-After} header given in seconds; null
         * if it has none (the HTTP-date form is ignored).
         */
        static Duration retryAfter( HttpResponse<?> response ) {
            return response.headers()
                    .firstValue( "Retry-After" )
                    .map( String::trim )
                    .filter( v -> v.matches( "\\d+" ) )
                    .map( v -> Duration.ofSeconds( Long.parseLong( v ) ) )
                    .orElse( null ) ;
        }
    }

    /**
     * The gateway could not be reached (connection refused or timed out).
     * Handled like a rate limit on the whole gateway, so a host that is down
     * doesn't fail every waiting job in turn.
     */
    static final class Unreachable extends RateLimited {

        Unreachable( String reason, String detail ) {
            super( FailureKind.UNREACHABLE, reason, null, detail, Scope.GATEWAY,
                   null ) ;
        }
    }
}
