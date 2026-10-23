package com.sandy.sconsole.qimgtools.apps.tagger.model;

/**
 * What a run works on: one topic, how many questions a Load asks for, the
 * tag mode, and where the next Load starts in each of the topic's queues.
 *
 * @param topicId     the topic whose tags are offered
 * @param batchSize   questions per Load, 1-500
 * @param mode        whether gaps may be proposed
 * @param ownAfterId  the highest id of the topic's own questions loaded so
 *                    far; null to start from the first
 * @param depAfterId  the same for its dependents
 */
public record Scope( int topicId, int batchSize, TagMode mode,
                     Integer ownAfterId, Integer depAfterId ) {

    /** True if a Load has moved either cursor on from the start. */
    public boolean hasCursors() {
        return ownAfterId != null || depAfterId != null ;
    }

    /** This scope with both cursors back at the start. */
    public Scope fromStart() {
        return new Scope( topicId, batchSize, mode, null, null ) ;
    }

    /** This scope with its cursors moved on to {@code own} and {@code dep}. */
    public Scope withCursors( Integer own, Integer dep ) {
        return new Scope( topicId, batchSize, mode, own, dep ) ;
    }
}
