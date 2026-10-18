package com.sandy.sconsole.ai.config;

/**
 * Requests allowed per minute and per UTC day (reset at 00:00 UTC); 0 means
 * no limit.
 */
public record RateLimit( int rpm, int rpd ) {

    /** No limit at all. */
    public static final RateLimit NONE = new RateLimit( 0, 0 ) ;

    /** True if neither limit is set. */
    public boolean isNone() { return rpm == 0 && rpd == 0 ; }

    /**
     * Both this limit and {@code other} enforced: per window, the smaller
     * of the set limits. {@code other} may be null.
     */
    public RateLimit and( RateLimit other ) {

        if( other == null ) return this ;
        return new RateLimit( stricter( rpm, other.rpm ),
                              stricter( rpd, other.rpd ) ) ;
    }

    private static int stricter( int a, int b ) {
        if( a == 0 ) return b ;
        if( b == 0 ) return a ;
        return Math.min( a, b ) ;
    }

    @Override
    public String toString() {

        if( isNone() ) return "no limit" ;
        return ( rpm > 0 ? rpm + " rpm" : "no rpm" ) + ", "
             + ( rpd > 0 ? rpd + " rpd" : "no rpd" ) ;
    }
}
