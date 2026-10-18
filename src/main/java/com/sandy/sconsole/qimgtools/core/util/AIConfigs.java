package com.sandy.sconsole.qimgtools.core.util;

import com.sandy.sconsole.ai.config.AIConfig;
import com.sandy.sconsole.ai.config.AIConfigLoader;

/** Loads an app's AI config, honouring the {@code ai.app.config} override. */
public final class AIConfigs {

    /** JVM property naming an app AI config file to use instead. */
    public static final String OVERRIDE = "ai.app.config" ;

    private AIConfigs() {}

    /**
     * The AI config at {@code location}, or at the file the
     * {@value #OVERRIDE} JVM property names.
     *
     * @throws IllegalStateException if a file is missing or invalid
     */
    public static AIConfig load( String location ) {
        return AIConfigLoader.load( System.getProperty( OVERRIDE, location ) ) ;
    }
}
