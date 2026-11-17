package com.sandy.sconsole.ai;

import java.util.List;

/**
 * A model's answer to one request.
 *
 * @param text      the reply text, never blank
 * @param reasoning the model's reasoning before it answered, when the
 *                  gateway returned it; null if none
 * @param usage     the tokens the request used; null if the gateway did not
 *                  say
 * @param tokens    the reply's tokens with their log probabilities and
 *                  alternatives, when the model's {@code logprobs} is set
 *                  and the gateway returned them; null otherwise
 */
public record AIReply( String text, String reasoning, TokenUsage usage,
                       List<TokenLogprob> tokens ) {

    /** A reply without token log probabilities. */
    public AIReply( String text, String reasoning, TokenUsage usage ) {
        this( text, reasoning, usage, null ) ;
    }

    public AIReply {
        tokens = tokens == null ? null : List.copyOf( tokens ) ;
    }
}
