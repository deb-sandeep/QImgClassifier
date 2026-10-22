package com.sandy.sconsole.ai.config;

import com.sandy.sconsole.ai.FailureKind;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Where a job goes after a model fails it for good: an ordered list of
 * models per failure kind, and a default list for the kinds without one.
 * A later model in a list takes the job only while every earlier one is
 * unavailable.
 *
 * @param guards   models per failure kind
 * @param defaults models for the kinds that have no list of their own
 */
public record FallbackInfo( Map<FailureKind,List<String>> guards,
                            List<String> defaults ) {

    /** No fallback at all. */
    public static final FallbackInfo NONE =
            new FallbackInfo( Map.of(), List.of() ) ;

    public FallbackInfo {
        Map<FailureKind,List<String>> copy = new EnumMap<>( FailureKind.class ) ;
        guards.forEach( ( k, v ) -> copy.put( k, List.copyOf( v ) ) ) ;
        guards   = Collections.unmodifiableMap( copy ) ;
        defaults = List.copyOf( defaults ) ;
    }

    /**
     * The models to try after a failure of {@code kind}, in order: its
     * guard's, else its parent kind's (see {@link FailureKind#parent}),
     * else the default ones.
     */
    public List<String> candidates( FailureKind kind ) {
        FailureKind guard = guarding( kind ) ;
        return guard != null ? guards.get( guard ) : defaults ;
    }

    /** The guard {@link #candidates} uses for {@code kind}. */
    public String guardFor( FailureKind kind ) {
        FailureKind guard = guarding( kind ) ;
        return guard != null ? guard.guard() : "default" ;
    }

    /** The kind whose guard {@code kind} follows; null for the default. */
    private FailureKind guarding( FailureKind kind ) {

        for( FailureKind k = kind ; k != null ; k = k.parent() ) {
            if( guards.containsKey( k ) ) return k ;
        }
        return null ;
    }

    public boolean isEmpty() { return guards.isEmpty() && defaults.isEmpty() ; }

    @Override
    public String toString() {

        if( isEmpty() ) return "none" ;
        String byKind = guards.entrySet().stream()
                              .map( e -> e.getKey().guard() + " " + e.getValue() )
                              .collect( Collectors.joining( ", " ) ) ;
        String dflt   = defaults.isEmpty() ? "" : "default " + defaults ;

        if( byKind.isEmpty() ) return dflt ;
        return dflt.isEmpty() ? byKind : byKind + ", " + dflt ;
    }
}
