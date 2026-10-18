package com.sandy.sconsole.ai.config;

/**
 * A config file that is missing, unreadable or breaks a rule. The message
 * names the file and, where it applies, the JSON path of the offending
 * value, e.g. {@code models."x".fallback.serverError[1]}.
 */
public class AIConfigException extends IllegalStateException {

    public AIConfigException( String message ) {
        super( message ) ;
    }

    public AIConfigException( String message, Throwable cause ) {
        super( message, cause ) ;
    }
}
