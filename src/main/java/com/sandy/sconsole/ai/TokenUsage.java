package com.sandy.sconsole.ai;

/**
 * Tokens one request used, as the gateway reported them. The optional counts
 * are null when the gateway does not report them.
 *
 * @param inputTokens     prompt tokens, text and images together
 * @param outputTokens    reply tokens, including any reasoning
 * @param reasoningTokens part of {@code outputTokens} spent on reasoning
 * @param cachedTokens    part of {@code inputTokens} read from a prompt cache
 * @param cost            what the request cost in US dollars
 */
public record TokenUsage( int inputTokens, int outputTokens,
                          Integer reasoningTokens, Integer cachedTokens,
                          Double cost ) {

    public int totalTokens() { return inputTokens + outputTokens ; }

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
             + ", " + totalTokens() + " total"
             + ( cost != null ? String.format( ", $%.4f", cost ) : "" ) ;
    }
}
