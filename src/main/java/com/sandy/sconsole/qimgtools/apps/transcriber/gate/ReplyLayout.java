package com.sandy.sconsole.qimgtools.apps.transcriber.gate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Where each character of a reply sits: on a {@code diagram} block's
 * description line, on a {@code smiles:} line, inside another fence, or in
 * the text; and the fence problems found on the way (replay.py
 * {@code line_spans}).
 */
final class ReplyLayout {

    /** What a character is part of. */
    enum Kind { TEXT, DESC, SMILES, META }

    static final String FENCE = "```" ;

    private final Kind[]       kinds ;
    private final List<String> problems = new ArrayList<>() ;

    ReplyLayout( String text ) {

        kinds = new Kind[text.length()] ;
        Arrays.fill( kinds, Kind.TEXT ) ;

        boolean      inFence    = false ;
        String       fenceInfo  = null ;
        List<String> fenceLines = new ArrayList<>() ;
        int          pos        = 0 ;

        for( String line : text.split( "\n", -1 ) ) {
            int    end      = pos + line.length() ;
            String stripped = PyText.strip( line ) ;

            if( stripped.startsWith( FENCE ) ) {
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
                    if( fenceInfo.equals( "diagram" )
                        && !hasDescription( fenceLines ) ) {
                        problems.add( "diagram block without description" ) ;
                    }
                    inFence = false ;
                }
            }
            else if( inFence ) {
                fenceLines.add( line ) ;
                Kind k = isSmiles( stripped ) ? Kind.SMILES
                       : fenceInfo.equals( "diagram" ) ? Kind.DESC
                       : Kind.META ;
                for( int i = pos ; i < Math.min( end + 1, kinds.length ) ;
                     i++ ) {
                    kinds[i] = k ;
                }
            }
            pos = end + 1 ;
        }
        if( inFence ) problems.add( "unclosed code fence" ) ;
    }

    /** What the character at {@code offset} is part of. */
    Kind kindAt( int offset ) {
        return offset < kinds.length ? kinds[offset] : Kind.TEXT ;
    }

    /** The fence problems, in the order found. */
    List<String> problems() { return problems ; }

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
