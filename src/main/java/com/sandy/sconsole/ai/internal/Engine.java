package com.sandy.sconsole.ai.internal;

import com.sandy.sconsole.ai.AIJob;
import com.sandy.sconsole.ai.AIReply;
import com.sandy.sconsole.ai.AIRequest;
import com.sandy.sconsole.ai.Attempt;
import com.sandy.sconsole.ai.AttemptResult;
import com.sandy.sconsole.ai.AttemptResult.Outcome;
import com.sandy.sconsole.ai.Candidate;
import com.sandy.sconsole.ai.Candidate.Via;
import com.sandy.sconsole.ai.FailureKind;
import com.sandy.sconsole.ai.HubStatus;
import com.sandy.sconsole.ai.HubView;
import com.sandy.sconsole.ai.JobHandle.State;
import com.sandy.sconsole.ai.ModelSlot;
import com.sandy.sconsole.ai.Priority;
import com.sandy.sconsole.ai.ReplyCheck;
import com.sandy.sconsole.ai.RoutingPolicy;
import com.sandy.sconsole.ai.config.AIConfig;
import com.sandy.sconsole.ai.config.GatewayInfo;
import com.sandy.sconsole.ai.config.ModelInfo;
import com.sandy.sconsole.ai.config.RateLimit;
import com.sandy.sconsole.ai.internal.GatewayException.RateLimited;
import com.sandy.sconsole.ai.internal.GatewayException.Scope;
import com.sandy.sconsole.ai.internal.GatewayException.Unreachable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The hub's scheduler. <b>Internal</b>: apps use {@code AIHub}.
 * <p>
 * Every usable model has {@code maxParallel} worker threads, its slots. A
 * free slot whose model may send (the model's and its gateway's limits
 * allow, nothing holds them back, the gateway's cap has room) collects the
 * waiting jobs that may run on the model and lets the {@link RoutingPolicy}
 * pick one. A job may run on a model:
 * <ul>
 *   <li>before its first attempt, if the model is in one of its roots (or,
 *       after an escalation, in the escalation root);</li>
 *   <li>after a failure, on the same model while it has retries left for
 *       the job, then on the models of that model's fallback guard for the
 *       failure kind (else its {@code default} guard);</li>
 *   <li>after a rate limit or an unreachable gateway (handed back, no retry
 *       used), on the same model again or its guard's models.</li>
 * </ul>
 * In a fallback list a later model takes the job only while every earlier
 * one is unavailable (held back, not merely busy or pacing); models the job
 * has used up are skipped. A job fails when, after a failure, no retry or
 * usable fallback is left.
 * <p>
 * The app may set a model to pass its jobs through ({@link #setPassthrough}):
 * it is then held back, taking no jobs, and its jobs go by its
 * {@code passthrough} guard (else its {@code default} one), as for any other
 * hold. Its requests in flight finish.
 * <p>
 * Each job carries a {@code keepGoing} check: once false, a job that has not
 * started is dropped, and a started one ends with its provisional
 * (escalated) result, else its last failure.
 * <p>
 * With a usage ledger in the config, the providers' limits (the global
 * file's) are counted there across every app using it: a request is sent
 * only once the ledger has granted it, and a provider's rate-limit hold is
 * recorded there for every app to honour. The apps' own limits are counted
 * here. Thread-safe.
 */
public final class Engine {

    private static final Logger log = LoggerFactory.getLogger( "ai.engine" ) ;

    /** Longest single wait for work, so stops and limits are seen. */
    private static final long MAX_WAIT_MS = 1000 ;

    /** Why a model passing its jobs through is held back. */
    private static final String PASSTHROUGH_REASON = "passing its jobs through" ;

    private final AIConfig      config ;
    private final RoutingPolicy policy ;

    private final Map<String,GatewayState> gateways = new LinkedHashMap<>() ;
    private final Map<String,ModelState>   models   = new LinkedHashMap<>() ;

    /** Where daily counts are kept across restarts; null for memory only. */
    private final RequestHistory history ;

    /** History entries of gateways and models not limited now, kept. */
    private final Map<String,List<Long>> otherHistory = new HashMap<>() ;

    /** Where the providers' limits are counted across apps; null if not. */
    private final UsageLedger ledger ;

    /** When the ledger was last read, epoch ms. */
    private long ledgerReadAt ;

    /** Guards the jobs, the model and gateway states, and the counters. */
    private final Object lock = new Object() ;

    private final List<JobImpl<?,?>> waiting = new ArrayList<>() ;
    private final Set<JobImpl<?,?>>  running = new HashSet<>() ;
    private final List<Thread>       workers = new ArrayList<>() ;
    private final View               view    = new View() ;

    private int  held ;
    private int  done ;
    private long nextId = 1 ;
    private long backOrder ;
    private long frontOrder ;

    private volatile boolean shutdown ;

    /** A job handed to a worker; {@code attempt} if it is to be sent. */
    private record Taken( JobImpl<?,?> job, boolean attempt, Via via ) {}

    /**
     * @param historyFile where daily request counts are kept across
     *        restarts; null to keep them in memory only
     * @param appName     sent to gateways that attribute requests to an app;
     *        may be null
     */
    public Engine( AIConfig config, File historyFile, RoutingPolicy policy,
                   String appName ) {

        this.config  = config ;
        this.policy  = policy ;
        this.history = historyFile == null ? null
                                           : new RequestHistory( historyFile ) ;
        this.ledger  = config.usageLedger() == null ? null
                     : new UsageLedger( config.usageLedger() ) ;

        Set<String> used = new HashSet<>() ;
        config.models().values().forEach( m -> used.add( m.gateway().name() ) ) ;

        for( GatewayInfo g : config.gateways().values() ) {
            boolean needed = g.enabled() && g.hasCredentials()
                             && used.contains( g.name() ) ;
            gateways.put( g.name(), new GatewayState(
                    g, needed ? Gateway.of( g, appName ) : null,
                    ledger != null ) ) ;
        }
        for( ModelInfo m : config.models().values() ) {
            models.put( m.id(), new ModelState(
                    m, gateways.get( m.gateway().name() ), ledger != null ) ) ;
        }
        restoreHistory() ;
    }

    /** Start the usable models' worker threads. Call once. */
    public void start() {

        for( ModelState m : models.values() ) {
            if( !m.info.usable() ) continue ;
            for( int slot = 1 ; slot <= m.info.maxParallel() ; slot++ ) {
                Thread t = new Thread( () -> work( m ), threadName( m, slot ) ) ;
                t.setDaemon( true ) ;
                workers.add( t ) ;
            }
            log.info( "[{}] {} slots", m.info.tag(), m.info.maxParallel() ) ;
        }
        workers.forEach( Thread::start ) ;
    }

    public AIConfig config() { return config ; }

    /**
     * Add {@code job} to the waiting jobs. A job whose roots have no usable
     * model fails at once, before this returns.
     *
     * @throws IllegalArgumentException if a root is not in the config
     */
    public <P,R> JobImpl<P,R> submit( AIJob<P,R> job, Priority priority ) {

        for( String root : job.roots() ) {
            if( !config.roots().containsKey( root ) ) {
                throw new IllegalArgumentException( "No root '" + root +
                        "' in " + config.appLocation() ) ;
            }
        }

        JobImpl<P,R> j ;
        boolean      usable ;

        synchronized( lock ) {
            j      = new JobImpl<>( this, nextId++, job ) ;
            usable = job.roots().stream().anyMatch( config::isRootUsable ) ;
            if( usable ) {
                place( j, priority, System.currentTimeMillis() ) ;
                waiting.add( j ) ;
                lock.notifyAll() ;
            }
            else {
                j.state = State.DONE ;
            }
        }
        if( usable ) {
            log.debug( "[{}] submitted: roots {}, {}", j, job.roots(),
                       priority ) ;
        }
        else {
            String reason = "no usable model in " + job.roots() ;
            log.warn( "[{}] failed at once: {}", j, reason ) ;
            safely( j, "failed", () -> job.listener().failed(
                    j, reason, FailureKind.OTHER ) ) ;
        }
        return j ;
    }

    /** Stop the workers, interrupting requests in flight. */
    public void shutdown() {

        shutdown = true ;
        workers.forEach( Thread::interrupt ) ;
        for( Thread t : workers ) {
            try {
                t.join( 10_000 ) ;
            }
            catch( InterruptedException e ) {
                Thread.currentThread().interrupt() ;
                return ;
            }
        }
        log.info( "Stopped" ) ;
    }

    /**
     * Set model {@code modelId} to pass its jobs through, or to take them
     * again. Its requests in flight finish either way.
     *
     * @return true if that changed it
     * @throws IllegalArgumentException if the app has no such model
     */
    public boolean setPassthrough( String modelId, boolean on ) {

        synchronized( lock ) {
            ModelState m = models.get( modelId ) ;
            if( m == null ) {
                throw new IllegalArgumentException( "No model '" + modelId +
                        "' in " + config.appLocation() ) ;
            }
            if( m.passthrough == on ) return false ;
            m.passthrough = on ;
            lock.notifyAll() ;

            List<String> to = m.info.fallback()
                                    .candidates( FailureKind.PASSTHROUGH ) ;
            if( on ) {
                log.info( "[{}] passing its jobs through to {}", m.info.tag(),
                          to.isEmpty() ? "none (they wait for it)" : to ) ;
            }
            else {
                log.info( "[{}] taking its jobs again", m.info.tag() ) ;
            }
            return true ;
        }
    }

    // ---- handles -------------------------------------------------------

    State state( JobImpl<?,?> j ) {
        synchronized( lock ) { return j.state ; }
    }

    boolean hold( JobImpl<?,?> j ) {

        synchronized( lock ) {
            if( j.state != State.WAITING || j.started ) return false ;
            waiting.remove( j ) ;
            j.state = State.HELD ;
            held++ ;
            lock.notifyAll() ;
        }
        log.debug( "[{}] held", j ) ;
        return true ;
    }

    boolean release( JobImpl<?,?> j, boolean front ) {

        synchronized( lock ) {
            if( j.state != State.HELD ) return false ;
            held-- ;
            place( j, front ? Priority.FRONT : Priority.NORMAL,
                   System.currentTimeMillis() ) ;
            j.state = State.WAITING ;
            waiting.add( j ) ;
            lock.notifyAll() ;
        }
        log.debug( "[{}] released{}", j, front ? " to the front" : "" ) ;
        return true ;
    }

    boolean cancel( JobImpl<?,?> j ) {

        synchronized( lock ) {
            boolean waitingUnstarted = j.state == State.WAITING && !j.started ;
            if( j.state != State.HELD && !waitingUnstarted ) return false ;
            if( j.state == State.HELD ) held-- ;
            waiting.remove( j ) ;
            j.state = State.DONE ;
            lock.notifyAll() ;
        }
        log.debug( "[{}] cancelled", j ) ;
        safely( j, "dropped", () -> dropped( j ) ) ;
        return true ;
    }

    // ---- workers -------------------------------------------------------

    private void work( ModelState m ) {

        while( !shutdown ) {
            Taken taken ;
            try {
                synchronized( lock ) { taken = take( m ) ; }
            }
            catch( InterruptedException e ) {
                continue ;   // shutdown is checked by the loop
            }
            if( taken == null ) continue ;

            try {
                if( taken.attempt() ) attempt( taken.job(), m, taken.via() ) ;
                else windUp( taken.job() ) ;
            }
            catch( RuntimeException e ) {
                log.error( "[{}] [{}] Unexpected error", taken.job(),
                           m.info.tag(), e ) ;
                end( taken.job(), null, null, e.toString(),
                     FailureKind.OTHER ) ;
            }
        }
    }

    /**
     * Under lock: wait until model {@code m} can take a job, and bind it. A
     * waiting job whose {@code keepGoing} turned false is handed over first,
     * not to be sent, so it can be wound up.
     *
     * @return null on shutdown
     */
    private Taken take( ModelState m ) throws InterruptedException {

        while( !shutdown ) {
            JobImpl<?,?> stopped = firstStopped() ;
            if( stopped != null ) {
                waiting.remove( stopped ) ;
                running.add( stopped ) ;
                stopped.state = State.RUNNING ;
                return new Taken( stopped, false, null ) ;
            }

            long now = System.currentTimeMillis() ;
            readLedger( now, false ) ;
            long at = sendableAt( m, now ) ;

            if( at <= now && m.gateway.hasRoom() ) {
                List<Candidate> eligible = candidates( m, now ) ;
                Candidate       chosen   = eligible.isEmpty()
                                         ? null : pick( m, eligible ) ;
                if( chosen != null && reserve( m, now ) ) {
                    JobImpl<?,?> j = (JobImpl<?,?>) chosen.job() ;
                    bind( j, m, now ) ;
                    return new Taken( j, true, chosen.via() ) ;
                }
                at = sendableAt( m, now ) ;
            }
            long waitMs = at > now ? Math.min( MAX_WAIT_MS, at - now )
                                   : MAX_WAIT_MS ;
            lock.wait( Math.max( 1, waitMs ) ) ;
        }
        return null ;
    }

    /** Under lock: the policy's pick among {@code eligible}. */
    private Candidate pick( ModelSlot slot, ModelState m,
                            List<Candidate> eligible ) {

        try {
            Candidate c = policy.pick( slot, eligible, view ) ;
            if( c != null && !eligible.contains( c ) ) {
                log.error( "[{}] The routing policy picked a job that was not " +
                           "offered - ignored", m.info.tag() ) ;
                return null ;
            }
            return c ;
        }
        catch( RuntimeException e ) {
            log.error( "[{}] The routing policy failed - taking the first " +
                       "job in order", m.info.tag(), e ) ;
            return RoutingPolicy.inOrder().pick( slot, eligible, view ) ;
        }
    }

    private Candidate pick( ModelState m, List<Candidate> eligible ) {

        ModelSlot slot = new ModelSlot( m.info, m.inFlight, m.avgLatency() ) ;
        Candidate c    = pick( slot, m, Collections.unmodifiableList( eligible ) ) ;

        if( log.isDebugEnabled() ) {
            log.debug( "[{}] slot free, {} eligible: {}", m.info.tag(),
                       eligible.size(),
                       c == null ? "none taken" : "took " + c.job() + " (" +
                                                  c.via() + ")" ) ;
        }
        return c ;
    }

    /** Under lock: {@code j} runs on {@code m}; count the request. */
    private void bind( JobImpl<?,?> j, ModelState m, long now ) {

        waiting.remove( j ) ;
        running.add( j ) ;
        j.state = State.RUNNING ;
        m.inFlight++ ;
        m.gateway.inFlight++ ;
        recordUse( m, now ) ;
    }

    // ---- one attempt ---------------------------------------------------

    /** Send {@code j}'s next attempt to {@code m}, then route it on. */
    private <P,R> void attempt( JobImpl<P,R> j, ModelState m, Via via ) {

        if( !mayContinue( j ) ) {
            slotDone( m ) ;
            windUp( j ) ;
            return ;
        }
        if( !j.started ) {
            j.started = true ;
            safely( j, "started", () -> j.job.listener().started( j ) ) ;
        }

        AIRequest request ;
        try {
            request = j.job.request().build( m.info ) ;
        }
        catch( Exception e ) {
            slotDone( m ) ;
            log.error( "[{}] Could not build the request", j, e ) ;
            end( j, null, null, "request error: " + e.getMessage(),
                 FailureKind.OTHER ) ;
            return ;
        }

        int     number  = j.attempts + 1 ;
        long    start   = System.currentTimeMillis() ;
        Attempt attempt = new Attempt( j.id, number, m.info, request, start ) ;

        log.info( "[{}] [{}] attempt {} ({})", j, m.info.tag(), number,
                  describe( via, j ) ) ;
        safely( j, "attemptStarted",
                () -> j.job.listener().attemptStarted( j, attempt ) ) ;

        AIReply          reply = null ;
        GatewayException error = null ;

        synchronized( lock ) { j.current = attempt ; }
        try {
            reply = m.gateway.transport.complete( m.info, request ) ;
        }
        catch( GatewayException e ) {
            error = e ;
        }
        finally {
            synchronized( lock ) { j.current = null ; }
        }
        long elapsed = System.currentTimeMillis() - start ;

        ReplyCheck<R> check = reply == null ? null : check( j, attempt, reply ) ;
        AttemptResult result = result( attempt, elapsed, reply, error, check ) ;

        afterAttempt( m, result, error ) ;
        log.info( "[{}] [{}] attempt {} {} in {}.{}s - {}", j, m.info.tag(),
                  number, describe( result ), elapsed / 1000,
                  elapsed % 1000 / 100,
                  result.usage() != null ? result.usage().describe()
                                         : "no token count" ) ;
        safely( j, "attemptFinished",
                () -> j.job.listener().attemptFinished( j, result ) ) ;

        if( check instanceof ReplyCheck.Accept<R> accept ) {
            counted( j, m, number ) ;
            end( j, accept.value(), m.info, null, null ) ;
        }
        else if( check instanceof ReplyCheck.Escalate<R> esc ) {
            counted( j, m, number ) ;
            escalate( j, m, esc ) ;
        }
        else if( result.kind().isHandedBack() ) {
            handedBack( j, m, result ) ;
        }
        else {
            counted( j, m, number ) ;
            failed( j, m, result ) ;
        }
    }

    /** The app's verdict on {@code reply}; a throwing check rejects it. */
    private <P,R> ReplyCheck<R> check( JobImpl<P,R> j, Attempt attempt,
                                       AIReply reply ) {

        try {
            ReplyCheck<R> check = j.job.listener().check( j, attempt, reply ) ;
            return check != null ? check
                                 : ReplyCheck.reject( "the reply was not checked" ) ;
        }
        catch( RuntimeException e ) {
            log.error( "[{}] The reply check failed", j, e ) ;
            return ReplyCheck.reject( "the reply could not be checked: " + e ) ;
        }
    }

    private static AttemptResult result( Attempt attempt, long elapsed,
                                         AIReply reply, GatewayException error,
                                         ReplyCheck<?> check ) {

        if( error != null ) {
            Outcome outcome = switch( error.getKind() ) {
                case RATE_LIMITED_RPM,
                     RATE_LIMITED_RPD -> Outcome.RATE_LIMITED ;
                case UNREACHABLE      -> Outcome.UNREACHABLE ;
                default               -> Outcome.FAILED ;
            } ;
            return new AttemptResult( attempt, elapsed, outcome,
                                      error.getDetail(), error.getMessage(),
                                      error.getKind(), error.getUsage() ) ;
        }
        if( check instanceof ReplyCheck.Reject<?> reject ) {
            return new AttemptResult( attempt, elapsed, Outcome.FAILED,
                                      reply.text(), reject.reason(),
                                      FailureKind.BAD_REPLY, reply.usage() ) ;
        }
        return new AttemptResult( attempt, elapsed, Outcome.OK, reply.text(),
                                  null, null, reply.usage() ) ;
    }

    /**
     * Free the slot and record what the attempt says about the model and
     * its gateway.
     */
    private void afterAttempt( ModelState m, AttemptResult result,
                               GatewayException error ) {

        long         now = System.currentTimeMillis() ;
        GatewayState g   = m.gateway ;
        boolean      down = error instanceof Unreachable ;

        synchronized( lock ) {
            m.inFlight-- ;
            g.inFlight-- ;
            if( !down ) m.addLatency( result.elapsedMs() ) ;
            if( result.isSuccess() ) m.succeeded++ ;
            else if( !result.kind().isHandedBack() ) m.failed++ ;
            if( error instanceof RateLimited rl ) suspend( m, rl, now ) ;
            if( down != g.unreachable ) setReachable( g, !down ) ;
            lock.notifyAll() ;
        }
    }

    /** Free a slot that sent nothing. */
    private void slotDone( ModelState m ) {

        synchronized( lock ) {
            m.inFlight-- ;
            m.gateway.inFlight-- ;
            lock.notifyAll() ;
        }
    }

    /** Under lock. */
    private void setReachable( GatewayState g, boolean reachable ) {

        g.unreachable = !reachable ;
        if( reachable ) {
            log.info( "[{}] reachable again", g.info.name() ) ;
        }
        else {
            log.warn( "[{}] cannot be reached - sending it one request at a " +
                      "time until it answers", g.info.name() ) ;
        }
    }

    /** {@code j}'s attempt {@code number} on {@code m} counts. */
    private static void counted( JobImpl<?,?> j, ModelState m, int number ) {
        j.attempts = number ;
        j.tries.merge( m.id(), 1, Integer::sum ) ;
    }

    // ---- routing after an attempt --------------------------------------

    private <P,R> void escalate( JobImpl<P,R> j, ModelState m,
                                 ReplyCheck.Escalate<R> esc ) {

        String root = esc.root() ;
        boolean other = config.roots().containsKey( root )
                        && config.root( root ).stream().anyMatch(
                                x -> x.usable() && !x.id().equals( m.id() ) ) ;

        if( j.escalationRoot != null || !other || !mayContinue( j ) ) {
            String why = j.escalationRoot != null ? "already escalated"
                       : !other ? "no other usable model in root " + root
                       : "stopping" ;
            log.info( "[{}] [{}] not escalating ({}) - keeping the reply", j,
                      m.info.tag(), why ) ;
            end( j, esc.value(), m.info, null, null ) ;
            return ;
        }
        log.info( "[{}] [{}] escalating to root {}: {}", j, m.info.tag(), root,
                  esc.why() ) ;
        j.provisional      = esc.value() ;
        j.provisionalModel = m.info ;
        j.escalationRoot   = root ;
        j.lastModel        = null ;
        j.lastKind         = null ;
        j.excluded.add( m.id() ) ;
        requeue( j ) ;
    }

    private void handedBack( JobImpl<?,?> j, ModelState m,
                             AttemptResult result ) {

        j.lastModel   = m.id() ;
        j.lastKind    = result.kind() ;
        j.lastFailure = result.failure() ;

        if( !mayContinue( j ) ) {
            windUp( j ) ;
            return ;
        }
        List<String> guard = m.info.fallback().candidates( result.kind() ) ;
        log.info( "[{}] [{}] handed back ({}) - next on {}{}", j, m.info.tag(),
                  result.failure(), m.id(),
                  guard.isEmpty() ? " when it may send again"
                                  : " or, while it is held back, " + guard ) ;
        requeue( j ) ;
    }

    private void failed( JobImpl<?,?> j, ModelState m, AttemptResult result ) {

        j.lastModel   = m.id() ;
        j.lastKind    = result.kind() ;
        j.lastFailure = result.failure() ;

        if( !mayContinue( j ) ) {
            windUp( j ) ;
            return ;
        }

        int     used  = j.tries( m.id() ) ;
        boolean retry = used <= m.info.retries() ;

        if( retry ) {
            log.info( "[{}] [{}] failed ({}: {}) - retry {} of {} on it", j,
                      m.info.tag(), result.kind(), result.failure(), used,
                      m.info.retries() ) ;
            requeue( j ) ;
            return ;
        }
        List<String> options = options( j, m.info.fallback()
                                              .candidates( result.kind() ) ) ;
        String guard = m.info.fallback().guardFor( result.kind() ) ;

        if( !options.isEmpty() ) {
            log.info( "[{}] [{}] failed ({}: {}) - falling back by guard '{}' " +
                      "to {}", j, m.info.tag(), result.kind(), result.failure(),
                      guard, options ) ;
            requeue( j ) ;
            return ;
        }
        log.info( "[{}] [{}] failed ({}: {}) - no retry or fallback left", j,
                  m.info.tag(), result.kind(), result.failure() ) ;
        end( j, null, null, result.failure(), result.kind() ) ;
    }

    /**
     * The models of {@code list} {@code j} may still run on: usable, not
     * used up by it, not excluded.
     */
    private List<String> options( JobImpl<?,?> j, List<String> list ) {

        List<String> options = new ArrayList<>() ;
        synchronized( lock ) {
            for( String id : list ) {
                ModelState s = models.get( id ) ;
                if( s != null && s.info.usable() && !usedUp( j, s )
                    && !j.excluded.contains( id ) ) {
                    options.add( id ) ;
                }
            }
        }
        return options ;
    }

    /** Finish a job whose {@code keepGoing} turned false. */
    private void windUp( JobImpl<?,?> j ) {

        if( j.provisional != null || j.started ) {
            log.info( "[{}] stopped", j ) ;
            end( j, null, null, j.lastFailure,
                 j.lastKind != null ? j.lastKind : FailureKind.OTHER ) ;
        }
        else {
            log.debug( "[{}] dropped unstarted", j ) ;
            synchronized( lock ) {
                running.remove( j ) ;
                waiting.remove( j ) ;
                j.state = State.DONE ;
                lock.notifyAll() ;
            }
            safely( j, "dropped", () -> dropped( j ) ) ;
        }
    }

    /**
     * End {@code j}: with {@code value} from {@code model}, else with its
     * provisional result if it has one, else as failed.
     */
    private <P,R> void end( JobImpl<P,R> j, Object value, ModelInfo model,
                            String reason, FailureKind kind ) {

        synchronized( lock ) {
            running.remove( j ) ;
            waiting.remove( j ) ;
            j.state = State.DONE ;
            if( j.started ) done++ ;
            lock.notifyAll() ;
        }

        @SuppressWarnings( "unchecked" )
        R result = (R) value ;

        if( result != null ) {
            log.info( "[{}] succeeded on {} after {} attempts", j, model.tag(),
                      j.attempts ) ;
            safely( j, "succeeded", () -> j.job.listener().succeeded(
                    j, result, model, false ) ) ;
        }
        else if( j.provisional != null ) {
            log.info( "[{}] escalation gave no answer - keeping the reply of " +
                      "{}", j, j.provisionalModel.tag() ) ;
            safely( j, "succeeded", () -> j.job.listener().succeeded(
                    j, j.provisional, j.provisionalModel, true ) ) ;
        }
        else {
            log.info( "[{}] failed after {} attempts: {}", j, j.attempts,
                      reason ) ;
            safely( j, "failed",
                    () -> j.job.listener().failed( j, reason, kind ) ) ;
        }
    }

    private static <P,R> void dropped( JobImpl<P,R> j ) {
        j.job.listener().dropped( j ) ;
    }

    /** Put a running job back among the waiting, keeping its place. */
    private void requeue( JobImpl<?,?> j ) {

        synchronized( lock ) {
            running.remove( j ) ;
            j.state    = State.WAITING ;
            j.queuedAt = System.currentTimeMillis() ;
            waiting.add( j ) ;
            lock.notifyAll() ;
        }
    }

    private boolean mayContinue( JobImpl<?,?> j ) {
        return !shutdown && !Thread.currentThread().isInterrupted()
               && j.job.keepGoing().getAsBoolean() ;
    }

    // ---- eligibility (under lock) --------------------------------------

    /** The waiting jobs that may run on {@code m} now. */
    private List<Candidate> candidates( ModelState m, long now ) {

        List<Candidate> list = new ArrayList<>() ;
        for( JobImpl<?,?> j : waiting ) {
            Via via = eligibility( j, m, now ) ;
            if( via != null ) {
                list.add( new Candidate( j, via, now - j.queuedAt, j.order ) ) ;
            }
        }
        return list ;
    }

    /** Why {@code j} may run on {@code m} now; null if it may not. */
    private Via eligibility( JobImpl<?,?> j, ModelState m, long now ) {

        String id = m.id() ;
        if( !m.info.usable() || m.passthrough || j.excluded.contains( id ) ) {
            return null ;
        }

        if( j.lastModel == null ) {
            if( usedUp( j, m ) ) return null ;

            List<String> entry = entryModels( j ) ;
            if( entry.contains( id ) ) {
                return j.escalationRoot != null ? Via.ESCALATION : Via.ROOT ;
            }
            return standsIn( j, entry, id, now )
                   ? ( j.escalationRoot != null ? Via.FALLBACK : Via.STAND_IN )
                   : null ;
        }

        ModelState last = models.get( j.lastModel ) ;
        if( j.lastKind.isHandedBack() ) {
            FailureKind  held  = holdKind( last, now ) ;
            List<String> order = new ArrayList<>() ;

            order.add( last.id() ) ;
            order.addAll( last.info.fallback().candidates(
                    held != null ? held : j.lastKind ) ) ;
            if( !firstAvailable( j, order, id, now ) ) return null ;
            return id.equals( last.id() ) ? Via.HANDED_BACK : Via.FALLBACK ;
        }
        if( !usedUp( j, last ) ) {
            if( id.equals( last.id() ) ) return Via.RETRY ;
            return heldFallback( j, last, id, now ) ? Via.FALLBACK : null ;
        }
        List<String> guard = last.info.fallback().candidates( j.lastKind ) ;
        return firstAvailable( j, guard, id, now ) ? Via.FALLBACK : null ;
    }

    /**
     * The models {@code j} starts on: its escalation root's if it was
     * escalated, else its roots'.
     */
    private List<String> entryModels( JobImpl<?,?> j ) {

        List<String> ids = new ArrayList<>() ;
        List<String> roots = j.escalationRoot != null
                           ? List.of( j.escalationRoot ) : j.job.roots() ;

        for( String root : roots ) {
            for( String id : config.roots().get( root ) ) {
                if( !ids.contains( id ) ) ids.add( id ) ;
            }
        }
        return ids ;
    }

    /**
     * True if {@code id} may take the not yet tried {@code j} in place of
     * its {@code entry} models: every one it can use is held back, and
     * {@code id} comes first in one's fallback guard for why it is held.
     */
    private boolean standsIn( JobImpl<?,?> j, List<String> entry, String id,
                              long now ) {

        List<ModelState> held = new ArrayList<>() ;
        for( String e : entry ) {
            ModelState s = models.get( e ) ;
            if( s == null || !s.info.usable() || j.excluded.contains( e )
                || usedUp( j, s ) ) {
                continue ;
            }
            if( holdKind( s, now ) == null ) return false ;
            held.add( s ) ;
        }
        for( ModelState s : held ) {
            if( heldFallback( j, s, id, now ) ) return true ;
        }
        return false ;
    }

    /**
     * True if {@code id} may take {@code j} from {@code from} while
     * {@code from} is held back: it comes first in {@code from}'s fallback
     * guard for why it is held.
     */
    private boolean heldFallback( JobImpl<?,?> j, ModelState from, String id,
                                  long now ) {

        FailureKind kind = holdKind( from, now ) ;
        if( kind == null || id.equals( from.id() ) ) return false ;

        List<String> order = new ArrayList<>() ;
        order.add( from.id() ) ;
        order.addAll( from.info.fallback().candidates( kind ) ) ;
        return firstAvailable( j, order, id, now ) ;
    }

    /**
     * Why {@code m} is held back, as the fallback guard that applies:
     * {@code PASSTHROUGH} while the app has set it so (whatever else holds
     * it), {@code UNREACHABLE} for its gateway, {@code RATE_LIMITED_RPD} while it
     * or its gateway is held for the day (its rpd used up, or a daily 429),
     * {@code RATE_LIMITED_RPM} for any other hold (a 429 pause or
     * suspension); null if it is not held back, or can't be used at all.
     * Routine per-minute pacing is no hold.
     */
    private FailureKind holdKind( ModelState m, long now ) {

        if( !m.info.usable() ) return null ;
        if( m.passthrough ) return FailureKind.PASSTHROUGH ;
        if( m.gateway.unreachable ) return FailureKind.UNREACHABLE ;
        if( m.gateway.limiter.isHeldForTheDay( now )
            || m.isHeldForTheDay( now ) ) {
            return FailureKind.RATE_LIMITED_RPD ;
        }
        return heldReason( m, now ) != null ? FailureKind.RATE_LIMITED_RPM
                                            : null ;
    }

    /**
     * True if {@code id} is the model of {@code order} that may take
     * {@code j}: models it can't use are skipped, and every one before
     * {@code id} is unavailable.
     */
    private boolean firstAvailable( JobImpl<?,?> j, List<String> order,
                                    String id, long now ) {

        for( String o : order ) {
            ModelState s = models.get( o ) ;
            if( s == null || !s.info.usable() || j.excluded.contains( o ) ) {
                continue ;
            }
            boolean handedBackHere = o.equals( j.lastModel )
                                     && j.lastKind.isHandedBack() ;
            if( !handedBackHere && usedUp( j, s ) ) continue ;
            if( o.equals( id ) ) return true ;
            if( isAvailable( s, now ) ) return false ;
        }
        return false ;
    }

    /** True if {@code j} has used up its attempts on {@code m}. */
    private static boolean usedUp( JobImpl<?,?> j, ModelState m ) {
        return j.tries( m.id() ) > m.info.retries() ;
    }

    private JobImpl<?,?> firstStopped() {

        for( JobImpl<?,?> j : waiting ) {
            if( !j.job.keepGoing().getAsBoolean() ) return j ;
        }
        return null ;
    }

    /** Under lock: give {@code j} its place among the waiting jobs. */
    private void place( JobImpl<?,?> j, Priority priority, long now ) {
        j.order    = priority == Priority.FRONT ? --frontOrder : backOrder++ ;
        j.queuedAt = now ;
    }

    // ---- limits (under lock) -------------------------------------------

    /**
     * Epoch ms from which {@code m} may send: its own and its gateway's
     * limits and suspensions, and its pause.
     */
    private long sendableAt( ModelState m, long now ) {

        long  at    = Math.max( m.availableAt( now ),
                                m.gateway.limiter.availableAt( now ) ) ;
        Pause pause = m.pause( now ) ;

        return pause != null ? Math.max( at, pause.until() ) : at ;
    }

    /**
     * True if {@code m} is usable and not held back: not passed through, not
     * paused, its own or its gateway's suspension or daily limit not in
     * force, its gateway reachable. Pacing and busy slots don't count.
     */
    private boolean isAvailable( ModelState m, long now ) {
        return m.info.usable() && heldReason( m, now ) == null ;
    }

    /** Why {@code m} is held back at {@code now}; null if it is not. */
    private String heldReason( ModelState m, long now ) {

        GatewayState g = m.gateway ;
        if( m.passthrough ) return PASSTHROUGH_REASON ;
        if( g.unreachable ) return g.info.name() + " cannot be reached" ;

        String reason = g.limiter.heldBackReason( now ) ;
        if( reason != null ) return reason ;

        Pause pause = m.pause( now ) ;
        if( pause != null ) return pause.reason() ;
        return m.heldBackReason( now ) ;
    }

    /** True if {@code m} has a free slot and may send right now. */
    private boolean canSendNow( ModelState m, long now ) {
        return m.info.usable() && !m.passthrough
               && m.inFlight < m.info.maxParallel()
               && m.gateway.hasRoom() && sendableAt( m, now ) <= now ;
    }

    /**
     * Hold back {@code m}, or its whole gateway when the limit covers every
     * model: for as long as the provider asked, else the gateway's cooldown.
     * A rate limit is recorded in the ledger, for every app to honour.
     */
    private void suspend( ModelState m, RateLimited e, long now ) {

        GatewayState g      = m.gateway ;
        Duration     pause  = e.getRetryAfter() != null ? e.getRetryAfter()
                                                        : g.info.cooldown() ;
        long         until  = now + pause.toMillis() ;
        String       reason = e.getExplanation() != null ? e.getExplanation()
                                                         : e.getMessage() ;

        if( e.getScope() == Scope.MODEL ) {
            Pause p = new Pause( until, reason, false ) ;
            m.pause = Pause.later( m.pause, p ) ;
            log.warn( "[{}] {} - holding the model back for {}s",
                      m.info.tag(), e.getMessage(), pause.toSeconds() ) ;
            if( ledger != null ) ledger.hold( m.historyKey(), p, now ) ;
        }
        else {
            Pause p = new Pause( until, reason, e.getScope() == Scope.DAY ) ;
            g.limiter.suspend( p.until(), p.reason(), p.daily() ) ;
            log.warn( "[{}] {} - holding the gateway back for {}s",
                      g.info.name(), e.getMessage(), pause.toSeconds() ) ;
            // A connection failure stays this app's: it probes until the
            // gateway answers, and a shared hold could not be lifted early.
            if( ledger != null && !( e instanceof Unreachable ) ) {
                ledger.hold( g.historyKey(), p, now ) ;
            }
        }
    }

    // ---- usage ledger (under lock) -------------------------------------

    /**
     * Ask the ledger to count a request on {@code m} at {@code now} against
     * the providers' limits of {@code m} and its gateway, and take every
     * app's counts and holds from it.
     *
     * @return true if the request may be sent: granted, or nothing to ask
     */
    private boolean reserve( ModelState m, long now ) {

        Map<String,RateLimit> limits = new LinkedHashMap<>() ;
        GatewayState          g      = m.gateway ;

        if( !g.info.rateLimit().isNone() ) {
            limits.put( g.historyKey(), g.info.rateLimit() ) ;
        }
        if( m.shared != null ) {
            limits.put( m.historyKey(), m.info.providerRateLimit() ) ;
        }
        if( ledger == null || limits.isEmpty() ) return true ;

        UsageLedger.Reservation r = ledger.reserve( limits, now ) ;
        apply( r.state(), now ) ;
        if( !r.granted() ) {
            log.debug( "[{}] the usage ledger has no room - another app sent " +
                       "first", m.info.tag() ) ;
        }
        return r.granted() ;
    }

    /**
     * Take every app's counts and holds from the ledger: at once if
     * {@code force}, else at most once in {@link #MAX_WAIT_MS}.
     */
    private void readLedger( long now, boolean force ) {

        if( ledger == null ) return ;
        if( !force && now - ledgerReadAt < MAX_WAIT_MS ) return ;
        apply( ledger.read( now ), now ) ;
    }

    private void apply( UsageLedger.State state, long now ) {

        ledgerReadAt = now ;
        for( GatewayState g : gateways.values() ) {
            g.limiter.sync( state.requests( g.historyKey() ), now ) ;

            Pause p = state.holds().get( g.historyKey() ) ;
            Pause s = g.limiter.suspension( now ) ;
            if( p == null || s != null && s.until() >= p.until() ) continue ;
            g.limiter.suspend( p.until(), p.reason(), p.daily() ) ;
            log.warn( "[{}] held back until {} as another app was told: {}",
                      g.info.name(), Instant.ofEpochMilli( p.until() ),
                      p.reason() ) ;
        }
        for( ModelState m : models.values() ) {
            if( m.shared != null ) {
                m.shared.sync( state.requests( m.historyKey() ), now ) ;
            }

            Pause p = state.holds().get( m.historyKey() ) ;
            Pause s = m.pause( now ) ;
            if( p == null || s != null && s.until() >= p.until() ) continue ;
            m.pause = p ;
            log.warn( "[{}] held back until {} as another app was told: {}",
                      m.info.tag(), Instant.ofEpochMilli( p.until() ),
                      p.reason() ) ;
        }
    }

    /**
     * Count a request on {@code m}, saving the history if it is kept. With
     * a ledger, the providers' counts are already in it ({@link #reserve}).
     */
    private void recordUse( ModelState m, long now ) {

        m.limiter.record( now ) ;
        if( ledger == null ) m.gateway.limiter.record( now ) ;
        if( history != null && ( hasDailyLimit( m ) || hasDailyLimit( m.gateway ) ) ) {
            saveHistory( now ) ;
        }
    }

    /** True if {@code m}'s counts in this app have a daily limit. */
    private static boolean hasDailyLimit( ModelState m ) {
        return m.limiter.rpd() > 0 ;
    }

    /** True if {@code g}'s counts are kept in this app's history. */
    private boolean hasDailyLimit( GatewayState g ) {
        return ledger == null && g.info.rateLimit().rpd() > 0 ;
    }

    /**
     * Load today's (UTC) request times into the daily-limited windows. With
     * a ledger, the times of provider-limited keys are added to it (they may
     * be from before it was used), and its counts and holds are taken.
     */
    private void restoreHistory() {

        long                   now   = System.currentTimeMillis() ;
        Map<String,List<Long>> saved = history == null ? new HashMap<>()
                                                       : history.load( now ) ;
        if( ledger != null ) seedLedger( saved, now ) ;
        if( history == null ) return ;

        for( GatewayState g : gateways.values() ) {
            List<Long> times = saved.remove( g.historyKey() ) ;
            if( times == null || !hasDailyLimit( g ) ) continue ;
            g.limiter.restore( times, now ) ;
            log.info( "[{}] {} of {} requests today already used",
                      g.info.name(), g.limiter.countToday( now ),
                      g.info.rateLimit().rpd() ) ;
        }
        for( ModelState m : models.values() ) {
            List<Long> times = saved.remove( m.historyKey() ) ;
            if( times == null || !hasDailyLimit( m ) ) continue ;
            m.limiter.restore( times, now ) ;
            log.info( "[{}] {} of {} requests today already used{}",
                      m.info.tag(), m.limiter.countToday( now ),
                      m.limiter.rpd(), ledger != null ? " (this app)" : "" ) ;
        }
        otherHistory.putAll( saved ) ;
    }

    /** Add the history's times of provider-limited keys to the ledger. */
    private void seedLedger( Map<String,List<Long>> saved, long now ) {

        Map<String,List<Long>> seed = new HashMap<>() ;
        for( GatewayState g : gateways.values() ) {
            List<Long> times = saved.get( g.historyKey() ) ;
            if( times != null && !g.info.rateLimit().isNone() ) {
                seed.put( g.historyKey(), times ) ;
            }
        }
        for( ModelState m : models.values() ) {
            List<Long> times = saved.get( m.historyKey() ) ;
            if( times != null && m.shared != null ) {
                seed.put( m.historyKey(), times ) ;
            }
        }
        if( !seed.isEmpty() ) ledger.merge( seed, now ) ;
        readLedger( now, true ) ;
        log.info( "Counting the providers' limits across apps in {}",
                  ledger.file() ) ;

        for( GatewayState g : gateways.values() ) {
            if( g.info.rateLimit().rpd() == 0 ) continue ;
            log.info( "[{}] {} of {} requests today already used (all apps)",
                      g.info.name(), g.limiter.countToday( now ),
                      g.info.rateLimit().rpd() ) ;
        }
        for( ModelState m : models.values() ) {
            if( m.shared == null || m.shared.rpd() == 0 ) continue ;
            log.info( "[{}] {} of {} requests today already used (all apps)",
                      m.info.tag(), m.shared.countToday( now ),
                      m.shared.rpd() ) ;
        }
    }

    private void saveHistory( long now ) {

        Map<String,List<Long>> all = new HashMap<>( otherHistory ) ;
        for( GatewayState g : gateways.values() ) {
            if( hasDailyLimit( g ) ) {
                all.put( g.historyKey(), g.limiter.starts( now ) ) ;
            }
        }
        for( ModelState m : models.values() ) {
            if( hasDailyLimit( m ) ) {
                all.put( m.historyKey(), m.limiter.starts( now ) ) ;
            }
        }
        history.save( all, now ) ;
    }

    // ---- status --------------------------------------------------------

    public HubStatus status() {

        synchronized( lock ) {
            long now = System.currentTimeMillis() ;
            readLedger( now, false ) ;

            List<HubStatus.GatewayStatus> gs = new ArrayList<>() ;
            for( GatewayState g : gateways.values() ) {
                Pause  susp   = g.limiter.suspension( now ) ;
                String reason = g.unreachable
                              ? g.info.name() + " cannot be reached"
                              : g.limiter.heldBackReason( now ) ;
                gs.add( new HubStatus.GatewayStatus(
                        g.info.name(), g.info.type(), g.info.enabled(),
                        !g.unreachable, susp != null ? susp.until() : 0,
                        reason, g.limiter.isHeldForTheDay( now ),
                        g.limiter.countToday( now ), g.inFlight,
                        g.info.maxParallel() ) ) ;
            }

            List<HubStatus.ModelStatus> ms = new ArrayList<>() ;
            long nextSendAt = Long.MAX_VALUE ;
            for( ModelState m : models.values() ) {
                Pause pause    = m.pause( now ) ;
                int   eligible = m.info.usable() ? candidates( m, now ).size()
                                                 : 0 ;
                if( eligible > 0 ) {
                    nextSendAt = Math.min( nextSendAt, sendableAt( m, now ) ) ;
                }
                ms.add( new HubStatus.ModelStatus(
                        m.id(), m.gateway.info.name(), m.info.label(),
                        m.info.usable(), isAvailable( m, now ),
                        pause != null ? pause.until() : 0,
                        m.info.usable() ? heldReason( m, now ) : m.info.whyUnusable(),
                        m.isHeldForTheDay( now ), m.inFlight,
                        m.info.maxParallel(), eligible, m.avgLatency(),
                        m.succeeded, m.failed, m.info.rateLimit().rpm(),
                        m.info.rateLimit().rpd(), m.countLastMinute( now ),
                        m.countToday( now ), m.info.enabledGlobally(),
                        m.info.enabledInApp(), m.passthrough ) ) ;
            }

            HubStatus.Jobs jobs = new HubStatus.Jobs(
                    waiting.size(), held, running.size(), done ) ;
            return new HubStatus( now, gs, ms, jobs, nextSendAt,
                                  notices( now ), requests(), queue( now ) ) ;
        }
    }

    /** Under lock: the requests in flight, oldest first. */
    private List<HubStatus.Request> requests() {

        List<HubStatus.Request> list = new ArrayList<>() ;
        for( JobImpl<?,?> j : running ) {
            Attempt a = j.current ;
            if( a == null ) continue ;
            list.add( new HubStatus.Request(
                    j.id, String.valueOf( j.job.payload() ), a.model().id(),
                    a.model().gateway().name(), a.number(), a.startedAt() ) ) ;
        }
        list.sort( Comparator.comparingLong( HubStatus.Request::startedAt ) ) ;
        return list ;
    }

    /** Under lock: the waiting jobs in order, with the models they may take. */
    private List<HubStatus.WaitingJob> queue( long now ) {

        Map<JobImpl<?,?>,List<String>> eligible = new HashMap<>() ;
        for( ModelState m : models.values() ) {
            for( JobImpl<?,?> j : waiting ) {
                if( eligibility( j, m, now ) != null ) {
                    eligible.computeIfAbsent( j, k -> new ArrayList<>() )
                            .add( m.id() ) ;
                }
            }
        }

        List<JobImpl<?,?>> jobs = new ArrayList<>( waiting ) ;
        jobs.sort( Comparator.comparingLong( j -> j.order ) ) ;

        List<HubStatus.WaitingJob> list = new ArrayList<>() ;
        for( JobImpl<?,?> j : jobs ) {
            list.add( new HubStatus.WaitingJob(
                    j.id, String.valueOf( j.job.payload() ), j.queuedAt,
                    j.attempts, j.lastModel,
                    j.lastModel != null ? j.lastFailure : null,
                    eligible.getOrDefault( j, List.of() ) ) ) ;
        }
        return list ;
    }

    /**
     * Under lock: the gateways held back right now, and the models held
     * back on their own (of gateways that are not).
     */
    private List<HubStatus.Notice> notices( long now ) {

        List<HubStatus.Notice> notices = new ArrayList<>() ;
        Set<String>            whole   = new HashSet<>() ;

        for( GatewayState g : gateways.values() ) {
            if( g.transport == null ) continue ;
            String reason = g.unreachable
                          ? g.info.name() + " cannot be reached"
                          : g.limiter.heldBackReason( now ) ;
            if( reason == null ) continue ;
            whole.add( g.info.name() ) ;
            notices.add( new HubStatus.Notice(
                    g.info.name(), g.info.type(), null, reason,
                    Math.max( now, g.limiter.availableAt( now ) ),
                    g.limiter.isHeldForTheDay( now ) ) ) ;
        }
        for( ModelState m : models.values() ) {
            if( !m.info.usable() || whole.contains( m.gateway.info.name() ) ) {
                continue ;
            }
            Pause  pause  = m.pause( now ) ;
            String reason = pause != null ? pause.reason()
                                          : m.heldBackReason( now ) ;
            if( reason == null ) continue ;
            long until = pause != null ? pause.until() : m.availableAt( now ) ;
            notices.add( new HubStatus.Notice(
                    m.gateway.info.name(), m.gateway.info.type(), m.id(),
                    reason, until, m.isHeldForTheDay( now ) ) ) ;
        }
        return notices ;
    }

    // ---- helpers -------------------------------------------------------

    /** Run a listener callback; an exception is logged, not thrown. */
    private static void safely( JobImpl<?,?> j, String what, Runnable call ) {

        try {
            call.run() ;
        }
        catch( RuntimeException e ) {
            log.error( "[{}] The {} callback failed", j, what, e ) ;
        }
    }

    private String describe( Via via, JobImpl<?,?> j ) {
        return switch( via ) {
            case ROOT        -> "new, roots " + j.job.roots() ;
            case STAND_IN    -> "new, standing in while the models of roots " +
                                j.job.roots() + " are held back" ;
            case ESCALATION  -> "escalated to root " + j.escalationRoot ;
            case RETRY       -> "retry" ;
            case HANDED_BACK -> "again after " + j.lastFailure ;
            case FALLBACK    -> j.lastModel == null
                                ? "fallback while the models of root " +
                                  j.escalationRoot + " are held back"
                                : "fallback from " + j.lastModel + " after " +
                                  j.lastKind ;
        } ;
    }

    private static String describe( AttemptResult r ) {
        return switch( r.outcome() ) {
            case OK           -> "OK" ;
            case FAILED       -> "FAILED (" + r.kind() + ": " + r.failure() + ")" ;
            case RATE_LIMITED -> "RATE LIMITED (" + r.failure() + ")" ;
            case UNREACHABLE  -> "UNREACHABLE (" + r.failure() + ")" ;
        } ;
    }

    private static String threadName( ModelState m, int slot ) {

        String id = m.id() ;
        id = id.substring( id.lastIndexOf( '/' ) + 1 ) ;
        return "ai-" + m.gateway.info.name() + "-" + id + "-" + slot ;
    }

    /** The models as the routing policy sees them; under lock. */
    private final class View implements HubView {

        @Override
        public boolean isAvailable( String modelId ) {
            ModelState m = models.get( modelId ) ;
            return m != null && Engine.this.isAvailable(
                    m, System.currentTimeMillis() ) ;
        }

        @Override
        public boolean canSendNow( String modelId ) {
            ModelState m = models.get( modelId ) ;
            return m != null && Engine.this.canSendNow(
                    m, System.currentTimeMillis() ) ;
        }

        @Override
        public List<String> rootModels( String root ) {
            return config.roots().getOrDefault( root, List.of() ) ;
        }
    }
}
