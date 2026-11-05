package com.sandy.sconsole.qimgtools.apps.tagger.regression;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What the regression makes of a question. {@code route} and {@code llmWhy}
 * are the reference's ({@code score_regression.py}), kept so the port can be
 * checked against it. The tagger routes by {@link #sendsToLlm()}, its own rule:
 * tags accepted stand, and what is undecided or triggered beside them is
 * dropped.
 *
 * @param accepted  the tags it would apply, implied parents dropped
 * @param undecided the tags whose score lies between their thresholds
 * @param triggered the {@code llm-only} tags whose pattern matches
 * @param route     where the reference sends the question
 * @param llmWhy    why the reference sends it to the LLM; empty if it doesn't
 * @param suspect   the model's reason for each {@code llmIfAcceptedTogether}
 *                  rule the accepted tags fall under
 * @param scores    the score of every tag with a trained model, in file order
 */
public record Decision( List<String> accepted, List<String> undecided,
                        List<String> triggered, Route route,
                        List<String> llmWhy, List<String> suspect,
                        Map<String,Double> scores ) {

    public enum Route { APPLY, LLM }

    public Decision {
        accepted  = List.copyOf( accepted ) ;
        undecided = List.copyOf( undecided ) ;
        triggered = List.copyOf( triggered ) ;
        llmWhy    = List.copyOf( llmWhy ) ;
        suspect   = List.copyOf( suspect ) ;
    }

    /**
     * True if the tagger sends the question to the LLM: nothing is accepted,
     * or the accepted tags fall under an {@code llmIfAcceptedTogether} rule.
     */
    public boolean sendsToLlm() {
        return accepted.isEmpty() || !suspect.isEmpty() ;
    }

    /** The undecided and triggered tags, which the tagger leaves out. */
    public List<String> notAccepted() {

        List<String> tags = new ArrayList<>( undecided ) ;
        tags.addAll( triggered ) ;
        return tags ;
    }

    /** Why the tagger sends it to the LLM, for a reader; empty if it doesn't. */
    public String llmReason() {

        if( accepted.isEmpty() ) return "nothing accepted" ;
        return suspect.isEmpty() ? ""
               : "accepted together: " + String.join( "; ", suspect ) ;
    }
}
