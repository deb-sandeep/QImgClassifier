package com.sandy.sconsole.ai.config;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An app's AI configuration, loaded and validated by
 * {@link AIConfigLoader}: the gateways of its parent (global) file, the
 * models the app uses with their merged settings, and the app's roots,
 * named pools of entry models. Immutable.
 */
public final class AIConfig {

    private final String                   appLocation ;
    private final String                   parentLocation ;
    private final Map<String,GatewayInfo>  gateways ;
    private final Map<String,ModelInfo>    models ;
    private final Map<String,List<String>> roots ;
    private final File                     usageLedger ;

    AIConfig( String appLocation, String parentLocation,
              Map<String,GatewayInfo> gateways, Map<String,ModelInfo> models,
              Map<String,List<String>> roots, File usageLedger ) {

        this.appLocation    = appLocation ;
        this.parentLocation = parentLocation ;
        this.usageLedger    = usageLedger ;
        this.gateways       = Collections.unmodifiableMap(
                                    new LinkedHashMap<>( gateways ) ) ;
        this.models         = Collections.unmodifiableMap(
                                    new LinkedHashMap<>( models ) ) ;

        Map<String,List<String>> r = new LinkedHashMap<>() ;
        roots.forEach( ( k, v ) -> r.put( k, List.copyOf( v ) ) ) ;
        this.roots = Collections.unmodifiableMap( r ) ;
    }

    /** Where the app file was read from. */
    public String appLocation() { return appLocation ; }

    /** Where its parent (global) file was read from. */
    public String parentLocation() { return parentLocation ; }

    /** Every gateway of the parent file, by name, in file order. */
    public Map<String,GatewayInfo> gateways() { return gateways ; }

    /** The models the app uses, by id, in app file order. */
    public Map<String,ModelInfo> models() { return models ; }

    /** The roots, by name: model ids in app file order. */
    public Map<String,List<String>> roots() { return roots ; }

    /**
     * The file the providers' limits (the parent file's) are counted in
     * across every app that uses it; null if the parent names none, and
     * each app then counts only its own requests against them.
     */
    public File usageLedger() { return usageLedger ; }

    /**
     * The model {@code id}.
     *
     * @throws IllegalArgumentException if the app does not use it
     */
    public ModelInfo model( String id ) {

        ModelInfo m = models.get( id ) ;
        if( m == null ) {
            throw new IllegalArgumentException( "Model '" + id + "' is not " +
                    "in " + appLocation ) ;
        }
        return m ;
    }

    /**
     * The models of root {@code name}.
     *
     * @throws IllegalArgumentException if there is no such root
     */
    public List<ModelInfo> root( String name ) {

        List<String> ids = roots.get( name ) ;
        if( ids == null ) {
            throw new IllegalArgumentException( "No root '" + name + "' in " +
                                                appLocation ) ;
        }
        return ids.stream().map( models::get ).toList() ;
    }

    /** True if root {@code name} has a model that can be sent requests. */
    public boolean isRootUsable( String name ) {
        return roots.containsKey( name )
               && root( name ).stream().anyMatch( ModelInfo::usable ) ;
    }

    /** The name {@code id} is shown by: its display name, else the id. */
    public String displayName( String id ) {
        ModelInfo m = models.get( id ) ;
        return m != null ? m.label() : id ;
    }

    @Override
    public String toString() {
        return appLocation + " (parent " + parentLocation + ")" ;
    }
}
