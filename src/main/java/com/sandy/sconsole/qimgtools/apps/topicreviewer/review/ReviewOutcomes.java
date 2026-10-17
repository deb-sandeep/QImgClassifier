package com.sandy.sconsole.qimgtools.apps.topicreviewer.review;

import com.sandy.sconsole.qimgtools.core.ai.AIInvoker;
import com.sandy.sconsole.qimgtools.core.ai.AIProcessor;
import com.sandy.sconsole.qimgtools.core.scheduler.TaskOutcomes;
import com.sandy.sconsole.qimgtools.core.model.AIAgent;
import com.sandy.sconsole.qimgtools.core.model.AttemptRequest;
import com.sandy.sconsole.qimgtools.core.model.AttemptTranscript;
import com.sandy.sconsole.qimgtools.core.model.QTask;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewItem;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewVerdict;
import com.sandy.sconsole.qimgtools.core.model.Topic;
import com.sandy.sconsole.qimgtools.core.model.TopicRepo;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.workspace.ReviewLog;
import com.sandy.sconsole.qimgtools.core.workspace.TranscriptStore;
import com.sandy.sconsole.qimgtools.core.workspace.UsageLog;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What happens to a question of the topic review around its AI attempts:
 * transcripts and token counts are saved as the classifier saves them, and
 * the model's reply becomes a {@link ReviewVerdict}, stored in the
 * {@link ReviewLog} and reported to the listener. Nothing is written to the
 * question bank or the server here; that waits for the user's decision.
 * Thread-safe.
 */
@Slf4j
public class ReviewOutcomes implements TaskOutcomes {

    private final ReviewListener  listener ;
    private final ReviewLog       reviewLog ;
    private final TranscriptStore transcripts ;
    private final UsageLog        usageLog ;
    private final TopicRepo       topicRepo ;

    /** The item each submitted task belongs to. */
    private final Map<QTask,ReviewItem> items = new ConcurrentHashMap<>() ;

    public ReviewOutcomes( ReviewListener listener, ReviewLog reviewLog,
                           TranscriptStore transcripts, UsageLog usageLog,
                           TopicRepo topicRepo ) {

        this.listener    = listener ;
        this.reviewLog   = reviewLog ;
        this.transcripts = transcripts ;
        this.usageLog    = usageLog ;
        this.topicRepo   = topicRepo ;
    }

    /** Remember which item {@code item.task()} belongs to; call on submit. */
    public void track( ReviewItem item ) {
        items.put( item.task(), item ) ;
    }

    @Override
    public void started( QTask task ) {
        listener.itemStarted( items.get( task ) ) ;
    }

    /**
     * Keep the topics the server knows for the question's syllabus (with the
     * server's names), store the verdict and report it; a reply naming no
     * such topic fails the question.
     */
    @Override
    public boolean succeeded( QTask task, List<Topic> topics, AIAgent agent,
                              String model, int attempts ) {

        ReviewItem  item  = items.get( task ) ;
        String      sylb  = item.question().getSyllabusName() ;
        List<Topic> known = new ArrayList<>() ;

        for( Topic t : topics ) {
            Topic master = topicRepo.getTopicById( t.getId() ) ;
            if( master == null || !master.getSyllabusName().equals( sylb ) ) {
                log.warn( "[{}] Ignoring topic {} - not a {} topic", task,
                          t.getId(), sylb ) ;
                continue ;
            }
            t.setTopicName( master.getTopicName() ) ;
            t.setSection( master.getSection() ) ;
            t.setSyllabusName( sylb ) ;
            known.add( t ) ;
        }
        if( known.isEmpty() ) {
            listener.itemFailed( item, attempts, "the model named no known topic" ) ;
            return false ;
        }

        ReviewVerdict verdict = new ReviewVerdict( model, known ) ;
        try {
            reviewLog.saveVerdict( item.question(), verdict ) ;
        }
        catch( Exception e ) {
            log.error( "[{}] Could not store the verdict", task, e ) ;
        }
        listener.verdictReached( item, verdict ) ;
        return true ;
    }

    @Override
    public void failed( QTask task, int attempts, String reason ) {
        listener.itemFailed( items.get( task ), attempts, reason ) ;
    }

    /** There is no second opinion in a review. */
    @Override
    public boolean needsSecondOpinion( QTask task, List<Topic> topics ) {
        return false ;
    }

    @Override
    public AIInvoker.AttemptListener attemptListener( QTask task ) {

        ReviewItem item = items.get( task ) ;
        return new AIInvoker.AttemptListener() {
            @Override
            public void attemptStarted( AIProcessor p, String model, int attempt ) {
                listener.itemAttempt( item, model, attempt ) ;
            }

            @Override
            public void requestSent( AttemptRequest request ) {
                listener.requestSent( request ) ;
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
                usageLog.append( transcript ) ;
                listener.attemptFinished( item ) ;
            }
        } ;
    }
}
