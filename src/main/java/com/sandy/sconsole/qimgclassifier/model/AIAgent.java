package com.sandy.sconsole.qimgclassifier.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** The AI backends a question can be sent to. */
@Getter
@RequiredArgsConstructor
public enum AIAgent {

    /** Gemma served by a local Ollama instance. */
    GEMMA( "gemma" ),

    /** A hosted vision model reached through OpenRouter. */
    OPENROUTER( "openrouter" ) ;

    /** Short lower-case label used in logs, failure reasons and the UI. */
    private final String label ;

    @Override
    public String toString() { return label ; }
}
