package com.sandy.sconsole.qimgtools.apps.topicreviewer.model;

import com.sandy.sconsole.qimgtools.core.model.Topic;

/**
 * What the AI made of a question of the topic review: the review model's
 * verdict and, when that differs from the server's topic, the tie-breaker's,
 * a stronger model asked to arbitrate.
 *
 * @param reviewer          the review model's verdict
 * @param tieBreaker        the tie-breaker's verdict; null if it wasn't asked
 *                          (the reviewer agreed) or gave no answer
 * @param tieBreakerFailure why the tie-breaker gave no answer; null if it
 *                          did or wasn't asked
 */
public record ReviewOpinions( ReviewVerdict reviewer, ReviewVerdict tieBreaker,
                              String tieBreakerFailure ) {

    /** How the opinions stand against the server's topic. */
    public enum Outcome {

        /** The reviewer's topic is the server's. */
        AGREED,

        /** The tie-breaker chose the reviewer's topic. */
        REVIEWER_CONFIRMED,

        /** The tie-breaker chose the server's topic. */
        SERVER_CONFIRMED,

        /** The tie-breaker chose a third topic, which wins all the same. */
        TIE_BREAKER_CHOSEN,

        /** The tie-breaker gave no answer: the user decides. */
        DISPUTED
    }

    public ReviewOpinions {
        if( reviewer == null ) {
            throw new IllegalArgumentException( "no reviewer verdict" ) ;
        }
    }

    /** Only the reviewer's verdict. */
    public static ReviewOpinions of( ReviewVerdict reviewer ) {
        return new ReviewOpinions( reviewer, null, null ) ;
    }

    /** How the opinions stand against the server's topic {@code topicId}. */
    public Outcome outcome( Integer topicId ) {

        if( reviewer.agreesWith( topicId ) ) return Outcome.AGREED ;
        if( tieBreaker == null ) return Outcome.DISPUTED ;
        if( tieBreaker.agreesWith( reviewer.top().getId() ) ) {
            return Outcome.REVIEWER_CONFIRMED ;
        }
        return tieBreaker.agreesWith( topicId ) ? Outcome.SERVER_CONFIRMED
                                                : Outcome.TIE_BREAKER_CHOSEN ;
    }

    /**
     * The topic the opinions settle on without the user: whenever the
     * tie-breaker answered, its topic wins (the reviewer's or the server's
     * when it sided with one, else its own); null when the reviewer agreed
     * with the server or the tie-breaker gave no answer.
     */
    public Topic settled( Integer topicId ) {
        return switch( outcome( topicId ) ) {
            case AGREED, DISPUTED -> null ;
            default               -> tieBreaker.top() ;
        } ;
    }

    /** True if the tie-breaker was asked but gave no answer. */
    public boolean tieBreakerFailed() {
        return tieBreakerFailure != null ;
    }
}
