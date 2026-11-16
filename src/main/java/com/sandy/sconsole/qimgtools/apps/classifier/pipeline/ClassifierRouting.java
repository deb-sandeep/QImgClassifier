package com.sandy.sconsole.qimgtools.apps.classifier.pipeline;

import java.util.List;
import java.util.function.Predicate;

import com.sandy.sconsole.ai.Candidate;
import com.sandy.sconsole.ai.HubView;
import com.sandy.sconsole.ai.ModelSlot;
import com.sandy.sconsole.ai.RoutingPolicy;

/**
 * The classifier's routing: {@link RoutingPolicy#inOrder()}, except that a
 * question whose roots go beyond its own (Physics and Maths may also run on
 * {@link QuestionClassifier#CHEM}) is <em>borrowed</em> by a model of a later
 * root only in that root's spare time: while no question of the root is
 * unsettled in the loaded sources, or while every model of the question's
 * own root (its first) is held back. A slot takes its own root's questions
 * (and any continuation: retries, fallbacks, stand-ins) before a borrowed
 * one.
 */
public final class ClassifierRouting implements RoutingPolicy {

    private final Predicate<String> rootPending ;

    /**
     * @param rootPending true while a root has questions unsettled in the
     *        loaded sources; called under the hub's lock, so it must be
     *        quick and take no lock the hub's callers may hold
     */
    public ClassifierRouting( Predicate<String> rootPending ) {
        this.rootPending = rootPending ;
    }

    @Override
    public Candidate pick( ModelSlot slot, List<Candidate> eligible,
                           HubView view ) {

        Candidate own      = null ;
        Candidate borrowed = null ;

        for( Candidate c : eligible ) {
            String lender = lender( c, slot, view ) ;
            if( lender == null ) {
                if( own == null || c.order() < own.order() ) own = c ;
            }
            else if( mayBorrow( c, lender, view ) &&
                     ( borrowed == null || c.order() < borrowed.order() ) ) {
                borrowed = c ;
            }
        }
        return own != null ? own : borrowed ;
    }

    /**
     * The root through which {@code slot}'s model would borrow {@code c}: a
     * later root of a new question whose first root doesn't have the model;
     * null if the question is the model's own.
     */
    private static String lender( Candidate c, ModelSlot slot, HubView view ) {

        List<String> roots = c.job().roots() ;
        String       model = slot.model().id() ;

        if( c.via() != Candidate.Via.ROOT || roots.size() < 2 ) return null ;
        if( view.rootModels( roots.get( 0 ) ).contains( model ) ) return null ;

        for( String root : roots.subList( 1, roots.size() ) ) {
            if( view.rootModels( root ).contains( model ) ) return root ;
        }
        return null ;
    }

    private boolean mayBorrow( Candidate c, String lender, HubView view ) {
        return !rootPending.test( lender ) ||
               !view.isRootAvailable( c.job().roots().get( 0 ) ) ;
    }
}
