package com.sandy.sconsole.ai.internal;

import com.sandy.sconsole.ai.config.GatewayInfo;

/**
 * The run-time state of one gateway. Guarded by the {@link Engine}'s lock.
 */
final class GatewayState {

    final GatewayInfo info ;

    /** The transport; null if none of the app's models use the gateway. */
    final Gateway transport ;

    /**
     * The provider's limits across the gateway's models: counted across
     * apps when there is a usage ledger, else in this app.
     */
    final RateLimiter limiter ;

    /** Requests in flight on any of its models. */
    int inFlight ;

    /**
     * Its last request could not connect, and none has got through since.
     * It then gets one request at a time, a probe, and counts as held back.
     */
    boolean unreachable ;

    /** @param shared true if its limits are counted in a usage ledger */
    GatewayState( GatewayInfo info, Gateway transport, boolean shared ) {

        this.info      = info ;
        this.transport = transport ;
        this.limiter   = new RateLimiter( info.rateLimit().rpm(),
                                          info.rateLimit().rpd(), shared ) ;
    }

    /** True if the provider's cap and the probe rule allow one more. */
    boolean hasRoom() {

        if( unreachable && inFlight > 0 ) return false ;
        return info.maxParallel() == 0 || inFlight < info.maxParallel() ;
    }

    /** The key of its request times in the history file and the ledger. */
    String historyKey() { return info.name() ; }
}
