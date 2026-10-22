package com.sandy.sconsole.qimgtools.core.scheduler;

import com.sandy.sconsole.ai.AIRequest;
import com.sandy.sconsole.qimgtools.core.model.QTask;

import java.io.IOException;

/**
 * What the {@link TaskScheduler} asks a model about a question, and how it
 * reads the answer: the classifier's and the reviewer's topic question, the
 * tagger's blind solve. Implementations must be thread-safe.
 *
 * @param <R> what a reply is read as
 */
public interface TaskPrompter<R> {

    /** A reply that could not be read; the attempt fails as a bad reply. */
    class BadReply extends Exception {
        public BadReply( String reason ) { super( reason ) ; }
    }

    /** The prompt for {@code task}, sent before its image. */
    String prompt( QTask task ) ;

    /**
     * The question's image, prepared once per question and reused on every
     * attempt.
     *
     * @throws IOException if an image cannot be read
     */
    AIRequest.Image prepareImage( QTask task ) throws IOException ;

    /**
     * {@code reply} read as the answer for {@code task}.
     *
     * @throws BadReply with a short readable reason if it can't be read
     */
    R parse( String reply, QTask task ) throws BadReply ;
}
