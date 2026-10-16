package com.sandy.sconsole.qimgclassifier.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The file that keeps the start times of requests made in the current UTC
 * day (and the last minute), per processor, so the per-day rate limits
 * survive a restart:
 * <pre>
 * { "openrouter" : [ 1792141131000, 1792141145000, ... ] }
 * </pre>
 * Times are epoch milliseconds, oldest first. Older entries are dropped
 * whenever the file is read or written. Not thread-safe; {@link AIInvoker}
 * uses it under its own lock.
 */
@Slf4j
final class RequestHistory {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable( SerializationFeature.INDENT_OUTPUT ) ;

    private final File file ;

    RequestHistory( File file ) {
        this.file = file ;
    }

    /**
     * The requests of the current UTC day, per processor name. Older entries
     * are dropped, and the file is rewritten without them. A missing file
     * reads as empty; an unreadable one is logged and reads as empty, and is
     * replaced at the next save.
     */
    Map<String,List<Long>> load( long now ) {

        Map<String,List<Long>> history = new HashMap<>() ;
        if( !file.exists() ) return history ;

        JsonNode root ;
        try {
            root = MAPPER.readTree( file ) ;
        }
        catch( IOException e ) {
            log.error( "Could not read the request history {} - starting " +
                       "the daily counts afresh", file, e ) ;
            return history ;
        }

        long horizon = RateLimiter.dailyHorizon( now ) ;
        int  dropped = 0 ;

        for( Iterator<Map.Entry<String,JsonNode>> it = root.fields() ;
             it.hasNext() ; ) {
            Map.Entry<String,JsonNode> e = it.next() ;
            List<Long> times = new ArrayList<>() ;

            for( JsonNode t : e.getValue() ) {
                if( t.canConvertToLong() && t.asLong() > horizon ) {
                    times.add( t.asLong() ) ;
                }
                else {
                    dropped++ ;
                }
            }
            times.sort( null ) ;
            history.put( e.getKey(), times ) ;
        }
        if( dropped > 0 ) {
            log.info( "Dropped {} request times before today (UTC) from {}",
                      dropped, file ) ;
            save( history, now ) ;
        }
        return history ;
    }

    /**
     * Replace the file with {@code history}, leaving out times before the
     * current UTC day. A failure is logged, never thrown.
     */
    void save( Map<String,List<Long>> history, long now ) {

        ObjectNode root    = MAPPER.createObjectNode() ;
        long       horizon = RateLimiter.dailyHorizon( now ) ;

        history.forEach( ( name, times ) -> {
            ArrayNode array = root.putArray( name ) ;
            times.stream().filter( t -> t > horizon ).forEach( array::add ) ;
        } ) ;

        File tmp = new File( file.getAbsoluteFile().getParentFile(),
                             file.getName() + ".tmp" ) ;
        try {
            MAPPER.writeValue( tmp, root ) ;
            Files.move( tmp.toPath(), file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE ) ;
        }
        catch( IOException e ) {
            log.error( "Could not save the request history {}", file, e ) ;
        }
    }
}
