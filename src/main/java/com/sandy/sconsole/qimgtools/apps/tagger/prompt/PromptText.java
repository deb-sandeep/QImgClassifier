package com.sandy.sconsole.qimgtools.apps.tagger.prompt;

import com.sandy.sconsole.qimgtools.apps.tagger.model.Axis;
import com.sandy.sconsole.qimgtools.apps.tagger.model.OfferedSet;

import java.util.ArrayList;
import java.util.List;

/**
 * Edits on a topic prompt's text by its {@code ## } sections. Stateless.
 */
final class PromptText {

    private PromptText() {}

    /** {@code body} without the section of {@code axis}. */
    static String withoutSection( String body, Axis axis ) {

        List<String> out  = new ArrayList<>() ;
        boolean      skip = false ;

        for( String line : body.split( "\\R", -1 ) ) {
            if( line.startsWith( "## " ) ) {
                skip = OfferedSet.axisOf( line.substring( 3 ) ) == axis ;
            }
            if( !skip ) out.add( line ) ;
        }
        return String.join( "\n", out ).strip() ;
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
