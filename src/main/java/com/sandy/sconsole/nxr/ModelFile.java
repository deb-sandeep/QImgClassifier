package com.sandy.sconsole.nxr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;

/** Reading the model file, each fault an {@link NXRException}. */
final class ModelFile {

    /** The model file version this class reads. */
    static final int VERSION = 1 ;

    private ModelFile() {}

    /** The model file at {@code file}, its version checked. */
    static JsonNode read( Path file ) throws NXRException {

        JsonNode root ;
        try {
            root = new ObjectMapper().readTree( file.toFile() ) ;
        }
        catch( IOException e ) {
            throw new NXRException( "Can't read the model file " + file +
                                    ": " + e.getMessage(), e ) ;
        }
        if( root == null || !root.isObject() ) {
            throw new NXRException( "The model file " + file +
                                    " is not a JSON object" ) ;
        }
        int version = node( root, "version" ).asInt( -1 ) ;
        if( version != VERSION ) {
            throw new NXRException( "The model file " + file + " is of " +
                                    "version " + version + ", not " +
                                    VERSION ) ;
        }
        return root ;
    }

    /** {@code parent}'s child {@code key}, which must be there. */
    static JsonNode node( JsonNode parent, String key ) throws NXRException {
        JsonNode n = parent.get( key ) ;
        if( n == null || n.isNull() ) {
            throw new NXRException( "The model file has no '" + key + "'" ) ;
        }
        return n ;
    }

    static String text( JsonNode parent, String key ) throws NXRException {
        JsonNode n = node( parent, key ) ;
        if( !n.isTextual() ) {
            throw new NXRException( "'" + key + "' is not a string" ) ;
        }
        return n.asText() ;
    }

    static double number( JsonNode parent, String key ) throws NXRException {
        JsonNode n = node( parent, key ) ;
        if( !n.isNumber() ) {
            throw new NXRException( "'" + key + "' is not a number" ) ;
        }
        return n.asDouble() ;
    }

    static int[] ints( JsonNode parent, String key ) throws NXRException {

        double[] d   = doubles( node( parent, key ), key ) ;
        int[]    out = new int[d.length] ;

        for( int i = 0 ; i < d.length ; i++ ) out[i] = (int) d[i] ;
        return out ;
    }

    /** {@code array} as numbers; {@code what} names it in a fault. */
    static double[] doubles( JsonNode array, String what )
            throws NXRException {

        if( !array.isArray() ) {
            throw new NXRException( "'" + what + "' is not an array" ) ;
        }
        double[] out = new double[array.size()] ;
        for( int i = 0 ; i < out.length ; i++ ) {
            if( !array.get( i ).isNumber() ) {
                throw new NXRException( "'" + what + "'[" + i +
                                        "] is not a number" ) ;
            }
            out[i] = array.get( i ).asDouble() ;
        }
        return out ;
    }
}
