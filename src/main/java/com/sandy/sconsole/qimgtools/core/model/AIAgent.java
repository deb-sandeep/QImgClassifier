package com.sandy.sconsole.qimgtools.core.model;

import com.sandy.sconsole.ai.config.GatewayType;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** The AI backends a question can be sent to. */
@Getter
@RequiredArgsConstructor
public enum AIAgent {

    /**
     * A model served by a local Ollama server. Transcripts and maps written
     * before it was renamed call it {@code gemma}.
     */
    OLLAMA( "ollama", "Ollama" ),

    /** A hosted vision model reached through OpenRouter. */
    OPENROUTER( "openrouter", "OpenRouter" ) ;

    /**
     * Short lower-case label used in logs, file names, failure reasons and
     * the table.
     */
    private final String label ;

    /** Name for messages, e.g. the rate-limit strip. */
    private final String displayName ;

    /** The agent of a gateway of {@code type}. */
    public static AIAgent of( GatewayType type ) {
        return switch( type ) {
            case OLLAMA     -> OLLAMA ;
            case OPENROUTER -> OPENROUTER ;
        } ;
    }

    @Override
    public String toString() { return label ; }
}
