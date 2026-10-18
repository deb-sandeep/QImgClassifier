package com.sandy.sconsole.ai.internal;

import com.sandy.sconsole.ai.config.ModelInfo;

/**
 * The run-time state of one model the app uses. Guarded by the
 * {@link Engine}'s lock.
 */
final class ModelState {

    /** Replies the average latency is taken over. */
    private static final int LATENCY_SAMPLES = 10 ;

    final ModelInfo    info ;
    final GatewayState gateway ;

    /** The provider's and the app's limits on this model. */
    final RateLimiter limiter ;

    /** Its pause after a rate limit on it alone; null if none yet. */
    Pause pause ;

    int inFlight ;
    int succeeded ;
    int failed ;

    private final long[] latencies = new long[ LATENCY_SAMPLES ] ;
    private int latencyCount ;
    private int latencyNext ;

    ModelState( ModelInfo info, GatewayState gateway ) {

        this.info    = info ;
        this.gateway = gateway ;
        this.limiter = new RateLimiter( info.rateLimit().rpm(),
                                        info.rateLimit().rpd() ) ;
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

    /** The key of its request times in the history file. */
    String historyKey() { return info.tag() ; }

    String id() { return info.id() ; }
}
