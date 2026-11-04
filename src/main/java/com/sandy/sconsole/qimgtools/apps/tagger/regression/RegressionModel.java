package com.sandy.sconsole.qimgtools.apps.tagger.regression;

import com.sandy.sconsole.qimgtools.core.workspace.JsonFiles;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * One topic's {@code regression-model.json} (format version 3, see the tag
 * workspace's {@code file-formats/regression-model.md}), read and checked, with
 * its patterns compiled. Immutable, so scoring threads can share it. Tags are
 * identified by name; the arrays it hands out must not be changed.
 */
public final class RegressionModel {

    public static final String FORMAT         = "sconsole-tag-regression" ;
    public static final int    FORMAT_VERSION = 3 ;

    /**
     * The patterns are written for Python, whose {@code \d}, {@code \w},
     * {@code \s} and {@code \b} follow Unicode; Java's don't unless asked.
     */
    private static final int PATTERN_FLAGS = Pattern.UNICODE_CHARACTER_CLASS ;

    /** What may happen to a tag. */
    public enum Route {

        /** It has a trained model. */
        MODEL( "model" ),

        /** Too few questions to train: only the LLM can apply it. */
        LLM_ONLY( "llm-only" ),

        /** Marked for retirement: never proposed. */
        NEVER( "never" ) ;

        private final String wire ;

        Route( String wire ) {
            this.wire = wire ;
        }

        static Route of( String wire ) {
            return Arrays.stream( values() )
                         .filter( r -> r.wire.equals( wire ) )
                         .findFirst().orElse( null ) ;
        }
    }

    /** How text is turned into terms. */
    public record Preprocessing( Pattern optionMarker, Pattern drop,
                                 int minWordLength, Pattern token,
                                 Set<String> stopWords ) {}

    /**
     * One tag.
     *
     * @param intercept    {@link Route#MODEL} only
     * @param coefficients one per vocabulary term; null unless
     *                     {@link Route#MODEL}
     * @param acceptAt     a score from here accepts it; null if it can't be
     *                     accepted without the LLM
     * @param rejectBelow  a score up to here rejects it; {@link Route#MODEL} only
     * @param trigger      sends a question to the LLM; {@link Route#LLM_ONLY}
     *                     only, and may be null
     */
    public record TagModel( String tagText, Route route, double intercept,
                            double[] coefficients, Double acceptAt,
                            double rejectBelow, Pattern trigger ) {}

    /** A child tag that implies a parent. */
    public record Implication( String child, String parent ) {}

    /** Tags that send a question to the LLM when accepted together. */
    public record AcceptedTogether( Set<String> anyOf, Set<String> withAnyOf,
                                    String why ) {}

    private final int                  topicId ;
    private final String               topicName ;
    private final String               cycle ;
    private final Preprocessing        preprocessing ;
    private final Map<String,Integer>  termIndex ;
    private final double[]             idf ;
    private final List<TagModel>       tags ;
    private final List<Implication>    implications ;
    private final List<AcceptedTogether> acceptedTogether ;

    private RegressionModel( JsonNode root ) throws TopicFileException {

        if( !FORMAT.equals( root.path( "format" ).asText() ) ) {
            throw problem( "its format is not " + FORMAT ) ;
        }
        if( root.path( "formatVersion" ).asInt( -1 ) != FORMAT_VERSION ) {
            throw problem( "format version " + root.path( "formatVersion" ) +
                           ", only " + FORMAT_VERSION + " can be read" ) ;
        }

        topicId          = whole( root, "topicId" ) ;
        topicName        = text( root, "topicName" ) ;
        cycle            = text( root, "cycle" ) ;
        preprocessing    = preprocessing( object( root, "preprocessing" ) ) ;
        termIndex        = termIndex( array( root, "vocabulary" ) ) ;
        idf              = numbers( root, "idf" ) ;
        tags             = tags( array( root, "tags" ) ) ;
        implications     = implications( array( root, "implications" ) ) ;
        acceptedTogether = acceptedTogether( root ) ;

        if( idf.length != termIndex.size() ) {
            throw problem( "idf has " + idf.length + " entries, vocabulary " +
                           termIndex.size() ) ;
        }
        checkNames() ;
    }

    /**
     * The model in {@code json}.
     *
     * @throws TopicFileException if it can't be read, or fails a check
     */
    public static RegressionModel parse( byte[] json )
            throws TopicFileException {

        try {
            return new RegressionModel( JsonFiles.MAPPER.readTree( json ) ) ;
        }
        catch( IOException e ) {
            throw new TopicFileException( TopicFileFinder.MODEL_FILE +
                    " is not valid JSON: " + e.getMessage(), e ) ;
        }
    }

    public int topicId() {
        return topicId ;
    }

    public String topicName() {
        return topicName ;
    }

    public String cycle() {
        return cycle ;
    }

    public Preprocessing preprocessing() {
        return preprocessing ;
    }

    /** {@code term}'s position in the vocabulary; null if it isn't in it. */
    public Integer termIndex( String term ) {
        return termIndex.get( term ) ;
    }

    public double idf( int term ) {
        return idf[term] ;
    }

    /** Every tag, in the file's order. */
    public List<TagModel> tags() {
        return tags ;
    }

    /** The implications, in the file's order, which the decision relies on. */
    public List<Implication> implications() {
        return implications ;
    }

    public List<AcceptedTogether> acceptedTogether() {
        return acceptedTogether ;
    }

    /** The tag named {@code tagText}; null if the model has none. */
    public TagModel tag( String tagText ) {
        return tags.stream().filter( t -> t.tagText().equals( tagText ) )
                   .findFirst().orElse( null ) ;
    }

    private static Preprocessing preprocessing( JsonNode n )
            throws TopicFileException {

        Set<String> stop = new LinkedHashSet<>() ;
        for( JsonNode w : array( n, "stopWords" ) ) stop.add( w.asText() ) ;

        return new Preprocessing( pattern( text( n, "optionMarkerPattern" ) ),
                                  pattern( text( n, "dropPattern" ) ),
                                  whole( n, "minWordLength" ),
                                  pattern( text( n, "tokenPattern" ) ),
                                  Set.copyOf( stop ) ) ;
    }

    private static Map<String,Integer> termIndex( JsonNode vocabulary )
            throws TopicFileException {

        Map<String,Integer> index = new HashMap<>() ;
        for( JsonNode term : vocabulary ) {
            if( index.putIfAbsent( term.asText(), index.size() ) != null ) {
                throw problem( "vocabulary repeats " + term.asText() ) ;
            }
        }
        return Map.copyOf( index ) ;
    }

    private List<TagModel> tags( JsonNode array ) throws TopicFileException {

        List<TagModel> list = new ArrayList<>() ;
        Set<String>    seen = new LinkedHashSet<>() ;

        for( JsonNode n : array ) {
            TagModel t = tag( n ) ;
            if( !seen.add( t.tagText() ) ) {
                throw problem( "tags repeats " + t.tagText() ) ;
            }
            list.add( t ) ;
        }
        return List.copyOf( list ) ;
    }

    private TagModel tag( JsonNode n ) throws TopicFileException {

        String name  = text( n, "tagText" ) ;
        Route  route = Route.of( n.path( "route" ).asText() ) ;

        if( route == null ) {
            throw problem( name + " has route " + n.path( "route" ) ) ;
        }
        return switch( route ) {
            case NEVER    -> new TagModel( name, route, 0, null, null, 0, null ) ;
            case LLM_ONLY -> new TagModel( name, route, 0, null, null, 0,
                                           trigger( n ) ) ;
            case MODEL    -> modelTag( n, name ) ;
        } ;
    }

    private TagModel modelTag( JsonNode n, String name )
            throws TopicFileException {

        double[] coefficients = numbers( n, "coefficients" ) ;
        JsonNode accept       = n.get( "acceptAt" ) ;

        if( coefficients.length != termIndex.size() ) {
            throw problem( name + " has " + coefficients.length +
                           " coefficients, vocabulary " + termIndex.size() ) ;
        }
        if( accept != null && !accept.isNull() && !accept.isNumber() ) {
            throw problem( name + ": acceptAt is not a number" ) ;
        }
        return new TagModel( name, Route.MODEL, number( n, "intercept" ),
                             coefficients,
                             accept == null || accept.isNull()
                             ? null : Double.valueOf( accept.asDouble() ),
                             number( n, "rejectBelow" ), null ) ;
    }

    private static Pattern trigger( JsonNode n ) throws TopicFileException {

        JsonNode p = n.get( "triggerPattern" ) ;
        return p == null || p.isNull() ? null : pattern( p.asText() ) ;
    }

    private static List<Implication> implications( JsonNode array )
            throws TopicFileException {

        List<Implication> list = new ArrayList<>() ;
        for( JsonNode n : array ) {
            list.add( new Implication( text( n, "child" ),
                                       text( n, "parent" ) ) ) ;
        }
        return List.copyOf( list ) ;
    }

    /** Optional in the file: no field means no entries. */
    private static List<AcceptedTogether> acceptedTogether( JsonNode root )
            throws TopicFileException {

        List<AcceptedTogether> list = new ArrayList<>() ;
        for( JsonNode n : root.path( "llmIfAcceptedTogether" ) ) {
            list.add( new AcceptedTogether( names( array( n, "anyOf" ) ),
                                            names( array( n, "withAnyOf" ) ),
                                            n.path( "why" ).asText( "" ) ) ) ;
        }
        return List.copyOf( list ) ;
    }

    private static Set<String> names( JsonNode array ) {

        Set<String> names = new LinkedHashSet<>() ;
        array.forEach( n -> names.add( n.asText() ) ) ;
        return Set.copyOf( names ) ;
    }

    /** Every tag the rules name must be one of the model's. */
    private void checkNames() throws TopicFileException {

        for( Implication r : implications ) {
            checkName( r.child() ) ;
            checkName( r.parent() ) ;
        }
        for( AcceptedTogether r : acceptedTogether ) {
            for( String name : r.anyOf() ) checkName( name ) ;
            for( String name : r.withAnyOf() ) checkName( name ) ;
        }
    }

    private void checkName( String name ) throws TopicFileException {
        if( tag( name ) == null ) {
            throw problem( "a rule names " + name + ", not one of its tags" ) ;
        }
    }

    private static Pattern pattern( String regex ) throws TopicFileException {
        try {
            return Pattern.compile( regex, PATTERN_FLAGS ) ;
        }
        catch( PatternSyntaxException e ) {
            throw problem( "the pattern " + regex + " doesn't compile in Java: " +
                           e.getDescription() ) ;
        }
    }

    private static String text( JsonNode n, String key )
            throws TopicFileException {

        JsonNode v = n.get( key ) ;
        if( v == null || !v.isTextual() ) {
            throw problem( key + " is missing or not text" ) ;
        }
        return v.asText() ;
    }

    private static int whole( JsonNode n, String key )
            throws TopicFileException {

        JsonNode v = n.get( key ) ;
        if( v == null || !v.isIntegralNumber() ) {
            throw problem( key + " is missing or not a whole number" ) ;
        }
        return v.asInt() ;
    }

    private static double number( JsonNode n, String key )
            throws TopicFileException {

        JsonNode v = n.get( key ) ;
        if( v == null || !v.isNumber() ) {
            throw problem( key + " is missing or not a number" ) ;
        }
        return v.asDouble() ;
    }

    private static double[] numbers( JsonNode n, String key )
            throws TopicFileException {

        JsonNode a      = array( n, key ) ;
        double[] values = new double[a.size()] ;

        for( int i = 0 ; i < values.length ; i++ ) {
            if( !a.get( i ).isNumber() ) {
                throw problem( key + " has an entry that is not a number" ) ;
            }
            values[i] = a.get( i ).asDouble() ;
        }
        return values ;
    }

    private static JsonNode array( JsonNode n, String key )
            throws TopicFileException {

        JsonNode v = n.get( key ) ;
        if( v == null || !v.isArray() ) {
            throw problem( key + " is missing or not a list" ) ;
        }
        return v ;
    }

    private static JsonNode object( JsonNode n, String key )
            throws TopicFileException {

        JsonNode v = n.get( key ) ;
        if( v == null || !v.isObject() ) {
            throw problem( key + " is missing or not an object" ) ;
        }
        return v ;
    }

    private static TopicFileException problem( String what ) {
        return new TopicFileException( TopicFileFinder.MODEL_FILE + ": " + what ) ;
    }
}
