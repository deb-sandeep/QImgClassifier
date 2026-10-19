package com.sandy.sconsole.ai.internal;

import java.util.concurrent.Future;

/**
 * One request in flight, so that it can be aborted from another thread: the
 * gateway attaches the HTTP exchange it sends, and {@link #abort} cancels it.
 * An abort before the exchange is attached cancels it as it is attached.
 */
final class Call {

    private Future<?> exchange ;
    private boolean   aborted ;

    /** Attach the exchange in flight; cancelled at once if aborted. */
    synchronized void attach( Future<?> exchange ) {
        this.exchange = exchange ;
        if( aborted ) exchange.cancel( true ) ;
    }

    /** Abort the request: its exchange is cancelled. */
    synchronized void abort() {
        aborted = true ;
        if( exchange != null ) exchange.cancel( true ) ;
    }

    synchronized boolean isAborted() { return aborted ; }
}
