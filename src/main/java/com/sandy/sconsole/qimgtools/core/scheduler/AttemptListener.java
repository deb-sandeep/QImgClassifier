package com.sandy.sconsole.qimgtools.core.scheduler;

import com.sandy.sconsole.qimgtools.core.model.AIAgent;
import com.sandy.sconsole.qimgtools.core.model.AttemptRequest;
import com.sandy.sconsole.qimgtools.core.model.AttemptTranscript;

/** Told when an attempt on a question starts and ends. */
public interface AttemptListener {

    /**
     * {@code attempt} (1-based, across all models) is about to be sent to
     * {@code model} on a gateway of {@code agent}'s kind.
     */
    void attemptStarted( AIAgent agent, String model, int attempt ) ;

    /** The request went out; {@code request} has what was sent. */
    default void requestSent( AttemptRequest request ) {}

    /**
     * An attempt ended; {@code transcript} has what was sent and received.
     */
    default void attemptFinished( AttemptTranscript transcript ) {}
}
