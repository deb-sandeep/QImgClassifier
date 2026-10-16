package com.sandy.sconsole.qimgclassifier;

import com.sandy.sconsole.qimgclassifier.ai.AIInvoker;
import com.sandy.sconsole.qimgclassifier.classifier.ClassificationListener;
import com.sandy.sconsole.qimgclassifier.classifier.QSourceClassifier;
import com.sandy.sconsole.qimgclassifier.classifier.QuestionClassifier;
import com.sandy.sconsole.qimgclassifier.model.ProcessingState;
import com.sandy.sconsole.qimgclassifier.model.QTask;
import com.sandy.sconsole.qimgclassifier.model.Topic;
import com.sandy.sconsole.qimgclassifier.ui.QClassifierFrame;
import com.sandy.sconsole.qimgclassifier.util.FileHelper;
import com.sandy.sconsole.qimgclassifier.util.QClassifierConfig;
import com.sandy.sconsole.qimgclassifier.workspace.ProcessedList;
import com.sandy.sconsole.qimgclassifier.workspace.ProcessedListRepair;
import com.sandy.sconsole.qimgclassifier.workspace.QuestionTopicAssociator;
import com.sandy.sconsole.qimgclassifier.workspace.WorkspaceTopicMap;

import lombok.extern.slf4j.Slf4j;

import javax.swing.SwingUtilities;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Application entry point and batch orchestrator.
 * <p>
 * Discovery walks the sources named in {@code qclassifier.sources.txt} (minus
 * those already in the processed list) and builds a plan of pending tasks per
 * source. Start runs the plan source by source on a batch thread; each
 * {@link QSourceClassifier} feeds a shared worker pool sized to the AI
 * processors' total parallelism. Stop is cooperative: running tasks finish,
 * no new ones start.
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
    private static final String SOURCES_RESOURCE = "/qclassifier.sources.txt" ;

    private final QClassifierConfig  config ;
    private final ProcessedList      processedList ;
    private final QuestionClassifier classifier ;
    private final ClassificationListener listener ;

    /** One thread per processor slot, so every processor can be kept busy. */
    private final int                maxParallelThreads ;
    private final ExecutorService    executor ;

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

    /**
     * Sources whose {@code process} has already finished in the current run.
     */
    private final Set<QSourceClassifier> finishedThisRun =
            ConcurrentHashMap.newKeySet() ;

    /** A source and the tasks it had pending at discovery. */
    private record SourceWork( QSourceClassifier sc, List<QTask> pending ) {}

    /** @param listener receives discovery and task events; may be null */
    public QImgClassifier( ClassificationListener listener ) {

        this.config        = QClassifierConfig.load() ;
        AIInvoker aiInvoker = AIInvoker.fromConfig( config ) ;

        this.listener      = listener != null ? listener
                                              : ClassificationListener.NOOP ;
        this.processedList = new ProcessedList( QClassifierConfig.PROCESSED_LIST_FILE ) ;
        this.classifier    = new QuestionClassifier( aiInvoker, this.listener,
                                                     processedList,
                                                     config.getQuestionFilter() ) ;
        this.maxParallelThreads = aiInvoker.getMaxParallel() ;
        this.executor = Executors.newFixedThreadPool( maxParallelThreads ) ;
    }

    /**
     * {@code --headless}: process the backlog once without UI. Otherwise open
     * the UI and discover the backlog.
     */
    public static void main( String[] args ) {

        if( Arrays.asList( args ).contains( "--headless" ) ) {
            QImgClassifier app = new QImgClassifier( ClassificationListener.NOOP ) ;
            app.startProcessing() ;
            app.awaitBatch() ;
            app.shutdown() ;
            return ;
        }

        QImgClassifier[] holder = new QImgClassifier[1] ;
        try {
            SwingUtilities.invokeAndWait( () -> {
                QClassifierFrame frame = new QClassifierFrame() ;
                QImgClassifier app = new QImgClassifier( frame ) ;
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
        QSourceClassifier sc = new QSourceClassifier(
                srcDir, classifier, executor, this::isProcessing,
                maxParallelThreads ) ;
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
     * Batch thread body: process each planned source in turn until done or
     * stopped.
     */
    private void runPlan() {

        try {
            ensureDiscovered() ;
            finishedThisRun.clear() ;
            List<SourceWork> p = plan ;
            for( int i = 0 ; i < p.size() ; i++ ) {
                if( !isProcessing() ) break ;
                SourceWork sw = p.get( i ) ;
                listener.batchProgress( i + 1, p.size(), sw.sc().getSourceName() ) ;
                sw.sc().process( sw.pending() ) ;
                finishedThisRun.add( sw.sc() ) ;
            }
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
    public void reprocess( QTask task ) {
        log.debug( "Re-queueing {}", task ) ;
        executor.submit( classifier.classifyCallable( task, () -> true ) ) ;
    }

    @Override
    public boolean holdForManualAssociation( QTask task ) {
        QSourceClassifier sc = sourceOf( task ) ;
        return sc == null || sc.hold( task ) ;
    }

    @Override
    public void releaseFromManualAssociation( QTask task, boolean associated ) {

        QSourceClassifier sc = sourceOf( task ) ;
        if( sc == null || sc.release( task, associated ) || associated ) return ;
        // Its source already ran in this batch, so nothing would pick it up
        // until the next Start.
        if( isProcessing() && finishedThisRun.contains( sc ) ) {
            log.debug( "Scheduling {} after cancelled manual association", task ) ;
            executor.submit( classifier.classifyCallable( task,
                                                          this::isProcessing ) ) ;
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

        log.debug( "Shutting down classifier executor" ) ;
        stopRequested = true ;
        executor.shutdownNow() ;
        try {
            executor.awaitTermination( 10, TimeUnit.SECONDS ) ;
        }
        catch( InterruptedException e ) {
            Thread.currentThread().interrupt() ;
        }
    }

    // ---- helpers -------------------------------------------------------

    /**
     * The planned source the task belongs to; null before discovery has
     * finished.
     */
    private QSourceClassifier sourceOf( QTask task ) {

        List<SourceWork> p = plan ;
        if( p == null ) return null ;
        for( SourceWork sw : p ) {
            if( sw.sc().getSrcDir().equals( task.getSrcDir() ) ) return sw.sc() ;
        }
        return null ;
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
