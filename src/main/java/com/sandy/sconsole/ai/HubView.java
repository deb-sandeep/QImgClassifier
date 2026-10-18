package com.sandy.sconsole.ai;

import java.util.List;

/**
 * The state of the hub's models as a {@link RoutingPolicy} sees it while it
 * picks. Only valid during {@link RoutingPolicy#pick}.
 */
public interface HubView {

    /**
     * True if {@code modelId} is usable and not held back: not paused or
     * suspended after a rate limit, not at a daily limit, its gateway
     * reachable. Being busy or pacing under a per-minute limit doesn't
     * count.
     */
    boolean isAvailable( String modelId ) ;

    /**
     * True if {@code modelId} has a free slot and may send right now.
     */
    boolean canSendNow( String modelId ) ;

    /** The model ids of root {@code root}; empty if there is no such root. */
    List<String> rootModels( String root ) ;

    /** True if some model of {@code root} {@link #isAvailable}. */
    default boolean isRootAvailable( String root ) {
        return rootModels( root ).stream().anyMatch( this::isAvailable ) ;
    }

    /** True if some model of {@code root} {@link #canSendNow}. */
    default boolean canRootSendNow( String root ) {
        return rootModels( root ).stream().anyMatch( this::canSendNow ) ;
    }
}
