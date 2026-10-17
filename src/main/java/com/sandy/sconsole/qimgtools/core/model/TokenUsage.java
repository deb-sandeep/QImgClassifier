package com.sandy.sconsole.qimgtools.core.model;

import lombok.Value;

/**
 * Tokens one AI request used, as reported by the backend. The optional
 * counts are null when the backend does not report them.
 */
@Value
public class TokenUsage {

    /** Prompt tokens, text and image together. */
    int inputTokens ;

    /** Reply tokens, including any reasoning. */
    int outputTokens ;

    /** Part of {@link #outputTokens} spent on reasoning; null if unknown. */
    Integer reasoningTokens ;

    /**
     * Part of {@link #inputTokens} read from a prompt cache; null if unknown.
     */
    Integer cachedTokens ;

    /** What the request cost in US dollars; null if unknown. */
    Double cost ;

    public int getTotalTokens() { return inputTokens + outputTokens ; }

    /**
     * One-line summary, e.g. {@code 6313 in (0 cached), 95 out (40
     * reasoning), 6408 total, $0.0000}.
     */
    public String describe() {

        return inputTokens + " in"
             + ( cachedTokens != null ? " (" + cachedTokens + " cached)" : "" )
             + ", " + outputTokens + " out"
             + ( reasoningTokens != null ? " (" + reasoningTokens + " reasoning)"
                                         : "" )
             + ", " + getTotalTokens() + " total"
             + ( cost != null ? String.format( ", $%.4f", cost ) : "" ) ;
    }
}
