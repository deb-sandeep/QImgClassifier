package com.sandy.sconsole.qimgtools.apps.transcriber.gate;

import org.openscience.cdk.exception.InvalidSmilesException;
import org.openscience.cdk.silent.SilentChemObjectBuilder;
import org.openscience.cdk.smiles.SmilesParser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The well-formed checks of a transcription (logprob-confidence.md §4.1):
 * a reply failing any of them is not accepted. {@link #problems} is the
 * calibration replay's {@code well_formed}, problem texts included;
 * {@link #appProblems} adds the app's own: the figure checks (figures are
 * blockquotes now, not {@code diagram} fences) and the SMILES parse, which
 * the replay left to the app.
 */
public final class WellFormed {

    private static final Pattern SOLUTION = Pattern.compile(
            "^\\s*(?:[-*]\\s*)?(Answer|Solution|Hence|∴)",
            PyText.FLAGS | Pattern.MULTILINE ) ;
    private static final Pattern META = Pattern.compile(
            "here is the transcription|transcription of the|i cannot|" +
            "i can't",
            PyText.FLAGS | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE ) ;
    private static final Pattern MATHS = Pattern.compile(
            "\\$\\$(.+?)\\$\\$|\\$(.+?)\\$", PyText.FLAGS | Pattern.DOTALL ) ;

    private WellFormed() {}

    /**
     * The problems of {@code text}, a reply that ended with
     * {@code doneReason} (Ollama's {@code done_reason}; null if not known);
     * empty if it is well formed.
     */
    public static List<String> problems( String text, String doneReason ) {

        List<String> problems = new ArrayList<>() ;
        String       outside  = ReplyLayout.outsideFences( text ) ;

        if( "length".equals( doneReason ) ) problems.add( "cut off (length)" ) ;
        if( PyText.strip( text ).isEmpty() ) problems.add( "empty" ) ;

        problems.addAll( new ReplyLayout( text ).problems() ) ;
        problems.addAll( mathsProblems( outside ) ) ;

        if( text.contains( "![" ) ) problems.add( "image placeholder" ) ;
        if( META.matcher( outside ).find() ) problems.add( "meta text" ) ;
        if( SOLUTION.matcher( outside ).find() ) {
            problems.add( "solution text" ) ;
        }
        return problems ;
    }

    /**
     * The app's own problems of {@code text}, beyond {@link #problems}:
     * its figures' and its SMILES'.
     */
    public static List<String> appProblems( String text ) {

        List<String> problems = new ArrayList<>(
                new ReplyLayout( text ).figureProblems() ) ;

        problems.addAll( smilesProblems( text ) ) ;
        return problems ;
    }

    /** The {@code smiles:} lines of {@code text} that CDK can't parse. */
    private static List<String> smilesProblems( String text ) {

        List<String> problems = new ArrayList<>() ;
        SmilesParser parser   = null ;

        for( String smiles : smilesLines( text ) ) {
            if( parser == null ) {
                parser = new SmilesParser(
                        SilentChemObjectBuilder.getInstance() ) ;
            }
            try {
                parser.parseSmiles( smiles ) ;
            }
            catch( InvalidSmilesException | RuntimeException e ) {
                problems.add( "SMILES doesn't parse: " + smiles ) ;
            }
        }
        return problems ;
    }

    /**
     * The SMILES of the {@code smiles:} lines of {@code text}, in a
     * figure's blockquote or a {@code diagram} fence.
     */
    public static List<String> smilesLines( String text ) {

        List<String> smiles = new ArrayList<>() ;
        for( String line : text.split( "\n", -1 ) ) {
            String s = ReplyLayout.unquote( PyText.strip( line ) ) ;
            if( ReplyLayout.isSmiles( s ) ) {
                smiles.add( PyText.strip( s.substring( s.indexOf( ':' ) + 1 ) ) ) ;
            }
        }
        return smiles ;
    }

    /** Unbalanced {@code $}, {@code $$} or braces inside maths. */
    private static List<String> mathsProblems( String outside ) {

        List<String> problems = new ArrayList<>() ;
        String       body     = outside.replace( "\\$", "" ) ;

        if( PyText.count( body, "$$" ) % 2 != 0 ) {
            problems.add( "unbalanced $$" ) ;
        }
        if( PyText.count( body.replace( "$$", "" ), "$" ) % 2 != 0 ) {
            problems.add( "unbalanced $" ) ;
        }

        Matcher m = MATHS.matcher( body ) ;
        while( m.find() ) {
            String seg = m.group( 1 ) != null ? m.group( 1 ) : m.group( 2 ) ;
            if( !bracesBalance( seg.replace( "\\{", "" )
                                   .replace( "\\}", "" ) ) ) {
                problems.add( "unbalanced {} in maths" ) ;
                break ;
            }
        }
        return problems ;
    }

    private static boolean bracesBalance( String s ) {

        int depth = 0 ;
        for( char c : s.toCharArray() ) {
            if( c == '{' ) depth++ ;
            if( c == '}' ) depth-- ;
            if( depth < 0 ) break ;
        }
        return depth == 0 ;
    }
}
