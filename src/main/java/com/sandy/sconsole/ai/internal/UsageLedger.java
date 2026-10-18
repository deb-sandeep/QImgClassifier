package com.sandy.sconsole.ai.internal;

import com.sandy.sconsole.ai.config.RateLimit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The file the providers' limits are counted in across every app (every
 * process) that names it in its global config, so a provider's rpm and rpd
 * hold however many apps run at once. Keys are those of the
 * {@link RequestHistory}: the gateway name, or {@code <gateway>/<model id>}.
 * <pre>
 * {
 *   "requests" : { "openrouter/x:free" : [ 1792141131000, ... ] },
 *   "holds"    : { "openrouter" : { "until" : 1792141431000,
 *                                   "reason" : "...", "daily" : false } }
 * }
 * </pre>
 * {@code requests} are the start times (epoch ms, oldest first) of the
 * current UTC day and the last minute; {@code holds} are the rate-limit
 * holds a provider asked for, which every app then honours.
 * <p>
 * Changes are made under an OS lock on {@code <file>.lock} (and a lock
 * within this JVM), and written to a temporary file that is then renamed,
 * so a read needs no lock and never sees half a file. Thread-safe. A
 * failure is logged, never thrown: the hub then goes by its own counts.
 */
final class UsageLedger {

    private static final Logger log = LoggerFactory.getLogger( "ai.engine" ) ;

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable( SerializationFeature.INDENT_OUTPUT ) ;

    /** One lock per ledger file within this JVM; OS locks are per process. */
    private static final Map<Path,Object> JVM_LOCKS = new ConcurrentHashMap<>() ;

    /**
     * What the ledger holds.
     *
     * @param requests start times per key, oldest first
     * @param holds    the holds in force, per key
     */
    record State( Map<String,List<Long>> requests, Map<String,Pause> holds ) {

        static State empty() {
            return new State( new HashMap<>(), new HashMap<>() ) ;
        }

        List<Long> requests( String key ) {
            return requests.getOrDefault( key, List.of() ) ;
        }
    }

    /**
     * The answer to {@link #reserve}.
     *
     * @param granted true if the request was counted and may be sent
     * @param state   the ledger after the reservation, or as it stood
     */
    record Reservation( boolean granted, State state ) {}

    /** A change to the state, made under the lock; true to write it. */
    private interface Change {
        boolean apply( State state ) ;
    }

    private final File file ;
    private final Path path ;
    private final Path lockPath ;
    private final Object jvmLock ;

    UsageLedger( File file ) {

        this.file     = file.getAbsoluteFile() ;
        this.path     = this.file.toPath() ;
        this.lockPath = path.resolveSibling( this.file.getName() + ".lock" ) ;
        this.jvmLock  = JVM_LOCKS.computeIfAbsent( path, p -> new Object() ) ;
    }

    File file() { return file ; }

    /** The ledger as it stands, without its stale entries. */
    State read( long now ) {

        try {
            return load( now ) ;
        }
        catch( IOException e ) {
            log.error( "Could not read the usage ledger {}", file, e ) ;
            return State.empty() ;
        }
    }

    /**
     * Count a request starting at {@code now} under every key of
     * {@code limits}, if each key's limit allows it at {@code now}; else
     * count nothing. Pacing counts, as in {@link RateLimiter}. If the
     * ledger can't be changed, the request is granted (and the failure
     * logged), so the hub isn't stopped by a file.
     */
    Reservation reserve( Map<String,RateLimit> limits, long now ) {

        boolean[] granted = { false } ;
        State state = change( now, s -> {
            for( Map.Entry<String,RateLimit> e : limits.entrySet() ) {
                RateLimit   limit = e.getValue() ;
                RateLimiter check = new RateLimiter( limit.rpm(), limit.rpd() ) ;

                check.restore( s.requests( e.getKey() ), now ) ;
                if( check.availableAt( now ) > now ) return false ;
            }
            for( String key : limits.keySet() ) {
                s.requests().computeIfAbsent( key, k -> new ArrayList<>() )
                            .add( now ) ;
            }
            granted[0] = true ;
            return true ;
        } ) ;

        if( state == null ) return new Reservation( true, read( now ) ) ;
        return new Reservation( granted[0], state ) ;
    }

    /** Record {@code pause} under {@code key}; a hold is never shortened. */
    void hold( String key, Pause pause, long now ) {
        change( now, s -> {
            s.holds().merge( key, pause, Pause::later ) ;
            return true ;
        } ) ;
    }

    /**
     * Add request times this app counted before (e.g. in its
     * {@link RequestHistory}), leaving out those already in the ledger.
     */
    void merge( Map<String,List<Long>> times, long now ) {
        change( now, s -> {
            times.forEach( ( key, list ) -> {
                List<Long> have = s.requests().computeIfAbsent(
                        key, k -> new ArrayList<>() ) ;
                for( Long t : list ) {
                    if( !have.contains( t ) ) have.add( t ) ;
                }
                have.sort( null ) ;
            } ) ;
            return true ;
        } ) ;
    }

    /**
     * Apply {@code change} to the ledger under the locks, and write it if
     * the change asks to.
     *
     * @return the state after the change; null if the ledger could not be
     *         read or written (logged)
     */
    private State change( long now, Change change ) {

        synchronized( jvmLock ) {
            try {
                Files.createDirectories( path.getParent() ) ;
                try( FileChannel ch = FileChannel.open(
                             lockPath, StandardOpenOption.CREATE,
                             StandardOpenOption.WRITE ) ;
                     FileLock ignored = ch.lock() ) {

                    State state ;
                    try {
                        state = load( now ) ;
                    }
                    catch( IOException e ) {
                        log.error( "Could not read the usage ledger {} - " +
                                   "starting it afresh", file, e ) ;
                        state = State.empty() ;
                    }
                    if( change.apply( state ) ) save( state, now ) ;
                    return state ;
                }
            }
            catch( IOException e ) {
                log.error( "Could not update the usage ledger {}", file, e ) ;
                return null ;
            }
        }
    }

    /** The file's state, without entries no window covers; empty if none. */
    private State load( long now ) throws IOException {

        State state = State.empty() ;
        if( !Files.exists( path ) ) return state ;

        JsonNode root    = MAPPER.readTree( file ) ;
        long     horizon = RateLimiter.dailyHorizon( now ) ;
        JsonNode reqs    = root.path( "requests" ) ;
        JsonNode holds   = root.path( "holds" ) ;

        for( Iterator<Map.Entry<String,JsonNode>> it = reqs.fields() ;
             it.hasNext() ; ) {
            Map.Entry<String,JsonNode> e = it.next() ;
            List<Long> times = new ArrayList<>() ;

            for( JsonNode t : e.getValue() ) {
                if( t.canConvertToLong() && t.asLong() > horizon ) {
                    times.add( t.asLong() ) ;
                }
            }
            times.sort( null ) ;
            state.requests().put( e.getKey(), times ) ;
        }
        for( Iterator<Map.Entry<String,JsonNode>> it = holds.fields() ;
             it.hasNext() ; ) {
            Map.Entry<String,JsonNode> e = it.next() ;
            JsonNode h     = e.getValue() ;
            Pause    pause = new Pause( h.path( "until" ).asLong(),
                                        h.path( "reason" ).asText( null ),
                                        h.path( "daily" ).asBoolean() ) ;
            if( pause.isActive( now ) ) state.holds().put( e.getKey(), pause ) ;
        }
        return state ;
    }

    private void save( State state, long now ) throws IOException {

        ObjectNode root    = MAPPER.createObjectNode() ;
        ObjectNode reqs    = root.putObject( "requests" ) ;
        ObjectNode holds   = root.putObject( "holds" ) ;
        long       horizon = RateLimiter.dailyHorizon( now ) ;

        state.requests().forEach( ( key, times ) -> {
            ArrayNode array = reqs.putArray( key ) ;
            times.stream().filter( t -> t > horizon ).forEach( array::add ) ;
        } ) ;
        state.holds().forEach( ( key, p ) -> {
            if( !p.isActive( now ) ) return ;
            ObjectNode h = holds.putObject( key ) ;
            h.put( "until", p.until() ) ;
            h.put( "reason", p.reason() ) ;
            h.put( "daily", p.daily() ) ;
        } ) ;

        Path tmp = path.resolveSibling( file.getName() + ".tmp" ) ;
        MAPPER.writeValue( tmp.toFile(), root ) ;
        Files.move( tmp, path, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE ) ;
    }
}
