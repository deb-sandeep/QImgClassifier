package com.sandy.sconsole.ai.internal;

import com.sandy.sconsole.ai.config.ModelInfo;
import com.sandy.sconsole.ai.config.RateLimit;

/**
 * The run-time state of one model the app uses. Guarded by the
 * {@link Engine}'s lock.
 */
final class ModelState {

    /** Replies the average latency is taken over. */
    private static final int LATENCY_SAMPLES = 10 ;

    final ModelInfo    info ;
    final GatewayState gateway ;

    /**
     * The limits counted in this app: the app's own on this model, or,
     * without a usage ledger, the provider's and the app's together.
     */
    final RateLimiter limiter ;

    /**
     * The provider's limits on this model, counted across apps in the usage
     * ledger; null without a ledger, or if the provider sets none.
     */
    final RateLimiter shared ;

    /** Its pause after a rate limit on it alone; null if none yet. */
    Pause pause ;

    /** Set by the app: it takes no jobs, its guard's models take them. */
    boolean passthrough ;

    int inFlight ;
    int succeeded ;
    int failed ;

    private final long[] latencies = new long[ LATENCY_SAMPLES ] ;
    private int latencyCount ;
    private int latencyNext ;

    /** @param ledger true if the provider's limits are counted in a ledger */
    ModelState( ModelInfo info, GatewayState gateway, boolean ledger ) {

        RateLimit provider = info.providerRateLimit() ;
        RateLimit local    = ledger ? info.appRateLimit() : info.rateLimit() ;

        this.info    = info ;
        this.gateway = gateway ;
        this.limiter = new RateLimiter( local.rpm(), local.rpd() ) ;
        this.shared  = ledger && !provider.isNone()
                     ? new RateLimiter( provider.rpm(), provider.rpd(), true )
                     : null ;
    }

    /** Earliest epoch ms its own limits let it send (not its gateway's). */
    long availableAt( long now ) {
        long at = limiter.availableAt( now ) ;
        return shared != null ? Math.max( at, shared.availableAt( now ) ) : at ;
    }

    /** Why its own limits hold it back; null if they don't. */
    String heldBackReason( long now ) {
        String reason = shared != null ? shared.heldBackReason( now ) : null ;
        return reason != null ? reason : limiter.heldBackReason( now ) ;
    }

    /** True while its own daily limit (app's or provider's) is used up. */
    boolean isHeldForTheDay( long now ) {
        return limiter.isHeldForTheDay( now )
               || shared != null && shared.isHeldForTheDay( now ) ;
    }

    /** Requests of the last minute, by the limiter setting its rpm. */
    int countLastMinute( long now ) {
        return ( usesShared( shared != null ? shared.rpm() : 0,
                             limiter.rpm() ) ? shared : limiter )
                .countLastMinute( now ) ;
    }

    /** Requests of today (UTC), by the limiter setting its rpd. */
    int countToday( long now ) {
        return ( usesShared( shared != null ? shared.rpd() : 0,
                             limiter.rpd() ) ? shared : limiter )
                .countToday( now ) ;
    }

    /** True if the shared limit {@code s} is the stricter of it and {@code l}. */
    private static boolean usesShared( int s, int l ) {
        return s > 0 && ( l == 0 || s <= l ) ;
    }

    /** Count a reply that took {@code ms}. */
    void addLatency( long ms ) {

        latencies[latencyNext] = ms ;
        latencyNext = ( latencyNext + 1 ) % LATENCY_SAMPLES ;
        if( latencyCount < LATENCY_SAMPLES ) latencyCount++ ;
    }

    /** The average of the last replies' times; 0 if there were none. */
    long avgLatency() {

        if( latencyCount == 0 ) return 0 ;
        long sum = 0 ;
        for( int i = 0 ; i < latencyCount ; i++ ) sum += latencies[i] ;
        return sum / latencyCount ;
    }

    /** The pause in force at {@code now}; null if there is none. */
    Pause pause( long now ) {
        return pause != null && pause.isActive( now ) ? pause : null ;
    }

    /** The key of its request times in the history file and the ledger. */
    String historyKey() { return info.tag() ; }

    String id() { return info.id() ; }
}
