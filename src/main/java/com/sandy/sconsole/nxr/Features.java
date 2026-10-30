package com.sandy.sconsole.nxr;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The feature vector of one question (spec §4.4), as the trainer builds it
 * ({@code predict.vector} and {@code train.engineered}): TF-IDF over the
 * word n-grams, L2-normalised; one-hot subject, type and topic; and the
 * engineered features named in the model file's {@code tokeniser}.
 */
final class Features {

    /** A term of the vocabulary: its feature index and idf. */
    private record Term( int index, double idf ) {}

    private final Normaliser          normaliser ;
    private final Map<String,Term>    vocab = new HashMap<>() ;
    private final Map<String,Integer> index = new HashMap<>() ;
    private final Set<String>         trainedTypes = new HashSet<>() ;
    private final List<String>        engineered = new ArrayList<>() ;
    private final Map<String,Pattern> cues = new LinkedHashMap<>() ;
    private final Pattern             marker ;
    private final Pattern             statement ;

    Features( JsonNode model, Normaliser normaliser ) throws NXRException {

        this.normaliser = normaliser ;

        JsonNode names = ModelFile.node( model, "featureNames" ) ;
        for( int i = 0 ; i < names.size() ; i++ ) {
            String name = names.get( i ).asText() ;
            index.put( name, i ) ;
            if( name.startsWith( "type:" ) && !name.equals( "type:other" ) ) {
                trainedTypes.add( name.substring( 5 ) ) ;
            }
        }
        for( String needed : List.of( "type:other", "topic:other" ) ) {
            feature( needed ) ;
        }

        var terms = ModelFile.node( model, "vocab" ).fields() ;
        while( terms.hasNext() ) {
            var      e = terms.next() ;
            double[] v = ModelFile.doubles( e.getValue(), "vocab" ) ;
            if( v.length != 2 || v[0] < 0 || v[0] >= names.size() ) {
                throw new NXRException( "Bad vocab entry '" + e.getKey() +
                                        "'" ) ;
            }
            vocab.put( e.getKey(), new Term( (int) v[0], v[1] ) ) ;
        }

        JsonNode tok = ModelFile.node( model, "tokeniser" ) ;
        marker    = Normaliser.pattern( tok, "optionMarker" ) ;
        statement = Normaliser.pattern( tok, "statement" ) ;

        JsonNode cueNode = ModelFile.node( tok, "cues" ) ;
        var      it      = cueNode.fieldNames() ;
        while( it.hasNext() ) {
            String name = it.next() ;
            cues.put( name, Normaliser.pattern( cueNode, name ) ) ;
        }
        for( JsonNode n : ModelFile.node( tok, "engineered" ) ) {
            String name = n.asText() ;
            if( !isEngineered( name ) ) {
                throw new NXRException( "Unknown engineered feature '" +
                                        name + "'" ) ;
            }
            feature( "e:" + name ) ;
            engineered.add( name ) ;
        }
    }

    /** True if {@code name} is a feature this class can compute. */
    private boolean isEngineered( String name ) {
        return switch( name ) {
            case "optionCount", "numericOptionShare", "statementCount",
                 "logTokens" -> true ;
            default -> cues.containsKey( name ) ;
        } ;
    }

    /** The index of feature {@code name}, which the model must have. */
    private int feature( String name ) throws NXRException {
        Integer i = index.get( name ) ;
        if( i == null ) {
            throw new NXRException( "The model has no feature '" + name +
                                    "'" ) ;
        }
        return i ;
    }

    /** The number of features. */
    int size() {
        return index.size() ;
    }

    /** True if the model was trained on questions of type {@code type}. */
    boolean trained( String type ) {
        return trainedTypes.contains( type ) ;
    }

    /**
     * The features of a question with OCR text {@code raw}.
     *
     * @param subject "P", "C" or "M"
     */
    double[] vector( String raw, String subject, String type,
                     Integer topicId ) throws NXRException {

        double[]     x    = new double[index.size()] ;
        String       text = Normaliser.clean( raw ) ;
        List<String> toks = normaliser.tokens( text ) ;

        words( x, toks ) ;
        x[feature( "subj:" + subject )] = 1 ;
        x[index.getOrDefault( "type:" + type, feature( "type:other" ) )] = 1 ;
        x[index.getOrDefault( "topic:" + topicId,
                              feature( "topic:other" ) )] = 1 ;

        for( String name : engineered ) {
            x[feature( "e:" + name )] = engineered( name, text, toks ) ;
        }
        return x ;
    }

    /** TF (raw counts of vocabulary terms) × idf, L2-normalised, into x. */
    private void words( double[] x, List<String> toks ) {

        Map<Integer,Double> w    = new HashMap<>() ;
        double              norm = 0 ;

        for( String t : normaliser.terms( toks ) ) {
            Term term = vocab.get( t ) ;
            if( term != null ) w.merge( term.index(), term.idf(), Double::sum ) ;
        }
        for( double v : w.values() ) norm += v * v ;
        norm = Math.sqrt( norm ) ;
        for( var e : w.entrySet() ) x[e.getKey()] = e.getValue() / norm ;
    }

    /** Engineered feature {@code name} of cleaned {@code text}. */
    private double engineered( String name, String text, List<String> toks ) {
        return switch( name ) {
            case "optionCount"        -> Math.min( markers( text ).size(),
                                                   4 ) / 4.0 ;
            case "numericOptionShare" -> numericOptionShare( text ) ;
            case "statementCount"     -> Math.min( count( statement, text ),
                                                   4 ) / 4.0 ;
            case "logTokens"          -> Math.log1p( toks.size() ) / 7 ;
            default                   -> cues.get( name ).matcher( text )
                                                .find() ? 1.0 : 0.0 ;
        } ;
    }

    /**
     * The share of the options (the text after each of two or more option
     * markers, up to the next) that are mostly numbers: at least half their
     * tokens.
     */
    private double numericOptionShare( String text ) {

        List<MatchResult> marks = markers( text ) ;
        if( marks.size() < 2 ) return 0.0 ;

        int numeric = 0 ;
        for( int i = 0 ; i < marks.size() ; i++ ) {
            int          end = i + 1 < marks.size() ? marks.get( i + 1 ).start()
                                                    : text.length() ;
            List<String> ot  = normaliser.tokens(
                    text.substring( marks.get( i ).end(), end ) ) ;
            long         num = ot.stream()
                                 .filter( normaliser.numberToken()::equals )
                                 .count() ;

            if( !ot.isEmpty() && 2 * num >= ot.size() ) numeric++ ;
        }
        return (double) numeric / marks.size() ;
    }

    /** The option markers of {@code text}, each a finished match. */
    private List<MatchResult> markers( String text ) {

        List<MatchResult> out = new ArrayList<>() ;
        Matcher           m   = marker.matcher( text ) ;

        while( m.find() ) out.add( m.toMatchResult() ) ;
        return out ;
    }

    private static int count( Pattern p, String text ) {

        int     n = 0 ;
        Matcher m = p.matcher( text ) ;

        while( m.find() ) n++ ;
        return n ;
    }
}
