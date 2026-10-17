package com.sandy.sconsole.qimgtools.apps.topicreviewer.model;

import com.sandy.sconsole.qimgtools.core.util.ModelNames;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * A question on the SConsole server: the fields of the server's
 * {@code QuestionVO} that the topic reviewer uses (the rest are ignored when
 * it is read).
 */
@Data
public class ServerQuestion {

    /** Numeric server id, used in the review endpoint's path. */
    private int id ;

    /** Server question id, e.g. {@code C/SCA/74}. */
    private String questionId ;

    /** Name of the source folder in the question bank. */
    private String sourceId ;
    private String syllabusName ;
    private String problemType ;

    private Integer topicId ;
    private String  topicName ;

    /** {@code AI} or {@code User}; null if unknown (most of the old bank). */
    private String  topicAssocActor ;
    private String  topicAssocAiModel ;
    private Integer topicAssocConfidence ;

    /**
     * True while the association awaits review; set only by the server's
     * rules.
     */
    private boolean topicReviewNeeded ;

    private List<ServerQuestionImage> questionImages = new ArrayList<>() ;

    /**
     * "AI gemma4:latest 72%", "User", or blank if unknown: who made the
     * current association, with the model shown by its name in {@code names}.
     */
    public String provenance( ModelNames names ) {
        return provenance( topicAssocActor, topicAssocAiModel,
                           topicAssocConfidence, names ) ;
    }

    /**
     * "AI gemma4:latest 72%", "User", or blank for a null {@code actor}, with
     * the model shown by its name in {@code names}.
     */
    public static String provenance( String actor, String model,
                                     Integer confidence, ModelNames names ) {

        if( !"AI".equals( actor ) ) return actor == null ? "" : actor ;

        StringBuilder sb = new StringBuilder( "AI" ) ;
        if( model != null ) sb.append( ' ' ).append( names.of( model ) ) ;
        if( confidence != null ) {
            sb.append( ' ' ).append( confidence ).append( '%' ) ;
        }
        return sb.toString() ;
    }
}
