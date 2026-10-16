package com.sandy.sconsole.qimgclassifier.ai;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Request-rate bookkeeping for one AI processor: sliding-window limits of
 * requests per minute and per day (rolling 24 hours), plus a suspension set
 * when the backend itself reports that a rate limit was hit.
 * <p>
 * Not thread-safe; {@link AIInvoker} calls it only while holding its slot
 * lock. Counts live in memory, so a restart starts the windows afresh.
 */
final class RateLimiter {

    private static final long MINUTE_MS = Duration.ofMinutes( 1 ).toMillis() ;
    private static final long DAY_MS    = Duration.ofDays( 1 ).toMillis() ;

    /** Requests allowed per minute; 0 for no limit. */
    private final int rpm ;

    /** Requests allowed per rolling day; 0 for no limit. */
    private final int rpd ;

    /**
     * Start times of requests in the last day (or minute, if only RPM is set).
     */
    private final Deque<Long> starts = new ArrayDeque<>() ;

    /** No requests before this epoch millisecond; 0 when not suspended. */
    private long suspendedUntil ;

    RateLimiter( int rpm, int rpd ) {
        this.rpm = rpm ;
        this.rpd = rpd ;
    }

    /**
     * Earliest epoch millisecond at which a request may start; {@code now} or
     * earlier means immediately.
     */
    long availableAt( long now ) {

        prune( now ) ;
        long at = Math.max( now, suspendedUntil ) ;
        if( rpm > 0 ) {
            int inLastMinute = countSince( now - MINUTE_MS ) ;
            if( inLastMinute >= rpm ) {
                at = Math.max( at, nthNewest( rpm ) + MINUTE_MS ) ;
            }
        }
        if( rpd > 0 && starts.size() >= rpd ) {
            at = Math.max( at, nthNewest( rpd ) + DAY_MS ) ;
        }
        return at ;
    }

    /** Count a request starting at {@code now}. */
    void record( long now ) {
        if( rpm > 0 || rpd > 0 ) starts.addLast( now ) ;
    }

    /**
     * Send nothing before {@code until} (epoch ms); never shortens a
     * suspension.
     */
    void suspendUntil( long until ) {
        suspendedUntil = Math.max( suspendedUntil, until ) ;
    }

    /** Drop starts older than the longest window in use. */
    private void prune( long now ) {

        long horizon = now - ( rpd > 0 ? DAY_MS : MINUTE_MS ) ;
        while( !starts.isEmpty() && starts.peekFirst() <= horizon ) {
            starts.pollFirst() ;
        }
    }

    private int countSince( long since ) {

        int n = 0 ;
        for( var it = starts.descendingIterator() ; it.hasNext() ; ) {
            if( it.next() <= since ) break ;
            n++ ;
        }
        return n ;
    }

    /** Start time of the {@code n}-th most recent request (1-based). */
    private long nthNewest( int n ) {

        var it = starts.descendingIterator() ;
        long t = 0 ;

        for( int i = 0 ; i < n && it.hasNext() ; i++ ) t = it.next() ;
        return t ;
    }
}
