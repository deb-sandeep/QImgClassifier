package com.sandy.sconsole.qimgtools.apps.tagger.regression;

import com.sandy.sconsole.qimgtools.apps.tagger.regression.RegressionModel.Preprocessing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns OCR text into a model's terms: a port of {@code tokens} in the tag
 * workspace's {@code scripts/score_regression.py}. Stateless.
 */
final class Terms {

    /** The characters Python's {@code str.split()} splits on. */
    private static final Pattern WHITESPACE = Pattern.compile(
            "[\\t\\n\\x0B\\f\\r\\x1C-\\x1F \\x85\\xA0\\u1680\\u2000-\\u200A" +
            "\\u2028\\u2029\\u202F\\u205F\\u3000]+" ) ;

    private Terms() {}

    /** Every token of {@code text}, then every adjacent pair of them. */
    static List<String> of( String text, Preprocessing spec ) {

        String t = text.toLowerCase( Locale.ROOT ) ;
        t = spec.optionMarker().matcher( t ).replaceAll( " " ) ;
        t = spec.drop().matcher( t ).replaceAll( " " ) ;

        List<String> tokens = tokens( longWords( t, spec.minWordLength() ),
                                      spec ) ;
        List<String> terms  = new ArrayList<>( tokens ) ;

        for( int i = 1 ; i < tokens.size() ; i++ ) {
            terms.add( tokens.get( i - 1 ) + " " + tokens.get( i ) ) ;
        }
        return terms ;
    }

    /** {@code text} in lower case with its whitespace reduced to spaces. */
    static String normalised( String text ) {
        return String.join( " ", words( text.toLowerCase( Locale.ROOT ) ) ) ;
    }

    private static String longWords( String text, int minLength ) {

        List<String> kept = new ArrayList<>() ;
        for( String w : words( text ) ) {
            if( w.codePointCount( 0, w.length() ) >= minLength ) kept.add( w ) ;
        }
        return String.join( " ", kept ) ;
    }

    private static List<String> tokens( String text, Preprocessing spec ) {

        List<String> tokens = new ArrayList<>() ;
        Matcher      m      = spec.token().matcher( text ) ;

        while( m.find() ) {
            if( !spec.stopWords().contains( m.group() ) ) tokens.add( m.group() ) ;
        }
        return tokens ;
    }

    private static List<String> words( String text ) {

        List<String> words = new ArrayList<>() ;
        for( String w : WHITESPACE.split( text ) ) {
            if( !w.isEmpty() ) words.add( w ) ;
        }
        return words ;
    }
}
