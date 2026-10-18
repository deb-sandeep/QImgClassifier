package com.sandy.sconsole.qimgtools.apps.topicreviewer.model;

import com.sandy.sconsole.qimgtools.core.model.Topic;
import com.sandy.sconsole.qimgtools.core.sequence.TopicChooser;

import java.io.IOException;
import java.util.List;

/**
 * The review model's opinion of a question: its topics, best first, each
 * with the model's confidence, and the one of them the question is filed
 * under ({@code TopicResolver}'s choice, via {@link TopicChooser}).
 *
 * @param model  the model that gave it
 * @param topics never empty
 * @param chosen one of {@code topics}
 */
public record ReviewVerdict( String model, List<Topic> topics, Topic chosen ) {

    public ReviewVerdict {
        topics = List.copyOf( topics ) ;
        if( !topics.contains( chosen ) ) {
            throw new IllegalArgumentException( "chosen topic not in topics" ) ;
        }
    }

    /**
     * The verdict of {@code model} recommending {@code topics}, with the
     * topic chosen from them.
     *
     * @throws IOException if the topic sequence file can't be read
     */
    public static ReviewVerdict of( String model, List<Topic> topics )
            throws IOException {

        Topic chosen = TopicChooser.choose(
                model, topics, Topic::getId,
                t -> Math.round( t.getMatchProbability() * 100 ) ) ;
        return new ReviewVerdict( model, topics, chosen ) ;
    }

    /**
     * The model's topic: the one chosen to file the question under, which is
     * not always the one it scored highest.
     */
    public Topic top() {
        return chosen ;
    }

    /** The chosen topic's own confidence, 0-100. */
    public int topConfidence() {
        return Math.round( chosen.getMatchProbability() * 100 ) ;
    }

    /** True if the model's chosen topic is {@code topicId}. */
    public boolean agreesWith( Integer topicId ) {
        return topicId != null && chosen.getId() == topicId ;
    }
}
