package com.sandy.sconsole.qimgclassifier.model;

/**
 * The server's answer to one confirmed review
 * ({@code POST /Master/Question/TopicReview}).
 *
 * @param questionId        the question's numeric server id
 * @param status            what the server did with it
 * @param topicReviewNeeded the question's review flag afterwards; null if
 *        the question was not found. Still true after an AI review by a
 *        model outside the server's strong-model list
 * @param message           why it was rejected; null otherwise
 */
public record ReviewResult( int questionId, Status status,
                            Boolean topicReviewNeeded, String message ) {

    public enum Status {

        /** The topic and provenance were stored. */
        APPLIED,

        /** The question already had this topic and provenance. */
        UNCHANGED,

        /**
         * Refused: e.g. no longer flagged (overridden by the user or added to
         * an exam since the fetch), or the topic failed the server's checks.
         */
        REJECTED
    }

    /** A rejection made here, for a request that got no per-item answer. */
    public static ReviewResult failed( int questionId, String message ) {
        return new ReviewResult( questionId, Status.REJECTED, null, message ) ;
    }

    /** True if the server holds the submitted topic and provenance. */
    public boolean accepted() {
        return status != Status.REJECTED ;
    }

    /** True if the question stays in the review queue after this. */
    public boolean stillFlagged() {
        return Boolean.TRUE.equals( topicReviewNeeded ) ;
    }
}
