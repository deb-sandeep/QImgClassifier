package com.sandy.sconsole.qimgtools.apps.nxrclassifier.llm;

import com.sandy.sconsole.qimgtools.apps.nxrclassifier.model.NXRItem;
import com.sandy.sconsole.qimgtools.apps.nxrclassifier.model.NXRReply;
import com.sandy.sconsole.qimgtools.core.model.AttemptRequest;
import com.sandy.sconsole.qimgtools.core.model.ProcessingState;

/**
 * Told how the questions sent to an AI model fare. Calls come from the AI
 * hub's worker threads, and from the run's own thread.
 */
public interface NXRListener {

    /** A model picked {@code item} up for its first attempt. */
    void itemStarted( NXRItem item ) ;

    /** An attempt on {@code item} starts on {@code model}. */
    default void itemAttempt( NXRItem item, String model, int attempt ) {}

    /** An attempt's request went out; its reply is awaited. */
    default void requestSent( AttemptRequest request ) {}

    /** An attempt on {@code item} ended, and its transcript is saved. */
    default void attemptFinished( NXRItem item ) {}

    /** A model labelled {@code item}. */
    void aiLabelled( NXRItem item, NXRReply reply ) ;

    /** No model could label {@code item}, for {@code reason}. */
    void aiFailed( NXRItem item, int attempts, String reason ) ;

    /** The AI run started, is stopping, or ended. */
    default void processingStateChanged( ProcessingState state ) {}
}
