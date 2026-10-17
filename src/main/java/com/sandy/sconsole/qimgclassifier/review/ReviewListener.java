package com.sandy.sconsole.qimgclassifier.review;

import com.sandy.sconsole.qimgclassifier.model.AttemptRequest;
import com.sandy.sconsole.qimgclassifier.model.ProcessingState;
import com.sandy.sconsole.qimgclassifier.model.ReviewItem;
import com.sandy.sconsole.qimgclassifier.model.ReviewVerdict;

/**
 * Receives the topic reviewer's progress. Calls arrive on worker threads.
 */
public interface ReviewListener {

    /** A worker picked the question up for its first attempt. */
    void itemStarted( ReviewItem item ) ;

    /** Attempt {@code attempt} (1-based) on {@code model} is starting. */
    default void itemAttempt( ReviewItem item, String model, int attempt ) {}

    /** A request went out and awaits its reply. */
    default void requestSent( AttemptRequest request ) {}

    /** An attempt ended and its transcript is saved. */
    default void attemptFinished( ReviewItem item ) {}

    /** The model classified the question. */
    void verdictReached( ReviewItem item, ReviewVerdict verdict ) ;

    /** The model gave no usable answer. */
    void itemFailed( ReviewItem item, int attempts, String reason ) ;

    default void processingStateChanged( ProcessingState state ) {}
}
