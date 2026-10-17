package com.sandy.sconsole.qimgtools.apps.classifier.pipeline;

import com.sandy.sconsole.qimgtools.core.model.QTask;
import com.sandy.sconsole.qimgtools.apps.classifier.workspace.QuestionTopicAssociator;
import com.sandy.sconsole.qimgtools.apps.classifier.workspace.WorkspaceTopicMap;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
     * images are folded into their parent question).
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
        Map<String,Integer> aiTopIds    =
                WorkspaceTopicMap.topTopicIds( srcDir ) ;
        Map<String,Integer> assignedIds =
                QuestionTopicAssociator.assignedTopicIds( srcDir ) ;
        ClassificationListener listener = classifier.getListener() ;

        List<QTask> pending = new ArrayList<>() ;
        for( Map.Entry<String,List<File>> e : groups.entrySet() ) {
            String qId  = e.getKey() ;
            QTask  task = new QTask( srcDir, qId, e.getValue() ) ;
            String storedResult = stored.get( qId ) ;

            if( storedResult != null ) {
                storedResult += QuestionTopicAssociator.indicator(
                        assignedIds.get( qId ), aiTopIds.get( qId ) ) ;
            }
            listener.taskDiscovered( task, storedResult ) ;
            if( storedResult == null ) pending.add( task ) ;
        }
        log.debug( "Source {} - {} of {} questions pending",
                   srcDir.getName(), pending.size(), groups.size() ) ;
        return pending ;
    }
}
