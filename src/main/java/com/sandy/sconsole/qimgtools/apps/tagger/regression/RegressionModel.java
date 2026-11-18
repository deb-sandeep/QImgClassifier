package com.sandy.sconsole.qimgtools.apps.tagger.regression;

import com.sandy.sconsole.qimgtools.core.workspace.JsonFiles;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * One topic's {@code regression-model.json} (format version 4, see the tag
 * workspace's {@code file-formats/regression-model.md}), read and checked, with
 * its patterns compiled. Version 4 reads the question's transcription; a
 * version 3 model was trained on tesseract text and is refused. Immutable,
 * so scoring threads can share it. Tags are identified by name; the arrays it
 * hands out must not be changed.
 */
public final class RegressionModel {

    public static final String FORMAT         = "sconsole-tag-regression" ;
    public static final int    FORMAT_VERSION = 4 ;

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

    /** A part of the transcription besides its stem. */
    public enum Part {

        FIGURE( "figure" ), OPTIONS( "options" ), PASSAGE( "passage" ) ;

        private final String wire ;

        Part( String wire ) {
            this.wire = wire ;
        }

        public String wire() {
            return wire ;
        }

        static Part of( String wire ) {
            return Arrays.stream( values() )
                         .filter( p -> p.wire.equals( wire ) )
                         .findFirst().orElse( null ) ;
        }
    }

    /** How a part's words join the stem's. */
    public enum PartUse {

        /** Its words, as they are. */
        PLAIN( "plain" ),

        /** Its words, each with the part's prefix. */
        PREFIXED( "prefixed" ),

        /** Its plain words, then its prefixed ones. */
        BOTH( "both" ),

        /** None of its words. */
        DROP( "drop" ) ;

        private final String wire ;

        PartUse( String wire ) {
            this.wire = wire ;
        }

        static PartUse of( String wire ) {
            return Arrays.stream( values() )
                         .filter( u -> u.wire.equals( wire ) )
                         .findFirst().orElse( null ) ;
        }
    }

    /**
     * How a transcription is turned into terms: the {@code preprocessing} of
     * the file, its patterns compiled.
     *
     * @param questionMarker before its first occurrence is the passage zone
     * @param figureLine     a line it matches at its start is figure text
     * @param figureStrip    its first match is taken off a figure line
     * @param optionLine     a body line it matches at its start is options
     * @param partOrder      the parts appended after the stem, in order
     * @param partUse        how each part of {@code partOrder} is appended
     * @param partPrefix     the prefix of each part that is prefixed
     * @param blankOut       strings replaced with a space first
     * @param layoutCommand  LaTeX layout commands, replaced with a space
     * @param command        any other command: its group 1 becomes a word
     */
    public record Preprocessing( String questionMarker, Pattern figureLine,
                                 Pattern figureStrip, Pattern optionLine,
                                 List<Part> partOrder,
                                 Map<Part,PartUse> partUse,
                                 Map<Part,String> partPrefix,
                                 List<String> blankOut, Pattern layoutCommand,
                                 Pattern command, Pattern optionMarker,
                                 Pattern drop, int minWordLength,
                                 Pattern token, Set<String> stopWords ) {}

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
     * @param fallback     it applies only when no other tag fits: its trigger
     *                     is ignored when any tag is accepted
     */
    public record TagModel( String tagText, Route route, double intercept,
                            double[] coefficients, Double acceptAt,
                            double rejectBelow, Pattern trigger,
                            boolean fallback ) {}

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

        List<Part>        order  = partOrder( array( n, "partOrder" ) ) ;
        Map<Part,PartUse> use    = partUse( object( n, "partUse" ), order ) ;
        Map<Part,String>  prefix = partPrefix( object( n, "partPrefix" ), use ) ;

        String marker = text( n, "questionMarker" ) ;
        if( marker.isEmpty() ) throw problem( "questionMarker is empty" ) ;

        return new Preprocessing( marker,
                                  pattern( text( n, "figureLinePattern" ) ),
                                  pattern( text( n, "figureStripPattern" ) ),
                                  pattern( text( n, "optionLinePattern" ) ),
                                  order, use, prefix,
                                  strings( array( n, "blankOut" ) ),
                                  pattern( text( n, "layoutCommandPattern" ) ),
                                  command( text( n, "commandPattern" ) ),
                                  pattern( text( n, "optionMarkerPattern" ) ),
                                  pattern( text( n, "dropPattern" ) ),
                                  whole( n, "minWordLength" ),
                                  pattern( text( n, "tokenPattern" ) ),
                                  Set.copyOf( strings( array( n,
                                                       "stopWords" ) ) ) ) ;
    }

    /** The parts after the stem, each known and named once. */
    private static List<Part> partOrder( JsonNode array )
            throws TopicFileException {

        List<Part> order = new ArrayList<>() ;
        for( JsonNode w : array ) {
            Part p = Part.of( w.asText() ) ;
            if( p == null ) throw problem( "partOrder names " + w ) ;
            if( order.contains( p ) ) throw problem( "partOrder repeats " + w ) ;
            order.add( p ) ;
        }
        return List.copyOf( order ) ;
    }

    /** How each part of {@code order} is used; every one must be given. */
    private static Map<Part,PartUse> partUse( JsonNode n, List<Part> order )
            throws TopicFileException {

        Map<Part,PartUse> use = new EnumMap<>( Part.class ) ;
        for( Part p : order ) {
            PartUse u = PartUse.of( text( n, p.wire() ) ) ;
            if( u == null ) {
                throw problem( "partUse." + p.wire() + " is " +
                               n.get( p.wire() ) ) ;
            }
            use.put( p, u ) ;
        }
        return Collections.unmodifiableMap( use ) ;
    }

    /** The prefix of every part that is prefixed. */
    private static Map<Part,String> partPrefix( JsonNode n,
                                                Map<Part,PartUse> use )
            throws TopicFileException {

        Map<Part,String> prefix = new EnumMap<>( Part.class ) ;
        for( Map.Entry<Part,PartUse> e : use.entrySet() ) {
            if( e.getValue() == PartUse.PREFIXED
                || e.getValue() == PartUse.BOTH ) {
                prefix.put( e.getKey(), text( n, e.getKey().wire() ) ) ;
            }
        }
        return Collections.unmodifiableMap( prefix ) ;
    }

    /** The command pattern, which must have the group that is kept. */
    private static Pattern command( String regex ) throws TopicFileException {

        Pattern p = pattern( regex ) ;
        if( p.matcher( "" ).groupCount() < 1 ) {
            throw problem( "commandPattern has no group" ) ;
        }
        return p ;
    }

    private static List<String> strings( JsonNode array )
            throws TopicFileException {

        List<String> list = new ArrayList<>() ;
        for( JsonNode w : array ) {
            if( !w.isTextual() ) throw problem( "a list holds " + w ) ;
            list.add( w.asText() ) ;
        }
        return List.copyOf( list ) ;
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
            case NEVER    -> new TagModel( name, route, 0, null, null, 0, null,
                                           fallback( n ) ) ;
            case LLM_ONLY -> new TagModel( name, route, 0, null, null, 0,
                                           trigger( n ), fallback( n ) ) ;
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
                             number( n, "rejectBelow" ), null,
                             fallback( n ) ) ;
    }

    /** Optional: absent or null is false. */
    private static boolean fallback( JsonNode n ) throws TopicFileException {

        JsonNode v = n.get( "fallback" ) ;
        if( v == null || v.isNull() ) return false ;
        if( !v.isBoolean() ) {
            throw problem( text( n, "tagText" ) + ": fallback is not true or " +
                           "false" ) ;
        }
        return v.asBoolean() ;
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
