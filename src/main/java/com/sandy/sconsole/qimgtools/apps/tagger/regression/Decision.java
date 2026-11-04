package com.sandy.sconsole.qimgtools.apps.tagger.regression;

import java.util.List;
import java.util.Map;

/**
 * What the regression makes of a question.
 *
 * @param accepted  the tags it would apply, implied parents dropped
 * @param undecided the tags whose score lies between their thresholds
 * @param triggered the {@code llm-only} tags whose pattern matches
 * @param route     where the question goes next
 * @param llmWhy    why it goes to the LLM; empty if it doesn't
 * @param scores    the score of every tag with a trained model, in file order
 */
public record Decision( List<String> accepted, List<String> undecided,
                        List<String> triggered, Route route,
                        List<String> llmWhy, Map<String,Double> scores ) {

    public enum Route { APPLY, LLM }

    public Decision {
        accepted  = List.copyOf( accepted ) ;
        undecided = List.copyOf( undecided ) ;
        triggered = List.copyOf( triggered ) ;
        llmWhy    = List.copyOf( llmWhy ) ;
    }

    /** True if the regression settles the question alone. */
    public boolean isSettled() {
        return route == Route.APPLY ;
    }
}
