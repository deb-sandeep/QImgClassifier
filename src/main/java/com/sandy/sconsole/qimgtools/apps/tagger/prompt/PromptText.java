package com.sandy.sconsole.qimgtools.apps.tagger.prompt;

import com.sandy.sconsole.qimgtools.apps.tagger.model.Axis;
import com.sandy.sconsole.qimgtools.apps.tagger.model.OfferedSet;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Edits on a topic prompt's text by its {@code ## } sections. Stateless.
 */
final class PromptText {

    private PromptText() {}

    /**
     * {@code body} as a dependent is asked it: without the math section
     * (math was done in the question's home run) and without the
     * dependency notes (a dependent is asked no dependencies).
     */
    static String forDependent( String body ) {
        return withoutSections( body, h -> OfferedSet.axisOf( h ) == Axis.MATH
                                           || OfferedSet.isDependencies( h ) ) ;
    }

    /** {@code body} without the sections whose heading {@code drop} takes. */
    private static String withoutSections( String body,
                                           Predicate<String> drop ) {

        List<String> out  = new ArrayList<>() ;
        boolean      skip = false ;

        for( String line : body.split( "\\R", -1 ) ) {
            if( line.startsWith( "## " ) ) {
                skip = drop.test( line.substring( 3 ) ) ;
            }
            if( !skip ) out.add( line ) ;
        }
        return String.join( "\n", out ).strip() ;
    }

    /**
     * The text of {@code body}'s section headed {@code ## <name>}, with the
     * blank lines around it dropped and its indentation kept; null if there
     * is no such section.
     */
    static String section( String body, String name ) {

        List<String> out = null ;

        for( String line : body.split( "\\R", -1 ) ) {
            if( line.startsWith( "## " ) ) {
                if( out != null ) break ;
                if( line.substring( 3 ).strip().equalsIgnoreCase( name ) ) {
                    out = new ArrayList<>() ;
                }
            }
            else if( out != null ) {
                out.add( line ) ;
            }
        }
        if( out == null ) return null ;
        return String.join( "\n", out ).replaceFirst( "^\\s*\\n", "" )
                     .stripTrailing() ;
    }

    /**
     * {@code body} with {@code line} added at the end of the section whose
     * heading starts with {@code section}; at the end of the text if there
     * is no such section.
     */
    static String appendToSection( String body, String section,
                                   String line ) {

        List<String> lines = new ArrayList<>( List.of(
                body.split( "\\R", -1 ) ) ) ;
        int start = -1 ;

        for( int i = 0 ; i < lines.size() ; i++ ) {
            if( lines.get( i ).startsWith( "## " )
                && lines.get( i ).substring( 3 ).strip().toLowerCase()
                        .startsWith( section.toLowerCase() ) ) {
                start = i ;
                break ;
            }
        }
        if( start < 0 ) return body.strip() + "\n" + line ;

        int end = lines.size() ;
        for( int i = start + 1 ; i < lines.size() ; i++ ) {
            if( lines.get( i ).startsWith( "## " ) ) {
                end = i ;
                break ;
            }
        }
        // after the section's last non-blank line
        int at = end ;
        while( at > start + 1 && lines.get( at - 1 ).isBlank() ) at-- ;
        lines.add( at, line ) ;
        return String.join( "\n", lines ).strip() ;
    }
}
