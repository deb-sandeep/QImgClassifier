package com.sandy.sconsole.ai.config;

/** The transports a gateway can use, by their {@code type} in the config. */
public enum GatewayType {

    /** OpenRouter's OpenAI-compatible chat completions API. */
    OPENROUTER( "openrouter" ),

    /** An Ollama server's {@code /api/generate} endpoint. */
    OLLAMA( "ollama" ) ;

    private final String key ;

    GatewayType( String key ) {
        this.key = key ;
    }

    /** The name used for it in the config files. */
    public String key() { return key ; }

    /** The type named {@code key} in a config file; null if none is. */
    public static GatewayType ofKey( String key ) {

        for( GatewayType t : values() ) {
            if( t.key.equals( key ) ) return t ;
        }
        return null ;
    }

    @Override
    public String toString() { return key ; }
}
