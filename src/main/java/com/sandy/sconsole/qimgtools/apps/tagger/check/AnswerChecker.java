package com.sandy.sconsole.qimgtools.apps.tagger.check;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Checks a model's answer against a question's key with the server's
 * evaluation rules ({@code SCAEvaluator}, {@code MCAEvaluator},
 * {@code NVTEvaluator}): SCA, ART and LCT by case-insensitive equality; MCA
 * by exact equality of the option sets (stricter than the exam's partial
 * credit); NVT within {@value #NVT_TOLERANCE}, or rounding to the key at
 * its decimal places; IVT exactly. Option labels
 * are compared as printed (letters or numbers), never mapped. Matrix match
 * (MMT) and keys that don't fit their type are not checked: such questions
 * are never sent. Stateless.
 */
public final class AnswerChecker {

    /** The most an NVT answer may be off the key, as the server allows. */
    public static final String NVT_TOLERANCE = "0.01" ;

    private static final BigDecimal TOLERANCE = new BigDecimal( NVT_TOLERANCE ) ;

    private static final Pattern LABEL   = Pattern.compile( "[A-Za-z0-9]" ) ;
    private static final Pattern LABELS  =
            Pattern.compile( "[A-Za-z0-9](\\s*,\\s*[A-Za-z0-9])*" ) ;
    private static final Pattern NUMBER  =
            Pattern.compile( "[-+]?(\\d+(\\.\\d*)?|\\.\\d+)" ) ;
    private static final Pattern INTEGER = Pattern.compile( "[-+]?\\d+" ) ;

    private AnswerChecker() {}

    /**
     * Why a question of {@code type} with key {@code key} can't be checked;
     * null if it can.
     */
    public static String unsupported( String type, String key ) {

        if( type == null ) return "the question has no type" ;
        if( key == null || key.isBlank() ) return "the question has no key" ;

        String  k  = key.trim() ;
        boolean ok = switch( type ) {
            case "SCA", "ART", "LCT" -> LABEL.matcher( k ).matches() ;
            case "MCA"               -> LABELS.matcher( k ).matches() ;
            case "NVT"               -> NUMBER.matcher( k ).matches() ;
            case "IVT"               -> INTEGER.matcher( k ).matches() ;
            default                  -> false ;
        } ;
        if( ok ) return null ;
        return switch( type ) {
            case "MMT" -> "matrix-match questions are not checked" ;
            case "SCA", "ART", "LCT", "MCA", "NVT", "IVT" ->
                    "the key '" + k + "' can't be read as a " + type +
                    " answer" ;
            default -> "type " + type + " is not checked" ;
        } ;
    }

    /**
     * True if {@code answer} is right for a question of {@code type} with
     * key {@code key}; false for no answer, or one that can't be read.
     */
    public static boolean matches( String type, String key, String answer ) {

        if( answer == null || key == null
            || unsupported( type, key ) != null ) return false ;
        return switch( type ) {
            case "SCA", "ART", "LCT" -> label( key ).equals( label( answer ) ) ;
            case "MCA" -> labels( key ).equals( labels( answer ) ) ;
            case "NVT" -> within( key, answer ) ;
            case "IVT" -> sameNumber( key, answer ) ;
            default    -> false ;
        } ;
    }

    /**
     * True if two answers to a question of {@code type} are the same answer
     * (by the rules {@link #matches} uses for the key).
     */
    public static boolean same( String type, String a, String b ) {
        return a != null && b != null && matches( type, a, b ) ;
    }

    /** An option label as compared: upper case, brackets dropped. */
    private static String label( String s ) {
        return s.trim().replaceAll( "[()\\[\\]\\s.]", "" )
                .toUpperCase( Locale.ROOT ) ;
    }

    /** A set of option labels, however separated. */
    private static Set<String> labels( String s ) {

        Set<String> set = new TreeSet<>() ;
        Arrays.stream( s.split( "[,;\\s]+" ) )
              .map( AnswerChecker::label )
              .filter( l -> !l.isEmpty() )
              .forEach( set::add ) ;
        return set ;
    }

    /**
     * True if {@code answer} is within the tolerance of {@code key}, or
     * rounds to it at the key's own decimal places: the prompt asks for two
     * places unless the question says otherwise, and a key given to one
     * ({@code 3.3}) would otherwise miss a right answer ({@code 3.33}).
     */
    private static boolean within( String key, String answer ) {

        BigDecimal k = number( key ) ;
        BigDecimal a = number( answer ) ;

        if( k == null || a == null ) return false ;
        if( k.subtract( a ).abs().compareTo( TOLERANCE ) <= 0 ) return true ;
        return a.setScale( Math.max( k.scale(), 0 ), RoundingMode.HALF_UP )
                .compareTo( k ) == 0 ;
    }

    private static boolean sameNumber( String key, String answer ) {

        BigDecimal k = number( key ) ;
        BigDecimal a = number( answer ) ;
        return k != null && a != null && k.compareTo( a ) == 0 ;
    }

    /** {@code s} as a number; null if it isn't one. */
    private static BigDecimal number( String s ) {

        String t = s.trim() ;
        if( !NUMBER.matcher( t ).matches() ) return null ;
        try {
            return new BigDecimal( t ) ;
        }
        catch( NumberFormatException e ) {
            return null ;
        }
    }
}
