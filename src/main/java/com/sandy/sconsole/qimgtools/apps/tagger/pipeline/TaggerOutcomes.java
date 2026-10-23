package com.sandy.sconsole.qimgtools.apps.tagger.pipeline;

import com.sandy.sconsole.ai.FailureKind;
import com.sandy.sconsole.qimgtools.apps.tagger.check.AnswerChecker;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagItem;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagJudgement;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagOpinions;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagReply;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TierReply;
import com.sandy.sconsole.qimgtools.apps.tagger.prompt.TagPrompter;
import com.sandy.sconsole.qimgtools.apps.tagger.prompt.TagPrompter.Assembly;
import com.sandy.sconsole.qimgtools.apps.tagger.workspace.PendingTagging;
import com.sandy.sconsole.qimgtools.apps.tagger.workspace.TagAuditLog;
import com.sandy.sconsole.qimgtools.apps.tagger.workspace.TagAuditLog.PromptStamp;
import com.sandy.sconsole.qimgtools.core.model.AIAgent;
import com.sandy.sconsole.qimgtools.core.model.AttemptRequest;
import com.sandy.sconsole.qimgtools.core.model.AttemptTranscript;
import com.sandy.sconsole.qimgtools.core.model.QTask;
import com.sandy.sconsole.qimgtools.core.scheduler.AttemptListener;
import com.sandy.sconsole.qimgtools.core.scheduler.TaskOutcomes;
import com.sandy.sconsole.qimgtools.core.workspace.TranscriptStore;
import com.sandy.sconsole.qimgtools.core.workspace.UsageLog;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What happens to a question of the tagger around its AI attempts:
 * transcripts and token counts are saved as the reviewer saves them, and
 * the replies are judged against the key. A tier 1 answer that misses the
 * key is escalated to the {@link #TIER2} root, which solves again, blind;
 * the replies become {@link TagOpinions}, judged ({@link TagJudgement}),
 * stored in {@link PendingTagging}, recorded in the {@link TagAuditLog} and
 * reported to the listener. Nothing is written to the server here; that
 * waits for Save. Thread-safe.
 */
@Slf4j
public class TaggerOutcomes implements TaskOutcomes<TagReply> {

    /** The root every question starts on. */
    public static final String TIER1 = "tier1" ;

    /** The root a question goes to when tier 1 misses the key. */
    public static final String TIER2 = "tier2" ;

    private final TaggerListener  listener ;
    private final PendingTagging  pending ;
    private final TagAuditLog     audit ;
    private final TranscriptStore transcripts ;
    private final UsageLog        usageLog ;
    private final TagPrompter     prompter ;

    /** The item each submitted task belongs to. */
    private final Map<QTask,TagItem> items = new ConcurrentHashMap<>() ;

    /** The tasks escalated to tier 2, with what is known so far. */
    private final Map<QTask,Escalation> escalations = new ConcurrentHashMap<>() ;

    /** A task sent to tier 2: tier 1's reply, and since. */
    private static final class Escalation {

        final TierReply tier1 ;

        /** Tier 2's reply, once it has given one. */
        volatile TierReply tier2 ;

        /** Why its latest attempt failed; null if none has. */
        volatile String failure ;

        Escalation( TierReply tier1 ) {
            this.tier1 = tier1 ;
        }
    }

    /** {@code prompter} asks the questions; its prompt is the scope's. */
    public TaggerOutcomes( TaggerListener listener, PendingTagging pending,
                           TagAuditLog audit, TranscriptStore transcripts,
                           UsageLog usageLog, TagPrompter prompter ) {

        this.listener    = listener ;
        this.pending     = pending ;
        this.audit       = audit ;
        this.transcripts = transcripts ;
        this.usageLog    = usageLog ;
        this.prompter    = prompter ;
    }

    /** Remember which item {@code item.task()} belongs to; call on submit. */
    public void track( TagItem item ) {
        items.put( item.task(), item ) ;
        escalations.remove( item.task() ) ;
    }

    /** The item {@code task} was submitted for; null if none. */
    public TagItem item( QTask task ) {
        return items.get( task ) ;
    }

    /** What the prompt in use is, for the logs; null before a scope. */
    public PromptStamp stamp() {

        Assembly a = prompter.assembly() ;
        return a == null ? null
               : new PromptStamp( a.catalogue().fingerprint(),
                                  a.templateVersion(), a.promptVersion() ) ;
    }

    @Override
    public List<String> roots( QTask task ) {
        return List.of( TIER1 ) ;
    }

    @Override
    public void started( QTask task ) {
        listener.itemStarted( items.get( task ) ) ;
    }

    /**
     * Tier 2's root when tier 1's answer misses the key; null for a correct
     * answer, and for tier 2's own reply.
     */
    @Override
    public String secondOpinionRoot( QTask task, TagReply reply,
                                     AIAgent agent, String model ) {

        Escalation esc = escalations.get( task ) ;
        if( esc != null ) {
            esc.tier2 = new TierReply( 2, model, reply ) ;
            return null ;
        }

        TagItem item = items.get( task ) ;
        if( AnswerChecker.matches( item.type(), item.key(), reply.answer() ) ) {
            return null ;
        }
        TierReply t1 = new TierReply( 1, model, reply ) ;
        escalations.put( task, new Escalation( t1 ) ) ;
        listener.tier1Missed( item, t1 ) ;
        if( reply.isAbstained() ) {
            log.info( "[{}] {} gave no answer - asking tier 2", task, model ) ;
        }
        else {
            log.info( "[{}] {} answered {}, the key is {} - asking tier 2",
                      task, model, reply.answer(), item.key() ) ;
        }
        return TIER2 ;
    }

    @Override
    public String secondOpinionReason() {
        return "tier 1's answer misses the key, or it gave none" ;
    }

    /**
     * Judge the replies, store them and report them. After an escalation
     * {@code reply} is ignored: tier 1's reply and tier 2's (if it gave one)
     * were taken as they came.
     */
    @Override
    public boolean succeeded( QTask task, TagReply reply, AIAgent agent,
                              String model, int attempts ) {

        TagItem     item = items.get( task ) ;
        Escalation  esc  = escalations.remove( task ) ;
        TagOpinions opinions ;

        if( esc != null ) {
            String failure = esc.tier2 != null ? null
                           : esc.failure != null ? esc.failure
                           : "tier 2 gave no answer" ;
            opinions = new TagOpinions( esc.tier1, esc.tier2, failure ) ;
        }
        else {
            opinions = TagOpinions.of( new TierReply( 1, model, reply ) ) ;
        }

        Assembly     a = prompter.assembly() ;
        TagJudgement j = TagJudgement.of( item, opinions, a.mode(),
                                          a.offered(), false ) ;
        int          scope = a.topic().getId() ;
        try {
            pending.save( item.question(), scope, item.dependent(), opinions ) ;
        }
        catch( Exception e ) {
            log.error( "[{}] Could not store the replies", task, e ) ;
        }
        try {
            audit.judgement( item.question(), scope, item.dependent(),
                             stamp(), opinions, j ) ;
        }
        catch( Exception e ) {
            log.error( "[{}] Could not record the judgement in the audit log",
                       task, e ) ;
        }
        log.info( "[{}] {}{}", task, j.status().getLabel(),
                  j.reason() != null ? " (" + j.reason() + ")" : "" ) ;
        listener.judged( item, opinions, j ) ;
        return true ;
    }

    @Override
    public void failed( QTask task, int attempts, String reason ) {
        failed( task, attempts, reason, FailureKind.OTHER ) ;
    }

    /**
     * Tier 1 failed for good: a reply that could not be used (bad, cut
     * off) hands the question to the user; anything else is a failure to
     * re-queue.
     */
    @Override
    public void failed( QTask task, int attempts, String reason,
                        FailureKind kind ) {

        escalations.remove( task ) ;
        TagItem item = items.get( task ) ;
        if( kind == FailureKind.BAD_REPLY || kind == FailureKind.TRUNCATED ) {
            listener.invalidOutput( item, attempts, reason ) ;
        }
        else {
            listener.itemFailed( item, attempts, reason ) ;
        }
    }

    @Override
    public String attemptContext( QTask task ) {

        Assembly a       = prompter.assembly() ;
        TagItem  item    = items.get( task ) ;
        int      tier    = escalations.containsKey( task ) ? 2 : 1 ;
        String   variant = item != null && item.dependent() ? ", dependent"
                                                            : "" ;
        return "tier " + tier + ", topic " + a.topic().getId() + variant +
               ", template v" + a.templateVersion() +
               ", prompt v" + a.promptVersion() ;
    }

    @Override
    public AttemptListener attemptListener( QTask task ) {

        TagItem item = items.get( task ) ;
        return new AttemptListener() {
            @Override
            public void attemptStarted( AIAgent agent, String model,
                                        int attempt ) {
                listener.itemAttempt( item, model, attempt,
                                      escalations.containsKey( task ) ? 2
                                                                      : 1 ) ;
            }

            @Override
            public void requestSent( AttemptRequest request ) {
                listener.requestSent( request ) ;
            }

            @Override
            public void attemptFinished( AttemptTranscript transcript ) {

                Escalation esc = escalations.get( task ) ;
                if( esc != null && transcript.getFailure() != null ) {
                    esc.failure = transcript.getFailure() ;
                }
                try {
                    transcripts.save( transcript ) ;
                }
                catch( Exception e ) {
                    log.error( "[{}] Could not save the transcript of " +
                               "attempt {}", task, transcript.getAttempt(), e ) ;
                }
                usageLog.append( transcript ) ;
                listener.attemptFinished( item ) ;
            }
        } ;
    }
}
