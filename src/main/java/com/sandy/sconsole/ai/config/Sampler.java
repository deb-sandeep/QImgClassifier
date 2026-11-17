package com.sandy.sconsole.ai.config;

import java.util.ArrayList;
import java.util.List;

/**
 * An app's sampler settings for a model, sent with every request to it in
 * place of the model's own defaults (e.g. an Ollama Modelfile's). Each is
 * optional: null leaves the model's default.
 *
 * @param temperature      0 for greedy decoding
 * @param topK             sample from the k most likely tokens only
 * @param topP             sample from the smallest set of tokens whose
 *                         probabilities add up to p
 * @param presencePenalty  penalty on tokens already used
 * @param frequencyPenalty penalty growing with a token's uses
 * @param repeatPenalty    multiplicative penalty on repeats (1 = none);
 *                         OpenRouter's {@code repetition_penalty}
 */
public record Sampler( Double temperature, Integer topK, Double topP,
                       Double presencePenalty, Double frequencyPenalty,
                       Double repeatPenalty ) {

    /** True if no setting is given. */
    public boolean isEmpty() {
        return temperature == null && topK == null && topP == null
               && presencePenalty == null && frequencyPenalty == null
               && repeatPenalty == null ;
    }

    @Override
    public String toString() {

        List<String> parts = new ArrayList<>() ;

        if( temperature != null ) parts.add( "temperature " + temperature ) ;
        if( topK != null ) parts.add( "top k " + topK ) ;
        if( topP != null ) parts.add( "top p " + topP ) ;
        if( presencePenalty != null ) {
            parts.add( "presence penalty " + presencePenalty ) ;
        }
        if( frequencyPenalty != null ) {
            parts.add( "frequency penalty " + frequencyPenalty ) ;
        }
        if( repeatPenalty != null ) {
            parts.add( "repeat penalty " + repeatPenalty ) ;
        }
        return String.join( ", ", parts ) ;
    }
}
