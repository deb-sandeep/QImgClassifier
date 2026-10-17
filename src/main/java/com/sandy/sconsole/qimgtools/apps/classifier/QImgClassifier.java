package com.sandy.sconsole.qimgtools.apps.classifier;

import com.sandy.sconsole.qimgtools.core.ai.AIInvoker;
import com.sandy.sconsole.qimgtools.apps.classifier.pipeline.ClassificationListener;
import com.sandy.sconsole.qimgtools.apps.classifier.pipeline.QSourceClassifier;
import com.sandy.sconsole.qimgtools.apps.classifier.pipeline.QuestionClassifier;
import com.sandy.sconsole.qimgtools.core.scheduler.TaskScheduler;
import com.sandy.sconsole.qimgtools.core.model.ProcessingState;
import com.sandy.sconsole.qimgtools.core.model.QTask;
import com.sandy.sconsole.qimgtools.core.model.RateLimitNotice;
import com.sandy.sconsole.qimgtools.core.model.Topic;
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
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Application entry point and batch orchestrator.
 * <p>
 * Discovery walks the sources named in {@code qclassifier.sources.txt} (minus
 * those already in the processed list) and builds a plan of pending tasks per
 * source. Start queues every pending question of the plan with the
 * {@link TaskScheduler}, which routes them across sources by subject (see
 * there), and waits on a batch thread until they are done. Stop is
 * cooperative: running tasks finish, no new ones start.
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

    private final QClassifierConfig  config ;
    private final AIInvoker          aiInvoker ;
    private final ProcessedList      processedList ;
    private final QuestionClassifier classifier ;
    private final TaskScheduler      scheduler ;
    private final ClassificationListener listener ;

    /** True while a batch thread is alive. */
    private final AtomicBoolean processing = new AtomicBoolean( false ) ;

    /**
     * Set by {@link #stopProcessing()}; the batch thread then spawns no new
     * tasks.
     */
    private volatile boolean stopRequested = false ;
    private volatile Thread  batchThread ;

    /**
     * Pending work per source, in source-list order; null until discovery has
     * run.
     */
    private volatile List<SourceWork> plan ;

    /** A source and the tasks it had pending at discovery. */
    private record SourceWork( QSourceClassifier sc, List<QTask> pending ) {}

    /** @param listener receives discovery and task events; may be null */
    public QImgClassifier( ClassificationListener listener,
                           QClassifierConfig config ) {

        File workspaceDir = config.getWorkspaceDir() ;
        if( !workspaceDir.isDirectory() && !workspaceDir.mkdirs() ) {
            log.error( "Could not create the workspace folder {}", workspaceDir ) ;
        }
        this.config        = config ;
        this.aiInvoker     = AIInvoker.fromConfig( config, PROMPT_DIR ) ;
        this.listener      = listener != null ? listener
                                              : ClassificationListener.NOOP ;
        this.processedList = new ProcessedList( config.getProcessedListFile() ) ;
        this.classifier    = new QuestionClassifier(
                this.listener, processedList,
                new TranscriptStore( config.getWorkspaceDir() ),
                new UsageLog( config.getUsageLogFile() ),
                config.getQuestionFilter() ) ;
        this.scheduler     = new TaskScheduler( aiInvoker, classifier ) ;

        scheduler.start() ;
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

        if( headless ) {
            QImgClassifier app = new QImgClassifier( ClassificationListener.NOOP,
                                                     config ) ;
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
                ModelNames names = ModelNames.of( config.getGemma(),
                                                  config.getOpenRouter() ) ;
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

        // Populate the table with the current backlog; processing waits for
        // Start.
        new Thread( holder[0]::ensureDiscovered, "qclassifier-discover" ).start() ;
    }

    // ---- discovery ---------------------------------------------------

    /**
     * Build {@link #plan} once: discover every listed, not-yet-processed
     * source.
     */
    private synchronized void ensureDiscovered() {

        if( plan != null ) return ;

        Set<String>  processed = processedList.load() ;
        List<String> sources   = loadSourceNames() ;
        List<SourceWork> p = new ArrayList<>() ;

        for( int i = 0 ; i < sources.size() ; i++ ) {
            String srcName = sources.get( i ) ;
            listener.batchProgress( i + 1, sources.size(), srcName ) ;
            if( processed.contains( srcName ) ) continue ;
            SourceWork sw = discoverSource( srcName ) ;
            if( sw != null ) p.add( sw ) ;
        }
        plan = p ;
        int pendingCount = p.stream().mapToInt( sw -> sw.pending().size() ).sum() ;

        log.info( "Discovered {} pending tasks across {} sources",
                  pendingCount, p.size() ) ;
    }

    /**
     * Locate and discover one source; null (and an error logged) if its folder
     * cannot be found.
     */
    private SourceWork discoverSource( String srcName ) {

        File srcDir = FileHelper.findDir( config.getQBankDir(), srcName ) ;
        if( srcDir == null ) {
            log.error( "Source directory {} not found under {}",
                       srcName, config.getQBankDir() ) ;
            return null ;
        }
        QSourceClassifier sc = new QSourceClassifier( srcDir, classifier ) ;
        return new SourceWork( sc, sc.discover() ) ;
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
        batchThread = new Thread( this::runPlan, "qclassifier-batch" ) ;
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
     * Batch thread body: queue the still-unclassified questions of every
     * planned source, then wait until they are done or the stop has wound
     * them up.
     */
    private void runPlan() {

        try {
            ensureDiscovered() ;
            List<SourceWork> p     = plan ;
            List<QTask>      batch = new ArrayList<>() ;

            for( SourceWork sw : p ) {
                File        srcDir     = sw.sc().getSrcDir() ;
                Set<String> classified =
                        WorkspaceTopicMap.classifiedQuestionIds( srcDir ) ;

                for( QTask t : sw.pending() ) {
                    if( !classified.contains( t.getQuestionId() ) ) batch.add( t ) ;
                }
                // Covers a source with nothing pending that was never listed.
                classifier.markProcessedIfComplete( srcDir ) ;
            }
            listener.batchProgress( p.size(), p.size(),
                                    batch.size() + " questions queued" ) ;
            scheduler.submitBatch( batch, this::isProcessing ) ;
            scheduler.awaitBatch() ;
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

    /** Block until the current batch thread (if any) has finished. */
    public void awaitBatch() {
        Thread t = batchThread ;
        if( t != null ) {
            try { t.join() ; }
            catch( InterruptedException e ) { Thread.currentThread().interrupt() ; }
        }
    }

    // ---- UI actions ----------------------------------------------------

    /**
     * {@inheritDoc}
     * <p>
     * Sources dropped from the list are discovered and appended to the plan, so
     * their unclassified questions show up and are picked up by the next Start.
     */
    @Override
    public synchronized ProcessedListRepair.Report repairProcessedList(
                                ProcessedListRepair.ProgressListener progress ) {

        if( processing.get() ) {
            throw new IllegalStateException(
                    "Stop processing before repairing the processed list" ) ;
        }
        ensureDiscovered() ;
        ProcessedListRepair.Report report = ProcessedListRepair.run(
                config.getQBankDir(), loadSourceNames(), processedList,
                config.getQuestionFilter(), progress ) ;

        Set<String> planned = plan.stream()
                                  .map( sw -> sw.sc().getSourceName() )
                                  .collect( Collectors.toSet() ) ;
        List<SourceWork> p = new ArrayList<>( plan ) ;

        for( String srcName : report.getRemoved() ) {
            if( planned.contains( srcName ) ) continue ;
            SourceWork sw = discoverSource( srcName ) ;
            if( sw != null ) p.add( sw ) ;
        }
        plan = p ;
        return report ;
    }

    @Override
    public List<RateLimitNotice> rateLimitNotices() {
        return aiInvoker.rateLimitNotices() ;
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
        return outcome ;
    }

    @Override
    public void shutdown() {

        log.debug( "Shutting down the task scheduler" ) ;
        stopRequested = true ;
        scheduler.shutdown() ;
    }

    // ---- helpers -------------------------------------------------------

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
