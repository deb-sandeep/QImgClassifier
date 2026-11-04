package com.sandy.sconsole.qimgtools.apps.tagger.prompt;

import com.sandy.sconsole.ai.AIRequest;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagReply;
import com.sandy.sconsole.qimgtools.core.model.QTask;
import com.sandy.sconsole.qimgtools.core.scheduler.TaskPrompter;
import com.sandy.sconsole.qimgtools.core.util.ImagePrep;

import java.io.IOException;

/**
 * What the models are asked, and reading their replies. The topic's
 * {@code llm-prompt.md} and its reply format are not read yet: until they
 * are, {@code TaggerFrame.Controller.canTag} is false, so no question reaches
 * {@link #prompt} or {@link #parse}. Thread-safe.
 */
public final class TagPrompter implements TaskPrompter<TagReply> {

    private final ImagePrep imagePrep ;

    /** @param imagePrep how images are made smaller before they are sent */
    public TagPrompter( ImagePrep imagePrep ) {
        this.imagePrep = imagePrep ;
    }

    @Override
    public String prompt( QTask task ) {
        throw new IllegalStateException( "the LLM prompt is not read yet" ) ;
    }

    @Override
    public AIRequest.Image prepareImage( QTask task ) throws IOException {
        return AIRequest.Image.of( imagePrep.bytes( task.getImgFiles() ),
                                   imagePrep.mimeType( task.getImgFiles() ) ) ;
    }

    @Override
    public TagReply parse( String reply, QTask task ) throws BadReply {
        throw new BadReply( "the LLM's reply format is not read yet" ) ;
    }
}
