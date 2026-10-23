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
     * The JSON schema the reply to {@code task} must follow, applied where
     * the model supports structured output; null (the default) for free
     * text. {@link #parse} must still read a reply that ignores it.
     */
    default AIRequest.ResponseSchema responseSchema( QTask task ) {
        return null ;
    }

    /**
     * {@code reply} read as the answer for {@code task}.
     *
     * @throws BadReply with a short readable reason if it can't be read
     */
    R parse( String reply, QTask task ) throws BadReply ;
}
