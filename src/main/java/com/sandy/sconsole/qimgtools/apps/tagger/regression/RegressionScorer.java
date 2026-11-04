package com.sandy.sconsole.qimgtools.apps.tagger.regression;

import com.sandy.sconsole.qimgtools.apps.tagger.regression.RegressionModel.AcceptedTogether;
import com.sandy.sconsole.qimgtools.apps.tagger.regression.RegressionModel.Implication;
import com.sandy.sconsole.qimgtools.apps.tagger.regression.RegressionModel.Route;
import com.sandy.sconsole.qimgtools.apps.tagger.regression.RegressionModel.TagModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scores a question's OCR text and decides what to do with it: a port of the
 * tag workspace's {@code scripts/score_regression.py}, following its
 * {@code file-formats/regression-model.md}. Stateless over an immutable model,
 * so scoring threads can share it.
 */
public final class RegressionScorer {

    private final RegressionModel model ;

    public RegressionScorer( RegressionModel model ) {
        this.model = model ;
    }

    /** What the regression makes of {@code text}. */
    public Decision decide( String text ) {

        Map<String,Double> scores = scores( text ) ;
        String             low    = Terms.normalised( text ) ;

        List<String> accepted  = new ArrayList<>() ;
        List<String> undecided = new ArrayList<>() ;
        List<String> triggered = new ArrayList<>() ;

        for( TagModel t : model.tags() ) {
            Double p = scores.get( t.tagText() ) ;
            if( p != null ) {
                if( t.acceptAt() != null && p >= t.acceptAt() ) {
                    accepted.add( t.tagText() ) ;
                }
                else if( p > t.rejectBelow() ) {
                    undecided.add( t.tagText() ) ;
                }
            }
            else if( t.trigger() != null && t.trigger().matcher( low ).find() ) {
                triggered.add( t.tagText() ) ;
            }
        }

        // In file order, so that the rules chain as the reference's do.
        for( Implication r : model.implications() ) {
            if( accepted.contains( r.child() )
                && accepted.contains( r.parent() ) ) {
                accepted.remove( r.parent() ) ;
            }
        }

        List<String> why = llmWhy( accepted, undecided, triggered ) ;
        return new Decision( accepted, undecided, triggered,
                             why.isEmpty() ? Decision.Route.APPLY
                                           : Decision.Route.LLM,
                             why, scores ) ;
    }

    /** The score of every tag with a trained model, in file order. */
    public Map<String,Double> scores( String text ) {

        Map<Integer,Double> weights = weights( text ) ;
        Map<String,Double>  scores  = new LinkedHashMap<>() ;

        for( TagModel t : model.tags() ) {
            if( t.route() != Route.MODEL ) continue ;

            double z = 0 ;
            for( Map.Entry<Integer,Double> w : weights.entrySet() ) {
                z += w.getValue() * t.coefficients()[w.getKey()] ;
            }
            scores.put( t.tagText(),
                        1 / ( 1 + Math.exp( -( t.intercept() + z ) ) ) ) ;
        }
        return Collections.unmodifiableMap( scores ) ;
    }

    /** The question's weights by term index, in order of first occurrence. */
    private Map<Integer,Double> weights( String text ) {

        Map<Integer,Integer> counts = new LinkedHashMap<>() ;
        for( String term : Terms.of( text, model.preprocessing() ) ) {
            Integer i = model.termIndex( term ) ;
            if( i != null ) counts.merge( i, 1, Integer::sum ) ;
        }

        Map<Integer,Double> weights = new LinkedHashMap<>() ;
        double              squares = 0 ;

        for( Map.Entry<Integer,Integer> c : counts.entrySet() ) {
            double w = ( 1 + Math.log( c.getValue() ) ) * model.idf( c.getKey() ) ;
            weights.put( c.getKey(), w ) ;
            squares += w * w ;
        }

        double norm = squares == 0 ? 1 : Math.sqrt( squares ) ;
        weights.replaceAll( ( i, w ) -> w / norm ) ;
        return weights ;
    }

    private List<String> llmWhy( List<String> accepted, List<String> undecided,
                                 List<String> triggered ) {

        List<String> why = new ArrayList<>() ;
        if( !undecided.isEmpty() ) {
            why.add( "undecided: " + String.join( ", ", undecided ) ) ;
        }
        if( !triggered.isEmpty() ) {
            why.add( "triggered: " + String.join( ", ", triggered ) ) ;
        }
        if( accepted.isEmpty() ) why.add( "nothing accepted" ) ;
        for( AcceptedTogether r : model.acceptedTogether() ) {
            if( !Collections.disjoint( accepted, r.anyOf() )
                && !Collections.disjoint( accepted, r.withAnyOf() ) ) {
                why.add( "accepted together: " + r.why() ) ;
            }
        }
        return why ;
    }
}
