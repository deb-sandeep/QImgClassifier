package com.sandy.sconsole.qimgtools.apps.transcriber.gate;

import java.util.regex.Pattern;

/**
 * Python's string behaviour where the gate must match the calibration
 * replay ({@code calibration/replay.py}) exactly: whitespace as
 * {@code str.isspace}, {@code str.strip}, {@code str.count} and
 * {@code repr} of a short string.
 */
final class PyText {

    /** Regex flags matching Python 3's defaults for a {@code str} pattern. */
    static final int FLAGS = Pattern.UNICODE_CHARACTER_CLASS
                             | Pattern.UNIX_LINES ;

    private PyText() {}

    /** Python's {@code str.isspace} for one character. */
    static boolean isSpace( char c ) {
        return Character.isWhitespace( c ) || Character.isSpaceChar( c )
               || c == '\u0085' ;
    }

    /** Python's {@code str.strip()}. */
    static String strip( String s ) {

        int start = 0 ;
        int end   = s.length() ;

        while( start < end && isSpace( s.charAt( start ) ) ) start++ ;
        while( end > start && isSpace( s.charAt( end - 1 ) ) ) end-- ;
        return s.substring( start, end ) ;
    }

    /** Python's {@code str.count( sub )}: non-overlapping occurrences. */
    static int count( String s, String sub ) {

        int n = 0 ;
        for( int i = s.indexOf( sub ) ; i >= 0 ;
             i = s.indexOf( sub, i + sub.length() ) ) {
            n++ ;
        }
        return n ;
    }

    /** The number of {@code c} in {@code s} before {@code end}. */
    static int count( String s, char c, int end ) {

        int n = 0 ;
        for( int i = 0 ; i < end && i < s.length() ; i++ ) {
            if( s.charAt( i ) == c ) n++ ;
        }
        return n ;
    }

    /** Python's {@code repr} of a string (quotes, backslash escapes). */
    static String repr( String s ) {

        char quote = s.indexOf( '\'' ) >= 0 && s.indexOf( '"' ) < 0
                     ? '"' : '\'' ;

        StringBuilder sb = new StringBuilder().append( quote ) ;
        for( char c : s.toCharArray() ) {
            switch( c ) {
                case '\\' -> sb.append( "\\\\" ) ;
                case '\n' -> sb.append( "\\n" ) ;
                case '\r' -> sb.append( "\\r" ) ;
                case '\t' -> sb.append( "\\t" ) ;
                default   -> {
                    if( c == quote ) sb.append( '\\' ) ;
                    sb.append( c ) ;
                }
            }
        }
        return sb.append( quote ).toString() ;
    }
}
