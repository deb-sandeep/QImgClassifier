package com.sandy.sconsole.qimgtools.apps.tagger.regression;

import com.sandy.sconsole.qimgtools.apps.tagger.regression.RegressionModel.Part;
import com.sandy.sconsole.qimgtools.apps.tagger.regression.RegressionModel.PartUse;
import com.sandy.sconsole.qimgtools.apps.tagger.regression.RegressionModel.Preprocessing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a question's transcription into a model's terms: a port of
 * {@code parts}, {@code words} and {@code tokens} in the tag workspace's
 * {@code scripts/score_regression.py} (format 4). Stateless.
 */
final class Terms {

    /** The characters Python's {@code str.split()} splits on. */
    private static final Pattern WHITESPACE = Pattern.compile(
            "[\\t\\n\\x0B\\f\\r\\x1C-\\x1F \\x85\\xA0\\u1680\\u2000-\\u200A" +
            "\\u2028\\u2029\\u202F\\u205F\\u3000]+" ) ;

    /** The stem, which every part's words follow. */
    private static final String STEM = "stem" ;

    private Terms() {}

    /** Every token of {@code content}, then every adjacent pair of them. */
    static List<String> of( String content, Preprocessing spec ) {

        Map<String,String> parts = parts( content, spec ) ;
        List<String>       words = new ArrayList<>( words( parts.get( STEM ),
                                                           spec ) ) ;

        for( Part p : spec.partOrder() ) {
            PartUse      how = spec.partUse().get( p ) ;
            List<String> pw  = words( parts.get( p.wire() ), spec ) ;

            if( how == PartUse.PLAIN || how == PartUse.BOTH ) words.addAll( pw ) ;
            if( how == PartUse.PREFIXED || how == PartUse.BOTH ) {
                String prefix = spec.partPrefix().get( p ) ;
                for( String w : pw ) words.add( prefix + w ) ;
            }
        }

        List<String> tokens = tokens( String.join( " ", words ), spec ) ;
        List<String> terms  = new ArrayList<>( tokens ) ;

        for( int i = 1 ; i < tokens.size() ; i++ ) {
            terms.add( tokens.get( i - 1 ) + " " + tokens.get( i ) ) ;
        }
        return terms ;
    }

    /** {@code text} in lower case with its whitespace reduced to spaces. */
    static String normalised( String text ) {
        return String.join( " ", split( text.toLowerCase( Locale.ROOT ) ) ) ;
    }

    /**
     * The text of the stem and of each part, by name: the passage zone is
     * what comes before the first question marker, the body what follows it
     * (all of it without a marker).
     */
    private static Map<String,String> parts( String content,
                                             Preprocessing spec ) {

        String marker  = spec.questionMarker() ;
        int    at      = content.indexOf( marker ) ;
        String passage = at < 0 ? "" : content.substring( 0, at ) ;
        String body    = at < 0 ? content
                                : content.substring( at + marker.length() ) ;

        Map<String,List<String>> lines = new HashMap<>() ;
        lines.put( STEM, new ArrayList<>() ) ;
        for( Part p : Part.values() ) lines.put( p.wire(), new ArrayList<>() ) ;

        sort( passage, true, spec, lines ) ;
        sort( body, false, spec, lines ) ;

        Map<String,String> parts = new HashMap<>() ;
        lines.forEach( ( k, v ) -> parts.put( k, String.join( "\n", v ) ) ) ;
        return parts ;
    }

    /** Puts each line of {@code zone} in the part it belongs to. */
    private static void sort( String zone, boolean isPassage,
                              Preprocessing spec,
                              Map<String,List<String>> lines ) {

        for( String line : zone.split( "\n", -1 ) ) {
            if( spec.figureLine().matcher( line ).lookingAt() ) {
                lines.get( Part.FIGURE.wire() ).add(
                        spec.figureStrip().matcher( line ).replaceFirst( "" ) ) ;
            }
            else if( isPassage ) {
                lines.get( Part.PASSAGE.wire() ).add( line ) ;
            }
            else if( spec.optionLine().matcher( line ).lookingAt() ) {
                lines.get( Part.OPTIONS.wire() ).add( line ) ;
            }
            else {
                lines.get( STEM ).add( line ) ;
            }
        }
    }

    /** The words of one part's text, as the reference's {@code words}. */
    private static List<String> words( String text, Preprocessing spec ) {

        String t = text ;
        for( String s : spec.blankOut() ) t = t.replace( s, " " ) ;

        t = spec.layoutCommand().matcher( t ).replaceAll( " " ) ;
        t = spec.command().matcher( t ).replaceAll( " $1 " )
                .toLowerCase( Locale.ROOT ) ;
        t = spec.optionMarker().matcher( t ).replaceAll( " " ) ;
        t = spec.drop().matcher( t ).replaceAll( " " ) ;

        List<String> kept = new ArrayList<>() ;
        for( String w : split( t ) ) {
            if( w.codePointCount( 0, w.length() ) >= spec.minWordLength() ) {
                kept.add( w ) ;
            }
        }
        return kept ;
    }

    private static List<String> tokens( String text, Preprocessing spec ) {

        List<String> tokens = new ArrayList<>() ;
        Matcher      m      = spec.token().matcher( text ) ;

        while( m.find() ) {
            if( !spec.stopWords().contains( m.group() ) ) tokens.add( m.group() ) ;
        }
        return tokens ;
    }

    /** Python's {@code str.split()}: on runs of whitespace, no empties. */
    private static List<String> split( String text ) {

        List<String> words = new ArrayList<>() ;
        for( String w : WHITESPACE.split( text ) ) {
            if( !w.isEmpty() ) words.add( w ) ;
        }
        return words ;
    }
}
