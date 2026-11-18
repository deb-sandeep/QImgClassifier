package com.sandy.sconsole.qimgtools.apps.tagger.model;

import java.util.List;

/**
 * A model's reply, as read and checked (the tag workspace's
 * {@code file-formats/llm-prompt.md}).
 *
 * @param tags       the names of the tags it chose, in reply order; empty
 *                   when {@code noTagFits} or {@code otherTopic} is set
 * @param confidence how sure it is
 * @param noTagFits  true if no tag of the topic fits the question
 * @param framing    the question's framing when no tag fits; may be null
 * @param otherTopic the topic of the syllabus the question plainly belongs
 *                   to instead, a recommendation to move it; null if none
 * @param reason     the words that decided it; may be null
 */
public record TagReply( List<String> tags, Confidence confidence,
                        boolean noTagFits, String framing,
                        OtherTopic otherTopic, String reason ) {

    /**
     * Where the model would move the question.
     *
     * @param topicId   one of the syllabus' other topics, as the server has it
     * @param topicName its name, as the server has it
     * @param reason    the words that decided it; may be null
     */
    public record OtherTopic( int topicId, String topicName, String reason ) {

        /** "105 - Work, Energy and Power". */
        public String label() {
            return topicId + " - " + topicName ;
        }
    }

    /** How sure a model says it is. */
    public enum Confidence {

        HIGH, MEDIUM, LOW ;

        /** The value of {@code text} in any case; null if it isn't one. */
        public static Confidence of( String text ) {
            for( Confidence c : values() ) {
                if( c.name().equalsIgnoreCase( text ) ) return c ;
            }
            return null ;
        }

        @Override
        public String toString() {
            return name().toLowerCase() ;
        }
    }

    public TagReply {
        tags = List.copyOf( tags ) ;
    }

    /** True if the model would move the question to another topic. */
    public boolean suggestsMove() {
        return otherTopic != null ;
    }

    /** True if the model says it is unsure: the row is marked for a look. */
    public boolean isLowConfidence() {
        return confidence == Confidence.LOW ;
    }
}
