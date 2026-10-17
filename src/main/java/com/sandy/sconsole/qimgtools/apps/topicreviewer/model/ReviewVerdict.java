package com.sandy.sconsole.qimgtools.apps.topicreviewer.model;

import java.util.List;
import com.sandy.sconsole.qimgtools.core.model.Topic;

/**
 * The review model's opinion of a question: its topics, best first, each
 * with the model's confidence.
 *
 * @param model  the model that gave it
 * @param topics never empty
 */
public record ReviewVerdict( String model, List<Topic> topics ) {

    public ReviewVerdict {
        topics = List.copyOf( topics ) ;
    }

    /** The model's best topic. */
    public Topic top() {
        return topics.get( 0 ) ;
    }

    /** The top topic's confidence, 0-100. */
    public int topConfidence() {
        return Math.round( top().getMatchProbability() * 100 ) ;
    }

    /** True if the model's best topic is {@code topicId}. */
    public boolean agreesWith( Integer topicId ) {
        return topicId != null && top().getId() == topicId ;
    }
}
