package com.sandy.sconsole.qimgtools.apps.topicreviewer.review;

import com.sandy.sconsole.qimgtools.core.model.AttemptRequest;
import com.sandy.sconsole.qimgtools.core.model.ProcessingState;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewItem;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewOpinions;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewVerdict;

/**
 * Receives the topic reviewer's progress. Calls arrive on worker threads.
 */
public interface ReviewListener {

    /** A worker picked the question up for its first attempt. */
    void itemStarted( ReviewItem item ) ;

    /**
     * Attempt {@code attempt} (1-based, counting the tie-breaker's) on
     * {@code model} is starting; {@code tieBreaker} if it asks the
     * tie-breaker.
     */
    default void itemAttempt( ReviewItem item, String model, int attempt,
                              boolean tieBreaker ) {}

    /**
     * The reviewer's {@code verdict} differs from the server's topic, so
     * the question goes on to the tie-breaker; {@link #verdictReached}
     * follows once that is done.
     */
    default void reviewerDiffers( ReviewItem item, ReviewVerdict verdict ) {}

    /** A request went out and awaits its reply. */
    default void requestSent( AttemptRequest request ) {}

    /** An attempt ended and its transcript is saved. */
    default void attemptFinished( ReviewItem item ) {}

    /** The models classified the question. */
    void verdictReached( ReviewItem item, ReviewOpinions opinions ) ;

    /** The model gave no usable answer. */
    void itemFailed( ReviewItem item, int attempts, String reason ) ;

    default void processingStateChanged( ProcessingState state ) {}
}
