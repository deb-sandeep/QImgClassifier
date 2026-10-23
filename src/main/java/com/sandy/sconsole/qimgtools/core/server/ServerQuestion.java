package com.sandy.sconsole.qimgtools.core.server;

import com.sandy.sconsole.qimgtools.core.util.ModelNames;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * A question on the SConsole server: the fields of the server's
 * {@code QuestionVO} that the topic reviewer and the tagger use (the rest are
 * ignored when it is read).
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

    /** The answer key, as the image prints the options (e.g. "B", "1,3"). */
    private String answer ;

    /** The question's number in its source. */
    private Integer questionNumber ;

    /** Position in its LCT group; -1 for a question not in one. */
    private Integer lctSequence ;

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

    /**
     * True while the question awaits the tagger's setup pass; set only by
     * the server, cleared by a setup refresh.
     */
    private boolean autoSetupTaggingNeeded ;

    /**
     * True while the question awaits the tagger's concept pass, in an exam
     * or not (the concept queue holds only those in an exam); set only by
     * the server, cleared by a concept refresh.
     */
    private boolean autoConceptTaggingNeeded ;

    /**
     * The other topics of its syllabus the question's solution depends on;
     * null where the server didn't load them (every fetch but the tagger's).
     */
    private List<TopicDependency> topicDependencies ;

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
