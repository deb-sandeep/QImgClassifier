package com.sandy.sconsole.ai;

/**
 * Why an attempt failed, as far as fallback is concerned. Each kind (but
 * {@link #OTHER}) is a guard name an app config's {@code fallback} may list
 * models under; a kind without its own guard uses the {@code default} one.
 */
public enum FailureKind {

    /** The gateway or the model reported a rate limit (HTTP 429). */
    RATE_LIMITED( "rateLimited" ),

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
        return this == RATE_LIMITED || this == UNREACHABLE ;
    }

    /** The kind whose guard name is {@code guard}; null if there is none. */
    public static FailureKind ofGuard( String guard ) {

        for( FailureKind k : values() ) {
            if( k.guard != null && k.guard.equals( guard ) ) return k ;
        }
        return null ;
    }
}
