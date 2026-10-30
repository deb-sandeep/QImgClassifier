package com.sandy.sconsole.nxr;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * The multinomial logistic regression of spec §4.4: per-class weights over
 * the features and biases, scored as {@code softmax( W · x + b )}, and the
 * confidence thresholds a label must reach.
 */
final class LinearModel {

    private final String[]           classes ;
    private final double[][]         weights ;
    private final double[]           bias ;
    private final Map<String,Double> thresholds = new HashMap<>() ;

    /** Per subject and label ("C:N"), in place of the threshold; optional. */
    private final Map<String,Double> labelThresholds = new HashMap<>() ;
    private final double             unseenTypeMargin ;

    /** @param features the number of features each class has a weight for */
    LinearModel( JsonNode model, int features ) throws NXRException {

        JsonNode cls = ModelFile.node( model, "classes" ) ;
        JsonNode w   = ModelFile.node( model, "weights" ) ;

        classes = new String[cls.size()] ;
        for( int i = 0 ; i < classes.length ; i++ ) {
            classes[i] = cls.get( i ).asText() ;
        }
        if( index( "N" ) < 0 ) {
            throw new NXRException( "'classes' has no N" ) ;
        }
        if( !w.isArray() || w.size() != classes.length ) {
            throw new NXRException( "'weights' has " + w.size() +
                                    " rows for " + classes.length +
                                    " classes" ) ;
        }
        weights = new double[classes.length][] ;
        for( int c = 0 ; c < classes.length ; c++ ) {
            weights[c] = ModelFile.doubles( w.get( c ), "weights" ) ;
            if( weights[c].length != features ) {
                throw new NXRException( "'weights' row " + c + " has " +
                                        weights[c].length + " weights for " +
                                        features + " features" ) ;
            }
        }
        bias = ModelFile.doubles( ModelFile.node( model, "bias" ), "bias" ) ;
        if( bias.length != classes.length ) {
            throw new NXRException( "'bias' has " + bias.length +
                                    " values for " + classes.length +
                                    " classes" ) ;
        }

        var th = ModelFile.node( model, "thresholds" ).fields() ;
        while( th.hasNext() ) {
            var e = th.next() ;
            thresholds.put( e.getKey(), e.getValue().asDouble() ) ;
        }
        if( !thresholds.containsKey( "default" ) ) {
            throw new NXRException( "'thresholds' has no 'default'" ) ;
        }
        unseenTypeMargin = ModelFile.number( model, "unseenTypeMargin" ) ;

        JsonNode lt = model.get( "labelThresholds" ) ;
        if( lt != null && !lt.isNull() ) {
            var subjects = lt.fields() ;
            while( subjects.hasNext() ) {
                var s      = subjects.next() ;
                var labels = s.getValue().fields() ;
                while( labels.hasNext() ) {
                    var l = labels.next() ;
                    if( index( l.getKey() ) < 0 || !l.getValue().isNumber() ) {
                        throw new NXRException( "Bad labelThresholds." +
                                                s.getKey() + "." + l.getKey() ) ;
                    }
                    labelThresholds.put( s.getKey() + ":" + l.getKey(),
                                         l.getValue().asDouble() ) ;
                }
            }
        }
    }

    /** The probability of each class for features {@code x}. */
    double[] probabilities( double[] x ) {

        double[] z   = new double[classes.length] ;
        double   max = Double.NEGATIVE_INFINITY ;
        double   sum = 0 ;

        for( int c = 0 ; c < classes.length ; c++ ) {
            double s = bias[c] ;
            for( int i = 0 ; i < x.length ; i++ ) {
                if( x[i] != 0 ) s += weights[c][i] * x[i] ;
            }
            z[c] = s ;
            max  = Math.max( max, s ) ;
        }
        for( int c = 0 ; c < z.length ; c++ ) {
            z[c] = Math.exp( z[c] - max ) ;
            sum += z[c] ;
        }
        for( int c = 0 ; c < z.length ; c++ ) z[c] /= sum ;
        return z ;
    }

    /** The index of class {@code label}, e.g. "N"; -1 if there is none. */
    int index( String label ) {
        return Arrays.asList( classes ).indexOf( label ) ;
    }

    /** The class of index {@code c}, e.g. "N". */
    String label( int c ) {
        return classes[c] ;
    }

    /**
     * The confidence {@code label} for a question of {@code subject} and
     * {@code type} must reach: the label threshold of the subject if the
     * model file has one; else the threshold of {@code subject:type}, else
     * of the subject, else the default, plus the unseen-type margin for a
     * type the model was not trained on.
     */
    double threshold( String subject, String type, boolean trainedType,
                      String label ) {

        Double lt = labelThresholds.get( subject + ":" + label ) ;
        if( lt != null ) return lt ;

        Double t = thresholds.get( subject + ":" + type ) ;
        if( t == null ) t = thresholds.get( subject ) ;
        if( t == null ) t = thresholds.get( "default" ) ;
        return t + ( trainedType ? 0 : unseenTypeMargin ) ;
    }
}
