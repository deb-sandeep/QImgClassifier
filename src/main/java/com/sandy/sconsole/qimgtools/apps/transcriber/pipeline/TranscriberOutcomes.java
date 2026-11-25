package com.sandy.sconsole.qimgtools.apps.transcriber.pipeline;

import com.sandy.sconsole.qimgtools.apps.transcriber.gate.ConfidenceScore;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.Transcription;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.TranscriptionRoute;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.TranscriptionRoute.Kind;
import com.sandy.sconsole.qimgtools.core.model.AIAgent;
import com.sandy.sconsole.qimgtools.core.model.AttemptRequest;
import com.sandy.sconsole.qimgtools.core.model.AttemptTranscript;
import com.sandy.sconsole.qimgtools.core.model.QTask;
import com.sandy.sconsole.qimgtools.core.model.Subject;
import com.sandy.sconsole.qimgtools.core.scheduler.AttemptListener;
import com.sandy.sconsole.qimgtools.core.scheduler.TaskOutcomes;
import com.sandy.sconsole.qimgtools.core.workspace.TranscriptStore;
import com.sandy.sconsole.qimgtools.core.workspace.UsageLog;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The transcriber's {@link TaskOutcomes}: every job starts on the
 * {@link #TRANSCRIBE} root (Qwen), except every
 * {@link #PHY_MATHS_SHARE}th Physics or Maths question, which starts on
 * {@link #PHY_MATHS} (Inkling) alone; a re-send is routed by the caller to
 * {@link #ESCALATE} or {@link #RECHECK}. A transcription that passed the
 * gate is taken as it is: after calibration C doesn't route, unless
 * {@code gate.route.on.c} is set, when a Qwen reply with C above
 * {@code gate.c.max} is escalated to Nemotron. Each transcription is
 * reported with its route, worked out from the job's attempts; each
 * attempt's transcript (with the gate's Confidence section) and token
 * counts are kept. Thread-safe.
 */
@Slf4j
public class TranscriberOutcomes implements TaskOutcomes<Transcription> {

    /** Qwen: every job starts here. */
    public static final String TRANSCRIBE = "transcribe" ;

    /** Inkling: one in {@link #PHY_MATHS_SHARE} Physics / Maths questions. */
    public static final String PHY_MATHS = "phy-maths" ;

    /**
     * One in this many Physics and Maths questions sent (counted together)
     * starts on {@link #PHY_MATHS}.
     */
    public static final int PHY_MATHS_SHARE = 4 ;

    /** The free Nemotron: a re-sent Qwen transcription, a second opinion. */
    public static final String ESCALATE = "escalate" ;

    /** GLM: a re-sent Nemotron transcription. */
    public static final String RECHECK = "recheck" ;

    /** One attempt on a job: where it went and how it ended. */
    private record Tried( AIAgent agent, String model,
                          AttemptTranscript.Outcome outcome,
                          String failure ) {}

    private final TranscriberListener listener ;
    private final TranscriptStore     transcripts ;
    private final UsageLog            usageLog ;
    private final boolean             routeOnC ;
    private final int                 cMax ;
    private final Set<String>         shared ;
    private final boolean             phyMaths ;

    /** The job each task sent is, by task. */
    private final Map<QTask,TranscriptionJob> jobs = new ConcurrentHashMap<>() ;

    /** Each job's attempts so far, by task. */
    private final Map<QTask,List<Tried>> tried = new ConcurrentHashMap<>() ;

    /** The gate's note on the reply of the attempt just checked, by task. */
    private final Map<QTask,String> notes = new ConcurrentHashMap<>() ;

    /** Why a job was sent for a second opinion, by task. */
    private final Map<QTask,String> escalations = new ConcurrentHashMap<>() ;

    /** The tasks sent to a model the user chose, that model alone. */
    private final Set<QTask> chosen = ConcurrentHashMap.newKeySet() ;

    /** The Physics / Maths tasks that start on {@link #PHY_MATHS}. */
    private final Set<QTask> onPhyMaths = ConcurrentHashMap.newKeySet() ;

    /** Physics and Maths questions sent so far, for the share. */
    private final AtomicInteger phyMathsSent = new AtomicInteger() ;

    /**
     * @param routeOnC true to escalate a Qwen reply with C above
     *        {@code cMax}
     * @param shared   the models besides Qwen which share its work (the
     *        other models of the {@link #TRANSCRIBE} root and those of
     *        {@link #PHY_MATHS}), by id and by the id sent to the gateway: a
     *        question one takes without Qwen having tried it is
     *        {@link Kind#SHARED}
     * @param phyMaths true if the {@link #PHY_MATHS} root is usable, so
     *        one in {@link #PHY_MATHS_SHARE} Physics / Maths questions
     *        starts there
     */
    public TranscriberOutcomes( TranscriberListener listener,
                                TranscriptStore transcripts, UsageLog usageLog,
                                boolean routeOnC, int cMax,
                                Set<String> shared, boolean phyMaths ) {

        this.listener    = listener ;
        this.transcripts = transcripts ;
        this.usageLog    = usageLog ;
        this.routeOnC    = routeOnC ;
        this.cMax        = cMax ;
        this.shared      = Set.copyOf( shared ) ;
        this.phyMaths    = phyMaths ;
    }

    /**
     * Note that {@code job}'s task is about to be sent; {@code byChoice}
     * if to a model the user chose: it is then never escalated, and its
     * route is {@link Kind#CHOSEN}. Of the Physics and Maths jobs that are
     * neither chosen nor re-sent, the first of every
     * {@link #PHY_MATHS_SHARE} starts on {@link #PHY_MATHS}.
     */
    public void track( TranscriptionJob job, boolean byChoice ) {

        QTask task = job.task() ;
        jobs.put( task, job ) ;
        tried.put( task, new ArrayList<>() ) ;
        escalations.remove( task ) ;
        if( byChoice ) chosen.add( task ) ;
        else chosen.remove( task ) ;

        Subject subject = task.getSubject() ;

        onPhyMaths.remove( task ) ;
        if( phyMaths && !byChoice && !job.resent()
            && ( subject == Subject.PHYSICS || subject == Subject.MATHS )
            && phyMathsSent.getAndIncrement() % PHY_MATHS_SHARE == 0 ) {
            onPhyMaths.add( task ) ;
        }
    }

    /** The job {@code task} is; null if it wasn't sent. */
    public TranscriptionJob job( QTask task ) {
        return jobs.get( task ) ;
    }

    /** The gate's note on the reply just checked for {@code task}. */
    public void note( QTask task, String note ) {
        notes.put( task, note ) ;
    }

    @Override
    public List<String> roots( QTask task ) {
        return onPhyMaths.contains( task ) ? List.of( PHY_MATHS )
                                           : List.of( TRANSCRIBE ) ;
    }

    @Override
    public void started( QTask task ) {
        listener.jobStarted( jobs.get( task ) ) ;
    }

    @Override
    public String secondOpinionRoot( QTask task, Transcription value,
                                     AIAgent agent, String model ) {

        TranscriptionJob job = jobs.get( task ) ;

        if( !routeOnC || value.c() == null || agent != AIAgent.OLLAMA
            || job == null || job.resent() || chosen.contains( task )
            || value.c() <= cMax ) {
            return null ;
        }
        escalations.put( task, secondOpinionReason( task, value ) ) ;
        return ESCALATE ;
    }

    /** "C = 2: "5f^4" vs "5f^{14}"", naming the closest contest. */
    @Override
    public String secondOpinionReason( QTask task, Transcription value ) {

        String why = "C = " + value.c() ;
        ConfidenceScore.Contest x = value.contests().stream()
                .min( ( a, b ) -> Double.compare( a.diff(), b.diff() ) )
                .orElse( null ) ;
        return x == null ? why
                         : why + ": \"" + x.chosen().strip() + "\" vs \"" +
                           x.rival().strip() + "\"" ;
    }

    @Override
    public boolean succeeded( QTask task, Transcription value, AIAgent agent,
                              String model, int attempts ) {
        return succeeded( task, value, agent, model, attempts, false ) ;
    }

    @Override
    public boolean succeeded( QTask task, Transcription value, AIAgent agent,
                              String model, int attempts,
                              boolean provisional ) {

        TranscriptionJob   job   = jobs.get( task ) ;
        TranscriptionRoute route = route( task, job, agent, model ) ;

        log.info( "[{}] transcribed by {} ({}, {}){}", job, model,
                  route.kind(), value.describeC(),
                  provisional ? ", provisional" : "" ) ;
        listener.transcribed( job, value.withModel( model ), route,
                              provisional ) ;
        return true ;
    }

    /**
     * How the job's transcription by {@code model} came about: a re-send;
     * Qwen's; a sharing model's (Inkling's, or Nemotron's) taken before
     * Qwen tried; Nemotron's after Qwen's reply failed or was escalated; or
     * Nemotron's standing in while Qwen was down or held back.
     */
    private TranscriptionRoute route( QTask task, TranscriptionJob job,
                                      AIAgent agent, String model ) {

        if( chosen.contains( task ) ) {
            return new TranscriptionRoute( Kind.CHOSEN, model, null ) ;
        }
        if( job != null && job.resent() ) {
            return new TranscriptionRoute( Kind.RESENT, model, null ) ;
        }
        boolean sharer = shared.contains( model ) ;
        if( agent == AIAgent.OLLAMA && !sharer ) {
            return new TranscriptionRoute( Kind.QWEN, model, null ) ;
        }

        String escalation = escalations.get( task ) ;
        if( escalation != null ) {
            return new TranscriptionRoute( Kind.ESCALATED, model, escalation ) ;
        }

        Tried qwen = null ;
        for( Tried t : tried.getOrDefault( task, List.of() ) ) {
            if( t.agent() == AIAgent.OLLAMA && !shared.contains( t.model() )
                && t.outcome() != null ) {
                qwen = t ;
            }
        }
        if( qwen != null
            && qwen.outcome() == AttemptTranscript.Outcome.FAILED ) {
            return new TranscriptionRoute( Kind.ESCALATED, model,
                                           qwen.failure() ) ;
        }
        if( qwen == null && sharer ) {
            return new TranscriptionRoute( Kind.SHARED, model, null ) ;
        }
        return new TranscriptionRoute( Kind.STAND_IN, model,
                                       qwen != null ? qwen.failure()
                                                    : "Qwen was held back" ) ;
    }

    @Override
    public void failed( QTask task, int attempts, String reason ) {
        log.info( "[{}] No transcription after {} attempts: {}",
                  jobs.get( task ), attempts, reason ) ;
        listener.jobFailed( jobs.get( task ), attempts, reason ) ;
    }

    @Override
    public AttemptListener attemptListener( QTask task ) {

        TranscriptionJob job = jobs.get( task ) ;
        return new AttemptListener() {
            @Override
            public void attemptStarted( AIAgent agent, String model,
                                        int attempt ) {
                tried.computeIfAbsent( task, t -> new ArrayList<>() ) ;
                listener.jobAttempt( job, model, attempt ) ;
            }

            @Override
            public void requestSent( AttemptRequest request ) {
                listener.requestSent( request ) ;
            }

            @Override
            public void attemptFinished( AttemptTranscript transcript ) {

                AttemptTranscript t = withNote( transcript,
                                                notes.remove( task ) ) ;

                List<Tried> list = tried.get( task ) ;
                if( list != null ) {
                    synchronized( list ) {
                        list.add( new Tried( t.getAgent(), t.getAiModel(),
                                             t.getOutcome(),
                                             t.getFailure() ) ) ;
                    }
                }
                try {
                    transcripts.save( t ) ;
                }
                catch( Exception e ) {
                    log.error( "[{}] Could not save the transcript of " +
                               "attempt {}", task, t.getAttempt(), e ) ;
                }
                usageLog.append( t ) ;
                listener.attemptFinished( job, t ) ;
            }
        } ;
    }

    /** {@code t} with the gate's {@code note} after its reply, if any. */
    private static AttemptTranscript withNote( AttemptTranscript t,
                                               String note ) {

        if( note == null ) return t ;
        String response = ( t.getResponse() == null ? "" : t.getResponse() ) +
                          "\n\n" + TranscriptStore.RULE + "\nConfidence:\n" +
                          note ;
        return new AttemptTranscript( t.getRequest(), t.getElapsedMs(),
                                      response, t.getFailure(), t.getOutcome(),
                                      t.getUsage(), t.getReasoning() ) ;
    }
}
