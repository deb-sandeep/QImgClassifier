package com.sandy.sconsole.qimgtools.apps.nxrclassifier.llm;

import com.sandy.sconsole.ai.AIRequest;
import com.sandy.sconsole.qimgtools.apps.nxrclassifier.model.NXRItem;
import com.sandy.sconsole.qimgtools.apps.nxrclassifier.model.NXRReply;
import com.sandy.sconsole.qimgtools.apps.nxrclassifier.model.NXRValue;
import com.sandy.sconsole.qimgtools.core.model.QTask;
import com.sandy.sconsole.qimgtools.core.model.Subject;
import com.sandy.sconsole.qimgtools.core.scheduler.TaskPrompter;
import com.sandy.sconsole.qimgtools.core.util.ImageCollator;
import com.sandy.sconsole.qimgtools.core.workspace.JsonFiles;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;

/**
 * What an AI model is asked about a question the local classifier left
 * undecided, and reading its answer. The prompt is the template
 * {@link #TEMPLATE} (the labels, the conventions, the reply's shape), the
 * same for every question so that it is cached as a prefix, followed by the
 * question's subject and type; the image is the question's, its parts (and
 * an LCT group's context) stacked into one. The reply is one JSON object,
 * {@code {"nxr", "confidence", "reason"}}.
 */
public final class NXRPrompter implements TaskPrompter<NXRReply> {

    /** Classpath location of the prompt template. */
    public static final String TEMPLATE =
            "/apps/nxrclassifier/nxrclassifier.prompt" ;

    private final String                  template ;
    private final Function<QTask,NXRItem> items ;

    /**
     * @param items the loaded question each submitted task is
     * @throws IllegalStateException if the template can't be read
     */
    public NXRPrompter( Function<QTask,NXRItem> items ) {
        this.items    = items ;
        this.template = load( TEMPLATE ) ;
    }

    private static String load( String path ) {

        try( InputStream is = NXRPrompter.class.getResourceAsStream( path ) ) {
            if( is == null ) {
                throw new IllegalStateException( "No prompt template " + path ) ;
            }
            return new String( is.readAllBytes(), StandardCharsets.UTF_8 ) ;
        }
        catch( IOException e ) {
            throw new IllegalStateException( "Could not read the prompt " +
                                             "template " + path, e ) ;
        }
    }

    @Override
    public String prompt( QTask task ) {

        NXRItem item = items.apply( task ) ;
        String  type = item == null ? null
                                    : item.question().getProblemType() ;

        return template.stripTrailing() + "\n\nSubject: " +
               task.getSubject().getSyllabusName() +
               ( type == null ? "" : "\nQuestion type: " + type ) + "\n" ;
    }

    /** The question's images stacked into one, LCT context on top. */
    @Override
    public AIRequest.Image prepareImage( QTask task ) throws IOException {

        List<File> files = task.getImgFiles() ;
        return AIRequest.Image.of( ImageCollator.collatedBytes( files ),
                                   ImageCollator.collatedMimeType( files ) ) ;
    }

    /**
     * The label in {@code reply}: a JSON object, possibly in markdown fences
     * or among other text. Physics and Maths are never recall.
     *
     * @throws BadReply if there is no object, or no N, X or R in it
     */
    @Override
    public NXRReply parse( String reply, QTask task ) throws BadReply {

        int from = reply == null ? -1 : reply.indexOf( '{' ) ;
        int to   = reply == null ? -1 : reply.lastIndexOf( '}' ) ;
        if( from < 0 || to < from ) {
            throw new BadReply( "the model's reply was not a JSON object" ) ;
        }

        JsonNode node ;
        try {
            node = JsonFiles.MAPPER.readTree( reply.substring( from, to + 1 ) ) ;
        }
        catch( IOException e ) {
            throw new BadReply( "the model's reply was not a JSON object" ) ;
        }

        NXRValue value = NXRValue.of( node.path( "nxr" ).asText( "" )
                                          .trim().toUpperCase() ) ;
        if( value == null ) {
            throw new BadReply( "the model's reply gave no N, X or R" ) ;
        }
        if( value == NXRValue.R && task.getSubject() != Subject.CHEMISTRY ) {
            throw new BadReply( "the model gave R (recall), never right " +
                                "for " + task.getSubject()
                                             .getSyllabusName() ) ;
        }

        JsonNode conf   = node.get( "confidence" ) ;
        JsonNode reason = node.get( "reason" ) ;
        return new NXRReply( value,
                             conf != null && conf.isNumber()
                             ? Math.max( 0, Math.min( 100, conf.asInt() ) )
                             : null,
                             reason != null && reason.isTextual()
                             ? reason.asText() : null,
                             null ) ;
    }
}
