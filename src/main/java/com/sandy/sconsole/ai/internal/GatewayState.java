package com.sandy.sconsole.ai.internal;

import com.sandy.sconsole.ai.config.GatewayInfo;

/**
 * The run-time state of one gateway. Guarded by the {@link Engine}'s lock.
 */
final class GatewayState {

    final GatewayInfo info ;

    /** The transport; null if none of the app's models use the gateway. */
    final Gateway transport ;

    /** The provider's limits across the gateway's models. */
    final RateLimiter limiter ;

    /** Requests in flight on any of its models. */
    int inFlight ;

    /**
     * Its last request could not connect, and none has got through since.
     * It then gets one request at a time, a probe, and counts as held back.
     */
    boolean unreachable ;

    GatewayState( GatewayInfo info, Gateway transport ) {

        this.info      = info ;
        this.transport = transport ;
        this.limiter   = new RateLimiter( info.rateLimit().rpm(),
                                          info.rateLimit().rpd() ) ;
    }

    /** True if the provider's cap and the probe rule allow one more. */
    boolean hasRoom() {

        if( unreachable && inFlight > 0 ) return false ;
        return info.maxParallel() == 0 || inFlight < info.maxParallel() ;
    }

    /** The key of its request times in the history file. */
    String historyKey() { return info.name() ; }
}
