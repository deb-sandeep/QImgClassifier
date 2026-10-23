package com.sandy.sconsole.qimgtools.apps.tagger.prompt;

import com.sandy.sconsole.qimgtools.apps.tagger.model.CatalogueTag;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagCatalogue;
import com.sandy.sconsole.qimgtools.core.model.Topic;

import java.util.List;
import java.util.Map;

/**
 * A topic prompt as it would go to the models, for review before it is
 * approved.
 *
 * @param topic           the topic
 * @param catalogue       its catalogue now, which approval is stamped with
 * @param topicPrompt     its {@code tags.reviewed.prompt} as read
 * @param state           where it stands against {@code catalogue}
 * @param own             the assembled prompt for the topic's own questions
 * @param dependent       the assembled prompt for its dependents
 * @param problems        what is wrong with its tag lines; empty if nothing
 * @param leftOut         the catalogue's tags it leaves out
 * @param costPer1000     per model the app may use, by name: the estimated
 *                        input cost in USD of 1,000 own questions, at list
 *                        prices without the cache discount; models without
 *                        a price are left out
 */
public record PromptReview( Topic topic, TagCatalogue catalogue,
                            PromptFile topicPrompt, PromptStore.State state,
                            String own, String dependent,
                            List<String> problems, List<CatalogueTag> leftOut,
                            Map<String,Double> costPer1000 ) {

    /** True if it may be approved: its tag lines have no problem. */
    public boolean approvable() {
        return problems.isEmpty() ;
    }
}
