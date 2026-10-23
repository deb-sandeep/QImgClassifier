package com.sandy.sconsole.qimgtools.apps.tagger.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The kind of a diagnostic tag, given by its prefix: a convention of the tag
 * design (the server knows only tags). Each axis has its own count rule: a
 * question gets every setup tag whose component is present, the one or two
 * dominant concepts, and at most two math techniques. Each pass tags its
 * own axes ({@link TagClass}). A dependent (a question run in the concept
 * pass of another topic it depends on) gets only 0-2 concept tags of that
 * topic: its setup and math belong to its own topic.
 */
@Getter
@RequiredArgsConstructor
public enum Axis {

    SETUP  ( "s.", "setup",   "Setup" ),
    CONCEPT( "c.", "concept", "Concept" ),
    MATH   ( "m.", "math",    "Math" ) ;

    /** The tag text's prefix, e.g. {@code s.} */
    private final String prefix ;

    /** The first segment of a tag's full name, e.g. {@code setup} */
    private final String word ;

    /** The heading of the axis' section in a topic prompt. */
    private final String heading ;

    /** The axis of {@code tagText}; null if it has no axis prefix. */
    public static Axis of( String tagText ) {

        if( tagText == null ) return null ;
        for( Axis a : values() ) {
            if( tagText.startsWith( a.prefix ) ) return a ;
        }
        return null ;
    }

    /** The fewest tags of this axis a question may get. */
    public int min( boolean dependent ) {
        return this == CONCEPT && !dependent ? 1 : 0 ;
    }

    /** The most tags of this axis a question may get. */
    public int max( boolean dependent ) {
        return switch( this ) {
            case SETUP   -> dependent ? 0 : Integer.MAX_VALUE ;
            case CONCEPT -> 2 ;
            case MATH    -> dependent ? 0 : 2 ;
        } ;
    }

    /** "1-2", "0-2", "any number": the count rule, for messages. */
    public String countRule( boolean dependent ) {

        int max = max( dependent ) ;
        return max == Integer.MAX_VALUE ? "any number"
                                        : min( dependent ) + "-" + max ;
    }
}
