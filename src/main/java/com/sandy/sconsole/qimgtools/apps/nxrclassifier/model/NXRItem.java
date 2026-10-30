package com.sandy.sconsole.qimgtools.apps.nxrclassifier.model;

import com.sandy.sconsole.qimgtools.core.model.QTask;
import com.sandy.sconsole.qimgtools.core.review.QuestionLocator;
import com.sandy.sconsole.qimgtools.core.server.ServerQuestion;

/**
 * One question awaiting its NXR label, as loaded. Immutable; what happens to
 * it later (its label, the server's answer) lives in the table model.
 *
 * @param question the question as the server sent it
 * @param task     the question's local images; null if they could not be
 *        found
 * @param problem  why {@code task} is null; null otherwise
 */
public record NXRItem( ServerQuestion question, QTask task, String problem ) {

    /** The item of a question {@code located} in the question bank. */
    public static NXRItem of( QuestionLocator.Located located ) {
        return new NXRItem( located.question(), located.task(),
                            located.problem() ) ;
    }

    /** The question's numeric server id. */
    public int id() {
        return question.getId() ;
    }

    /** True if the question's images were found, so it can be classified. */
    public boolean hasImages() {
        return task != null ;
    }

    @Override
    public String toString() {
        return question.getId() + " " + question.getQuestionId() ;
    }
}
