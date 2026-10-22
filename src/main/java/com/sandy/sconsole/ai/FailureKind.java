package com.sandy.sconsole.ai;

/**
 * Why an attempt failed, or why a model is held back, as far as fallback is
 * concerned. Each kind (but {@link #OTHER}) is a guard name an app config's
 * {@code fallback} may list models under; a kind without its own guard uses
 * the {@code default} one.
 */
public enum FailureKind {

    /**
     * A rate limit short of a daily one: an HTTP 429 for the requests per
     * minute or from the model's busy provider, or the model (or its
     * gateway) held back after one.
     */
    RATE_LIMITED_RPM( "rateLimitedRPM" ),

    /**
     * A daily limit: an HTTP 429 for the requests per day, or the model's or
     * its gateway's own {@code rpd} used up for the (UTC) day.
     */
    RATE_LIMITED_RPD( "rateLimitedRPD" ),

    /** The gateway or the model's provider failed (5xx, upstream error). */
    SERVER_ERROR( "serverError" ),

    /** No reply in time. */
    TIMEOUT( "timeout" ),

    /** The gateway could not be reached (connection refused / timed out). */
    UNREACHABLE( "unreachable" ),

    /**
     * A reply came, but held no usable answer: empty, cut off, refused, or
     * rejected by the app's {@link ReplyCheck}.
     */
    BAD_REPLY( "badReply" ),

    /**
     * The reply was cut off at the length limit (the model's
     * {@code maxOutputTokens}, or the provider's). Falls back by its own
     * guard, else by {@code badReply}'s.
     */
    TRUNCATED( "truncated" ),

    /**
     * Never an attempt's failure: the app has set the model to pass its
     * jobs through ({@code AIHub.setPassthrough}). It takes no jobs, and
     * those that would go to it go by this guard instead.
     */
    PASSTHROUGH( "passthrough" ),

    /** Anything else, e.g. a request the gateway refused. */
    OTHER( null ) ;

    /** The guard name in an app config's {@code fallback}; null for OTHER. */
    private final String guard ;

    FailureKind( String guard ) {
        this.guard = guard ;
    }

    public String guard() { return guard ; }

    /**
     * True for the kinds that are not the job's fault: the attempt is handed
     * back without using a retry.
     */
    public boolean isHandedBack() {
        return this == RATE_LIMITED_RPM || this == RATE_LIMITED_RPD
               || this == UNREACHABLE ;
    }

    /**
     * The kind whose guard a failure of this kind follows when the model
     * has no guard of its own for it; null to follow {@code default}.
     */
    public FailureKind parent() {
        return this == TRUNCATED ? BAD_REPLY : null ;
    }

    /** The kind whose guard name is {@code guard}; null if there is none. */
    public static FailureKind ofGuard( String guard ) {

        for( FailureKind k : values() ) {
            if( k.guard != null && k.guard.equals( guard ) ) return k ;
        }
        return null ;
    }
}
