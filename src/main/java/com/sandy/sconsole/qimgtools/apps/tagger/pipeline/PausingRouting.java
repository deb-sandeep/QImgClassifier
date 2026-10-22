package com.sandy.sconsole.qimgtools.apps.tagger.pipeline;

import com.sandy.sconsole.ai.Candidate;
import com.sandy.sconsole.ai.HubView;
import com.sandy.sconsole.ai.ModelSlot;
import com.sandy.sconsole.ai.RoutingPolicy;

import java.util.List;

/**
 * The tagger's routing: strictly in order, as the reviewer's, except while
 * paused (a gap proposal waits for the user), when no job is sent at all.
 * Requests in flight finish; queued jobs keep their places. Thread-safe.
 */
public final class PausingRouting implements RoutingPolicy {

    private final RoutingPolicy inOrder = RoutingPolicy.inOrder() ;

    private volatile boolean paused ;

    /** Send nothing new while {@code paused}. */
    public void setPaused( boolean paused ) {
        this.paused = paused ;
    }

    public boolean isPaused() {
        return paused ;
    }

    @Override
    public Candidate pick( ModelSlot slot, List<Candidate> eligible,
                           HubView view ) {
        return paused ? null : inOrder.pick( slot, eligible, view ) ;
    }
}
