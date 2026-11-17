package com.sandy.sconsole.qimgtools.apps.transcriber;

import com.sandy.sconsole.ai.AIHub;
import com.sandy.sconsole.ai.HubStatus;
import com.sandy.sconsole.ai.RoutingPolicy;
import com.sandy.sconsole.ai.config.AIConfig;
import com.sandy.sconsole.ai.config.FallbackInfo;
import com.sandy.sconsole.ai.config.ModelInfo;
import com.sandy.sconsole.qimgtools.apps.transcriber.gate.GoldenCases;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.Passage;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.SaveItem;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.SaveResult;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.Saving;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.Transcription;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.TranscriptionCriteria;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.TranscriptionItem;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.TranscriptionRoute;
import com.sandy.sconsole.qimgtools.apps.transcriber.pipeline.TranscriberListener;
import com.sandy.sconsole.qimgtools.apps.transcriber.pipeline.TranscriberOutcomes;
import com.sandy.sconsole.qimgtools.apps.transcriber.pipeline.TranscriptionJob;
import com.sandy.sconsole.qimgtools.apps.transcriber.prompt.PromptFile;
import com.sandy.sconsole.qimgtools.apps.transcriber.prompt.TranscriptionPrompter;
import com.sandy.sconsole.qimgtools.apps.transcriber.server.LctContext;
import com.sandy.sconsole.qimgtools.apps.transcriber.server.PendingTranscriptions;
import com.sandy.sconsole.qimgtools.apps.transcriber.server.StoredTranscription;
import com.sandy.sconsole.qimgtools.apps.transcriber.server.TranscriberServerClient;
import com.sandy.sconsole.qimgtools.apps.transcriber.server.TranscriptionSearch;
import com.sandy.sconsole.qimgtools.apps.transcriber.ui.TranscriberFrame;
import com.sandy.sconsole.qimgtools.apps.transcriber.util.TranscriberConfig;
import com.sandy.sconsole.qimgtools.apps.transcriber.workspace.CriteriaStore;
import com.sandy.sconsole.qimgtools.apps.transcriber.workspace.HeldPassages;
import com.sandy.sconsole.qimgtools.apps.transcriber.workspace.StagedTranscriptions;
import com.sandy.sconsole.qimgtools.apps.transcriber.workspace.StagedTranscriptions.Staged;
import com.sandy.sconsole.qimgtools.apps.transcriber.workspace.TranscriptionAuditLog;
import com.sandy.sconsole.qimgtools.core.model.AttemptRequest;
import com.sandy.sconsole.qimgtools.core.model.ProcessingState;
import com.sandy.sconsole.qimgtools.core.model.QTask;
import com.sandy.sconsole.qimgtools.core.model.RateLimitNotice;
import com.sandy.sconsole.qimgtools.core.model.Topic;
import com.sandy.sconsole.qimgtools.core.review.QuestionLocator;
import com.sandy.sconsole.qimgtools.core.scheduler.TaskScheduler;
import com.sandy.sconsole.qimgtools.core.server.SConsoleServer;
import com.sandy.sconsole.qimgtools.core.server.SConsoleServer.ServerException;
import com.sandy.sconsole.qimgtools.core.server.ServerQuestion;
import com.sandy.sconsole.qimgtools.core.util.ModelNames;
import com.sandy.sconsole.qimgtools.core.workspace.TranscriptStore;
import com.sandy.sconsole.qimgtools.core.workspace.UsageLog;

import lombok.extern.slf4j.Slf4j;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The transcriber's {@code main} and controller (feature-design.md): Load
 * fetches the pending questions matching the criteria
 * ({@code GET /Master/Question/Transcription/Pending}) and finds their
 * images in the question bank; Start sends them to Qwen through an
 * {@link AIHub} taking them in order, the gate ({@link TranscriptionPrompter})
 * sending a malformed reply on to Nemotron; each transcription is staged in
 * the workspace and shown in the {@link TranscriberFrame}; Save stores them
 * ({@code POST /Master/Question/Transcription}).
 * <p>
 * An LCT question needs its group's passage first: held in the workspace,
 * stored on the server, or else transcribed by a passage job, one per
 * group, which the group's questions wait for. They are submitted from the
 * hub's thread as the passage job ends, so the run can't end between the
 * two. The passage is held locally, and sent with the group's first
 * question saved.
 */
@Slf4j
public class QTranscriber implements TranscriberFrame.Controller,
                                     TranscriberListener {

    /** The syllabuses the criteria offer. */
    public static final List<String> SYLLABUSES =
            List.of( "IIT Physics", "IIT Chemistry", "IIT Maths" ) ;

    /** Questions a search page brings. */
    public static final int SEARCH_PAGE = 100 ;

    private final TranscriberConfig       config ;
    private final TranscriberServerClient server ;
    private final QuestionLocator         locator ;
    private final PromptFile              prompt ;
    private final Map<String,List<Topic>> topics ;
    private final CriteriaStore           criteriaStore ;
    private final StagedTranscriptions    staged ;
    private final HeldPassages            held ;
    private final TranscriptionAuditLog   audit ;
    private final TranscriberListener     frame ;

    private final TranscriberOutcomes          outcomes ;
    private final TranscriptionPrompter        prompter ;
    private final TaskScheduler<Transcription> scheduler ;

    /** The criteria in effect. */
    private volatile TranscriptionCriteria criteria ;

    /** The passages known, by group. */
    private final Map<String,Passage> passages = new ConcurrentHashMap<>() ;

    /**
     * The transcriptions models gave the questions loaded, oldest first,
     * by server id: read from the audit log on Load, added to as they come.
     */
    private final Map<Integer,List<TranscriptionAuditLog.Given>> given =
            new ConcurrentHashMap<>() ;

    /**
     * The questions waiting for their group's passage job, by group; a
     * group is here while its passage job runs. Guarded by {@code this}.
     */
    private final Map<String,List<TranscriptionItem>> waiting =
            new HashMap<>() ;

    /** True while a model run is going. */
    private final AtomicBoolean processing = new AtomicBoolean( false ) ;

    /** True once Stop is asked for, until the run ends. */
    private volatile boolean stopRequested ;

    public QTranscriber( TranscriberConfig config,
                         TranscriberServerClient server, PromptFile prompt,
                         Map<String,List<Topic>> topics,
                         TranscriberFrame frame ) {

        this.config        = config ;
        this.server        = server ;
        this.prompt        = prompt ;
        this.topics        = topics ;
        this.frame         = frame ;
        this.locator       = new QuestionLocator( config.getQBankDir() ) ;
        this.criteriaStore = new CriteriaStore( config.getCriteriaFile() ) ;
        this.staged        = new StagedTranscriptions( config.getStagedFile() ) ;
        this.held          = new HeldPassages( config.getHeldPassagesFile() ) ;
        this.audit         = new TranscriptionAuditLog( config.getAuditFile() ) ;

        TranscriptionCriteria saved = criteriaStore.load(
                SYLLABUSES, TranscriberServerClient.MAX_BATCH ) ;
        this.criteria = saved != null ? saved : config.getCriteria() ;

        AIHub hub = AIHub.create( config.getAi(), new AIHub.Options(
                config.getRequestHistoryFile(), RoutingPolicy.inOrder(),
                "qtranscriber" ) ) ;

        this.outcomes  = new TranscriberOutcomes(
                this, new TranscriptStore( config.getWorkspaceDir() ),
                new UsageLog( config.getUsageLogFile() ),
                config.isGateRouteOnC(), config.getGateCMax() ) ;
        this.prompter  = new TranscriptionPrompter(
                prompt, outcomes::job, config.getGateMargin(),
                outcomes::note ) ;
        this.scheduler = new TaskScheduler<>( hub, prompter, outcomes ) ;
    }

    public static void main( String[] args ) {

        TranscriberConfig config ;
        PromptFile        prompt ;
        try {
            config = TranscriberConfig.load() ;
            prompt = PromptFile.load( config.getPromptFile() ) ;
        }
        catch( IllegalStateException e ) {
            fail( "Invalid configuration", e.getMessage(), e ) ;
            return ;
        }

        AIConfig ai = config.getAi() ;
        for( String root : List.of( TranscriberOutcomes.TRANSCRIBE,
                                    TranscriberOutcomes.ESCALATE ) ) {
            if( !ai.isRootUsable( root ) ) {
                fail( "AI models", ai.appLocation() + ": root '" + root +
                      "' is missing or has no usable model.", null ) ;
                return ;
            }
        }
        if( !ai.isRootUsable( TranscriberOutcomes.RECHECK ) ) {
            log.warn( "{}: root '{}' has no usable model - a faulty " +
                      "Nemotron transcription can't be re-sent",
                      ai.appLocation(), TranscriberOutcomes.RECHECK ) ;
        }

        try {
            log.info( "Gate: {} golden cases match", GoldenCases.check() ) ;
        }
        catch( IOException | IllegalStateException e ) {
            fail( "Transcription gate", e.getMessage(), e ) ;
            return ;
        }

        File workspaceDir = config.getWorkspaceDir() ;
        if( !workspaceDir.isDirectory() && !workspaceDir.mkdirs() ) {
            log.error( "Could not create the workspace folder {}",
                       workspaceDir ) ;
        }

        TranscriberServerClient server = new TranscriberServerClient(
                new SConsoleServer( config.getServerUrl(),
                                    config.getServerConnectTimeout(),
                                    config.getServerRequestTimeout() ) ) ;

        Map<String,List<Topic>> topics = new LinkedHashMap<>() ;
        try {
            for( String s : SYLLABUSES ) topics.put( s, server.topics( s ) ) ;
        }
        catch( ServerException e ) {
            fail( "SConsole server", "Could not fetch the topics from " +
                  config.getServerUrl() + ":\n" + e.getMessage(), e ) ;
            return ;
        }

        try {
            SwingUtilities.invokeAndWait( () -> {
                TranscriberFrame frame = new TranscriberFrame(
                        config.getServerUrl(),
                        new TranscriptStore( config.getWorkspaceDir() ),
                        ModelNames.of( ai ) ) ;
                QTranscriber     app   = new QTranscriber(
                        config, server, prompt, topics, frame ) ;

                frame.setController( app ) ;
                frame.setVisible( true ) ;
            } ) ;
        }
        catch( Exception e ) {
            log.error( "Could not start UI", e ) ;
        }
    }

    /** Report a startup failure and exit. */
    private static void fail( String title, String message, Exception e ) {
        log.error( "{}: {}", title, message, e ) ;
        JOptionPane.showMessageDialog( null, message, title,
                                       JOptionPane.ERROR_MESSAGE ) ;
        System.exit( 1 ) ;
    }

    // ---- criteria and loading ------------------------------------------

    @Override
    public List<String> syllabusNames() {
        return SYLLABUSES ;
    }

    @Override
    public List<Topic> topics( String syllabusName ) {
        return topics.getOrDefault( syllabusName, List.of() ) ;
    }

    @Override
    public TranscriptionCriteria criteria() {
        return criteria ;
    }

    @Override
    public void setCriteria( TranscriptionCriteria criteria )
            throws IOException {
        this.criteria = criteria ;
        criteriaStore.save( criteria ) ;
    }

    /**
     * The first pending questions matching the criteria after server id
     * {@code afterId}, with their images found and their staged
     * transcriptions (made with the current prompt) restored.
     */
    @Override
    public TranscriberFrame.Loaded load( int batchSize, Integer afterId )
            throws ServerException {

        TranscriptionCriteria c = criteria.withBatchSize( Math.max( 1,
                Math.min( batchSize, TranscriberServerClient.MAX_BATCH ) ) ) ;
        PendingTranscriptions r = server.pending( c, afterId ) ;

        List<TranscriptionItem> items    = new ArrayList<>() ;
        Map<Integer,Staged>     restored = new HashMap<>() ;

        for( PendingTranscriptions.Item i : r.items() ) {
            TranscriptionItem item = TranscriptionItem.of(
                    locator.locate( i.question() ), i.lctCtxId(),
                    i.lctCtxTranscribed() ) ;
            Staged s = staged.get( item.id(), prompt.sha256() ) ;

            items.add( item ) ;
            if( s != null ) restored.put( item.id(), s ) ;
            if( item.isLct() ) knownPassage( item ) ;
        }
        readGiven( items ) ;
        log.info( "Loaded {} questions ({} restored from the workspace), " +
                  "{} await transcription for {}", items.size(),
                  restored.size(), r.totalResults(), c.describe() ) ;
        return new TranscriberFrame.Loaded( items, restored,
                                            r.totalResults(), false ) ;
    }

    @Override
    public List<TranscriptionAuditLog.Saved> savedSince( Instant since )
            throws IOException {
        return audit.savedSince( since ) ;
    }

    /**
     * {@code saved}, each with its transcription as the server stores it
     * now (an LCT question's part cut at the marker, its group's stored
     * passage noted), to check in the table. A question the server no
     * longer has a transcription for is left out.
     */
    @Override
    public TranscriberFrame.Loaded loadSaved(
            List<TranscriptionAuditLog.Saved> saved ) throws ServerException {

        List<TranscriptionItem> items  = new ArrayList<>() ;
        Map<Integer,Staged>     stored = new HashMap<>() ;
        ServerException         failed = null ;

        for( TranscriptionAuditLog.Saved e : saved ) {
            TranscriptionRoute route = e.route() != null ? e.route()
                    : new TranscriptionRoute( TranscriptionRoute.Kind.QWEN,
                                              null, null ) ;
            try {
                loadStored( e.question(), route, e.c(), e.minP(), items,
                            stored ) ;
            }
            catch( ServerException ex ) {
                failed = notLoaded( e.question(), ex ) ;
            }
        }
        if( items.isEmpty() && failed != null ) throw failed ;
        readGiven( items ) ;
        log.info( "Loaded {} saved questions to check", items.size() ) ;
        return new TranscriberFrame.Loaded( items, stored, -1, true ) ;
    }

    /**
     * {@code questions}, found by a search, each with its transcription as
     * the server stores it now, to check in the table as
     * {@link #loadSaved} does; route Stored, no C.
     */
    @Override
    public TranscriberFrame.Loaded loadFound( List<ServerQuestion> questions )
            throws ServerException {

        List<TranscriptionItem> items  = new ArrayList<>() ;
        Map<Integer,Staged>     stored = new HashMap<>() ;
        ServerException         failed = null ;

        for( ServerQuestion q : questions ) {
            TranscriptionRoute route = new TranscriptionRoute(
                    TranscriptionRoute.Kind.STORED, null, null ) ;
            try {
                loadStored( q, route, null, null, items, stored ) ;
            }
            catch( ServerException ex ) {
                failed = notLoaded( q, ex ) ;
            }
        }
        if( items.isEmpty() && failed != null ) throw failed ;
        readGiven( items ) ;
        log.info( "Loaded {} questions found by a search", items.size() ) ;
        return new TranscriberFrame.Loaded( items, stored, -1, true ) ;
    }

    /**
     * Note that {@code q}'s stored transcription could not be read (e.g.
     * a search hit the server no longer has one for), so it is left out
     * of a load to check; the others still load.
     *
     * @return {@code e}, to throw if no question loads
     */
    private ServerException notLoaded( ServerQuestion q, ServerException e ) {
        log.warn( "{} (server id {}) left out: {}", q.getQuestionId(),
                  q.getId(), e.getMessage() ) ;
        return e ;
    }

    /**
     * Read {@code q}'s transcription from the server and add it to
     * {@code items} and {@code stored} with {@code route} (its model, if
     * null, the stored one's), {@code c} and {@code minP}; an LCT
     * question's part only, its group's passage noted. A question the
     * server has no transcription for is left out.
     */
    private void loadStored( ServerQuestion q, TranscriptionRoute route,
                             Integer c, Double minP,
                             List<TranscriptionItem> items,
                             Map<Integer,Staged> stored )
            throws ServerException {

        StoredTranscription st = server.transcription( q.getId() ) ;
        if( st == null || st.content() == null ) {
            log.warn( "{}: the server has no transcription now",
                      q.getQuestionId() ) ;
            return ;
        }

        String            lct  = st.lctCtx() == null ? null
                                                     : st.lctCtx().lctCtxId() ;
        TranscriptionItem item = TranscriptionItem.of(
                locator.locate( q ), lct, lct != null ) ;
        String            text = st.content() ;

        if( item.isLct() ) {
            String part = Transcription.questionPart( text ) ;
            if( part != null ) text = part ;
            storedPassage( st.lctCtx() ) ;
        }
        if( route.model() == null ) {
            route = new TranscriptionRoute( route.kind(), st.aiModel(),
                                            route.reason() ) ;
        }

        Transcription t = new Transcription(
                text, st.aiModel(), c, null, minP, null ) ;
        items.add( item ) ;
        stored.put( item.id(), new Staged( t, route, false, false ) ) ;
    }

    @Override
    public TranscriptionSearch search( String text, List<Integer> topicIds,
                                       List<String> types, int page )
            throws ServerException {

        TranscriptionSearch found = server.search(
                text, topicIds, types, page, SEARCH_PAGE ) ;
        log.info( "Search \"{}\" (topics {}, types {}), page {}: {} found",
                  text, topicIds.isEmpty() ? "any" : topicIds,
                  types.isEmpty() ? "any" : types, page + 1,
                  found.totalResults() ) ;
        return found ;
    }

    /**
     * Read from the audit log the transcriptions given {@code items}
     * before; a failed read is only logged (no choice is offered).
     */
    private void readGiven( List<TranscriptionItem> items ) {

        Set<Integer> ids = new HashSet<>() ;
        for( TranscriptionItem item : items ) ids.add( item.id() ) ;
        try {
            Map<Integer,List<TranscriptionAuditLog.Given>> read =
                    audit.given( ids ) ;
            for( Integer id : ids ) {
                given.put( id, List.copyOf( read.getOrDefault(
                        id, List.of() ) ) ) ;
            }
        }
        catch( IOException e ) {
            log.warn( "Could not read the earlier transcriptions from the " +
                      "audit log: {}", e.getMessage() ) ;
        }
    }

    /**
     * The transcriptions models gave {@code item}, oldest first: those in
     * the audit log when it was loaded and those since.
     */
    @Override
    public List<TranscriptionAuditLog.Given> given( TranscriptionItem item ) {
        return given.getOrDefault( item.id(), List.of() ) ;
    }

    /**
     * Note {@code p}, a group's stored passage, unless a passage of the
     * group is known already (one held, not stored yet, stays).
     */
    private void storedPassage( StoredTranscription.Passage p ) {

        if( p == null || p.content() == null ) return ;
        Passage passage = new Passage(
                p.lctCtxId(), Transcription.unscored( p.content(), p.aiModel() ),
                new TranscriptionRoute( TranscriptionRoute.Kind.STORED,
                                        p.aiModel(), null ),
                true ) ;
        if( passages.putIfAbsent( p.lctCtxId(), passage ) == null ) {
            frame.passageKnown( passage ) ;
        }
    }

    /**
     * Note the passage held in the workspace for {@code item}'s group, if
     * there is one and it isn't known yet, and tell the frame.
     */
    private void knownPassage( TranscriptionItem item ) {

        if( passages.containsKey( item.lctCtxId() ) ) {
            frame.passageKnown( passages.get( item.lctCtxId() ) ) ;
            return ;
        }
        Passage p = held.get( item.lctCtxId(), prompt.sha256() ) ;
        if( p != null ) {
            passages.put( p.lctCtxId(), p ) ;
            frame.passageKnown( p ) ;
        }
    }

    @Override
    public String unsupported( TranscriptionItem item ) {
        return prompter.unsupported( item.task().getSubject(),
                                     item.question().getProblemType() ) ;
    }

    // ---- transcribing --------------------------------------------------

    /**
     * Send {@code items} to Qwen: start a run, or add them to the one
     * going. An LCT question goes once its group's passage is known: held,
     * on the server (read here, so call this off the EDT), or transcribed
     * by a passage job started here. Ignored while a run is stopping.
     */
    @Override
    public void transcribe( List<TranscriptionItem> items ) {

        if( stopRequested ) return ;

        // Read the passages the server holds before taking the lock.
        for( TranscriptionItem item : items ) {
            if( item.isLct() && !passages.containsKey( item.lctCtxId() ) ) {
                findPassage( item ) ;
            }
        }

        synchronized( this ) {
            if( stopRequested ) return ;
            List<TranscriptionJob> jobs = new ArrayList<>() ;

            for( TranscriptionItem item : items ) {
                if( !item.isLct() ) {
                    jobs.add( TranscriptionJob.question( item, null, false ) ) ;
                    continue ;
                }
                Passage p = passages.get( item.lctCtxId() ) ;
                if( p != null ) {
                    jobs.add( TranscriptionJob.question( item, p.content(),
                                                         false ) ) ;
                    continue ;
                }
                List<TranscriptionItem> group = waiting.get( item.lctCtxId() ) ;
                if( group == null ) {
                    group = new ArrayList<>() ;
                    waiting.put( item.lctCtxId(), group ) ;
                    jobs.add( 0, TranscriptionJob.passage( item, false ) ) ;
                }
                if( !group.contains( item ) ) group.add( item ) ;
                frame.waitingForPassage( item ) ;
            }
            submit( jobs, null ) ;
        }
    }

    /**
     * Note the passage of {@code item}'s group if it is held in the
     * workspace or stored on the server; a failed read is only logged
     * (the passage is then transcribed).
     */
    private void findPassage( TranscriptionItem item ) {

        Passage p = held.get( item.lctCtxId(), prompt.sha256() ) ;
        if( p == null && item.lctCtxTranscribed() ) {
            try {
                LctContext ctx = server.lctContext( item.lctCtxId() ) ;
                if( ctx.hasPassage() ) {
                    p = new Passage( ctx.lctCtxId(),
                                     Transcription.unscored( ctx.content(),
                                                             ctx.aiModel() ),
                                     new TranscriptionRoute(
                                             TranscriptionRoute.Kind.STORED,
                                             ctx.aiModel(), null ),
                                     true ) ;
                }
            }
            catch( ServerException e ) {
                log.warn( "{}: could not read the passage of {}: {}", item,
                          item.lctCtxId(), e.getMessage() ) ;
            }
        }
        if( p != null ) {
            passages.put( p.lctCtxId(), p ) ;
            frame.passageKnown( p ) ;
        }
    }

    /**
     * Send {@code items} again, each to the root after the model whose
     * transcription was marked faulty ({@code faultyModel}); for an LCT
     * question with {@code passage} true, its group's passage instead.
     */
    @Override
    public void resend( List<TranscriptionItem> items, String faultyModel,
                        boolean passage ) {

        String root = nextRoot( faultyModel ) ;
        if( root == null || stopRequested ) return ;

        List<TranscriptionJob> jobs = new ArrayList<>() ;
        Set<String>            seen = new HashSet<>() ;

        for( TranscriptionItem item : items ) {
            if( passage ) {
                if( item.isLct() && seen.add( item.lctCtxId() ) ) {
                    jobs.add( TranscriptionJob.passage( item, true ) ) ;
                }
                continue ;
            }
            Passage p = item.isLct() ? passages.get( item.lctCtxId() ) : null ;
            if( item.isLct() && p == null ) continue ;
            jobs.add( TranscriptionJob.question(
                    item, p == null ? null : p.content(), true ) ) ;
        }
        log.info( "Re-sending {} {} to the {} root", jobs.size(),
                  passage ? "passages" : "questions", root ) ;
        synchronized( this ) {
            submit( jobs, new TaskScheduler.Route( root, null ) ) ;
        }
    }

    /**
     * Send {@code items} to {@code model} alone: its retries, no fallback,
     * no escalation. An LCT question goes with its group's passage, so one
     * whose passage isn't known is left out (the frame offers only those
     * that have one).
     */
    @Override
    public void transcribeWith( List<TranscriptionItem> items,
                                String model ) {

        if( stopRequested ) return ;
        synchronized( this ) {
            List<TranscriptionJob> jobs = new ArrayList<>() ;

            for( TranscriptionItem item : items ) {
                Passage p = item.isLct() ? passages.get( item.lctCtxId() )
                                         : null ;
                if( item.isLct() && p == null ) {
                    log.info( "{}: not sent to {}, its passage isn't known",
                              item, model ) ;
                    continue ;
                }
                jobs.add( TranscriptionJob.question(
                        item, p == null ? null : p.content(), false ) ) ;
            }
            log.info( "Sending {} questions to {} alone", jobs.size(),
                      model ) ;
            submit( jobs, new TaskScheduler.Route(
                    TranscriberOutcomes.TRANSCRIBE, model ) ) ;
        }
    }

    /**
     * The models a question may be sent to by hand: every usable one, in
     * the AI file's order.
     */
    @Override
    public List<String> models() {
        return scheduler.hubStatus().models().stream()
                        .filter( HubStatus.ModelStatus::usable )
                        .map( HubStatus.ModelStatus::id )
                        .toList() ;
    }

    /**
     * The root a transcription by {@code model} found faulty goes to:
     * Qwen's to Nemotron ({@code escalate}), Nemotron's to GLM
     * ({@code recheck}); null for GLM's, or with no usable root left.
     */
    @Override
    public String nextRoot( String model ) {

        AIConfig ai   = config.getAi() ;
        String   root = inRoot( TranscriberOutcomes.TRANSCRIBE, model )
                        ? TranscriberOutcomes.ESCALATE
                        : inRoot( TranscriberOutcomes.RECHECK, model )
                        ? null : TranscriberOutcomes.RECHECK ;

        return root != null && ai.isRootUsable( root ) ? root : null ;
    }

    private boolean inRoot( String root, String model ) {
        return config.getAi().root( root ).stream()
                     .anyMatch( m -> m.id().equals( model ) ) ;
    }

    /**
     * Submit {@code jobs}, starting a run if none is going; a route pinned
     * to a model is the user's choice. Under the lock.
     */
    private void submit( List<TranscriptionJob> jobs,
                         TaskScheduler.Route route ) {

        if( jobs.isEmpty() ) return ;
        List<QTask> tasks = new ArrayList<>() ;

        for( TranscriptionJob job : jobs ) {
            outcomes.track( job, route != null && route.model() != null ) ;
            tasks.add( job.task() ) ;
        }
        log.info( "Transcribing {} jobs{}", tasks.size(),
                  route == null ? "" : " on the " + route.root() + " root" ) ;
        if( processing.get() ) {
            scheduler.submitBatch( tasks, route, this::isProcessing ) ;
            return ;
        }
        processing.set( true ) ;
        frame.processingStateChanged( ProcessingState.RUNNING ) ;
        scheduler.submitBatch( tasks, route, this::isProcessing ) ;
        new Thread( this::runBatch, "qtranscriber-run" ).start() ;
    }

    /**
     * Run thread body: wait until every job sent, including those added
     * since the run started, is done.
     */
    private void runBatch() {

        try {
            do {
                scheduler.awaitBatch() ;
            }
            while( !endRun() ) ;
        }
        catch( InterruptedException e ) {
            Thread.currentThread().interrupt() ;
            endRun() ;
        }
        log.info( "Model run stopped" ) ;
    }

    /**
     * End the run unless jobs were added since the batch was last found
     * done; under the lock {@link #submit} adds them with.
     *
     * @return true if the run ended
     */
    private synchronized boolean endRun() {

        if( !Thread.currentThread().isInterrupted()
            && scheduler.hasBatchWork() ) return false ;
        stopRequested = false ;
        waiting.clear() ;
        processing.set( false ) ;
        frame.processingStateChanged( ProcessingState.STOPPED ) ;
        return true ;
    }

    private boolean isProcessing() {
        return processing.get() && !stopRequested ;
    }

    @Override
    public boolean isRunning() {
        return processing.get() ;
    }

    /** Stop the run: jobs not sent yet are dropped; requests in flight finish. */
    @Override
    public void stop() {

        if( processing.get() && !stopRequested ) {
            stopRequested = true ;
            frame.processingStateChanged( ProcessingState.STOPPING ) ;
            log.info( "Stop scheduled - running requests will finish" ) ;
        }
    }

    // ---- what the models report (hub threads) --------------------------

    @Override
    public void jobStarted( TranscriptionJob job ) {
        frame.jobStarted( job ) ;
    }

    @Override
    public void jobAttempt( TranscriptionJob job, String model, int attempt ) {
        frame.jobAttempt( job, model, attempt ) ;
    }

    @Override
    public void requestSent( AttemptRequest request ) {
        frame.requestSent( request ) ;
    }

    @Override
    public void attemptFinished( TranscriptionJob job ) {
        frame.attemptFinished( job ) ;
    }

    /**
     * Stage a question's transcription, or hold a passage and send the
     * questions waiting for it, then tell the frame.
     */
    @Override
    public void transcribed( TranscriptionJob job, Transcription t,
                             TranscriptionRoute route, boolean provisional ) {

        ServerQuestion q = job.item().question() ;
        String         what = switch( job.kind() ) {
            case QUESTION     -> "question" ;
            case LCT_QUESTION -> "part" ;
            case PASSAGE      -> "passage" ;
        } ;

        try {
            audit.transcribed( q, job.lctCtxId(), what, t, route,
                               provisional ) ;
        }
        catch( IOException e ) {
            log.error( "{}: could not write the audit log", job, e ) ;
        }

        if( job.kind() == TranscriptionJob.Kind.PASSAGE ) {
            passageTranscribed( job, new Passage( job.lctCtxId(), t, route,
                                                  false ) ) ;
            return ;
        }
        TranscriptionAuditLog.Given g = new TranscriptionAuditLog.Given(
                Instant.now(), t, route ) ;
        given.merge( q.getId(), List.of( g ), ( a, b ) -> {
            List<TranscriptionAuditLog.Given> all = new ArrayList<>( a ) ;
            all.addAll( b ) ;
            return List.copyOf( all ) ;
        } ) ;
        stage( job.item(), new Staged( t, route, false, provisional ) ) ;
        frame.transcribed( job, t, route, provisional ) ;
    }

    /** Hold {@code p}, tell the frame, and send its group's waiting questions. */
    private void passageTranscribed( TranscriptionJob job, Passage p ) {

        holdPassage( p ) ;
        frame.passageKnown( p ) ;

        synchronized( this ) {
            List<TranscriptionItem> group = waiting.remove( job.lctCtxId() ) ;
            if( group == null || group.isEmpty() || !isProcessing() ) return ;
            submit( group.stream()
                         .map( i -> TranscriptionJob.question(
                                 i, p.content(), false ) )
                         .toList(), null ) ;
        }
    }

    @Override
    public void jobFailed( TranscriptionJob job, int attempts, String reason ) {

        if( job.kind() == TranscriptionJob.Kind.PASSAGE ) {
            synchronized( this ) {
                waiting.remove( job.lctCtxId() ) ;
            }
        }
        frame.jobFailed( job, attempts, reason ) ;
    }

    // ---- the workspace -------------------------------------------------

    @Override
    public void stage( TranscriptionItem item, Staged s ) {
        try {
            staged.put( item.id(), item.question().getQuestionId(),
                        item.lctCtxId(), s, prompt.sha256() ) ;
        }
        catch( IOException e ) {
            log.error( "{}: could not stage the transcription", item, e ) ;
        }
    }

    @Override
    public void holdPassage( Passage p ) {

        passages.put( p.lctCtxId(), p ) ;
        try {
            held.put( p, prompt.sha256() ) ;
        }
        catch( IOException e ) {
            log.error( "Could not hold the passage of {}", p.lctCtxId(), e ) ;
        }
    }

    @Override
    public Passage passage( String lctCtxId ) {
        return lctCtxId == null ? null : passages.get( lctCtxId ) ;
    }

    @Override
    public void mark( TranscriptionItem item, String mark,
                      TranscriptionRoute route ) {
        try {
            audit.mark( item.question(), mark, route ) ;
        }
        catch( IOException e ) {
            log.error( "{}: could not write the audit log", item, e ) ;
        }
    }

    @Override
    public void edited( TranscriptionItem item, String what, String before,
                        String after ) {
        try {
            audit.edited( item.question(), item.lctCtxId(), what, before,
                          after ) ;
        }
        catch( IOException e ) {
            log.error( "{}: could not write the audit log", item, e ) ;
        }
    }

    // ---- saving --------------------------------------------------------

    /**
     * Store {@code savings} on the server, at most
     * {@link TranscriberServerClient#MAX_SAVE} per request, a group's
     * questions together after its first; an LCT question gets its group's
     * passage in front, and the group's first carries the passage if the
     * server doesn't hold it. A request that fails refuses all of its
     * items, with the reason.
     *
     * @return the server's result for each of {@code savings}, in order
     */
    @Override
    public List<SaveResult> save( List<Saving> savings ) {

        List<Saving>           ordered = grouped( savings ) ;
        Map<Saving,SaveResult> results = new HashMap<>() ;
        List<Saving>           batch   = new ArrayList<>() ;
        List<SaveItem>         items   = new ArrayList<>() ;
        Set<String>            carried = new HashSet<>() ;

        for( Saving s : ordered ) {
            SaveItem item = saveItem( s, carried ) ;
            if( item == null ) {
                results.put( s, SaveResult.failed( s.item().id(),
                        "the passage of " + s.item().lctCtxId() +
                        " is not known" ) ) ;
                continue ;
            }
            batch.add( s ) ;
            items.add( item ) ;
            if( items.size() == TranscriberServerClient.MAX_SAVE ) {
                saveBatch( batch, items, results ) ;
                batch = new ArrayList<>() ;
                items = new ArrayList<>() ;
            }
        }
        if( !items.isEmpty() ) saveBatch( batch, items, results ) ;
        for( String lctCtxId : carried ) {
            Passage p = passages.get( lctCtxId ) ;
            if( p != null && p.stored() ) resync( p ) ;
        }
        return savings.stream().map( results::get ).toList() ;
    }

    /**
     * Group {@code p}'s questions on the server whose content no longer
     * starts with its passage, {@code p} just stored, are saved again with
     * it in front of their own question part (feature-design.md §7.4); no
     * model is asked. A failure is only logged: the next save of the group
     * tries again.
     */
    private void resync( Passage p ) {

        List<SaveItem> items = new ArrayList<>() ;
        try {
            for( LctContext.Member m : server.lctContext( p.lctCtxId() )
                                             .questions() ) {
                if( !Boolean.FALSE.equals( m.current() ) ) continue ;

                StoredTranscription st   = server.transcription( m.id() ) ;
                String              part = st == null ? null
                        : Transcription.questionPart( st.content() ) ;
                if( part == null ) {
                    log.warn( "{}: {} has no question part to keep",
                              p.lctCtxId(), m.questionId() ) ;
                    continue ;
                }
                items.add( new SaveItem( m.id(), st.aiModel(),
                        Transcription.lctContent( p.content(), part ),
                        null, null ) ) ;
            }
            for( int i = 0 ; i < items.size() ;
                 i += TranscriberServerClient.MAX_SAVE ) {
                List<SaveItem>   chunk   = items.subList( i, Math.min(
                        items.size(), i + TranscriberServerClient.MAX_SAVE ) ) ;
                List<SaveResult> answers = server.save( chunk ) ;

                for( int j = 0 ; j < chunk.size() ; j++ ) {
                    log.info( "{}: question {} saved again with the new " +
                              "passage - {}", p.lctCtxId(),
                              chunk.get( j ).questionId(),
                              answers.get( j ).status() ) ;
                }
            }
        }
        catch( ServerException e ) {
            log.error( "{}: could not save its questions again with the new " +
                       "passage: {}", p.lctCtxId(), e.getMessage() ) ;
        }
    }

    /** {@code savings}, each LCT group's questions moved up to its first. */
    private static List<Saving> grouped( List<Saving> savings ) {

        Map<String,List<Saving>> groups = new LinkedHashMap<>() ;
        List<Saving>             out    = new ArrayList<>() ;

        for( Saving s : savings ) {
            String key = s.item().isLct() ? s.item().lctCtxId()
                                          : "#" + s.item().id() ;
            groups.computeIfAbsent( key, k -> new ArrayList<>() ).add( s ) ;
        }
        groups.values().forEach( out::addAll ) ;
        return out ;
    }

    /**
     * The item to send for {@code s}; for the first of an LCT group not
     * stored yet ({@code carried} notes which), with the passage. Null if
     * an LCT question's passage isn't known.
     */
    private SaveItem saveItem( Saving s, Set<String> carried ) {

        TranscriptionItem item = s.item() ;
        String            text = s.transcription().content() ;
        String            ai   = s.route().aiModel() ;

        if( !item.isLct() ) return new SaveItem( item.id(), ai, text, null,
                                                 null ) ;

        Passage p = passages.get( item.lctCtxId() ) ;
        if( p == null ) return null ;

        String content = Transcription.lctContent( p.content(), text ) ;
        if( p.stored() || !carried.add( item.lctCtxId() ) ) {
            return new SaveItem( item.id(), ai, content, null, null ) ;
        }
        return new SaveItem( item.id(), ai, content, p.route().aiModel(),
                             p.content() ) ;
    }

    private void saveBatch( List<Saving> batch, List<SaveItem> items,
                            Map<Saving,SaveResult> results ) {

        List<SaveResult> answers ;
        try {
            answers = server.save( items ) ;
        }
        catch( ServerException e ) {
            log.error( "Could not save {} transcriptions: {}", items.size(),
                       e.getMessage() ) ;
            for( Saving s : batch ) {
                results.put( s, SaveResult.failed( s.item().id(),
                                                   e.getMessage() ) ) ;
            }
            return ;
        }

        List<Integer> applied = new ArrayList<>() ;
        for( int i = 0 ; i < batch.size() ; i++ ) {
            Saving     s    = batch.get( i ) ;
            SaveItem   item = items.get( i ) ;
            SaveResult r    = answers.get( i ) ;

            log.info( "{}: {} - {}{}", s.item(), item.aiModel(), r.status(),
                      r.accepted() ? "" : ": " + r.message() ) ;
            results.put( s, r ) ;
            record( s, item, r ) ;
            if( !r.accepted() ) continue ;

            applied.add( s.item().id() ) ;
            if( item.hasPassage() ) passageStored( s.item().lctCtxId() ) ;
        }
        try {
            staged.remove( applied ) ;
        }
        catch( IOException e ) {
            log.error( "Could not drop {} saved transcriptions from the " +
                       "workspace", applied.size(), e ) ;
        }
    }

    /** The server now holds group {@code lctCtxId}'s passage. */
    private void passageStored( String lctCtxId ) {

        Passage p = passages.computeIfPresent( lctCtxId,
                                               ( k, v ) -> v.asStored() ) ;
        try {
            held.remove( lctCtxId ) ;
        }
        catch( IOException e ) {
            log.error( "Could not drop the held passage of {}", lctCtxId, e ) ;
        }
        if( p != null ) frame.passageKnown( p ) ;
    }

    /** Note the save in the audit log; a failure is only logged. */
    private void record( Saving s, SaveItem item, SaveResult r ) {
        try {
            audit.saved( s.item().question(), item,
                         s.transcription().withContent( item.content() ),
                         s.route(), r ) ;
        }
        catch( IOException e ) {
            log.error( "{}: could not write the audit log", s.item(), e ) ;
        }
    }

    @Override
    public StoredTranscription stored( int id ) throws ServerException {
        return server.transcription( id ) ;
    }

    // ---- the hub's state -----------------------------------------------

    @Override
    public List<RateLimitNotice> rateLimitNotices() {
        return scheduler.rateLimitNotices() ;
    }

    @Override
    public HubStatus hubStatus() {
        return scheduler.hubStatus() ;
    }

    /**
     * Why Qwen can't be asked: its gateway unreachable, or it held for the
     * day; null if it can.
     */
    @Override
    public String qwenDown() {

        HubStatus   hs   = scheduler.hubStatus() ;
        Set<String> qwen = new HashSet<>() ;

        config.getAi().root( TranscriberOutcomes.TRANSCRIBE )
              .forEach( m -> qwen.add( m.gateway().name() ) ) ;
        for( HubStatus.GatewayStatus g : hs.gateways() ) {
            if( qwen.contains( g.name() ) && !g.reachable() ) {
                return "Qwen's server (" + g.name() + ") is unreachable" ;
            }
        }
        return null ;
    }

    /**
     * Why the unattended run must stop: Qwen is down and the free Nemotron
     * is held for the day, so only the paid one is left; null otherwise.
     */
    @Override
    public String unattendedStop() {

        String down = qwenDown() ;
        if( down == null ) return null ;

        String held = heldForTheDay( config.getAi().root(
                TranscriberOutcomes.ESCALATE ), false ) ;
        return held == null ? null
                            : down + " and the free Nemotron is held for " +
                              "the day" ;
    }

    /**
     * "held" if every usable model of {@code models} (with their fallbacks,
     * if {@code fallbacks}) is held for the day, by its own daily limit or
     * its gateway's; null otherwise.
     */
    private String heldForTheDay( List<ModelInfo> models,
                                  boolean fallbacks ) {

        AIConfig      ai   = config.getAi() ;
        HubStatus     hs   = scheduler.hubStatus() ;
        Set<String>   ids  = new HashSet<>() ;
        Deque<String> next = new ArrayDeque<>() ;

        models.forEach( m -> next.add( m.id() ) ) ;
        while( !next.isEmpty() ) {
            String id = next.pop() ;
            if( !ids.add( id ) || !fallbacks ) continue ;

            FallbackInfo fb = ai.model( id ).fallback() ;
            fb.guards().values().forEach( next::addAll ) ;
            next.addAll( fb.defaults() ) ;
        }

        Set<String> dayGateways = new HashSet<>() ;
        hs.gateways().stream()
          .filter( HubStatus.GatewayStatus::heldForTheDay )
          .forEach( g -> dayGateways.add( g.name() ) ) ;

        boolean held = hs.models().stream()
                         .filter( m -> m.usable() && ids.contains( m.id() ) )
                         .allMatch( m -> m.heldForTheDay()
                                         || dayGateways.contains( m.gateway() ) ) ;
        return held ? "held" : null ;
    }

    @Override
    public void setPassthrough( String modelId, boolean on ) {
        scheduler.setPassthrough( modelId, on ) ;
    }

    @Override
    public void abortRequest( long jobId ) {
        scheduler.abortRequest( jobId ) ;
    }

    @Override
    public TaskScheduler.Counts queueCounts() {
        return scheduler.counts() ;
    }

    @Override
    public long nextRequestAt() {
        return scheduler.nextRequestAt() ;
    }

    @Override
    public void shutdown() {
        log.debug( "Shutting down the AI hub" ) ;
        stopRequested = true ;
        scheduler.shutdown() ;
    }
}
