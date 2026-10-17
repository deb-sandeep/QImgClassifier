package com.sandy.sconsole.qimgtools.core.ai;

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
 * minute (sliding window, with requests spread evenly across it) and per
 * UTC calendar day (reset at 00:00 UTC, as
 * OpenRouter's daily free-model quota is), plus a suspension set when the
 * backend itself reports a rate limit covering every model, or cannot be
 * reached.
 * <p>
 * Not thread-safe; {@link AIInvoker} calls it only while holding its lock.
 * Counts live in memory; {@link AIInvoker} saves them to a
 * {@link RequestHistory} file and restores them at startup.
 */
final class RateLimiter {

    private static final long MINUTE_MS = Duration.ofMinutes( 1 ).toMillis() ;
    private static final long DAY_MS    = Duration.ofDays( 1 ).toMillis() ;

    /**
     * Requests are spaced this much wider than {@code 60 s / rpm}, so the
     * rate stays just under the limit and the backend's own count of the
     * minute, which may not line up with ours, isn't exceeded.
     */
    private static final double PACING_MARGIN = 1.05 ;

    /** Requests allowed per minute; 0 for no limit. */
    private final int rpm ;

    /** Requests allowed per UTC day; 0 for no limit. */
    private final int rpd ;

    /** Least time between request starts, in ms; 0 without an rpm. */
    private final long spacing ;

    /**
     * Start times of requests in the current UTC day and the last minute (or
     * only the last minute, if only RPM is set).
     */
    private final Deque<Long> starts = new ArrayDeque<>() ;

    /** The latest suspension; null if there has been none. */
    private Pause suspension ;

    RateLimiter( int rpm, int rpd ) {
        this.rpm     = rpm ;
        this.rpd     = rpd ;
        this.spacing = rpm > 0 ? Math.round( MINUTE_MS * PACING_MARGIN / rpm )
                               : 0 ;
    }

    /**
     * Earliest epoch millisecond at which a request may start; {@code now} or
     * earlier means immediately.
     */
    long availableAt( long now ) {

        prune( now ) ;
        long at = suspension != null ? Math.max( now, suspension.until() )
                                     : now ;
        if( rpm > 0 && countSince( now - MINUTE_MS ) >= rpm ) {
            at = Math.max( at, nthNewest( rpm ) + MINUTE_MS ) ;
        }
        // Evenly, rather than the whole minute's worth at once and then
        // nothing until the window moves on.
        if( spacing > 0 && !starts.isEmpty() ) {
            at = Math.max( at, starts.peekLast() + spacing ) ;
        }
        if( dailyLimitReached( now ) ) {
            at = Math.max( at, utcDayStart( now ) + DAY_MS ) ;   // next 00:00 UTC
        }
        return at ;
    }

    /**
     * Why requests are held back at {@code now} for longer than routine
     * pacing: the suspension's reason, else the daily limit; null if neither
     * applies. A full per-minute window is left out: at the cap it is full
     * for a moment before every request.
     */
    String heldBackReason( long now ) {

        if( suspension != null && suspension.isActive( now ) ) {
            return suspension.reason() ;
        }
        prune( now ) ;
        return dailyLimitReached( now )
               ? "daily limit of " + rpd + " requests reached"
               : null ;
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
     * Send nothing before {@code until} (epoch ms), because of
     * {@code reason}; {@code daily} if the backend's daily quota is used up.
     * Never shortens a suspension.
     */
    void suspend( long until, String reason, boolean daily ) {
        suspension = Pause.later( suspension,
                                  new Pause( until, reason, daily ) ) ;
    }

    /**
     * True while requests are held back for the day at {@code now}: a
     * suspension after the backend reported its daily quota used up, or the
     * configured daily limit reached.
     */
    boolean isHeldForTheDay( long now ) {

        if( suspension != null && suspension.isActive( now )
            && suspension.daily() ) return true ;
        prune( now ) ;
        return dailyLimitReached( now ) ;
    }

    /** Under a pruned window: true if today's (UTC) requests use up the rpd. */
    private boolean dailyLimitReached( long now ) {
        return rpd > 0 && countSince( utcDayStart( now ) - 1 ) >= rpd ;
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
