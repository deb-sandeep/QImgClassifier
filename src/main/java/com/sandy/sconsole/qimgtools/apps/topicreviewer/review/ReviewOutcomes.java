package com.sandy.sconsole.qimgtools.apps.topicreviewer.review;

import com.sandy.sconsole.qimgtools.core.scheduler.AttemptListener;
import com.sandy.sconsole.qimgtools.core.scheduler.TaskOutcomes;
import com.sandy.sconsole.qimgtools.core.model.AIAgent;
import com.sandy.sconsole.qimgtools.core.model.AttemptRequest;
import com.sandy.sconsole.qimgtools.core.model.AttemptTranscript;
import com.sandy.sconsole.qimgtools.core.model.QTask;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewItem;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewOpinions;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewVerdict;
import com.sandy.sconsole.qimgtools.core.model.Topic;
import com.sandy.sconsole.qimgtools.core.model.TopicRepo;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.workspace.PendingVerdicts;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.workspace.ReviewAuditLog;
import com.sandy.sconsole.qimgtools.core.workspace.TranscriptStore;
import com.sandy.sconsole.qimgtools.core.workspace.UsageLog;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What happens to a question of the topic review around its AI attempts:
 * transcripts and token counts are saved as the classifier saves them, and
 * the model's reply becomes a {@link ReviewVerdict}. A verdict whose topic
 * differs from the server's is escalated to the {@link #TIE_BREAKER_ROOT},
 * a stronger model whose verdict is kept beside it. The two become
 * {@link ReviewOpinions}, stored in {@link PendingVerdicts}, recorded in the
 * {@link ReviewAuditLog} and reported to the listener. Nothing is written
 * to the question bank or the server here; that waits for the user's
 * decision. Thread-safe.
 */
@Slf4j
public class ReviewOutcomes implements TaskOutcomes<List<Topic>> {

    /** The root of the reviewer's AI config the questions go to. */
    public static final String ROOT = "review" ;

    /** The root asked to arbitrate when the reviewer differs. */
    public static final String TIE_BREAKER_ROOT = "tieBreaker" ;

    private final ReviewListener  listener ;
    private final PendingVerdicts pending ;
    private final ReviewAuditLog  audit ;
    private final TranscriptStore transcripts ;
    private final UsageLog        usageLog ;
    private final TopicRepo       topicRepo ;

    /** The item each submitted task belongs to. */
    private final Map<QTask,ReviewItem> items = new ConcurrentHashMap<>() ;

    /** The tasks escalated to the tie-breaker, with what is known so far. */
    private final Map<QTask,TieBreak> tieBreaks = new ConcurrentHashMap<>() ;

    /** A task sent to the tie-breaker: the reviewer's verdict, and since. */
    private static final class TieBreak {

        final ReviewVerdict reviewer ;

        /** The tie-breaker's verdict, once it has given one. */
        volatile ReviewVerdict verdict ;

        /** Why its latest attempt failed; null if none has. */
        volatile String failure ;

        TieBreak( ReviewVerdict reviewer ) {
            this.reviewer = reviewer ;
        }
    }

    public ReviewOutcomes( ReviewListener listener, PendingVerdicts pending,
                           ReviewAuditLog audit, TranscriptStore transcripts,
                           UsageLog usageLog, TopicRepo topicRepo ) {

        this.listener    = listener ;
        this.pending     = pending ;
        this.audit       = audit ;
        this.transcripts = transcripts ;
        this.usageLog    = usageLog ;
        this.topicRepo   = topicRepo ;
    }

    /** Remember which item {@code item.task()} belongs to; call on submit. */
    public void track( ReviewItem item ) {
        items.put( item.task(), item ) ;
        tieBreaks.remove( item.task() ) ;
    }

    @Override
    public List<String> roots( QTask task ) {
        return List.of( ROOT ) ;
    }

    @Override
    public void started( QTask task ) {
        listener.itemStarted( items.get( task ) ) ;
    }

    /**
     * The tie-breaker's root for the reviewer's {@code topics} if the topic
     * chosen from them is not the server's; null for the tie-breaker's own
     * reply, and for a reply {@link #succeeded} will fail.
     */
    @Override
    public String secondOpinionRoot( QTask task, List<Topic> topics,
                                     AIAgent agent, String model ) {

        TieBreak tb = tieBreaks.get( task ) ;
        if( tb != null ) {
            tb.verdict = verdictOf( task, topics, model ) ;
            if( tb.verdict == null ) {
                tb.failure = "the tie-breaker named no known topic" ;
            }
            return null ;
        }

        ReviewItem    item    = items.get( task ) ;
        ReviewVerdict verdict = verdictOf( task, topics, model ) ;
        if( verdict == null
            || verdict.agreesWith( item.question().getTopicId() ) ) {
            return null ;
        }
        tieBreaks.put( task, new TieBreak( verdict ) ) ;
        listener.reviewerDiffers( item, verdict ) ;
        log.info( "[{}] {} chose {}, the server has {} - asking the " +
                  "tie-breaker", task, model, verdict.top().getId(),
                  item.question().getTopicId() ) ;
        return TIE_BREAKER_ROOT ;
    }

    @Override
    public String secondOpinionReason() {
        return "the reviewer's topic differs from the server's" ;
    }

    /**
     * The verdict of {@code model} recommending {@code topics}, limited to
     * the topics of the question's syllabus; null if none is one, or the
     * topic sequence can't be read.
     */
    private ReviewVerdict verdictOf( QTask task, List<Topic> topics,
                                     String model ) {

        List<Topic> known = known( task, topics ) ;
        if( known.isEmpty() ) return null ;
        try {
            return ReviewVerdict.of( model, known ) ;
        }
        catch( IOException e ) {
            log.error( "[{}] Could not choose the topic: {}", task,
                       e.getMessage() ) ;
            return null ;
        }
    }

    /**
     * Those of {@code topics} the server knows for the question's syllabus,
     * with the server's names.
     */
    private List<Topic> known( QTask task, List<Topic> topics ) {

        String      sylb  = items.get( task ).question().getSyllabusName() ;
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
        return known ;
    }

    /**
     * Turn the reply into the question's opinions, store them and report
     * them. After a tie-break {@code topics} are ignored: the reviewer's
     * verdict and the tie-breaker's (if it gave one) were taken as they
     * came. A reply naming no topic of the question's syllabus fails the
     * question, as does a topic sequence that can't be read.
     */
    @Override
    public boolean succeeded( QTask task, List<Topic> topics, AIAgent agent,
                              String model, int attempts ) {

        ReviewItem     item = items.get( task ) ;
        TieBreak       tb   = tieBreaks.remove( task ) ;
        ReviewOpinions opinions ;

        if( tb != null ) {
            String failure = tb.verdict != null ? null
                           : tb.failure != null ? tb.failure
                           : "the tie-breaker gave no answer" ;
            opinions = new ReviewOpinions( tb.reviewer, tb.verdict, failure ) ;
        }
        else {
            if( known( task, topics ).isEmpty() ) {
                listener.itemFailed( item, attempts,
                                     "the model named no known topic" ) ;
                return false ;
            }
            ReviewVerdict verdict = verdictOf( task, topics, model ) ;
            if( verdict == null ) {
                listener.itemFailed( item, attempts,
                                     "the topic sequence could not be read" ) ;
                return false ;
            }
            opinions = ReviewOpinions.of( verdict ) ;
        }
        try {
            pending.save( item.question(), opinions ) ;
        }
        catch( Exception e ) {
            log.error( "[{}] Could not store the verdict", task, e ) ;
        }
        try {
            audit.verdict( item.question(), opinions ) ;
        }
        catch( Exception e ) {
            log.error( "[{}] Could not record the verdict in the audit log",
                       task, e ) ;
        }
        listener.verdictReached( item, opinions ) ;
        return true ;
    }

    @Override
    public void failed( QTask task, int attempts, String reason ) {
        tieBreaks.remove( task ) ;
        listener.itemFailed( items.get( task ), attempts, reason ) ;
    }

    @Override
    public AttemptListener attemptListener( QTask task ) {

        ReviewItem item = items.get( task ) ;
        return new AttemptListener() {
            @Override
            public void attemptStarted( AIAgent agent, String model,
                                        int attempt ) {
                listener.itemAttempt( item, model, attempt,
                                      tieBreaks.containsKey( task ) ) ;
            }

            @Override
            public void requestSent( AttemptRequest request ) {
                listener.requestSent( request ) ;
            }

            @Override
            public void attemptFinished( AttemptTranscript transcript ) {

                TieBreak tb = tieBreaks.get( task ) ;
                if( tb != null && transcript.getFailure() != null ) {
                    tb.failure = transcript.getFailure() ;
                }
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
