package com.sandy.sconsole.qimgclassifier.model;

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
     * "AI gemma4:latest 72%", "User" or "Unknown": who made the current
     * association.
     */
    public String provenance() {

        if( !"AI".equals( topicAssocActor ) ) {
            return topicAssocActor == null ? "Unknown" : topicAssocActor ;
        }
        StringBuilder sb = new StringBuilder( "AI" ) ;
        if( topicAssocAiModel != null ) sb.append( ' ' ).append( topicAssocAiModel ) ;
        if( topicAssocConfidence != null ) {
            sb.append( ' ' ).append( topicAssocConfidence ).append( '%' ) ;
        }
        return sb.toString() ;
    }
}
