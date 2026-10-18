package com.sandy.sconsole.ai;

import java.util.Comparator;
import java.util.List;

/**
 * The app's say in which job a model takes. Jobs are bound to a model as
 * late as possible: when one of the model's slots is free and the model may
 * send (its own and its gateway's limits allow, nothing holds it back), the
 * hub offers the slot every waiting job that may run on the model, and the
 * policy picks one, or none to leave the slot idle for now. A slow model
 * frees its slots less often, so the other models of a root take more of
 * the work.
 * <p>
 * Called under the hub's lock: be quick, and don't call back into the hub
 * (use {@code view}). An exception is logged and the slot takes the first
 * candidate in order instead.
 */
@FunctionalInterface
public interface RoutingPolicy {

    /**
     * @param eligible the jobs that may run on {@code slot}'s model, never
     *                 empty
     * @return one of {@code eligible}, or null to take none
     */
    Candidate pick( ModelSlot slot, List<Candidate> eligible, HubView view ) ;

    /**
     * Strictly in order: front-submitted jobs first, then the rest by
     * submission; a job keeps its place between attempts.
     */
    static RoutingPolicy inOrder() {
        return ( slot, eligible, view ) -> eligible.stream()
                .min( Comparator.comparingLong( Candidate::order ) )
                .orElse( null ) ;
    }
}
