package com.sandy.sconsole.qimgclassifier.ai;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Request-rate bookkeeping for one AI processor: limits of requests per
 * minute (sliding window) and per UTC calendar day (reset at 00:00 UTC, as
 * OpenRouter's daily free-model quota is), plus a suspension set when the
 * backend itself reports that a rate limit was hit.
 * <p>
 * Not thread-safe; {@link AIInvoker} calls it only while holding its lock.
 * Counts live in memory; {@link AIInvoker} saves them to a
 * {@link RequestHistory} file and restores them at startup.
 */
final class RateLimiter {

    private static final long MINUTE_MS = Duration.ofMinutes( 1 ).toMillis() ;
    private static final long DAY_MS    = Duration.ofDays( 1 ).toMillis() ;

    /** Requests allowed per minute; 0 for no limit. */
    private final int rpm ;

    /** Requests allowed per UTC day; 0 for no limit. */
    private final int rpd ;

    /**
     * Start times of requests in the current UTC day and the last minute (or
     * only the last minute, if only RPM is set).
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
        if( rpd > 0 ) {
            long dayStart = utcDayStart( now ) ;
            if( countSince( dayStart - 1 ) >= rpd ) {
                at = Math.max( at, dayStart + DAY_MS ) ;   // next 00:00 UTC
            }
        }
        return at ;
    }

    /** True while a backend-reported limit holds every request back. */
    boolean isSuspended( long now ) { return suspendedUntil > now ; }

    /**
     * Which window holds requests back at {@code now}: the daily or the
     * per-minute limit, as a readable reason; null if neither is full.
     */
    String windowReason( long now ) {

        prune( now ) ;
        if( rpd > 0 && countSince( utcDayStart( now ) - 1 ) >= rpd ) {
            return "daily limit of " + rpd + " requests reached" ;
        }
        if( rpm > 0 && countSince( now - MINUTE_MS ) >= rpm ) {
            return "limit of " + rpm + " requests per minute reached" ;
        }
        return null ;
    }

    /**
     * Requests started within the longest window in use, oldest first; empty
     * if no limit is set.
     */
    List<Long> starts( long now ) {
        prune( now ) ;
        return new ArrayList<>( starts ) ;
    }

    /**
     * Add request start times kept from an earlier run (epoch ms, oldest
     * first), dropping those outside the windows. Call before
     * {@link #record}.
     */
    void restore( List<Long> times, long now ) {
        if( rpm == 0 && rpd == 0 ) return ;
        starts.addAll( times ) ;
        prune( now ) ;
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

    /**
     * Drop starts that no window in use covers: the last minute, and with a
     * daily limit, the current UTC day.
     */
    private void prune( long now ) {

        long horizon = rpd > 0 ? dailyHorizon( now ) : now - MINUTE_MS ;

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

    /**
     * Latest epoch ms that neither the minute window nor the current UTC day
     * covers; starts at or before it no longer count.
     */
    static long dailyHorizon( long now ) {
        return Math.min( now - MINUTE_MS, utcDayStart( now ) - 1 ) ;
    }

    /** Epoch ms of the most recent 00:00 UTC at or before {@code now}. */
    private static long utcDayStart( long now ) {
        return LocalDate.ofInstant( Instant.ofEpochMilli( now ), ZoneOffset.UTC )
                        .atStartOfDay( ZoneOffset.UTC )
                        .toInstant().toEpochMilli() ;
    }

    /** Start time of the {@code n}-th most recent request (1-based). */
    private long nthNewest( int n ) {

        var it = starts.descendingIterator() ;
        long t = 0 ;

        for( int i = 0 ; i < n && it.hasNext() ; i++ ) t = it.next() ;
        return t ;
    }
}
