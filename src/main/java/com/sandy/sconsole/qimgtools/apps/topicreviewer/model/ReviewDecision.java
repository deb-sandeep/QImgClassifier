package com.sandy.sconsole.qimgtools.apps.topicreviewer.model;

import com.sandy.sconsole.qimgtools.core.model.Topic;

/**
 * A topic the user confirms for a question of the topic review, with the
 * provenance sent to the server along with it.
 *
 * @param item       the question
 * @param topic      the topic it gets; its current one to accept that
 * @param actor      {@code AI} or {@code User}
 * @param model      the reviewer model for {@code AI}; null for {@code User}
 * @param confidence 0-100
 */
public record ReviewDecision( ReviewItem item, Topic topic, String actor,
                              String model, int confidence ) {

    public static final String AI   = "AI" ;
    public static final String USER = "User" ;

    /**
     * The decision giving {@code item} the topic {@code topic}. It is the
     * model's (AI, with the model's confidence) when {@code topic} is the
     * model's chosen topic ({@link ReviewVerdict#top}), and the user's (User,
     * 100) otherwise: keeping the server's topic against the model, picking
     * another of the model's topics, or choosing another.
     *
     * @param verdict the model's verdict on the question; null if none
     */
    public static ReviewDecision of( ReviewItem item, Topic topic,
                                     ReviewVerdict verdict ) {

        if( verdict != null && verdict.top().getId() == topic.getId() ) {
            return new ReviewDecision( item, topic, AI, verdict.model(),
                                       verdict.topConfidence() ) ;
        }
        return new ReviewDecision( item, topic, USER, null, 100 ) ;
    }

    /** True if this keeps the question's current server topic. */
    public boolean keepsTopic() {
        Integer current = item.question().getTopicId() ;
        return current != null && current == topic.getId() ;
    }

    /** True if the model's verdict is what is confirmed. */
    public boolean isAI() {
        return AI.equals( actor ) ;
    }
}
