package com.sandy.sconsole.qimgtools.apps.transcriber.gate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Where each character of a reply sits: on a figure's description line, on
 * a {@code smiles:} line, inside another fence, or in the text; and the
 * fence problems found on the way (replay.py {@code line_spans}).
 * <p>
 * A figure is a blockquote: its {@code >} lines (outside fences) are its
 * description, but for a {@code > smiles:} line. A {@code diagram} fence,
 * the form calibration ran with, is read the same way, so the golden cases
 * replay as they were; the app refuses it in new transcriptions
 * ({@link #figureProblems}).
 */
final class ReplyLayout {

    /** What a character is part of. */
    enum Kind { TEXT, DESC, SMILES, META }

    static final String FENCE = "```" ;
    static final String QUOTE = ">" ;

    private final Kind[]       kinds ;
    private final List<String> problems       = new ArrayList<>() ;
    private final List<String> figureProblems = new ArrayList<>() ;

    ReplyLayout( String text ) {

        kinds = new Kind[text.length()] ;
        Arrays.fill( kinds, Kind.TEXT ) ;

        boolean      inFence    = false ;
        String       fenceInfo  = null ;
        List<String> fenceLines = new ArrayList<>() ;
        List<String> quoteLines = null ;
        int          pos        = 0 ;

        for( String line : text.split( "\n", -1 ) ) {
            int    end      = pos + line.length() ;
            String stripped = PyText.strip( line ) ;

            if( quoteLines != null && !stripped.startsWith( QUOTE ) ) {
                if( !hasDescription( quoteLines ) ) {
                    figureProblems.add( "figure without description" ) ;
                }
                if( !stripped.isEmpty() ) {
                    figureProblems.add( "no blank line after a figure" ) ;
                }
                quoteLines = null ;
            }

            if( !inFence && stripped.startsWith( QUOTE ) ) {
                if( quoteLines == null ) quoteLines = new ArrayList<>() ;
                String body = unquote( stripped ) ;
                quoteLines.add( body ) ;
                mark( pos, end, isSmiles( body ) ? Kind.SMILES : Kind.DESC ) ;
            }
            else if( stripped.startsWith( FENCE ) ) {
                if( !inFence ) {
                    inFence    = true ;
                    fenceInfo  = PyText.strip( stripped.substring( 3 ) ) ;
                    fenceLines = new ArrayList<>() ;
                    if( !fenceInfo.equals( "diagram" )
                        && !fenceInfo.equals( "uncertain" ) ) {
                        problems.add( "fence info string " +
                                      PyText.repr( fenceInfo ) ) ;
                    }
                }
                else {
                    if( fenceInfo.equals( "diagram" ) ) {
                        if( !hasDescription( fenceLines ) ) {
                            problems.add( "diagram block without " +
                                          "description" ) ;
                        }
                        figureProblems.add( "a diagram fence: write the " +
                                            "figure as a > blockquote" ) ;
                    }
                    inFence = false ;
                }
            }
            else if( inFence ) {
                fenceLines.add( line ) ;
                mark( pos, end, isSmiles( stripped ) ? Kind.SMILES
                              : fenceInfo.equals( "diagram" ) ? Kind.DESC
                              : Kind.META ) ;
            }
            pos = end + 1 ;
        }
        if( inFence ) problems.add( "unclosed code fence" ) ;
        if( quoteLines != null && !hasDescription( quoteLines ) ) {
            figureProblems.add( "figure without description" ) ;
        }
    }

    /** Mark the line from {@code pos} to {@code end}, its newline too. */
    private void mark( int pos, int end, Kind k ) {
        for( int i = pos ; i < Math.min( end + 1, kinds.length ) ; i++ ) {
            kinds[i] = k ;
        }
    }

    /** What the character at {@code offset} is part of. */
    Kind kindAt( int offset ) {
        return offset < kinds.length ? kinds[offset] : Kind.TEXT ;
    }

    /** The fence problems, in the order found (the replay's). */
    List<String> problems() { return problems ; }

    /**
     * The figure problems, the app's own: a {@code diagram} fence, a
     * figure with no description, or one not followed by a blank line
     * (markdown would take the next line into the figure).
     */
    List<String> figureProblems() { return figureProblems ; }

    /** {@code stripped}, a stripped blockquote line, without its marks. */
    static String unquote( String stripped ) {

        int i = 0 ;
        while( i < stripped.length()
               && ( stripped.charAt( i ) == '>'
                    || Character.isWhitespace( stripped.charAt( i ) ) ) ) {
            i++ ;
        }
        return stripped.substring( i ) ;
    }

    /** True if {@code stripped}, a stripped line, is a SMILES line. */
    static boolean isSmiles( String stripped ) {
        return stripped.toLowerCase().startsWith( "smiles:" ) ;
    }

    /** {@code text} without its fence lines and what is inside fences. */
    static String outsideFences( String text ) {

        List<String> out     = new ArrayList<>() ;
        boolean      inFence = false ;

        for( String line : text.split( "\n", -1 ) ) {
            if( PyText.strip( line ).startsWith( FENCE ) ) {
                inFence = !inFence ;
                continue ;
            }
            if( !inFence ) out.add( line ) ;
        }
        return String.join( "\n", out ) ;
    }

    private static boolean hasDescription( List<String> lines ) {

        for( String l : lines ) {
            String s = PyText.strip( l ) ;
            if( !s.isEmpty() && !isSmiles( s ) ) return true ;
        }
        return false ;
    }
}
