package com.sandy.sconsole.qimgtools.apps.classifier.pipeline;

import com.sandy.sconsole.qimgtools.core.model.QTask;
import com.sandy.sconsole.qimgtools.apps.classifier.workspace.QuestionTopicAssociator;
import com.sandy.sconsole.qimgtools.apps.classifier.workspace.WorkspaceTopicMap;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Discovers the questions of one source folder. Classification is scheduled
 * across all sources by {@link TaskScheduler}.
 */
@Slf4j
public class QSourceClassifier {

    @Getter
    private final File               srcDir ;
    private final QuestionClassifier classifier ;

    public QSourceClassifier( File srcDir, QuestionClassifier classifier ) {
        this.srcDir     = srcDir ;
        this.classifier = classifier ;
    }

    public String getSourceName() { return srcDir.getName() ; }

    /**
     * Fire {@code taskDiscovered} for every question of this source (part
     * images are folded into their parent question). A classified question
     * with no topic in question-info.json (classified before association
     * took every reply, or its topic lost to a QImgExtractor save) gets the
     * topic chosen from its stored recommendations first, with no AI call.
     *
     * @return the tasks with no stored classification, in question order
     */
    public List<QTask> discover() {

        Map<String,List<File>> groups = classifier.questionImages( srcDir ) ;
        if( groups == null ) {
            log.error( "No question-images folder in source {}", srcDir.getName() ) ;
            return List.of() ;
        }

        Map<String,String>  stored      =
                WorkspaceTopicMap.associatedSummaries( srcDir ) ;
        Map<String,Integer> aiTopicIds  =
                WorkspaceTopicMap.chosenTopicIds( srcDir ) ;
        Map<String,Integer> assignedIds =
                QuestionTopicAssociator.assignedTopicIds( srcDir ) ;
        ClassificationListener listener = classifier.getListener() ;

        Set<String> classified = new HashSet<>( stored.keySet() ) ;
        classified.retainAll( groups.keySet() ) ;
        associateMissing( classified, assignedIds, aiTopicIds ) ;

        List<QTask> pending = new ArrayList<>() ;
        for( Map.Entry<String,List<File>> e : groups.entrySet() ) {
            String qId  = e.getKey() ;
            QTask  task = new QTask( srcDir, qId, e.getValue() ) ;
            String storedResult = stored.get( qId ) ;

            if( storedResult != null ) {
                storedResult += QuestionTopicAssociator.indicator(
                        assignedIds.get( qId ), aiTopicIds.get( qId ) ) ;
            }
            listener.taskDiscovered( task, storedResult ) ;
            if( storedResult == null ) pending.add( task ) ;
        }
        log.debug( "Source {} - {} of {} questions pending",
                   srcDir.getName(), pending.size(), groups.size() ) ;
        return pending ;
    }

    /**
     * Associate the topic of each of {@code classified} that has none in
     * {@code assignedIds}, adding the ones written to it.
     */
    private void associateMissing( Set<String> classified,
                                   Map<String,Integer> assignedIds,
                                   Map<String,Integer> aiTopicIds ) {

        int associated = 0 ;
        for( String qId : classified ) {
            if( assignedIds.containsKey( qId ) ) continue ;

            QuestionTopicAssociator.Outcome outcome =
                    QuestionTopicAssociator.associate( srcDir, qId ) ;
            if( outcome == QuestionTopicAssociator.Outcome.ASSOCIATED ) {
                assignedIds.put( qId, aiTopicIds.get( qId ) ) ;
                associated++ ;
            }
            else {
                log.warn( "Source {} - {} is classified but has no topic: {}",
                          srcDir.getName(), qId, outcome ) ;
            }
        }
        if( associated > 0 ) {
            log.info( "Source {} - associated the topics of {} classified " +
                      "questions", srcDir.getName(), associated ) ;
        }
    }
}
