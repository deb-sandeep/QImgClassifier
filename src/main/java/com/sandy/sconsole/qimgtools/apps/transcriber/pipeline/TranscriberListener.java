package com.sandy.sconsole.qimgtools.apps.transcriber.pipeline;

import com.sandy.sconsole.qimgtools.apps.transcriber.model.Passage;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.Transcription;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.TranscriptionItem;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.TranscriptionRoute;
import com.sandy.sconsole.qimgtools.core.model.AttemptRequest;
import com.sandy.sconsole.qimgtools.core.model.AttemptTranscript;
import com.sandy.sconsole.qimgtools.core.model.ProcessingState;

/**
 * Told how the jobs sent to the models fare: questions, LCT question parts
 * and passages. Calls come from the AI hub's worker threads, and from the
 * run's own thread.
 */
public interface TranscriberListener {

    /** A model picked {@code job} up for its first attempt. */
    void jobStarted( TranscriptionJob job ) ;

    /** An attempt on {@code job} starts on {@code model}. */
    default void jobAttempt( TranscriptionJob job, String model,
                             int attempt ) {}

    /** An attempt's request went out; its reply is awaited. */
    default void requestSent( AttemptRequest request ) {}

    /**
     * An attempt on {@code job} ended as {@code transcript} says, and its
     * transcript is saved.
     */
    default void attemptFinished( TranscriptionJob job,
                                  AttemptTranscript transcript ) {}

    /**
     * A model transcribed {@code job}.
     *
     * @param provisional true if it is Qwen's, kept because the model
     *        asked for a second opinion gave none
     */
    void transcribed( TranscriptionJob job, Transcription transcription,
                      TranscriptionRoute route, boolean provisional ) ;

    /** No model could transcribe {@code job}, for {@code reason}. */
    void jobFailed( TranscriptionJob job, int attempts, String reason ) ;

    /**
     * {@code item} waits for its LCT group's passage, which is being
     * transcribed.
     */
    default void waitingForPassage( TranscriptionItem item ) {}

    /** {@code item}, its passage now known, is queued for a model. */
    default void queued( TranscriptionItem item ) {}

    /**
     * The passage of an LCT group is known: read from the server or the
     * workspace, transcribed, or stored by a save.
     */
    default void passageKnown( Passage passage ) {}

    /** The model run started, is stopping, or ended. */
    default void processingStateChanged( ProcessingState state ) {}
}
