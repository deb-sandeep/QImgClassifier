package com.sandy.sconsole.qimgtools.apps.tagger.model;

/**
 * What a run works on: one topic, and where the next Load starts.
 *
 * @param topicId the topic whose questions are tagged
 * @param afterId the highest id of the questions loaded so far; null to
 *                start from the first
 */
public record Scope( int topicId, Integer afterId ) {

    /** This scope with its cursor back at the start. */
    public Scope fromStart() {
        return new Scope( topicId, null ) ;
    }

    /** This scope with its cursor moved on to {@code afterId}. */
    public Scope withCursor( Integer afterId ) {
        return new Scope( topicId, afterId ) ;
    }
}
