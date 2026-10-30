package com.sandy.sconsole.qimgtools.apps.nxrclassifier;

import com.sandy.sconsole.ai.AIHub;
import com.sandy.sconsole.ai.HubStatus;
import com.sandy.sconsole.ai.RoutingPolicy;
import com.sandy.sconsole.ai.config.AIConfig;
import com.sandy.sconsole.ai.config.FallbackInfo;
import com.sandy.sconsole.qimgtools.apps.nxrclassifier.llm.NXROutcomes;
import com.sandy.sconsole.qimgtools.apps.nxrclassifier.llm.NXRPrompter;
import com.sandy.sconsole.qimgtools.apps.nxrclassifier.model.NXRItem;
import com.sandy.sconsole.qimgtools.apps.nxrclassifier.model.NXRReply;
import com.sandy.sconsole.qimgtools.apps.nxrclassifier.model.NXRSaveResult;
import com.sandy.sconsole.qimgtools.apps.nxrclassifier.model.NXRUpdate;
import com.sandy.sconsole.qimgtools.apps.nxrclassifier.server.NXRServerClient;
import com.sandy.sconsole.qimgtools.apps.nxrclassifier.ui.NXRFrame;
import com.sandy.sconsole.qimgtools.apps.nxrclassifier.util.NXRConfig;
import com.sandy.sconsole.qimgtools.apps.nxrclassifier.workspace.NXRAuditLog;
import com.sandy.sconsole.qimgtools.core.model.ProcessingState;
import com.sandy.sconsole.qimgtools.core.model.QTask;
import com.sandy.sconsole.qimgtools.core.model.RateLimitNotice;
import com.sandy.sconsole.qimgtools.core.review.QuestionLocator;
import com.sandy.sconsole.qimgtools.core.scheduler.TaskScheduler;
import com.sandy.sconsole.qimgtools.core.server.PendingResult;
import com.sandy.sconsole.qimgtools.core.server.SConsoleServer;
import com.sandy.sconsole.qimgtools.core.server.SConsoleServer.ServerException;
import com.sandy.sconsole.qimgtools.core.util.FileHelper;
import com.sandy.sconsole.qimgtools.core.util.ImageCollator;
import com.sandy.sconsole.qimgtools.core.util.ModelNames;
import com.sandy.sconsole.qimgtools.core.workspace.TranscriptStore;
import com.sandy.sconsole.qimgtools.core.workspace.UsageLog;
import com.sandy.sconsole.nxr.NXRException;
import com.sandy.sconsole.nxr.NXRResult;

import lombok.extern.slf4j.Slf4j;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * Entry point and controller of the NXR classifier: labels the questions on
 * the SConsole server N (numeric), X (reasoning) or R (recall), and saves
 * the labels on the server.
 * <p>
 * Load fetches the next questions with no label after the frame's cursor
 * ({@code GET /Master/Question/NXR/Pending}); each question's images are
 * found in the local question bank ({@link QuestionLocator}), stacked into
 * one ({@link ImageCollator}) and given, with the question's subject,
 * type and topic, to the local {@link com.sandy.sconsole.nxr.NXRClassifier}
 * (which shares this class's name). Its label is taken as it is. The
 * questions it leaves undecided may be sent to an AI model (the
 * {@link NXROutcomes#ROOT} root of {@code nxrclassifier.ai.json}, through
 * an {@link AIHub} taking them in order), whose label is taken as it is too;
 * the user labels the rest, or overrides either, in the {@link NXRFrame}.
 * Save sends the labels in batches ({@code POST /Master/Question/NXR}) and
 * notes each one the server took in the {@link NXRAuditLog}. Settings come
 * from {@code nxrclassifier.properties} ({@link NXRConfig}).
 */
@Slf4j
public class NXRClassifier implements NXRFrame.Controller {

    /** True for an LCT group's context image, left out of what is sent. */
    private static final Predicate<File> CONTEXT = f -> FileHelper
            .baseQuestionId( FileHelper.extractQuestionId( f ) )
            .endsWith( "_Ctx" ) ;

    private final NXRConfig       config ;
    private final NXRServerClient server ;
    private final QuestionLocator locator ;
    private final NXRAuditLog     audit ;
    private final NXRFrame        listener ;

    /** The local classifier; thread-safe. */
    private final com.sandy.sconsole.nxr.NXRClassifier classifier ;

    private final NXROutcomes             outcomes ;
    private final TaskScheduler<NXRReply> scheduler ;

    /** True while an AI run is going. */
    private final AtomicBoolean processing = new AtomicBoolean( false ) ;

    /** True once Stop is asked for, until the run ends. */
    private volatile boolean stopRequested ;

    public NXRClassifier( NXRConfig config, NXRServerClient server,
                          com.sandy.sconsole.nxr.NXRClassifier classifier,
                          NXRFrame frame ) {

        this.config     = config ;
        this.server     = server ;
        this.classifier = classifier ;
        this.listener   = frame ;
        this.locator    = new QuestionLocator( config.getQBankDir() ) ;
        this.audit      = new NXRAuditLog( config.getAuditFile() ) ;

        AIHub hub = AIHub.create( config.getAi(), new AIHub.Options(
                config.getRequestHistoryFile(), RoutingPolicy.inOrder(),
                "nxrclassifier" ) ) ;

        this.outcomes  = new NXROutcomes(
                frame, new TranscriptStore( config.getWorkspaceDir() ),
                new UsageLog( config.getUsageLogFile() ) ) ;
        this.scheduler = new TaskScheduler<>(
                hub, new NXRPrompter( outcomes::item ), outcomes ) ;
    }

    public static void main( String[] args ) {

        NXRConfig config ;
        try {
            config = NXRConfig.load() ;
            if( !config.getAi().isRootUsable( NXROutcomes.ROOT ) ) {
                log.warn( "{}: root '{}' is missing or has no model " +
                          "enabled - asking the AI is disabled",
                          config.getAi().appLocation(), NXROutcomes.ROOT ) ;
            }
        }
        catch( IllegalStateException e ) {
            fail( "Invalid configuration", e.getMessage(), e ) ;
            return ;
        }

        File workspaceDir = config.getWorkspaceDir() ;
        if( !workspaceDir.isDirectory() && !workspaceDir.mkdirs() ) {
            log.error( "Could not create the workspace folder {}", workspaceDir ) ;
        }

        com.sandy.sconsole.nxr.NXRClassifier classifier ;
        try {
            classifier = com.sandy.sconsole.nxr.NXRClassifier.load(
                    config.getModelFile().toPath(),
                    config.getTesseractBin().toPath() ) ;
        }
        catch( NXRException e ) {
            fail( "NXR classifier", "Could not load the classifier:\n" +
                  e.getMessage(), e ) ;
            return ;
        }

        NXRServerClient server = new NXRServerClient(
                new SConsoleServer( config.getServerUrl(),
                                    config.getServerConnectTimeout(),
                                    config.getServerRequestTimeout() ) ) ;
        try {
            SwingUtilities.invokeAndWait( () -> {
                NXRFrame      frame = new NXRFrame(
                        config.getBatchSize(), config.getClassifyThreads(),
                        config.getServerUrl(),
                        new TranscriptStore( config.getWorkspaceDir() ),
                        ModelNames.of( config.getAi() ) ) ;
                NXRClassifier app   = new NXRClassifier( config, server,
                                                         classifier, frame ) ;

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

    // ---- Controller ----------------------------------------------------

    @Override
    public NXRFrame.Loaded load( int batchSize, Integer afterId )
            throws ServerException {

        PendingResult r = server.pending( Math.max( 1, Math.min(
                batchSize, NXRServerClient.MAX_BATCH ) ), afterId ) ;
        List<NXRItem> items = r.getQuestions().stream()
                               .map( locator::locate )
                               .map( NXRItem::of )
                               .toList() ;

        log.info( "Loaded {} questions, {} await a label", items.size(),
                  r.getTotalResults() ) ;
        return new NXRFrame.Loaded( items, r.getTotalResults() ) ;
    }

    /**
     * The classifier's result for {@code item}: its own images stacked into
     * one (an LCT group's shared context left out, as the classifier asks),
     * with its subject, type and server topic.
     */
    @Override
    public NXRResult classify( NXRItem item ) throws IOException {

        List<File> own = item.task().getImgFiles().stream()
                             .filter( CONTEXT.negate() )
                             .toList() ;
        NXRResult  r   = classifier.classify(
                ImageCollator.collate( own ),
                item.task().getSubject().getCode(),
                item.question().getProblemType(),
                item.question().getTopicId() ) ;

        log.debug( "{}: {} {} {} - {}", item, r.value(), r.confidence(),
                   r.method(), r.reason() ) ;
        return r ;
    }

    /**
     * Send {@code updates} to the server, at most
     * {@link NXRServerClient#MAX_UPDATES} per request, and note each one it
     * took in the audit log. A request that fails refuses all of its
     * updates, with the reason.
     */
    @Override
    public List<NXRSaveResult> save( List<NXRUpdate> updates ) {

        List<NXRSaveResult> results = new ArrayList<>() ;
        int             size    = NXRServerClient.MAX_UPDATES ;

        for( int i = 0 ; i < updates.size() ; i += size ) {
            results.addAll( saveBatch( updates.subList(
                    i, Math.min( updates.size(), i + size ) ) ) ) ;
        }
        return results ;
    }

    private List<NXRSaveResult> saveBatch( List<NXRUpdate> batch ) {

        List<NXRSaveResult> results ;
        try {
            results = server.update( batch ) ;
        }
        catch( ServerException e ) {
            log.error( "Could not save {} labels: {}", batch.size(),
                       e.getMessage() ) ;
            return batch.stream()
                        .map( u -> NXRSaveResult.failed( u.item().id(),
                                                     e.getMessage() ) )
                        .toList() ;
        }

        for( int i = 0 ; i < batch.size() ; i++ ) {
            NXRUpdate u = batch.get( i ) ;
            NXRSaveResult r = results.get( i ) ;

            log.info( "{}: {} - {}{}", u.item(), u.describe(), r.status(),
                      r.accepted() ? "" : ": " + r.message() ) ;
            if( r.accepted() ) record( u, r ) ;
        }
        return results ;
    }

    // ---- the AI ----------------------------------------------------------

    @Override
    public boolean aiAvailable() {
        return config.getAi().isRootUsable( NXROutcomes.ROOT ) ;
    }

    /**
     * Send {@code items} to the AI model: start a run, or add them to the
     * one going. Ignored while a run is stopping.
     */
    @Override
    public synchronized void askAI( List<NXRItem> items ) {

        if( stopRequested || !aiAvailable() ) return ;
        List<QTask> tasks = new ArrayList<>() ;

        for( NXRItem item : items ) {
            outcomes.track( item ) ;
            tasks.add( item.task() ) ;
        }
        log.info( "Asking the AI about {} questions", tasks.size() ) ;
        if( processing.get() ) {
            scheduler.submitBatch( tasks, this::isProcessing ) ;
            return ;
        }
        processing.set( true ) ;
        listener.processingStateChanged( ProcessingState.RUNNING ) ;
        scheduler.submitBatch( tasks, this::isProcessing ) ;
        new Thread( this::runBatch, "nxrclassifier-ai" ).start() ;
    }

    /**
     * AI run thread body: wait until every question sent, including those
     * added since the run started, is done.
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
        log.info( "AI run stopped" ) ;
    }

    /**
     * End the run unless questions were added since the batch was last
     * found done; under the lock {@link #askAI} adds them with.
     *
     * @return true if the run ended
     */
    private synchronized boolean endRun() {

        if( !Thread.currentThread().isInterrupted()
            && scheduler.hasBatchWork() ) return false ;
        stopRequested = false ;
        processing.set( false ) ;
        listener.processingStateChanged( ProcessingState.STOPPED ) ;
        return true ;
    }

    private boolean isProcessing() {
        return processing.get() && !stopRequested ;
    }

    @Override
    public boolean isAIRunning() {
        return processing.get() ;
    }

    /**
     * Stop the AI run: questions not sent yet are dropped, requests in
     * flight finish.
     */
    @Override
    public void stopAI() {

        if( processing.get() && !stopRequested ) {
            stopRequested = true ;
            listener.processingStateChanged( ProcessingState.STOPPING ) ;
            log.info( "AI stop scheduled - running requests will finish" ) ;
        }
    }

    @Override
    public List<RateLimitNotice> rateLimitNotices() {
        return scheduler.rateLimitNotices() ;
    }

    @Override
    public HubStatus hubStatus() {
        return scheduler.hubStatus() ;
    }

    /**
     * Why no more questions can be sent today: every usable model the root
     * reaches, its own and those in their fallbacks, is held for the day,
     * by its own daily limit or its gateway's; null otherwise.
     */
    @Override
    public String heldForTheDay() {

        AIConfig      ai   = config.getAi() ;
        HubStatus     hs   = scheduler.hubStatus() ;
        Set<String>   ids  = new HashSet<>() ;
        Deque<String> next = new ArrayDeque<>() ;

        ai.root( NXROutcomes.ROOT ).forEach( m -> next.add( m.id() ) ) ;
        while( !next.isEmpty() ) {
            String id = next.pop() ;
            if( !ids.add( id ) ) continue ;

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
        return held ? "every model of the " + NXROutcomes.ROOT + " root, " +
                      "fallbacks included, is held for the day"
                    : null ;
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

    /** Note the label in the audit log; a failure is only logged. */
    private void record( NXRUpdate u, NXRSaveResult r ) {
        try {
            audit.saved( u, r ) ;
        }
        catch( Exception e ) {
            log.error( "{}: could not record the label in the audit log",
                       u.item(), e ) ;
        }
    }
}
