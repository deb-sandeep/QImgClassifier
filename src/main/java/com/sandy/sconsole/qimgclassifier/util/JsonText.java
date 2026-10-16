package com.sandy.sconsole.qimgclassifier.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Pretty-prints the JSON found inside free text, such as a prompt carrying a
 * topic list or a model reply wrapped in a markdown fence. Only objects, and
 * arrays holding objects or arrays, are reformatted, so prose like
 * {@code [1]} is left alone. Everything outside the JSON is kept as is.
 */
@NoArgsConstructor( access = AccessLevel.PRIVATE )
public final class JsonText {

    private static final ObjectMapper MAPPER = new ObjectMapper() ;

    /** Indents array elements as well as object fields, one per line. */
    private static final ObjectWriter WRITER = MAPPER.writer(
            new DefaultPrettyPrinter()
                    .withArrayIndenter( DefaultIndenter.SYSTEM_LINEFEED_INSTANCE ) ) ;

    /**
     * {@code text} with each embedded JSON value indented; null stays null.
     */
    public static String format( String text ) {

        if( text == null ) return null ;

        StringBuilder sb = new StringBuilder( text.length() ) ;
        int           i  = 0 ;

        while( i < text.length() ) {
            char c   = text.charAt( i ) ;
            int  end = ( c == '{' || c == '[' ) ? jsonEnd( text, i ) : -1 ;

            if( end < 0 ) {
                sb.append( c ) ;
                i++ ;
                continue ;
            }
            sb.append( pretty( text.substring( i, end ) ) ) ;
            i = end ;
        }
        return sb.toString() ;
    }

    /**
     * End offset (exclusive) of a JSON container starting at {@code start}
     * that is worth reformatting, else -1.
     */
    private static int jsonEnd( String text, int start ) {

        try( JsonParser p = MAPPER.getFactory()
                                  .createParser( text.substring( start ) ) ) {
            JsonNode node = MAPPER.readTree( p ) ;
            if( !isStructured( node ) ) return -1 ;
            return start + (int)p.currentLocation().getCharOffset() ;
        }
        catch( Exception e ) {
            return -1 ;
        }
    }

    private static boolean isStructured( JsonNode node ) {

        if( node == null ) return false ;
        if( node.isObject() ) return true ;
        if( !node.isArray() ) return false ;
        for( JsonNode child : node ) {
            if( child.isContainerNode() ) return true ;
        }
        return false ;
    }

    private static String pretty( String json ) {

        try {
            return WRITER.writeValueAsString( MAPPER.readTree( json ) ) ;
        }
        catch( Exception e ) {
            return json ;
        }
    }
}
