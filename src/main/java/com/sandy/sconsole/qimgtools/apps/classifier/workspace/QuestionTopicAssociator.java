package com.sandy.sconsole.qimgtools.apps.classifier.workspace;

import com.sandy.sconsole.qimgtools.core.model.Topic;
import com.sandy.sconsole.qimgtools.core.model.TopicRepo;
import com.sandy.sconsole.qimgtools.apps.classifier.workspace.WorkspaceTopicMap.StoredMapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.sandy.sconsole.qimgtools.core.workspace.JsonFiles;

/**
 * Writes topics into a source's {@code .workspace/question-info.json} (the
 * file QImgExtractor owns), so QImgExtractor shows the questions as mapped.
 * <p>
 * Auto association follows QImgExtractor's {@code AutoTopicAssociator}: it
 * assigns the top stored AI recommendation whatever its confidence, as the
 * associations are reviewed later by a stronger model. Recommendations are
 * taken confidence-descending, and the first whose topic is in the topic
 * master is chosen.
 * Auto association never changes a question that already has a topic;
 * {@link #associateManual} always overwrites. Each written topic records who
 * chose it:
 * <pre>
 * "topic" : { "id" : 214, "name" : "...", "syllabusName" : "...",
 *             "topicAssociator" : { "actor" : "AI",
 *                                   "model" : "gemma4:latest",
 *                                   "confidence" : 96 } }
 * </pre>
 * {@code actor} is {@code AI} or {@code User}; a user's choice has
 * confidence 100 and a null model. Sources without a
 * question-info.json (never opened in QImgExtractor) are skipped.
 * <p>
 * <b>Caveat:</b> if the source is open in QImgExtractor while it is being
 * classified, QImgExtractor's next save rewrites question-info.json from its
 * in-memory model and drops these topics. Reopen the project in QImgExtractor
 * after classification.
 */
@Slf4j
@NoArgsConstructor( access = AccessLevel.PRIVATE )
public final class QuestionTopicAssociator {

    /** Result of an association attempt. */
    public enum Outcome {

        /** The topic was written. */
        ASSOCIATED,

        /** Auto association only: the question already has a topic. */
        ALREADY_SET,

        /**
         * Auto association only: none of the recommended topics is in the
         * topic master.
         */
        UNRESOLVED_TOPIC,

        /** Auto association only: no stored recommendation for the question. */
        NO_RECOMMENDATION,

        /** The source has no question-info.json. */
        NO_QUESTION_INFO,

        /** question-info.json has no entry for the question. */
        NOT_IN_QUESTION_INFO,

        /** question-info.json could not be read or written (logged). */
        ERROR
    }

    /**
     * Result-column suffix: the question's topic in question-info.json matches
     * the AI's top topic.
     */
    public static final String MATCHES_AI_MARK = " ✓" ;

    /**
     * Result-column suffix: the question's topic in question-info.json differs
     * from the AI's top topic.
     */
    public static final String DIFFERS_FROM_AI_MARK = " ≠" ;

    /**
     * Question id -> topic id assigned in the source's question-info.json, for
     * every question that has a topic. Empty if the file does not exist or
     * cannot be read.
     */
    public static Map<String,Integer> assignedTopicIds( File srcDir ) {

        File file = infoFile( srcDir ) ;
        Map<String,Integer> ids = new HashMap<>() ;

        if( !file.exists() ) return ids ;
        synchronized( JsonFiles.lockFor( file ) ) {
            try {
                JsonNode questions = JsonFiles.readObject( file ).path( "questions" ) ;
                for( JsonNode q : questions ) {
                    JsonNode topic = q.get( "topic" ) ;
                    if( topic != null && !topic.isNull() && topic.has( "id" ) ) {
                        ids.put( toQuestionId( q.path( "qid" ).asText() ),
                                 topic.path( "id" ).asInt() ) ;
                    }
                }
            }
            catch( IOException e ) {
                log.error( "Error reading {}", file, e ) ;
            }
        }
        return ids ;
    }

    /**
     * Result-column suffix comparing the assigned topic with the AI's top
     * topic: {@link #MATCHES_AI_MARK}, {@link #DIFFERS_FROM_AI_MARK}, or ""
     * when no topic is assigned.
     */
    public static String indicator( Integer assignedTopicId, Integer aiTopTopicId ) {
        if( assignedTopicId == null ) return "" ;
        return assignedTopicId.equals( aiTopTopicId ) ? MATCHES_AI_MARK
                                                      : DIFFERS_FROM_AI_MARK ;
    }

    /**
     * Who chose a written topic: {@code actor}, {@code model},
     * {@code confidence}.
     */
    private record Associator( String actor, String model, int confidence ) {}

    /**
     * Associate the question's top stored AI topic, whatever its confidence,
     * if the question has no topic yet. The associator recorded is the model
     * named in the map's {@code classifiedBy}, with the chosen topic's
     * confidence.
     */
    public static Outcome associate( File srcDir, String questionId ) {

        if( !infoFile( srcDir ).exists() ) {
            return logged( questionId, Outcome.NO_QUESTION_INFO ) ;
        }

        List<StoredMapping> mappings =
                WorkspaceTopicMap.topicMappings( srcDir, questionId ) ;
        if( mappings.isEmpty() ) {
            return logged( questionId, Outcome.NO_RECOMMENDATION ) ;
        }

        for( StoredMapping m : mappings ) {
            Topic topic = TopicRepo.instance().getTopicById( m.topicId() ) ;
            if( topic == null ) continue ;

            Associator by = new Associator(
                    "AI", WorkspaceTopicMap.classifierModel( srcDir, questionId ),
                    m.confidenceLevel() ) ;
            return writeTopic( srcDir, questionId, topic, by, false ) ;
        }
        return logged( questionId, Outcome.UNRESOLVED_TOPIC ) ;
    }

    /**
     * Associate a hand-picked topic, bypassing the quality gate and replacing
     * any topic the question has.
     */
    public static Outcome associateManual( File srcDir, String questionId,
                                           Topic topic ) {
        if( !infoFile( srcDir ).exists() ) {
            return logged( questionId, Outcome.NO_QUESTION_INFO ) ;
        }
        Associator by = new Associator( "User", null, 100 ) ;
        return writeTopic( srcDir, questionId, topic, by, true ) ;
    }

    private static File infoFile( File srcDir ) {
        return new File( srcDir, ".workspace/question-info.json" ) ;
    }

    /**
     * question-info.json identifies questions as {@code C/SCA/74}; this app
     * uses {@code C_SCA_74}.
     */
    private static String toQuestionId( String qid ) {
        return qid.replace( '/', '_' ) ;
    }

    private static String toQid( String questionId ) {
        return questionId.replace( '_', '/' ) ;
    }

    private static Outcome writeTopic( File srcDir, String questionId,
                                       Topic topic, Associator by,
                                       boolean overwrite ) {

        File file = infoFile( srcDir ) ;
        String qid = toQid( questionId ) ;

        synchronized( JsonFiles.lockFor( file ) ) {
            try {
                ObjectNode root = JsonFiles.readObject( file ) ;
                ObjectNode question = null ;

                for( JsonNode q : root.path( "questions" ) ) {
                    if( qid.equals( q.path( "qid" ).asText() )
                        && q instanceof ObjectNode obj ) {
                        question = obj ;
                        break ;
                    }
                }
                if( question == null ) {
                    return logged( questionId, Outcome.NOT_IN_QUESTION_INFO ) ;
                }

                JsonNode existing = question.get( "topic" ) ;
                if( !overwrite && existing != null && !existing.isNull() ) {
                    return logged( questionId, Outcome.ALREADY_SET ) ;
                }

                ObjectNode topicNode = question.putObject( "topic" )
                        .put( "id",           topic.getId() )
                        .put( "name",         topic.getTopicName() )
                        .put( "syllabusName", topic.getSyllabusName() ) ;
                topicNode.putObject( "topicAssociator" )
                         .put( "actor",      by.actor() )
                         .put( "model",      by.model() )
                         .put( "confidence", by.confidence() ) ;
                JsonFiles.writeAtomically( file, root ) ;
            }
            catch( IOException e ) {
                log.error( "[{}] Error associating topic in {}",
                           questionId, file, e ) ;
                return Outcome.ERROR ;
            }
        }
        log.info( "[{}] {} {}-associated with topic {} ({}) - {} {} {}%",
                  srcDir.getName(), questionId, overwrite ? "manually" : "auto",
                  topic.getId(), topic.getTopicName(), by.actor(), by.model(),
                  by.confidence() ) ;
        return Outcome.ASSOCIATED ;
    }

    private static Outcome logged( String questionId, Outcome outcome ) {
        log.debug( "[{}] Topic association: {}", questionId, outcome ) ;
        return outcome ;
    }
}
