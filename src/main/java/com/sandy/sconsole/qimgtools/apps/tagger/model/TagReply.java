package com.sandy.sconsole.qimgtools.apps.tagger.model;

import java.util.List;

/**
 * A model's reply, as read and checked (the tag workspace's
 * {@code file-formats/llm-prompt.md}).
 *
 * @param tags       the names of the tags it chose, in reply order; empty
 *                   when {@code noTagFits}
 * @param confidence how sure it is
 * @param noTagFits  true if no tag of the topic fits the question
 * @param framing    the question's framing when no tag fits; may be null
 * @param reason     the words that decided it; may be null
 */
public record TagReply( List<String> tags, Confidence confidence,
                        boolean noTagFits, String framing, String reason ) {

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

    /** True if the model says it is unsure: the row is marked for a look. */
    public boolean isLowConfidence() {
        return confidence == Confidence.LOW ;
    }
}
