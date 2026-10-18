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

    /** The models to try after a failure of {@code kind}, in order. */
    public List<String> candidates( FailureKind kind ) {
        return guards.getOrDefault( kind, defaults ) ;
    }

    /** The guard {@link #candidates} uses for {@code kind}. */
    public String guardFor( FailureKind kind ) {
        return guards.containsKey( kind ) ? kind.guard() : "default" ;
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
