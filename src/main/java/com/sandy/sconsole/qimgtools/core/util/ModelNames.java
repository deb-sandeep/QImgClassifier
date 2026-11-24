package com.sandy.sconsole.qimgtools.core.util;

import com.sandy.sconsole.ai.config.AIConfig;
import com.sandy.sconsole.ai.config.ModelInfo;

import java.util.HashMap;
import java.util.Map;

/**
 * The names models are shown by: the display name the AI config gives a
 * model ({@code displayName} in the global file), else its id. Only what
 * is shown changes; requests, transcripts, logs and the server keep the
 * model id. A model is found by its config name or by the id sent to its
 * gateway ({@code modelId}), which the transcripts and the server record;
 * an id shared by two entries (one model on two gateways) is shown by the
 * display name of the entry named after it, else of the first.
 */
public final class ModelNames {

    /** No display names: every model is shown by its id. */
    public static final ModelNames NONE = new ModelNames( Map.of() ) ;

    private final Map<String,String> names ;

    private ModelNames( Map<String,String> names ) {
        this.names = Map.copyOf( names ) ;
    }

    /** The display names of the models {@code config} uses. */
    public static ModelNames of( AIConfig config ) {

        Map<String,String> names = new HashMap<>() ;
        for( ModelInfo m : config.models().values() ) {
            if( m.displayName() != null ) names.put( m.id(), m.displayName() ) ;
        }
        for( ModelInfo m : config.models().values() ) {
            if( m.displayName() != null ) {
                names.putIfAbsent( m.modelId(), m.displayName() ) ;
            }
        }
        return new ModelNames( names ) ;
    }

    /** {@code model}'s display name, else {@code model} (null stays null). */
    public String of( String model ) {
        return model == null ? null : names.getOrDefault( model, model ) ;
    }
}
