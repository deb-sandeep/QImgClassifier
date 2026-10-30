package com.sandy.sconsole.qimgtools.apps.nxrclassifier.llm;

import com.sandy.sconsole.qimgtools.apps.nxrclassifier.model.NXRItem;
import com.sandy.sconsole.qimgtools.apps.nxrclassifier.model.NXRReply;
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
 * The NXR classifier's {@link TaskOutcomes}: the questions the local
 * classifier left undecided start on the {@link #ROOT} root, a reply that
 * names a label is taken as it is (no second opinion), and each attempt's
 * transcript and token counts are kept. The label itself is the frame's to
 * show and save, through the {@link NXRListener}. Thread-safe.
 */
@Slf4j
public class NXROutcomes implements TaskOutcomes<NXRReply> {

    /** The root of the AI config the questions are sent to. */
    public static final String ROOT = "nxr" ;

    private final NXRListener     listener ;
    private final TranscriptStore transcripts ;
    private final UsageLog        usageLog ;

    /** The question each task sent is, by task. */
    private final Map<QTask,NXRItem> items = new ConcurrentHashMap<>() ;

    public NXROutcomes( NXRListener listener, TranscriptStore transcripts,
                        UsageLog usageLog ) {
        this.listener    = listener ;
        this.transcripts = transcripts ;
        this.usageLog    = usageLog ;
    }

    /** Note that {@code item}'s task is about to be sent. */
    public void track( NXRItem item ) {
        items.put( item.task(), item ) ;
    }

    /** The question {@code task} is; null if it wasn't sent. */
    public NXRItem item( QTask task ) {
        return items.get( task ) ;
    }

    @Override
    public List<String> roots( QTask task ) {
        return List.of( ROOT ) ;
    }

    @Override
    public void started( QTask task ) {
        listener.itemStarted( items.get( task ) ) ;
    }

    @Override
    public String secondOpinionRoot( QTask task, NXRReply reply,
                                     AIAgent agent, String model ) {
        return null ;
    }

    @Override
    public boolean succeeded( QTask task, NXRReply reply, AIAgent agent,
                              String model, int attempts ) {

        NXRReply credited = reply.from( model ) ;
        log.info( "[{}] {} labelled it {}", task, model, credited.describe() ) ;
        listener.aiLabelled( items.get( task ), credited ) ;
        return true ;
    }

    @Override
    public void failed( QTask task, int attempts, String reason ) {
        log.info( "[{}] No AI label after {} attempts: {}", task, attempts,
                  reason ) ;
        listener.aiFailed( items.get( task ), attempts, reason ) ;
    }

    @Override
    public AttemptListener attemptListener( QTask task ) {

        NXRItem item = items.get( task ) ;
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
