package com.sandy.sconsole.ai;

import java.util.List;

/**
 * One generated token with its log probability and the most likely
 * alternatives the model weighed at its place.
 *
 * @param token   the token as text
 * @param logprob its natural log probability
 * @param bytes   its UTF-8 bytes as the model gave them; a token can be
 *                part of a multi-byte character, which its text can't show
 * @param top     the alternatives, most likely first (the chosen token may
 *                be among them); empty if none were asked for
 */
public record TokenLogprob( String token, double logprob, byte[] bytes,
                            List<Alternative> top ) {

    /**
     * An alternative token.
     *
     * @param token   the token as text
     * @param logprob its natural log probability
     * @param bytes   its UTF-8 bytes
     */
    public record Alternative( String token, double logprob, byte[] bytes ) {

        /** Its probability. */
        public double probability() { return Math.exp( logprob ) ; }
    }

    public TokenLogprob {
        top = top == null ? List.of() : List.copyOf( top ) ;
    }

    /** Its probability. */
    public double probability() { return Math.exp( logprob ) ; }
}
