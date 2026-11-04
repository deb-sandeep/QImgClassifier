package com.sandy.sconsole.qimgtools.apps.tagger.pipeline;

import com.sandy.sconsole.qimgtools.apps.tagger.model.TagItem;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagProposal;
import com.sandy.sconsole.qimgtools.core.model.AttemptRequest;
import com.sandy.sconsole.qimgtools.core.model.ProcessingState;

/** Receives the tagger's progress. Calls arrive on worker threads. */
public interface TaggerListener {

    /** A worker picked the question up for its first attempt. */
    void itemStarted( TagItem item ) ;

    /** Attempt {@code attempt} (1-based) on {@code model} is starting. */
    default void itemAttempt( TagItem item, String model, int attempt ) {}

    /** A request went out and awaits its reply. */
    default void requestSent( AttemptRequest request ) {}

    /** An attempt ended and its transcript is saved. */
    default void attemptFinished( TagItem item ) {}

    /** The model replied with {@code proposal}. */
    void proposed( TagItem item, TagProposal proposal ) ;

    /** The model's replies could not be used, even after a resend. */
    void invalidOutput( TagItem item, int attempts, String reason ) ;

    /** The models gave no answer (rate limits, errors, timeouts). */
    void itemFailed( TagItem item, int attempts, String reason ) ;

    default void processingStateChanged( ProcessingState state ) {}
}
