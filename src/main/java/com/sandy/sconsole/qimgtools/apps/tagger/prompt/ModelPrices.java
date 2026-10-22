package com.sandy.sconsole.qimgtools.apps.tagger.prompt;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * The models' list prices per million input tokens, from the
 * {@code costPerMillionToken} entries of the global AI file, which the hub
 * itself never reads (they are documentation there). Used only to estimate
 * what a topic prompt costs.
 */
@Slf4j
public final class ModelPrices {

    private static final String CLASSPATH = "classpath:" ;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable( JsonParser.Feature.ALLOW_COMMENTS ) ;

    private final Map<String,Double> input = new HashMap<>() ;

    /**
     * Read the prices from the global AI file at {@code location}
     * ({@code classpath:/...} or a path); none if it can't be read.
     */
    public ModelPrices( String location ) {

        try( InputStream in = open( location ) ) {
            JsonNode models = MAPPER.readTree( in ).path( "models" ) ;
            models.fields().forEachRemaining( e -> {
                JsonNode p = e.getValue().path( "costPerMillionToken" )
                              .path( "input" ) ;
                if( p.isNumber() ) input.put( e.getKey(), p.asDouble() ) ;
            } ) ;
        }
        catch( IOException | RuntimeException e ) {
            log.warn( "Could not read the model prices from {}: {}",
                      location, e.getMessage() ) ;
        }
    }

    private static InputStream open( String location ) throws IOException {

        if( location.startsWith( CLASSPATH ) ) {
            InputStream in = ModelPrices.class.getResourceAsStream(
                    location.substring( CLASSPATH.length() ) ) ;
            if( in == null ) throw new IOException( "not on the classpath" ) ;
            return in ;
        }
        return new FileInputStream( new File( location ) ) ;
    }

    /** USD per million input tokens of {@code model}; null if unknown. */
    public Double input( String model ) {
        return input.get( model ) ;
    }
}
