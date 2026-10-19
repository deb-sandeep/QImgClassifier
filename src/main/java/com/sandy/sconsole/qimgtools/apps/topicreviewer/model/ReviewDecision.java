package com.sandy.sconsole.qimgtools.apps.topicreviewer.model;

import com.sandy.sconsole.qimgtools.core.model.Topic;

/**
 * A topic the user confirms for a question of the topic review, with the
 * provenance sent to the server along with it.
 *
 * @param item       the question
 * @param topic      the topic it gets; its current one to accept that
 * @param actor      {@code AI} or {@code User}
 * @param model      the model for {@code AI}; null for {@code User}
 * @param confidence 0-100
 */
public record ReviewDecision( ReviewItem item, Topic topic, String actor,
                              String model, int confidence ) {

    public static final String AI   = "AI" ;
    public static final String USER = "User" ;

    /**
     * The decision giving {@code item} the topic {@code topic}, credited to
     * the model whose chosen topic ({@link ReviewVerdict#top}) it is: the
     * reviewer's (AI, with its confidence), else the tie-breaker's (AI, with
     * its confidence; also when it sided with the server's topic), else the
     * user's (User, 100): keeping the server's topic against the models,
     * picking another of their topics, or choosing another.
     *
     * @param opinions the AI's opinions on the question; null if none
     */
    public static ReviewDecision of( ReviewItem item, Topic topic,
                                     ReviewOpinions opinions ) {

        if( opinions != null ) {
            for( ReviewVerdict v : new ReviewVerdict[] {
                    opinions.reviewer(), opinions.tieBreaker() } ) {
                if( v != null && v.agreesWith( topic.getId() ) ) {
                    return new ReviewDecision( item, topic, AI, v.model(),
                                               v.topConfidence() ) ;
                }
            }
        }
        return new ReviewDecision( item, topic, USER, null, 100 ) ;
    }

    /** True if this keeps the question's current server topic. */
    public boolean keepsTopic() {
        Integer current = item.question().getTopicId() ;
        return current != null && current == topic.getId() ;
    }

    /** True if a model's verdict is what is confirmed. */
    public boolean isAI() {
        return AI.equals( actor ) ;
    }
}
