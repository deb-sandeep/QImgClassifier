package com.sandy.sconsole.qimgclassifier.classifier;

import com.sandy.sconsole.qimgclassifier.ai.AIInvoker;
import com.sandy.sconsole.qimgclassifier.model.AIAgent;
import com.sandy.sconsole.qimgclassifier.model.QTask;
import com.sandy.sconsole.qimgclassifier.model.Topic;

import java.util.List;

/**
 * What the {@link TaskScheduler} reports about each question it runs: when
 * it starts, every attempt, and how it ends. The classifier stores results in
 * the question bank ({@link QuestionClassifier}); the topic reviewer compares
 * them with the server's topics. Implementations must be thread-safe: the
 * calls come from the scheduler's worker threads.
 */
public interface TaskOutcomes {

    /** A worker picked the task up for its first attempt. */
    void started( QTask task ) ;

    /**
     * A model classified the task. Never throws.
     *
     * @param agent the backend and {@code model} the model that gave
     *        {@code topics}
     * @return true if the result was accepted
     */
    boolean succeeded( QTask task, List<Topic> topics, AIAgent agent,
                       String model, int attempts ) ;

    /** The task failed for good. */
    void failed( QTask task, int attempts, String reason ) ;

    /**
     * True if Gemma's {@code topics} are not good enough to keep, so the task
     * should get a second opinion from OpenRouter.
     */
    boolean failsGate( QTask task, List<Topic> topics ) ;

    /** Told about each attempt on {@code task} as it starts and ends. */
    AIInvoker.AttemptListener attemptListener( QTask task ) ;
}
