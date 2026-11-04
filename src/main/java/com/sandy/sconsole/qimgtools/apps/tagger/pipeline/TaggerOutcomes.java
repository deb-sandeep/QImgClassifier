package com.sandy.sconsole.qimgtools.apps.tagger.pipeline;

import com.sandy.sconsole.ai.FailureKind;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagItem;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagProposal;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagReply;
import com.sandy.sconsole.qimgtools.apps.tagger.workspace.PendingTagging;
import com.sandy.sconsole.qimgtools.apps.tagger.workspace.TagAuditLog;
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
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * What happens to a question of the tagger around its AI attempts:
 * transcripts and token counts are saved as the reviewer saves them, and a
 * model's reply is stored in {@link PendingTagging}, recorded in the
 * {@link TagAuditLog} and reported to the listener. Nothing is written to the
 * server here; that waits for Save. Thread-safe.
 */
@Slf4j
public class TaggerOutcomes implements TaskOutcomes<TagReply> {

    /** The root every question goes to. */
    public static final String LLM = "llm" ;

    private final TaggerListener  listener ;
    private final PendingTagging  pending ;
    private final TagAuditLog     audit ;
    private final TranscriptStore transcripts ;
    private final UsageLog        usageLog ;

    /** The scoped topic's id, for what is stored. */
    private final IntSupplier scopeTopic ;

    /** The cycle of the scoped topic's files, for what is stored. */
    private final Supplier<String> scopeCycle ;

    /** The item each submitted task belongs to. */
    private final Map<QTask,TagItem> items = new ConcurrentHashMap<>() ;

    public TaggerOutcomes( TaggerListener listener, PendingTagging pending,
                           TagAuditLog audit, TranscriptStore transcripts,
                           UsageLog usageLog, IntSupplier scopeTopic,
                           Supplier<String> scopeCycle ) {

        this.listener    = listener ;
        this.pending     = pending ;
        this.audit       = audit ;
        this.transcripts = transcripts ;
        this.usageLog    = usageLog ;
        this.scopeTopic  = scopeTopic ;
        this.scopeCycle  = scopeCycle ;
    }

    /** Remember which item {@code item.task()} belongs to; call on submit. */
    public void track( TagItem item ) {
        items.put( item.task(), item ) ;
    }

    @Override
    public List<String> roots( QTask task ) {
        return List.of( LLM ) ;
    }

    @Override
    public void started( QTask task ) {
        listener.itemStarted( items.get( task ) ) ;
    }

    /** No second opinion: a valid reply stands. */
    @Override
    public String secondOpinionRoot( QTask task, TagReply reply, AIAgent agent,
                                     String model ) {
        return null ;
    }

    /** Store the reply and report it. */
    @Override
    public boolean succeeded( QTask task, TagReply reply, AIAgent agent,
                              String model, int attempts ) {

        TagItem     item     = items.get( task ) ;
        TagProposal proposal = new TagProposal( model, reply ) ;
        int         scope    = scopeTopic.getAsInt() ;
        String      cycle    = scopeCycle.get() ;

        try {
            pending.save( item.question(), scope, cycle, proposal ) ;
        }
        catch( Exception e ) {
            log.error( "[{}] Could not store the reply", task, e ) ;
        }
        try {
            audit.reply( item.question(), scope, cycle, proposal ) ;
        }
        catch( Exception e ) {
            log.error( "[{}] Could not record the reply in the audit log",
                       task, e ) ;
        }
        log.info( "[{}] {}: {} ({}{})", task, model, reply.tags(),
                  reply.confidence(), reply.noTagFits() ? ", no tag fits" : "" ) ;
        listener.proposed( item, proposal ) ;
        return true ;
    }

    @Override
    public void failed( QTask task, int attempts, String reason ) {
        failed( task, attempts, reason, FailureKind.OTHER ) ;
    }

    /**
     * The model failed for good: a reply that could not be used (bad, cut
     * off) hands the question to the user; anything else is a failure to
     * re-queue.
     */
    @Override
    public void failed( QTask task, int attempts, String reason,
                        FailureKind kind ) {

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
        return "topic " + scopeTopic.getAsInt() ;
    }

    @Override
    public AttemptListener attemptListener( QTask task ) {

        TagItem item = items.get( task ) ;
        return new AttemptListener() {
            @Override
            public void attemptStarted( AIAgent agent, String model,
                                        int attempt ) {
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
                    log.error( "[{}] Could not save the transcript of " +
                               "attempt {}", task, transcript.getAttempt(), e ) ;
                }
                usageLog.append( transcript ) ;
                listener.attemptFinished( item ) ;
            }
        } ;
    }
}
