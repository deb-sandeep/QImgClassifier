package com.sandy.sconsole.qimgtools.apps.tagger.prompt;

import com.sandy.sconsole.qimgtools.apps.tagger.model.TagCatalogue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A prompt file: a header of {@code key: value} lines, a {@code ---} line,
 * then the text sent to the model as written. Immutable.
 *
 * @param header the header's keys and values, in file order
 * @param body   the text after the {@code ---} line
 */
public record PromptFile( Map<String,String> header, String body ) {

    /** The line ending the header. */
    public static final String RULE = "---" ;

    public PromptFile {
        header = Collections.unmodifiableMap( new LinkedHashMap<>( header ) ) ;
    }

    /**
     * {@code text} read as a prompt file; one without a {@code ---} line is
     * all body.
     */
    public static PromptFile parse( String text ) {

        Map<String,String> header = new LinkedHashMap<>() ;
        String[]           lines  = text.split( "\\R", -1 ) ;

        for( int i = 0 ; i < lines.length ; i++ ) {
            if( lines[i].strip().equals( RULE ) ) {
                StringBuilder body = new StringBuilder() ;
                for( int j = i + 1 ; j < lines.length ; j++ ) {
                    body.append( lines[j] ) ;
                    if( j < lines.length - 1 ) body.append( '\n' ) ;
                }
                return new PromptFile( header, body.toString().strip() ) ;
            }
            int colon = lines[i].indexOf( ':' ) ;
            if( colon > 0 ) {
                header.put( lines[i].substring( 0, colon ).strip(),
                            lines[i].substring( colon + 1 ).strip() ) ;
            }
        }
        return new PromptFile( Map.of(), text.strip() ) ;
    }

    /** The value of header key {@code key}; "" if absent. */
    public String get( String key ) {
        return header.getOrDefault( key, "" ) ;
    }

    /** The value of {@code key} as a whole number; {@code dflt} if not one. */
    public int getInt( String key, int dflt ) {
        try {
            return Integer.parseInt( get( key ) ) ;
        }
        catch( NumberFormatException e ) {
            return dflt ;
        }
    }

    /** This file with header key {@code key} set to {@code value}. */
    public PromptFile with( String key, Object value ) {

        Map<String,String> h = new LinkedHashMap<>( header ) ;
        h.put( key, value == null ? "" : value.toString() ) ;
        return new PromptFile( h, body ) ;
    }

    /** A hash of the body: it changes whenever the text is edited. */
    public String contentHash() {
        return TagCatalogue.sha256( body ).substring( 0, 16 ) ;
    }

    /** The file's text. */
    public String text() {

        StringBuilder sb = new StringBuilder() ;
        header.forEach( ( k, v ) -> sb.append( k ).append( ": " )
                                      .append( v ).append( '\n' ) ) ;
        return sb.append( RULE ).append( '\n' )
                 .append( body ).append( '\n' ).toString() ;
    }
}
