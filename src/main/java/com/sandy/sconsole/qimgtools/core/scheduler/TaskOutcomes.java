package com.sandy.sconsole.qimgtools.core.scheduler;

import com.sandy.sconsole.ai.FailureKind;
import com.sandy.sconsole.qimgtools.core.model.AIAgent;
import com.sandy.sconsole.qimgtools.core.model.QTask;

import java.util.List;

/**
 * What the {@link TaskScheduler} reports about each question it runs, and
 * the app's say in where it goes: when it starts, every attempt, and how it
 * ends. The classifier stores results in the question bank; the topic
 * reviewer compares them with the server's topics. Implementations must be
 * thread-safe: the calls come from the AI hub's worker threads.
 *
 * @param <R> what a reply is read as (see {@link TaskPrompter})
 */
public interface TaskOutcomes<R> {

    /**
     * The roots of the app's AI config the task may start on; the app's
     * routing policy picks among their models.
     */
    List<String> roots( QTask task ) ;

    /** A worker picked the task up for its first attempt. */
    void started( QTask task ) ;

    /**
     * A model answered the task. Never throws.
     *
     * @param agent the kind of gateway and {@code model} the model that gave
     *        {@code value}
     * @return true if the result was accepted
     */
    boolean succeeded( QTask task, R value, AIAgent agent, String model,
                       int attempts ) ;

    /** The task failed for good. */
    void failed( QTask task, int attempts, String reason ) ;

    /**
     * The task failed for good, its last attempt as {@code kind}; by
     * default {@link #failed(QTask, int, String)}.
     */
    default void failed( QTask task, int attempts, String reason,
                         FailureKind kind ) {
        failed( task, attempts, reason ) ;
    }

    /**
     * The root to ask for a second opinion on {@code value}, from
     * {@code model} on a gateway of {@code agent}'s kind; null to keep it.
     * If no model of that root gives an answer, {@code value} is kept.
     * Called on every reply that parses, the second opinion's too.
     */
    String secondOpinionRoot( QTask task, R value, AIAgent agent,
                              String model ) ;

    /** Why a reply is sent for a second opinion, for the logs. */
    default String secondOpinionReason() {
        return "the top topic is not confident enough" ;
    }

    /**
     * What the transcript of an attempt on {@code task} starting now should
     * note beside the model (e.g. the tier asked, the prompt's version);
     * null for nothing.
     */
    default String attemptContext( QTask task ) {
        return null ;
    }

    /** Told about each attempt on {@code task} as it starts and ends. */
    AttemptListener attemptListener( QTask task ) ;
}
