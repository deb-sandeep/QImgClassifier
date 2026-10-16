package com.sandy.sconsole.qimgclassifier.classifier;

import com.sandy.sconsole.qimgclassifier.ai.AIInvoker;
import com.sandy.sconsole.qimgclassifier.model.QTask;
import com.sandy.sconsole.qimgclassifier.model.TaskStatus;
import com.sandy.sconsole.qimgclassifier.model.Topic;
import com.sandy.sconsole.qimgclassifier.util.FileHelper;
import com.sandy.sconsole.qimgclassifier.workspace.ProcessedList;
import com.sandy.sconsole.qimgclassifier.workspace.QuestionTopicAssociator;
import com.sandy.sconsole.qimgclassifier.workspace.WorkspaceTopicMap;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;

/**
 * Classifies single questions and keeps the processed-sources list in step.
 * Shared by every {@link QSourceClassifier} and by the UI's re-queue and
 * manual-association actions. Thread-safe.
 */
@Slf4j
public class QuestionClassifier {

    private final AIInvoker              aiInvoker ;
    @Getter
    private final ClassificationListener listener ;
    private final ProcessedList          processedList ;
    private final String                 questionFilter ;

    /**
     * @param listener       receives task lifecycle events; may be null
     * @param questionFilter debug filter on question ids (see
     *         {@code QClassifierConfig.questionFilter})
     */
    public QuestionClassifier( AIInvoker aiInvoker, ClassificationListener listener,
                               ProcessedList processedList, String questionFilter ) {
        this.aiInvoker      = aiInvoker ;
        this.listener       = listener != null ? listener
                                               : ClassificationListener.NOOP ;
        this.processedList  = processedList ;
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
     * A callable that classifies {@code task}: invokes the model, stores the
     * recommendations in the source's {@code ai-topic-map.json}, attempts auto
     * association into {@code question-info.json}, and fires
     * {@code taskStarted} / {@code taskFinished}. After a successful
     * classification the source is added to the processed list if that was
     * its last unclassified question. Never throws.
     *
     * @param keepGoing consulted before a retry; false suppresses it
     * @return a callable yielding true if the task was classified and stored
     */
    public Callable<Boolean> classifyCallable( QTask task,
                                               BooleanSupplier keepGoing ) {

        return () -> {
            listener.taskStarted( task ) ;
            try {
                AIInvoker.InvocationResult r = aiInvoker.invoke( task, keepGoing ) ;
                if( !r.isSuccess() ) {
                    listener.taskFinished( task, TaskStatus.FAILED,
                                           r.getAttempts(), r.getFailureReason() ) ;
                    return false ;
                }
                WorkspaceTopicMap.write( task.getSrcDir(), task.getQuestionId(),
                                         r.getTopics() ) ;
                listener.taskFinished( task, TaskStatus.DONE, r.getAttempts(),
                                       summarise( task, r.getTopics() ) ) ;
                markProcessedIfComplete( task.getSrcDir() ) ;
                return true ;
            }
            catch( Exception e ) {
                log.error( "Error classifying {}", task, e ) ;
                listener.taskFinished( task, TaskStatus.FAILED, 0, describe( e ) ) ;
                return false ;
            }
        } ;
    }

    /**
     * Add the source to the processed list if every one of its questions is
     * classified.
     */
    public void markProcessedIfComplete( File srcDir ) {

        Map<String,List<File>> groups = questionImages( srcDir ) ;
        if( groups == null || groups.isEmpty() ) return ;
        Set<String> classified = WorkspaceTopicMap.classifiedQuestionIds( srcDir ) ;
        if( classified.containsAll( groups.keySet() ) ) {
            processedList.add( srcDir.getName() ) ;
        }
    }

    /**
     * Result-column text for a fresh classification: the top topic's summary,
     * plus {@link QuestionTopicAssociator#MATCHES_AI_MARK} if auto association
     * wrote it into question-info.json. An association failure is logged but
     * does not fail the task - the classification itself is already stored.
     */
    private static String summarise( QTask task, List<Topic> topics ) {

        if( topics.isEmpty() ) return "" ;
        Topic top = topics.get( 0 ) ;
        int    confidence = Math.round( top.getMatchProbability() * 100 ) ;
        String summary    = WorkspaceTopicMap.summary( top.getTopicName(),
                                                       confidence, false ) ;
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
