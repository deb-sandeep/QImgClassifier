package com.sandy.sconsole.qimgtools.apps.tagger.regression;

/**
 * What the local stage made of a question: the regression's decision on its
 * transcription, or why there is none.
 *
 * @param text     the transcription scored, as stored; null if it failed
 * @param decision the regression's decision; null if it failed
 * @param failure  why it failed; null otherwise
 */
public record Assessment( String text, Decision decision, String failure ) {

    public static Assessment failed( String why ) {
        return new Assessment( null, null, why ) ;
    }

    /** True if the question goes to the LLM. */
    public boolean needsLlm() {
        return decision != null && decision.sendsToLlm() ;
    }
}
