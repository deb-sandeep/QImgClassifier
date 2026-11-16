package com.sandy.sconsole.qimgtools.apps.classifier.pipeline;

import com.sandy.sconsole.qimgtools.core.model.AIAgent;
import com.sandy.sconsole.qimgtools.core.model.AttemptRequest;
import com.sandy.sconsole.qimgtools.core.model.AttemptTranscript;
import com.sandy.sconsole.qimgtools.core.model.QTask;
import com.sandy.sconsole.qimgtools.apps.classifier.model.TaskStatus;
import com.sandy.sconsole.qimgtools.core.model.Topic;
import com.sandy.sconsole.qimgtools.core.util.FileHelper;
import com.sandy.sconsole.qimgtools.apps.classifier.workspace.ProcessedList;
import com.sandy.sconsole.qimgtools.core.workspace.TranscriptStore;
import com.sandy.sconsole.qimgtools.core.workspace.UsageLog;
import com.sandy.sconsole.qimgtools.apps.classifier.workspace.QuestionTopicAssociator;
import com.sandy.sconsole.qimgtools.apps.classifier.workspace.WorkspaceTopicMap;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.sandy.sconsole.qimgtools.core.scheduler.AttemptListener;
import com.sandy.sconsole.qimgtools.core.scheduler.TaskOutcomes;
import com.sandy.sconsole.qimgtools.core.scheduler.TaskScheduler;

/**
 * What happens to a single question around its AI attempts: lifecycle events,
 * transcripts, storing the result and auto association, and keeping the
 * processed-sources list in step. The attempts themselves are scheduled by
 * {@link TaskScheduler}. Thread-safe.
 */
@Slf4j
public class QuestionClassifier implements TaskOutcomes<List<Topic>> {

    /** The root Physics and Maths questions start on (Qwen). */
    public static final String PHY_MATHS = "phy-maths" ;

    /** The root Chemistry questions start on (the free Nemotron). */
    public static final String CHEM = "chem" ;

    @Getter
    private final ClassificationListener listener ;
    private final ProcessedList          processedList ;
    private final TranscriptStore        transcripts ;
    private final UsageLog               usageLog ;
    private final String                 questionFilter ;

    /**
     * @param listener       receives task lifecycle events; may be null
     * @param transcripts    where each attempt's request and response are saved
     * @param usageLog       where each attempt's token counts are added; may be
     *         null
     * @param questionFilter debug filter on question ids (the
     *         {@code question.filter} setting)
     */
    public QuestionClassifier( ClassificationListener listener,
                               ProcessedList processedList,
                               TranscriptStore transcripts, UsageLog usageLog,
                               String questionFilter ) {

        this.listener       = listener != null ? listener
                                               : ClassificationListener.NOOP ;
        this.processedList  = processedList ;
        this.transcripts    = transcripts ;
        this.usageLog       = usageLog ;
        this.questionFilter = questionFilter ;
    }

    /**
     * The source's question images grouped by base question id, honouring the
     * question filter; null if the source has no {@code question-images}
     * folder.
     */
    public Map<String,List<File>> questionImages( File srcDir ) {
        return FileHelper.getQuestionImgGroups( srcDir, questionFilter ) ;
    }

    /**
     * Physics and Maths start on {@link #PHY_MATHS}: they classify accurately
     * on a smaller model; {@link #CHEM}'s models may borrow them in their
     * spare time ({@link ClassifierRouting}). Chemistry, which is trickier
     * and needs a larger model, and a question of no known subject start on
     * {@link #CHEM}. Each model takes its own questions in the order they
     * were submitted.
     */
    @Override
    public List<String> roots( QTask task ) {
        return switch( task.getSubject() ) {
            case PHYSICS, MATHS     -> List.of( PHY_MATHS, CHEM ) ;
            case CHEMISTRY, UNKNOWN -> List.of( CHEM ) ;
        } ;
    }

    @Override
    public void started( QTask task ) {
        listener.taskStarted( task ) ;
    }

    /**
     * Store a successful classification in the source's
     * {@code ai-topic-map.json}, attempt auto association into
     * {@code question-info.json}, fire {@code taskFinished}, and add the source
     * to the processed list if that was its last unclassified question. Never
     * throws.
     *
     * @param agent the backend and {@code model} the model that gave
     *        {@code topics}; stored with them, and with an auto association
     * @return true if the result was stored
     */
    @Override
    public boolean succeeded( QTask task, List<Topic> topics, AIAgent agent,
                              String model, int attempts ) {

        try {
            WorkspaceTopicMap.write( task.getSrcDir(), task.getQuestionId(), topics,
                                     agent, model ) ;
            listener.taskFinished( task, TaskStatus.DONE, attempts,
                                   summarise( task, topics ) ) ;
            markProcessedIfComplete( task.getSrcDir() ) ;
            return true ;
        }
        catch( Exception e ) {
            log.error( "Error storing the classification of {}", task, e ) ;
            listener.taskFinished( task, TaskStatus.FAILED, attempts, describe( e ) ) ;
            return false ;
        }
    }

    @Override
    public void failed( QTask task, int attempts, String reason ) {
        listener.taskFinished( task, TaskStatus.FAILED, attempts, reason ) ;
    }

    /** Every reply is kept as it comes: no second opinion is asked. */
    @Override
    public String secondOpinionRoot( QTask task, List<Topic> topics,
                                     AIAgent agent, String model ) {
        return null ;
    }

    /**
     * Forwards attempt starts and sent requests to the listener, and saves
     * each attempt's transcript and token counts.
     */
    @Override
    public AttemptListener attemptListener( QTask task ) {

        return new AttemptListener() {
            @Override
            public void attemptStarted( AIAgent agent, String model,
                                        int attempt ) {
                listener.taskAttempt( task, agent, model, attempt ) ;
            }

            @Override
            public void requestSent( AttemptRequest request ) {
                listener.taskRequestSent( request ) ;
            }

            @Override
            public void attemptFinished( AttemptTranscript transcript ) {

                try {
                    transcripts.save( transcript ) ;
                }
                catch( Exception e ) {
                    log.error( "[{}] Could not save the transcript of attempt {}",
                               task, transcript.getAttempt(), e ) ;
                }
                if( usageLog != null ) usageLog.append( transcript ) ;
                listener.taskAttemptFinished( task ) ;
            }
        } ;
    }

    /**
     * Add the source to the processed list if it has been synced to the
     * server, or if every one of its questions is classified and has a topic
     * in question-info.json.
     */
    public void markProcessedIfComplete( File srcDir ) {

        if( QuestionTopicAssociator.isServerSynced( srcDir ) ) {
            processedList.add( srcDir.getName() ) ;
            return ;
        }
        Map<String,List<File>> groups = questionImages( srcDir ) ;
        if( groups == null || groups.isEmpty() ) return ;

        Set<String> classified = WorkspaceTopicMap.classifiedQuestionIds( srcDir ) ;
        Set<String> assigned   =
                QuestionTopicAssociator.assignedTopicIds( srcDir ).keySet() ;

        if( classified.containsAll( groups.keySet() )
            && assigned.containsAll( groups.keySet() ) ) {
            processedList.add( srcDir.getName() ) ;
        }
    }

    /**
     * Result-column text for a fresh classification: the summary of the topic
     * chosen as stored (see {@link WorkspaceTopicMap#associatedSummary}),
     * plus {@link QuestionTopicAssociator#MATCHES_AI_MARK} if auto association
     * wrote it into question-info.json. An association failure is logged but
     * does not fail the task - the classification itself is already stored.
     */
    private static String summarise( QTask task, List<Topic> topics ) {

        if( topics.isEmpty() ) return "" ;
        String summary = WorkspaceTopicMap.associatedSummary(
                task.getSrcDir(), task.getQuestionId() ) ;

        try {
            QuestionTopicAssociator.Outcome outcome = QuestionTopicAssociator
                    .associate( task.getSrcDir(), task.getQuestionId() ) ;
            if( outcome == QuestionTopicAssociator.Outcome.ASSOCIATED ) {
                summary += QuestionTopicAssociator.MATCHES_AI_MARK ;
            }
        }
        catch( Exception e ) {
            log.error( "[{}] Topic auto-association failed", task, e ) ;
        }
        return summary ;
    }

    /** Short failure text for the Result column: "ExceptionType: message". */
    private static String describe( Exception e ) {
        String msg = e.getMessage() ;
        return e.getClass().getSimpleName() + ( msg != null ? ": " + msg : "" ) ;
    }
}
