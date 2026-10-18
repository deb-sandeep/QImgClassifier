package com.sandy.sconsole.ai.internal;

import com.sandy.sconsole.ai.AIJob;
import com.sandy.sconsole.ai.Attempt;
import com.sandy.sconsole.ai.FailureKind;
import com.sandy.sconsole.ai.JobContext;
import com.sandy.sconsole.ai.JobHandle;
import com.sandy.sconsole.ai.config.ModelInfo;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One job's way through the {@link Engine}. Touched under the engine's lock
 * while waiting, and only by its worker while running.
 */
final class JobImpl<P,R> implements JobContext<P>, JobHandle {

    final Engine     engine ;
    final long       id ;
    final AIJob<P,R> job ;

    /** Its place among the waiting jobs, lower first. */
    long order ;

    /** Epoch ms it last joined the waiting jobs. */
    long queuedAt ;

    State   state = State.WAITING ;
    boolean started ;
    int     attempts ;

    /** Attempts that failed on each model (not those handed back). */
    final Map<String,Integer> tries = new HashMap<>() ;

    /**
     * The model of its last failed or handed-back attempt; null before its
     * first attempt, and again after an escalation.
     */
    String      lastModel ;
    FailureKind lastKind ;
    String      lastFailure = "unknown error" ;

    /** The root it was escalated to; null if it wasn't. */
    String escalationRoot ;

    /** The escalated reply, kept in case the escalation root gives none. */
    R         provisional ;
    ModelInfo provisionalModel ;

    /** Its request in flight; null while it has none. Under the lock. */
    Attempt current ;

    /** Models it may no longer run on (the one that gave the escalation). */
    final Set<String> excluded = new HashSet<>() ;

    JobImpl( Engine engine, long id, AIJob<P,R> job ) {
        this.engine = engine ;
        this.id     = id ;
        this.job    = job ;
    }

    int tries( String model ) { return tries.getOrDefault( model, 0 ) ; }

    // ---- JobContext ----------------------------------------------------

    @Override public long id() { return id ; }

    @Override public P payload() { return job.payload() ; }

    @Override public List<String> roots() { return job.roots() ; }

    @Override public int attempts() { return attempts ; }

    @Override public String escalationRoot() { return escalationRoot ; }

    // ---- JobHandle -----------------------------------------------------

    @Override public State state() { return engine.state( this ) ; }

    @Override public boolean hold() { return engine.hold( this ) ; }

    @Override
    public boolean release( boolean front ) {
        return engine.release( this, front ) ;
    }

    @Override public boolean cancel() { return engine.cancel( this ) ; }

    @Override
    public String toString() { return "job " + id ; }
}
