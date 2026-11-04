package com.sandy.sconsole.qimgtools.apps.tagger.prompt;

import com.sandy.sconsole.ai.AIRequest;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagReply;
import com.sandy.sconsole.qimgtools.apps.tagger.regression.Decision;
import com.sandy.sconsole.qimgtools.core.model.QTask;
import com.sandy.sconsole.qimgtools.core.scheduler.TaskPrompter;
import com.sandy.sconsole.qimgtools.core.util.ImagePrep;
import com.sandy.sconsole.qimgtools.core.workspace.JsonFiles;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the models are asked, and reading their replies. The request is the
 * topic's {@code llm-prompt.md} filled in for the question, with its image. A
 * reply is held strictly to the format: one tag that isn't in the prompt's
 * table rejects the whole answer, with no mending. Thread-safe.
 */
public final class TagPrompter implements TaskPrompter<TagReply> {

    /**
     * What is needed to ask about a question.
     *
     * @param prompt     the topic's prompt
     * @param questionId the question's text id
     * @param ocrText    its OCR text
     * @param decision   the regression's decision, whose lists are the hints
     */
    public record Context( TopicPrompt prompt, String questionId, String ocrText,
                           Decision decision ) {}

    private final ImagePrep          imagePrep ;
    private final Map<QTask,Context> contexts = new ConcurrentHashMap<>() ;

    /** @param imagePrep how images are made smaller before they are sent */
    public TagPrompter( ImagePrep imagePrep ) {
        this.imagePrep = imagePrep ;
    }

    /** Note what to ask about {@code task}, which can then be sent. */
    public void track( QTask task, Context context ) {
        contexts.put( task, context ) ;
    }

    /** Forget every question: none of them can be sent any more. */
    public void clear() {
        contexts.clear() ;
    }

    @Override
    public String prompt( QTask task ) {

        Context  c = context( task ) ;
        Decision d = c.decision() ;

        return c.prompt().fill( c.questionId(), c.ocrText(), d.accepted(),
                                d.undecided(), d.triggered() ) ;
    }

    @Override
    public AIRequest.Image prepareImage( QTask task ) throws IOException {
        return AIRequest.Image.of( imagePrep.bytes( task.getImgFiles() ),
                                   imagePrep.mimeType( task.getImgFiles() ) ) ;
    }

    @Override
    public TagReply parse( String reply, QTask task ) throws BadReply {
        return read( reply, context( task ).prompt().tagNames() ) ;
    }

    /**
     * {@code reply} read and checked against the tags {@code allowed}.
     *
     * @throws BadReply if it isn't a reply the format allows
     */
    static TagReply read( String reply, Set<String> allowed ) throws BadReply {

        JsonNode node = object( reply ) ;

        TagReply.Confidence confidence = TagReply.Confidence.of(
                node.path( "confidence" ).asText( "" ) ) ;
        if( confidence == null ) {
            throw new BadReply( "the reply's confidence was not high, medium " +
                                "or low" ) ;
        }
        if( !node.path( "noTagFits" ).isBoolean() ) {
            throw new BadReply( "the reply's noTagFits was not true or false" ) ;
        }

        boolean      noTagFits = node.get( "noTagFits" ).asBoolean() ;
        List<String> tags      = tags( node, allowed ) ;

        if( noTagFits && !tags.isEmpty() ) {
            throw new BadReply( "the reply named tags, and said no tag fits" ) ;
        }
        if( !noTagFits && tags.isEmpty() ) {
            throw new BadReply( "the reply named no tags, and didn't say no " +
                                "tag fits" ) ;
        }
        return new TagReply( tags, confidence, noTagFits,
                             text( node, "framing" ), text( node, "reason" ) ) ;
    }

    private Context context( QTask task ) {

        Context c = contexts.get( task ) ;
        if( c == null ) {
            throw new IllegalStateException( task + " has not been read" ) ;
        }
        return c ;
    }

    /** The JSON object in {@code reply}, ignoring fences and text around it. */
    private static JsonNode object( String reply ) throws BadReply {

        int from = reply == null ? -1 : reply.indexOf( '{' ) ;
        int to   = reply == null ? -1 : reply.lastIndexOf( '}' ) ;

        if( from < 0 || to < from ) {
            throw new BadReply( "the model's reply was not a JSON object" ) ;
        }
        try {
            return JsonFiles.MAPPER.readTree( reply.substring( from, to + 1 ) ) ;
        }
        catch( IOException e ) {
            throw new BadReply( "the model's reply was not a JSON object" ) ;
        }
    }

    private static List<String> tags( JsonNode node, Set<String> allowed )
            throws BadReply {

        JsonNode array = node.get( "tags" ) ;
        if( array == null || !array.isArray() ) {
            throw new BadReply( "the reply had no list of tags" ) ;
        }

        Set<String> tags = new LinkedHashSet<>() ;
        for( JsonNode t : array ) {
            if( !t.isTextual() || !allowed.contains( t.asText() ) ) {
                throw new BadReply( "the reply named a tag not in the list: " +
                                    t.asText() ) ;
            }
            tags.add( t.asText() ) ;
        }
        return new ArrayList<>( tags ) ;
    }

    /** The text at {@code key}; null if it is missing, null or blank. */
    private static String text( JsonNode node, String key ) {

        JsonNode v = node.get( key ) ;
        return v == null || !v.isTextual() || v.asText().isBlank() ? null
                                                                   : v.asText() ;
    }
}
