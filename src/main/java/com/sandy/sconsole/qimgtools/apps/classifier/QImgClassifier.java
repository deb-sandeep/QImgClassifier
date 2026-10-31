package com.sandy.sconsole.qimgtools.apps.classifier;

import com.sandy.sconsole.ai.HubStatus;
import com.sandy.sconsole.ai.AIHub;
import com.sandy.sconsole.qimgtools.core.ai.TopicPrompter;
import com.sandy.sconsole.qimgtools.apps.classifier.pipeline.ClassificationListener;
import com.sandy.sconsole.qimgtools.apps.classifier.pipeline.ClassifierRouting;
import com.sandy.sconsole.qimgtools.apps.classifier.pipeline.QSourceClassifier;
import com.sandy.sconsole.qimgtools.apps.classifier.pipeline.QuestionClassifier;
import com.sandy.sconsole.qimgtools.core.scheduler.TaskScheduler;
import com.sandy.sconsole.qimgtools.core.sequence.TopicSequenceGenerator;
import com.sandy.sconsole.qimgtools.core.server.SConsoleServer;
import com.sandy.sconsole.qimgtools.apps.classifier.model.TaskStatus;
import com.sandy.sconsole.qimgtools.core.model.AIAgent;
import com.sandy.sconsole.qimgtools.core.model.AttemptRequest;
import com.sandy.sconsole.qimgtools.core.model.ProcessingState;
import com.sandy.sconsole.qimgtools.core.model.QTask;
import com.sandy.sconsole.qimgtools.core.model.RateLimitNotice;
import com.sandy.sconsole.qimgtools.core.model.Topic;
import com.sandy.sconsole.qimgtools.core.model.TopicRepo;
import com.sandy.sconsole.qimgtools.apps.classifier.ui.QClassifierFrame;
import com.sandy.sconsole.qimgtools.core.util.FileHelper;
import com.sandy.sconsole.qimgtools.core.util.ModelNames;
import com.sandy.sconsole.qimgtools.core.util.QClassifierConfig;
import com.sandy.sconsole.qimgtools.apps.classifier.net.QImgExtractorClient;
import com.sandy.sconsole.qimgtools.apps.classifier.workspace.ProcessedList;
import com.sandy.sconsole.qimgtools.apps.classifier.workspace.ProcessedListRepair;
import com.sandy.sconsole.qimgtools.apps.classifier.workspace.QuestionTopicAssociator;
import com.sandy.sconsole.qimgtools.core.workspace.TranscriptStore;
import com.sandy.sconsole.qimgtools.core.workspace.UsageLog;
import com.sandy.sconsole.qimgtools.apps.classifier.workspace.WorkspaceTopicMap;

import lombok.extern.slf4j.Slf4j;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Application entry point and batch orchestrator.
 * <p>
 * Loading and submitting are decoupled by a blocking queue. A loader thread
 * discovers the sources named in {@code qclassifier.sources.txt} (minus those
 * already in the processed list) one at a time, keeping at most
 * {@link #MAX_LOADED_SOURCES} with unsettled questions, and puts their
 * pending questions on {@link #ready}. Start runs a batch thread that takes
 * them from there and hands them to the {@link TaskScheduler}, whose AI hub
 * shares them out across the models of the app's AI config by subject
 * ({@link ClassifierRouting}), keeping at most {@link #MAX_QUEUED_QUESTIONS}
 * in the hub. A source is unloaded once each of its questions has finished
 * (classified or failed), which lets the loader take the next. Stop is
 * cooperative: running tasks finish, no new ones start, and the questions
 * dropped unstarted go back to the front of {@link #ready}.
 * <p>
 * Runs with the {@link QClassifierFrame} UI, or with {@code --headless} to
 * process the whole backlog once and exit.
 */
@Slf4j
public class QImgClassifier implements QClassifierFrame.Controller {

    /**
     * Classpath resource listing the source folder names to process, one per
     * line ({@code #} comments out).
     */
    private static final String SOURCES_RESOURCE =
            "/apps/classifier/qclassifier.sources.txt" ;

    /** Classpath folder of the prompt templates and syllabus files. */
    private static final String PROMPT_DIR = "/apps/classifier/" ;

    /** Most sources loaded with questions not yet finished. */
    private static final int MAX_LOADED_SOURCES = 15 ;

    /** Most batch questions queued or running in the AI hub. */
    private static final int MAX_QUEUED_QUESTIONS = 50 ;

    /** How long the batch thread waits on {@link #ready} at a time. */
    private static final long POLL_MS = 500 ;

    private final QClassifierConfig  config ;
    private final ProcessedList      processedList ;
    private final QuestionClassifier classifier ;
    private final TaskScheduler<List<Topic>> scheduler ;
    private final ClassificationListener listener ;
    private final TopicSequenceGenerator sequenceGenerator ;

    /** True while a batch thread is alive. */
    private final AtomicBoolean processing = new AtomicBoolean( false ) ;

    /**
     * Set by {@link #stopProcessing()}; the batch thread then spawns no new
     * tasks.
     */
    private volatile boolean stopRequested = false ;
    private volatile Thread  batchThread ;

    /** Source names waiting for the loader, in source-list order. */
    private final BlockingQueue<String> toLoad = new LinkedBlockingQueue<>() ;

    /**
     * Source names put on {@link #toLoad} whose questions are not yet all on
     * {@link #ready}.
     */
    private final AtomicInteger namesPending = new AtomicInteger() ;

    /**
     * Pending questions of the loaded sources, waiting for the batch thread;
     * the loader adds at the end, a stop puts dropped ones back at the front.
     */
    private final BlockingDeque<QTask> ready = new LinkedBlockingDeque<>() ;

    /**
     * Guards {@link #unsettled}, {@link #submitted}, {@link #seen} and
     * {@link #sourceCount}; the loader waits on it for a free source slot.
     */
    private final Object loadLock = new Object() ;

    /**
     * Per loaded source, the ids of its pending questions not yet finished;
     * a source leaves once none is left.
     */
    private final Map<File,Set<String>> unsettled = new HashMap<>() ;

    /** Questions this batch handed to the scheduler, in order. */
    private final List<QTask> submitted = new ArrayList<>() ;

    /** Source names ever put on {@link #toLoad}. */
    private final Set<String> seen = new HashSet<>() ;

    private int sourceCount ;
    private int sourcesLoaded ;

    private volatile Thread loaderThread ;

    /** @param listener receives discovery and task events; may be null */
    public QImgClassifier( ClassificationListener listener,
                           QClassifierConfig config ) {

        File workspaceDir = config.getWorkspaceDir() ;
        if( !workspaceDir.isDirectory() && !workspaceDir.mkdirs() ) {
            log.error( "Could not create the workspace folder {}", workspaceDir ) ;
        }
        this.config        = config ;
        this.listener      = listener != null ? listener
                                              : ClassificationListener.NOOP ;
        this.processedList = new ProcessedList( config.getProcessedListFile() ) ;
        this.classifier    = new QuestionClassifier(
                new SettlingListener( this.listener ), processedList,
                new TranscriptStore( config.getWorkspaceDir() ),
                new UsageLog( config.getUsageLogFile() ),
                config.getQuestionFilter() ) ;

        AIHub hub = AIHub.create( config.getAi(), new AIHub.Options(
                config.getRequestHistoryFile(), new ClassifierRouting(),
                "qclassifier" ) ) ;
        this.scheduler = new TaskScheduler<>(
                hub, new TopicPrompter( TopicRepo.instance(), PROMPT_DIR ),
                classifier ) ;
        this.sequenceGenerator = sequenceGenerator( config ) ;
    }

    private static TopicSequenceGenerator sequenceGenerator(
            QClassifierConfig config ) {

        return new TopicSequenceGenerator( new SConsoleServer(
                config.getServerUrl(), config.getServerConnectTimeout(),
                config.getServerRequestTimeout() ) ) ;
    }

    /**
     * {@code --headless}: process the backlog once without UI. Otherwise open
     * the UI and discover the backlog.
     */
    public static void main( String[] args ) {

        boolean headless = Arrays.asList( args ).contains( "--headless" ) ;
        QClassifierConfig config ;

        try {
            config = QClassifierConfig.load() ;
        }
        catch( IllegalStateException e ) {
            log.error( "Invalid configuration: {}", e.getMessage(), e ) ;
            if( !headless ) {
                JOptionPane.showMessageDialog( null, e.getMessage(),
                        "Invalid configuration", JOptionPane.ERROR_MESSAGE ) ;
            }
            System.exit( 1 ) ;
            return ;
        }

        // Topics can't be associated without the topic sequence
        try {
            sequenceGenerator( config ).generateIfMissing() ;
        }
        catch( Exception e ) {
            String msg = "Could not generate the missing topic sequence from " +
                         config.getServerUrl() + ":\n" + e.getMessage() ;
            log.error( msg, e ) ;
            if( !headless ) {
                JOptionPane.showMessageDialog( null, msg, "Topic sequence",
                                               JOptionPane.ERROR_MESSAGE ) ;
            }
            System.exit( 1 ) ;
            return ;
        }

        if( headless ) {
            QImgClassifier app = new QImgClassifier( ClassificationListener.NOOP,
                                                     config ) ;
            app.startLoading() ;
            app.startProcessing() ;
            app.awaitBatch() ;
            app.shutdown() ;
            return ;
        }

        QImgClassifier[] holder = new QImgClassifier[1] ;
        try {
            SwingUtilities.invokeAndWait( () -> {
                QImgExtractorClient extractor =
                        new QImgExtractorClient( config.getQImgExtractorApiUrl() ) ;
                TranscriptStore transcripts =
                        new TranscriptStore( config.getWorkspaceDir() ) ;
                ModelNames names = ModelNames.of( config.getAi() ) ;
                QClassifierFrame frame =
                        new QClassifierFrame( extractor, transcripts, names ) ;
                QImgClassifier   app   = new QImgClassifier( frame, config ) ;

                frame.setController( app ) ;
                frame.setVisible( true ) ;
                holder[0] = app ;
            } ) ;
        }
        catch( Exception e ) {
            log.error( "Could not start UI", e ) ;
            return ;
        }

        // Populate the table with the first sources of the backlog;
        // processing waits for Start.
        holder[0].startLoading() ;
    }

    // ---- loading -----------------------------------------------------

    /**
     * Queue every listed, not-yet-processed source for loading and start the
     * loader thread. Call once.
     */
    private void startLoading() {

        Set<String> processed = processedList.load() ;
        for( String srcName : loadSourceNames() ) {
            if( !processed.contains( srcName ) ) enqueueSource( srcName ) ;
        }
        loaderThread = new Thread( this::runLoader, "qclassifier-loader" ) ;
        loaderThread.setDaemon( true ) ;
        loaderThread.start() ;
    }

    /** Put {@code srcName} on {@link #toLoad}, unless it was put there before. */
    private void enqueueSource( String srcName ) {

        synchronized( loadLock ) {
            if( !seen.add( srcName ) ) return ;
            sourceCount++ ;
        }
        namesPending.incrementAndGet() ;
        toLoad.add( srcName ) ;
    }

    /**
     * Loader thread body: load each queued source in turn, once fewer than
     * {@link #MAX_LOADED_SOURCES} are loaded. Runs until interrupted.
     */
    private void runLoader() {

        try {
            while( true ) {
                String srcName = toLoad.take() ;
                try {
                    awaitSourceSlot() ;
                    loadSource( srcName ) ;
                }
                catch( RuntimeException e ) {
                    log.error( "Error loading source {}", srcName, e ) ;
                }
                finally {
                    namesPending.decrementAndGet() ;
                }
            }
        }
        catch( InterruptedException e ) {
            Thread.currentThread().interrupt() ;
        }
    }

    /** Block until fewer than {@link #MAX_LOADED_SOURCES} are loaded. */
    private void awaitSourceSlot() throws InterruptedException {
        synchronized( loadLock ) {
            while( unsettled.size() >= MAX_LOADED_SOURCES ) loadLock.wait() ;
        }
    }

    /**
     * Discover one source and put its pending questions on {@link #ready}.
     * A source that cannot be found is logged and skipped.
     */
    private void loadSource( String srcName ) {

        int index, count ;
        synchronized( loadLock ) {
            index = ++sourcesLoaded ;
            count = sourceCount ;
        }
        listener.batchProgress( index, count, srcName ) ;

        File srcDir = FileHelper.findDir( config.getQBankDir(), srcName ) ;
        if( srcDir == null ) {
            log.error( "Source directory {} not found under {}",
                       srcName, config.getQBankDir() ) ;
            return ;
        }
        if( QuestionTopicAssociator.isServerSynced( srcDir ) ) {
            log.info( "Source {} is synced to the server - marked processed, " +
                      "not loaded", srcName ) ;
            processedList.add( srcName ) ;
            return ;
        }
        List<QTask> pending =
                new QSourceClassifier( srcDir, classifier ).discover() ;

        // Covers a source with nothing pending that was never listed.
        classifier.markProcessedIfComplete( srcDir ) ;
        if( pending.isEmpty() ) return ;

        synchronized( loadLock ) {
            unsettled.put( srcDir, pending.stream()
                                          .map( QTask::getQuestionId )
                                          .collect( Collectors.toSet() ) ) ;
        }
        ready.addAll( pending ) ;
        log.info( "Loaded source {} ({} of {}) - {} questions pending",
                  srcName, index, count, pending.size() ) ;
    }

    /**
     * The task finished (or was associated by hand): off its source's
     * unsettled questions, unloading the source once none is left.
     */
    private void settled( QTask task ) {

        synchronized( loadLock ) {
            Set<String> ids = unsettled.get( task.getSrcDir() ) ;
            if( ids == null || !ids.remove( task.getQuestionId() ) ) return ;
            if( ids.isEmpty() ) {
                unsettled.remove( task.getSrcDir() ) ;
                loadLock.notifyAll() ;
                log.debug( "Source {} settled - unloaded",
                           task.getSrcDir().getName() ) ;
            }
        }
    }

    /** True if {@code task} is pending in a loaded source. */
    private boolean isUnsettled( QTask task ) {
        synchronized( loadLock ) {
            Set<String> ids = unsettled.get( task.getSrcDir() ) ;
            return ids != null && ids.contains( task.getQuestionId() ) ;
        }
    }

    /** True while a batch runs and no stop has been requested. */
    private boolean isProcessing() {
        return processing.get() && !stopRequested ;
    }

    // ---- batch -------------------------------------------------------

    @Override
    public void startProcessing() {
        if( !processing.compareAndSet( false, true ) ) return ;
        stopRequested = false ;
        listener.processingStateChanged( ProcessingState.RUNNING ) ;
        batchThread = new Thread( this::runBatch, "qclassifier-batch" ) ;
        batchThread.start() ;
    }

    @Override
    public void stopProcessing() {

        if( processing.get() && !stopRequested ) {
            stopRequested = true ;
            listener.processingStateChanged( ProcessingState.STOPPING ) ;
            log.info( "Stop scheduled - active tasks will finish, " +
                      "no new tasks will start" ) ;
        }
    }

    /**
     * Batch thread body: take the loaded questions off {@link #ready} and
     * hand them to the scheduler while fewer than
     * {@link #MAX_QUEUED_QUESTIONS} are in it, until every source is loaded
     * and its questions handed over, or a stop; then wait until they are
     * done or the stop has wound them up, and put the ones dropped unstarted
     * back at the front of {@link #ready}.
     */
    private void runBatch() {

        try {
            while( scheduler.awaitBatchBelow( MAX_QUEUED_QUESTIONS,
                                              this::isProcessing ) ) {
                // Read before polling: once 0, every question is on ready
                boolean allLoaded = namesPending.get() == 0 ;
                QTask   task      = ready.poll( POLL_MS, TimeUnit.MILLISECONDS ) ;

                if( task == null ) {
                    if( allLoaded ) break ;
                    continue ;
                }
                // Finished meanwhile (a re-queue, a manual association)
                if( !isUnsettled( task ) ) continue ;

                synchronized( loadLock ) { submitted.add( task ) ; }
                scheduler.submitBatch( List.of( task ), this::isProcessing ) ;
            }
            scheduler.awaitBatch() ;
            requeueDropped() ;
        }
        catch( InterruptedException e ) {
            Thread.currentThread().interrupt() ;
        }
        finally {
            stopRequested = false ;
            processing.set( false ) ;
            listener.processingStateChanged( ProcessingState.STOPPED ) ;
            log.info( "Processing stopped" ) ;
        }
    }

    /**
     * Put the questions handed over in this batch that have not finished
     * (dropped by a stop, or held) back at the front of {@link #ready}, in
     * their order, for the next Start.
     */
    private void requeueDropped() {

        synchronized( loadLock ) {
            for( int i = submitted.size() - 1 ; i >= 0 ; i-- ) {
                QTask task = submitted.get( i ) ;
                if( isUnsettled( task ) ) ready.addFirst( task ) ;
            }
            submitted.clear() ;
        }
    }

    /** Block until the current batch thread (if any) has finished. */
    public void awaitBatch() {
        Thread t = batchThread ;
        if( t != null ) {
            try { t.join() ; }
            catch( InterruptedException e ) { Thread.currentThread().interrupt() ; }
        }
    }

    // ---- UI actions ----------------------------------------------------

    @Override
    public TopicSequenceGenerator.Summary generateTopicSequence()
            throws Exception {
        return sequenceGenerator.generate() ;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Sources dropped from the list are queued for loading, unless they were
     * before, so their unclassified questions show up and are picked up by
     * the next Start.
     */
    @Override
    public synchronized ProcessedListRepair.Report repairProcessedList(
                                ProcessedListRepair.ProgressListener progress ) {

        if( processing.get() ) {
            throw new IllegalStateException(
                    "Stop processing before repairing the processed list" ) ;
        }
        ProcessedListRepair.Report report = ProcessedListRepair.run(
                config.getQBankDir(), loadSourceNames(), processedList,
                config.getQuestionFilter(), progress ) ;

        report.getRemoved().forEach( this::enqueueSource ) ;
        return report ;
    }

    @Override
    public List<RateLimitNotice> rateLimitNotices() {
        return scheduler.rateLimitNotices() ;
    }

    @Override
    public HubStatus hubStatus() {
        return scheduler.hubStatus() ;
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
    public void reprocess( QTask task ) {
        log.debug( "Re-queueing {}", task ) ;
        scheduler.submitFirst( task, () -> true ) ;
    }

    @Override
    public boolean holdForManualAssociation( QTask task ) {
        return scheduler.hold( task ) ;
    }

    @Override
    public void releaseFromManualAssociation( QTask task, boolean associated ) {
        if( scheduler.release( task, associated ) ) {
            log.debug( "Re-queued {} after cancelled manual association", task ) ;
        }
    }

    @Override
    public QuestionTopicAssociator.Outcome manualAssociate( QTask task, Topic topic )
            throws IOException {

        log.debug( "Manually associating {} with topic {}", task, topic.getId() ) ;
        File   srcDir = task.getSrcDir() ;
        String qId    = task.getQuestionId() ;

        WorkspaceTopicMap.writeManual( srcDir, qId, topic ) ;
        QuestionTopicAssociator.Outcome outcome =
                QuestionTopicAssociator.associateManual( srcDir, qId, topic ) ;
        classifier.markProcessedIfComplete( task.getSrcDir() ) ;
        settled( task ) ;
        return outcome ;
    }

    @Override
    public void shutdown() {

        log.debug( "Shutting down the AI hub" ) ;
        stopRequested = true ;
        Thread loader = loaderThread ;
        if( loader != null ) loader.interrupt() ;
        scheduler.shutdown() ;
    }

    // ---- helpers -------------------------------------------------------

    /**
     * Forwards every event to the app's listener, and marks a finished task
     * {@link #settled}.
     */
    private final class SettlingListener implements ClassificationListener {

        private final ClassificationListener target ;

        SettlingListener( ClassificationListener target ) {
            this.target = target ;
        }

        @Override
        public void taskDiscovered( QTask task, String storedResult ) {
            target.taskDiscovered( task, storedResult ) ;
        }

        @Override
        public void taskStarted( QTask task ) {
            target.taskStarted( task ) ;
        }

        @Override
        public void taskAttempt( QTask task, AIAgent agent, String model,
                                 int attempt ) {
            target.taskAttempt( task, agent, model, attempt ) ;
        }

        @Override
        public void taskRequestSent( AttemptRequest request ) {
            target.taskRequestSent( request ) ;
        }

        @Override
        public void taskAttemptFinished( QTask task ) {
            target.taskAttemptFinished( task ) ;
        }

        @Override
        public void taskFinished( QTask task, TaskStatus status, int attempts,
                                  String result ) {
            try {
                target.taskFinished( task, status, attempts, result ) ;
            }
            finally {
                settled( task ) ;
            }
        }

        @Override
        public void batchProgress( int sourceIndex, int sourceCount,
                                   String sourceName ) {
            target.batchProgress( sourceIndex, sourceCount, sourceName ) ;
        }

        @Override
        public void processingStateChanged( ProcessingState state ) {
            target.processingStateChanged( state ) ;
        }
    }

    /**
     * Source names from {@link #SOURCES_RESOURCE}, skipping blanks and
     * {@code #} lines; empty on error (logged).
     */
    private static List<String> loadSourceNames() {

        try( InputStream is =
                     QImgClassifier.class.getResourceAsStream( SOURCES_RESOURCE ) ) {
            if( is == null ) {
                log.error( "Sources resource {} not found", SOURCES_RESOURCE ) ;
                return List.of() ;
            }
            return new String( is.readAllBytes(), StandardCharsets.UTF_8 ).lines()
                    .map( String::trim )
                    .filter( s -> !s.isEmpty() && !s.startsWith( "#" ) )
                    .collect( Collectors.toList() ) ;
        }
        catch( IOException e ) {
            log.error( "Error loading sources resource {}", SOURCES_RESOURCE, e ) ;
            return List.of() ;
        }
    }
}
