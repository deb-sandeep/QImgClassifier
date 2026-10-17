package com.sandy.sconsole.qimgtools.core.util;

import java.util.HashMap;
import java.util.Map;

/**
 * The names models are shown by: the display name configured for a model
 * ({@code <target>.display.names}), else its id. Only what is shown changes;
 * requests, transcripts, logs and the server keep the model id.
 */
public final class ModelNames {

    /** No display names: every model is shown by its id. */
    public static final ModelNames NONE = new ModelNames( Map.of() ) ;

    private final Map<String,String> names ;

    private ModelNames( Map<String,String> names ) {
        this.names = Map.copyOf( names ) ;
    }

    /** The display names configured for {@code targets}. */
    public static ModelNames of( QClassifierConfig.Target... targets ) {

        Map<String,String> names = new HashMap<>() ;
        for( QClassifierConfig.Target t : targets ) {
            if( t != null ) names.putAll( t.getDisplayNames() ) ;
        }
        return new ModelNames( names ) ;
    }

    /** {@code model}'s display name, else {@code model} (null stays null). */
    public String of( String model ) {
        return model == null ? null : names.getOrDefault( model, model ) ;
    }
}
