package com.sandy.sconsole.qimgtools.core.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** The AI backends a question can be sent to. */
@Getter
@RequiredArgsConstructor
public enum AIAgent {

    /** Gemma served by a local Ollama instance. */
    GEMMA( "gemma", "Gemma" ),

    /** A hosted vision model reached through OpenRouter. */
    OPENROUTER( "openrouter", "OpenRouter" ) ;

    /**
     * Short lower-case label used in logs, file names, failure reasons and
     * the table.
     */
    private final String label ;

    /** Name for messages, e.g. the rate-limit strip. */
    private final String displayName ;

    @Override
    public String toString() { return label ; }
}
