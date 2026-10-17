package com.sandy.sconsole.qimgtools.core.ai;

/**
 * A stop on sending requests, to a whole processor or one model, after the
 * backend reported a rate limit or could not be reached.
 *
 * @param until  epoch ms from which requests may be sent again
 * @param reason readable cause, shown in the rate-limit notices
 * @param daily  true if it lasts until the daily quota resets
 */
record Pause( long until, String reason, boolean daily ) {

    /** True while the pause holds requests back. */
    boolean isActive( long now ) { return until > now ; }

    /**
     * The pause that lasts longer of {@code a} and {@code b}, either of which
     * may be null; {@code b} when they end together. A pause is never
     * shortened.
     */
    static Pause later( Pause a, Pause b ) {

        if( a == null ) return b ;
        if( b == null ) return a ;
        return b.until >= a.until ? b : a ;
    }
}
