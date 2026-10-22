package com.sandy.sconsole.qimgtools.apps.tagger.pipeline;

import com.sandy.sconsole.qimgtools.apps.tagger.model.TagItem;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagJudgement;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagOpinions;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TierReply;
import com.sandy.sconsole.qimgtools.core.model.AttemptRequest;
import com.sandy.sconsole.qimgtools.core.model.ProcessingState;

/** Receives the tagger's progress. Calls arrive on worker threads. */
public interface TaggerListener {

    /** A worker picked the question up for its first attempt. */
    void itemStarted( TagItem item ) ;

    /**
     * Attempt {@code attempt} (1-based, counting both tiers') on
     * {@code model} of tier {@code tier} is starting.
     */
    default void itemAttempt( TagItem item, String model, int attempt,
                              int tier ) {}

    /**
     * Tier 1's {@code reply} misses the key, so the question goes on to
     * tier 2; {@link #judged} follows once that is done.
     */
    default void tier1Missed( TagItem item, TierReply reply ) {}

    /** A request went out and awaits its reply. */
    default void requestSent( AttemptRequest request ) {}

    /** An attempt ended and its transcript is saved. */
    default void attemptFinished( TagItem item ) {}

    /** The tiers replied, and {@code judgement} is where that leaves it. */
    void judged( TagItem item, TagOpinions opinions, TagJudgement judgement ) ;

    /** Tier 1's replies could not be used, even after a resend. */
    void invalidOutput( TagItem item, int attempts, String reason ) ;

    /** The models gave no answer (rate limits, errors, timeouts). */
    void itemFailed( TagItem item, int attempts, String reason ) ;

    default void processingStateChanged( ProcessingState state ) {}
}
