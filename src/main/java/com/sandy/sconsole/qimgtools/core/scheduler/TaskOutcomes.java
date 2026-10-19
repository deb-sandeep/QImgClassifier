package com.sandy.sconsole.qimgtools.core.scheduler;

import com.sandy.sconsole.qimgtools.core.model.AIAgent;
import com.sandy.sconsole.qimgtools.core.model.QTask;
import com.sandy.sconsole.qimgtools.core.model.Topic;

import java.util.List;

/**
 * What the {@link TaskScheduler} reports about each question it runs, and
 * the app's say in where it goes: when it starts, every attempt, and how it
 * ends. The classifier stores results in the question bank; the topic
 * reviewer compares them with the server's topics. Implementations must be
 * thread-safe: the calls come from the AI hub's worker threads.
 */
public interface TaskOutcomes {

    /**
     * The roots of the app's AI config the task may start on; the app's
     * routing policy picks among their models.
     */
    List<String> roots( QTask task ) ;

    /** A worker picked the task up for its first attempt. */
    void started( QTask task ) ;

    /**
     * A model classified the task. Never throws.
     *
     * @param agent the kind of gateway and {@code model} the model that gave
     *        {@code topics}
     * @return true if the result was accepted
     */
    boolean succeeded( QTask task, List<Topic> topics, AIAgent agent,
                       String model, int attempts ) ;

    /** The task failed for good. */
    void failed( QTask task, int attempts, String reason ) ;

    /**
     * The root to ask for a second opinion on {@code topics}, from
     * {@code model} on a gateway of {@code agent}'s kind; null to keep them.
     * If no model of that root gives an answer, {@code topics} are kept.
     * Called on every reply that parses, the second opinion's too.
     */
    String secondOpinionRoot( QTask task, List<Topic> topics, AIAgent agent,
                              String model ) ;

    /** Why a reply is sent for a second opinion, for the logs. */
    default String secondOpinionReason() {
        return "the top topic is not confident enough" ;
    }

    /** Told about each attempt on {@code task} as it starts and ends. */
    AttemptListener attemptListener( QTask task ) ;
}
