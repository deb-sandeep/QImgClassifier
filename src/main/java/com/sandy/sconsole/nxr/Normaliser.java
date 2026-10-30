package com.sandy.sconsole.nxr;

import com.fasterxml.jackson.databind.JsonNode;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The text side of the classifier, as the model file's {@code tokeniser}
 * section defines it and the trainer ({@code train.py}: {@code clean},
 * {@code tokens}, {@code terms}) does it: the golden vectors check that the
 * two agree.
 */
final class Normaliser {

    private static final Pattern WHITESPACE =
            Pattern.compile( "\\s+", Pattern.UNICODE_CHARACTER_CLASS ) ;

    private final Pattern token ;
    private final String  numberToken ;
    private final int[]   ngrams ;

    Normaliser( JsonNode tokeniser ) throws NXRException {

        token       = pattern( tokeniser, "token" ) ;
        numberToken = ModelFile.text( tokeniser, "numberToken" ) ;
        ngrams      = ModelFile.ints( tokeniser, "ngrams" ) ;

        for( int n : ngrams ) {
            if( n < 1 ) throw new NXRException( "tokeniser.ngrams: " + n ) ;
        }
    }

    /** The regex at {@code key} of {@code node}. */
    static Pattern pattern( JsonNode node, String key ) throws NXRException {
        try {
            return Pattern.compile( ModelFile.text( node, key ) ) ;
        }
        catch( PatternSyntaxException e ) {
            throw new NXRException( "Bad regex at " + key + ": " +
                                    e.getMessage(), e ) ;
        }
    }

    /**
     * NFKC, lower-case, whitespace runs to one space, trimmed: the text the
     * gate, the cues and the tokens read.
     */
    static String clean( String raw ) {

        String s = Normalizer.normalize( raw, Normalizer.Form.NFKC )
                             .toLowerCase( Locale.ROOT ) ;
        return WHITESPACE.matcher( s ).replaceAll( " " ).strip() ;
    }

    /** The tokens of cleaned {@code text}, a number as the number token. */
    List<String> tokens( String text ) {

        List<String> out = new ArrayList<>() ;
        Matcher      m   = token.matcher( text ) ;

        while( m.find() ) {
            String t = m.group() ;
            out.add( Character.isDigit( t.charAt( 0 ) ) ? numberToken : t ) ;
        }
        return out ;
    }

    /** The n-grams of {@code toks}, for each n of the model, space-joined. */
    List<String> terms( List<String> toks ) {

        List<String> out = new ArrayList<>() ;

        for( int n : ngrams ) {
            for( int i = 0 ; i + n <= toks.size() ; i++ ) {
                out.add( String.join( " ", toks.subList( i, i + n ) ) ) ;
            }
        }
        return out ;
    }

    String numberToken() {
        return numberToken ;
    }
}
