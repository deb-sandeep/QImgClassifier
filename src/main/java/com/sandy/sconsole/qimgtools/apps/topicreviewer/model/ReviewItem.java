package com.sandy.sconsole.qimgtools.apps.topicreviewer.model;

import com.sandy.sconsole.qimgtools.core.model.QTask;
import com.sandy.sconsole.qimgtools.core.review.QuestionLocator;
import com.sandy.sconsole.qimgtools.core.server.ServerQuestion;

/**
 * One question of the server's topic review queue, as loaded. Immutable; the
 * question's later state (its verdict, the user's decision, the server's
 * reply to it) lives in the reviewer's table model.
 *
 * @param question the question as the server sent it
 * @param task     the question's local images, ready for the model; null if
 *        they could not be found
 * @param problem  why {@code task} is null; null otherwise
 */
public record ReviewItem( ServerQuestion question, QTask task, String problem ) {

    /** The item of a question {@code located} in the question bank. */
    public static ReviewItem of( QuestionLocator.Located located ) {
        return new ReviewItem( located.question(), located.task(),
                               located.problem() ) ;
    }

    /** The question's numeric server id. */
    public int id() {
        return question.getId() ;
    }

    /** True if the question's images were found, so it can be reviewed. */
    public boolean hasImages() {
        return task != null ;
    }

    @Override
    public String toString() {
        return question.getId() + " " + question.getQuestionId() ;
    }
}
