package com.sandy.sconsole.nxr;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The Chemistry patterns of spec §4.6, as the trainer ({@code train.py}:
 * {@code chem_pattern}, {@code chem_decide}) has them: the boost of N for a
 * stem with a measured value, and the patterns that label a Chemistry
 * question the model leaves below its threshold. Their regexes are the
 * model file's {@code chemistryPatterns}, and {@link #check} proves the two
 * agree with the model file's {@code chemistryPatternGolden}.
 */
final class ChemistryPatterns {

    static final String NUMERIC_OPTIONS_N = "chemNumericOptionsN" ;
    static final String OPTION_SETS_X     = "chemOptionSetsX" ;
    static final String COMPARE_X         = "chemCompareX" ;
    static final String CHAIN_X           = "chemChainX" ;
    static final String SCENARIO_X        = "chemScenarioX" ;

    private static final List<String> KEYS = List.of(
            "countStem", "measuredOption", "compareStem", "arrow",
            "asksProduct", "setOption", "scenarioStem" ) ;

    private final Normaliser          normaliser ;
    private final Pattern             marker ;
    private final Map<String,Pattern> re = new HashMap<>() ;

    /** Multiplies the odds of N for a stem with a measured value. */
    private final double numericStemOdds ;

    /** A scenario stem is X when p(N) and p(R) are both below this. */
    private final double scenarioMax ;

    ChemistryPatterns( JsonNode model, Normaliser normaliser )
            throws NXRException {

        this.normaliser = normaliser ;
        this.marker     = Normaliser.pattern(
                ModelFile.node( model, "tokeniser" ), "optionMarker" ) ;
        this.numericStemOdds = ModelFile.number( model,
                                                 "chemistryNumericStemOdds" ) ;
        this.scenarioMax     = ModelFile.number( model,
                                                 "chemistryScenarioMax" ) ;

        JsonNode node = ModelFile.node( model, "chemistryPatterns" ) ;
        for( String key : KEYS ) {
            try {
                // Unicode classes, as Python's re has for str patterns
                re.put( key, Pattern.compile( ModelFile.text( node, key ),
                                    Pattern.UNICODE_CHARACTER_CLASS ) ) ;
            }
            catch( PatternSyntaxException e ) {
                throw new NXRException( "Bad regex at chemistryPatterns." +
                                        key + ": " + e.getMessage(), e ) ;
            }
        }
    }

    /**
     * Run each golden case (cleaned text; the pattern the trainer found, or
     * null; whether its stem is numeric and a scenario; whether it asks for
     * a comparison) and fail on the first that differs.
     */
    void check( JsonNode golden ) throws NXRException {

        if( !golden.isArray() || golden.isEmpty() ) {
            throw new NXRException( "The model file has no Chemistry " +
                                    "pattern golden cases" ) ;
        }
        for( int i = 0 ; i < golden.size() ; i++ ) {
            JsonNode g    = golden.get( i ) ;
            JsonNode want = g.get( "rule" ) ;
            String[] got  = match( ModelFile.text( g, "text" ) ) ;
            String   w    = want == null || want.isNull() ? null
                                                          : want.asText() ;
            String   r    = got == null ? null : got[1] ;

            if( w == null ? r != null : !w.equals( r ) ) {
                throw new NXRException( "Chemistry pattern golden case " +
                                        i + " gives " + r + ", the " +
                                        "trainer " + w ) ;
            }

            String stem = stemOptions( ModelFile.text( g, "text" ),
                                       new ArrayList<>() ) ;
            if( find( "measuredOption", stem ) !=
                ModelFile.node( g, "numericStem" ).asBoolean()
                || find( "scenarioStem", stem ) !=
                   ModelFile.node( g, "scenarioStem" ).asBoolean()
                || compare( ModelFile.text( g, "text" ) ) !=
                   ModelFile.node( g, "compare" ).asBoolean() ) {
                throw new NXRException( "Chemistry pattern golden case " +
                                        i + " reads its stem otherwise " +
                                        "than the trainer" ) ;
            }
        }
    }

    /**
     * The first pattern cleaned {@code text} matches, as {label, rule};
     * null if none does.
     */
    String[] match( String text ) {

        List<String> opts = new ArrayList<>() ;
        String       stem = stemOptions( text, opts ) ;
        int          n    = opts.size() ;
        int          num  = 0 ;
        int          meas = 0 ;
        int          sets = 0 ;

        for( String o : opts ) {
            if( numeric( o ) ) {
                num++ ;
                if( find( "measuredOption", o ) ) meas++ ;
            }
            if( find( "setOption", o ) ) sets++ ;
        }

        if( n >= 3 && 4 * num >= 3 * n && !find( "countStem", stem )
            && 2 * meas >= n ) {
            return new String[] { "N", NUMERIC_OPTIONS_N } ;
        }
        if( sets >= 2 ) {
            return new String[] { "X", OPTION_SETS_X } ;
        }
        boolean nonNumeric = n >= 3 && 4 * num <= n ;
        if( nonNumeric && find( "compareStem", stem ) ) {
            return new String[] { "X", COMPARE_X } ;
        }
        if( nonNumeric && count( "arrow", stem ) >= 2
            && find( "asksProduct", stem ) ) {
            return new String[] { "X", CHAIN_X } ;
        }
        return null ;
    }

    /**
     * True if cleaned {@code text} asks for a comparison: three or more
     * options, at most a quarter of them numeric, and a comparing stem. Such
     * a question is X even when the model is sure of R.
     */
    boolean compare( String text ) {

        List<String> opts = new ArrayList<>() ;
        String       stem = stemOptions( text, opts ) ;
        int          num  = 0 ;

        for( String o : opts ) if( numeric( o ) ) num++ ;
        return opts.size() >= 3 && 4 * num <= opts.size()
               && find( "compareStem", stem ) ;
    }

    /**
     * Boost N in the probabilities {@code p} of cleaned {@code text} if its
     * stem shows a measured value: the odds of N, at index {@code n}, are
     * multiplied by {@code chemistryNumericStemOdds} and {@code p}
     * renormalised.
     */
    void boost( String text, double[] p, int n ) {

        String stem = stemOptions( text, new ArrayList<>() ) ;
        if( !find( "measuredOption", stem ) ) return ;

        double sum = 0 ;
        p[n] *= numericStemOdds ;
        for( double v : p ) sum += v ;
        for( int c = 0 ; c < p.length ; c++ ) p[c] /= sum ;
    }

    /**
     * True if cleaned {@code text} is a scenario the model doesn't see as
     * numeric or recall: its stem sets up a situation, and {@code pN} and
     * {@code pR} are both below {@code chemistryScenarioMax}.
     */
    boolean scenario( String text, double pN, double pR ) {
        String stem = stemOptions( text, new ArrayList<>() ) ;
        return pN < scenarioMax && pR < scenarioMax
               && find( "scenarioStem", stem ) ;
    }

    /**
     * The text before the first option marker; the options (the text after
     * each marker, up to the next) go to {@code opts}. With fewer than two
     * markers, the whole text and no options.
     */
    private String stemOptions( String text, List<String> opts ) {

        List<int[]> marks = new ArrayList<>() ;
        Matcher     m     = marker.matcher( text ) ;

        while( m.find() ) marks.add( new int[] { m.start(), m.end() } ) ;
        if( marks.size() < 2 ) return text ;

        for( int i = 0 ; i < marks.size() ; i++ ) {
            int end = i + 1 < marks.size() ? marks.get( i + 1 )[0]
                                           : text.length() ;
            opts.add( text.substring( marks.get( i )[1], end ) ) ;
        }
        return text.substring( 0, marks.get( 0 )[0] ) ;
    }

    /** True if at least half of {@code option}'s tokens are numbers. */
    private boolean numeric( String option ) {

        List<String> ot  = normaliser.tokens( option ) ;
        long         num = ot.stream()
                             .filter( normaliser.numberToken()::equals )
                             .count() ;
        return !ot.isEmpty() && 2 * num >= ot.size() ;
    }

    private boolean find( String key, String text ) {
        return re.get( key ).matcher( text ).find() ;
    }

    private int count( String key, String text ) {

        int     n = 0 ;
        Matcher m = re.get( key ).matcher( text ) ;

        while( m.find() ) n++ ;
        return n ;
    }
}
