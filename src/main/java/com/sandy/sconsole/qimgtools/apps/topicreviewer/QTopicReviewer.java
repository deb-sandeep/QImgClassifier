package com.sandy.sconsole.qimgtools.apps.topicreviewer;

import com.sandy.sconsole.qimgtools.core.ai.AIInvoker;
import com.sandy.sconsole.qimgtools.core.ai.AIProcessor;
import com.sandy.sconsole.qimgtools.core.ai.OpenRouterProcessor;
import com.sandy.sconsole.qimgtools.core.scheduler.TaskScheduler;
import com.sandy.sconsole.qimgtools.core.model.ProcessingState;
import com.sandy.sconsole.qimgtools.core.model.QTask;
import com.sandy.sconsole.qimgtools.core.model.RateLimitNotice;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewCriteria;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewDecision;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewItem;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewResult;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewVerdict;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ServerQuestion;
import com.sandy.sconsole.qimgtools.core.model.Subject;
import com.sandy.sconsole.qimgtools.core.model.Topic;
import com.sandy.sconsole.qimgtools.core.model.TopicRepo;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.review.QuestionLocator;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.review.ReviewListener;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.review.ReviewOutcomes;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.server.PendingResult;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.server.SConsoleServerClient;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.server.SConsoleServerClient.ServerException;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.ui.TopicReviewFrame;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.util.ReviewConfig;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.workspace.ReviewCriteriaStore;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.workspace.PendingVerdicts;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.workspace.ReviewAuditLog;
import com.sandy.sconsole.qimgtools.core.util.ModelNames;
import com.sandy.sconsole.qimgtools.core.workspace.TranscriptStore;
import com.sandy.sconsole.qimgtools.core.workspace.UsageLog;

import lombok.extern.slf4j.Slf4j;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Entry point and controller of the topic reviewer: re-checks the topic
 * associations awaiting review on the SConsole server with a stronger
 * OpenRouter model, and records the user's decisions back on the server.
 * <p>
 * Load fetches the first questions of the review queue
 * ({@code review.syllabus.names} or {@code review.topic.ids}) in place
 * of those shown, and each question's images are found in the local question bank. Start sends the questions
 * without a verdict to the model through the classifier's
 * {@link TaskScheduler} (retries, backup models, rate limits); each reply is
 * compared with the server's topic and kept in {@link PendingVerdicts} until
 * the server settles the question (and in the {@link ReviewAuditLog}). The
 * user then confirms a topic for each question, which is sent to the
 * server's review endpoint in batches with its provenance (see
 * {@link ReviewDecision#of}). Settings come from
 * {@code qtopicreview.properties} ({@link ReviewConfig}).
 */
@Slf4j
public class QTopicReviewer implements TopicReviewFrame.Controller {

    /** The subjects whose topics the model chooses from. */
    private static final List<Subject> SUBJECTS =
            List.of( Subject.PHYSICS, Subject.CHEMISTRY, Subject.MATHS ) ;

    /**
     * The classifier's prompt templates and syllabus files: the review asks
     * the model the classifier's question again.
     */
    private static final String PROMPT_DIR = "/apps/classifier/" ;

    private final ReviewConfig         config ;
    private final SConsoleServerClient server ;
    private final TopicRepo            topicRepo ;
    private final PendingVerdicts      pending ;
    private final ReviewAuditLog       audit ;
    private final QuestionLocator      locator ;
    private final AIInvoker            aiInvoker ;
    private final ReviewOutcomes       outcomes ;
    private final TaskScheduler        scheduler ;
    private final ReviewListener       listener ;

    /** True while a batch thread is alive. */
    private final AtomicBoolean processing = new AtomicBoolean( false ) ;

    /** Set by {@link #stop()}; queued questions are then dropped. */
    private volatile boolean stopRequested ;

    /**
     * What Load asks for: as last saved in the criteria
     * dialog, else as configured.
     */
    private volatile ReviewCriteria criteria ;

    private final ReviewCriteriaStore criteriaStore ;

    public QTopicReviewer( ReviewConfig config, SConsoleServerClient server,
                           TopicRepo topicRepo, String apiKey,
                           TopicReviewFrame frame ) {

        this.config    = config ;
        this.server    = server ;
        this.topicRepo = topicRepo ;
        this.listener  = frame ;
        this.pending   = new PendingVerdicts( config.getPendingVerdictsFile() ) ;
        this.audit     = new ReviewAuditLog( config.getReviewAuditFile() ) ;
        this.locator   = new QuestionLocator( config.getQBankDir() ) ;
        this.criteriaStore = new ReviewCriteriaStore(
                config.getReviewCriteriaFile() ) ;

        ReviewCriteria saved = criteriaStore.load(
                syllabusNames(), SConsoleServerClient.MAX_LIMIT ) ;
        this.criteria = saved != null
                      ? saved
                      : new ReviewCriteria( config.getSyllabusNames(),
                                            config.getTopicIds(),
                                            config.getFetchLimit() ) ;

        AIProcessor openRouter = new OpenRouterProcessor(
                apiKey, config.getOpenRouter(),
                config.isOpenRouterPromptCaching() ) ;
        log.info( "AI processor: openrouter - {}", config.getOpenRouter() ) ;

        this.aiInvoker = new AIInvoker( List.of( openRouter ),
                                        config.getRequestHistoryFile(),
                                        topicRepo, PROMPT_DIR ) ;
        this.outcomes  = new ReviewOutcomes(
                frame, pending, audit,
                new TranscriptStore( config.getWorkspaceDir() ),
                new UsageLog( config.getUsageLogFile() ), topicRepo ) ;
        this.scheduler = new TaskScheduler( aiInvoker, outcomes ) ;

        scheduler.start() ;
    }

    public static void main( String[] args ) {

        ReviewConfig config ;
        try {
            config = ReviewConfig.load() ;
            if( !config.getOpenRouter().isEnabled() ) {
                throw new IllegalStateException( ReviewConfig.RESOURCE +
                        ": openrouter.enabled must be true" ) ;
            }
            if( config.getOpenRouterApiKey() == null ) {
                throw new IllegalStateException( "No OpenRouter API key " +
                        "found (openrouter.api.key.file)" ) ;
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

        SConsoleServerClient server = new SConsoleServerClient(
                config.getServerUrl(), config.getServerConnectTimeout(),
                config.getServerRequestTimeout() ) ;
        TopicRepo topicRepo ;
        try {
            topicRepo = loadTopics( server ) ;
        }
        catch( ServerException e ) {
            fail( "SConsole server", "Could not fetch the topic list from " +
                  config.getServerUrl() + ":\n" + e.getMessage(), e ) ;
            return ;
        }

        try {
            SwingUtilities.invokeAndWait( () -> {
                TopicReviewFrame frame = new TopicReviewFrame(
                        new TranscriptStore( config.getWorkspaceDir() ),
                        ModelNames.of( config.getOpenRouter() ) ) ;
                QTopicReviewer app = new QTopicReviewer(
                        config, server, topicRepo,
                        config.getOpenRouterApiKey(), frame ) ;

                frame.setController( app ) ;
                frame.showScope() ;
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

    /** The server's topics of every reviewed subject. */
    private static TopicRepo loadTopics( SConsoleServerClient server )
            throws ServerException {

        List<Topic> topics = new ArrayList<>() ;
        for( Subject subject : SUBJECTS ) {
            List<Topic> ts = server.topics( subject.getSyllabusName() ) ;
            if( ts.isEmpty() ) {
                throw new ServerException( "the server has no topics for " +
                                           subject.getSyllabusName() ) ;
            }
            topics.addAll( ts ) ;
        }
        log.info( "Loaded {} topics from the server", topics.size() ) ;
        return new TopicRepo( topics ) ;
    }

    // ---- Controller ----------------------------------------------------

    /**
     * The first questions of the review queue matching the criteria in
     * effect, by id. There is no cursor: confirmed questions leave the
     * queue, so the questions after them move up, while ones still flagged
     * (undecided, refused, or reviewed by a model the server doesn't count
     * as strong) come back.
     */
    @Override
    public TopicReviewFrame.Loaded load() throws ServerException {

        ReviewCriteria criteria = this.criteria ;
        PendingResult  r        = server.pending( criteria.syllabusNames(),
                                                  criteria.topicIds(),
                                                  criteria.limit() ) ;

        List<ReviewItem> items = r.getQuestions().stream()
                                  .map( locator::locate ).toList() ;
        long located = items.stream().filter( ReviewItem::hasImages ).count() ;

        log.info( "Loaded {} questions ({} with local images); {} await " +
                  "review", items.size(), located, r.getTotalResults() ) ;
        return new TopicReviewFrame.Loaded( items, r.getTotalResults() ) ;
    }

    @Override
    public ReviewVerdict storedVerdict( ReviewItem item ) {
        return pending.verdict( item.question() ) ;
    }

    /**
     * Send {@code items} to the review model: start a run, or add them to
     * the one going. Ignored while a run is stopping.
     */
    @Override
    public synchronized void start( List<ReviewItem> items ) {

        if( stopRequested ) return ;
        List<QTask> tasks = new ArrayList<>() ;

        for( ReviewItem item : items ) {
            outcomes.track( item ) ;
            tasks.add( item.task() ) ;
        }
        log.info( "Sending {} questions to the review model", tasks.size() ) ;
        if( processing.get() ) {
            scheduler.submitBatch( tasks, this::isProcessing ) ;
            return ;
        }
        processing.set( true ) ;
        listener.processingStateChanged( ProcessingState.RUNNING ) ;
        scheduler.submitBatch( tasks, this::isProcessing ) ;
        new Thread( this::runBatch, "qtopicreview-batch" ).start() ;
    }

    /**
     * Batch thread body: wait until every question sent, including those
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
        listener.processingStateChanged( ProcessingState.STOPPED ) ;
        log.info( "Review run stopped" ) ;
    }

    /**
     * End the run unless questions were added since the batch was last
     * found done; under the lock {@link #start} adds them with.
     *
     * @return true if the run ended
     */
    private synchronized boolean endRun() {

        if( !Thread.currentThread().isInterrupted()
            && scheduler.hasBatchWork() ) return false ;
        stopRequested = false ;
        processing.set( false ) ;
        return true ;
    }

    private boolean isProcessing() {
        return processing.get() && !stopRequested ;
    }

    @Override
    public void stop() {

        if( processing.get() && !stopRequested ) {
            stopRequested = true ;
            listener.processingStateChanged( ProcessingState.STOPPING ) ;
            log.info( "Stop scheduled - running requests will finish" ) ;
        }
    }

    /**
     * Send {@code decisions} to the server, at most
     * {@link SConsoleServerClient#MAX_REVIEWS} per request, and note each one
     * it took in the review log. A request that fails refuses all of its
     * decisions, with the reason.
     *
     * @return the server's result for each decision, in the same order
     */
    @Override
    public List<ReviewResult> confirm( List<ReviewDecision> decisions ) {

        List<ReviewResult> results = new ArrayList<>() ;
        int                size    = SConsoleServerClient.MAX_REVIEWS ;

        for( int i = 0 ; i < decisions.size() ; i += size ) {
            results.addAll( confirmBatch( decisions.subList(
                    i, Math.min( decisions.size(), i + size ) ) ) ) ;
        }
        return results ;
    }

    private List<ReviewResult> confirmBatch( List<ReviewDecision> batch ) {

        List<ReviewResult> results ;
        try {
            results = server.review( batch ) ;
        }
        catch( ServerException e ) {
            log.error( "Could not confirm {} reviews: {}", batch.size(),
                       e.getMessage() ) ;
            return batch.stream()
                        .map( d -> ReviewResult.failed( d.item().id(),
                                                        e.getMessage() ) )
                        .toList() ;
        }

        List<ServerQuestion> settled = new ArrayList<>() ;

        for( int i = 0 ; i < batch.size() ; i++ ) {
            ReviewDecision d    = batch.get( i ) ;
            ReviewResult   r    = results.get( i ) ;
            String         note = !r.accepted()    ? ": " + r.message()
                                : r.stillFlagged() ? ", still flagged" : "" ;

            log.info( "{}: topic {} ({} {}) - {}{}", d.item(),
                      d.topic().getId(), d.actor(),
                      d.model() != null ? d.model() : "", r.status(), note ) ;
            if( !r.accepted() ) continue ;

            recordDecision( d, r ) ;
            // A question still flagged comes back in the next fetch; its
            // verdict is kept so it isn't asked again.
            if( !r.stillFlagged() ) settled.add( d.item().question() ) ;
        }
        try {
            pending.remove( settled ) ;
        }
        catch( Exception e ) {
            log.error( "Could not drop {} settled verdicts", settled.size(),
                       e ) ;
        }
        return results ;
    }

    /** Note the decision in the audit log; a failure is only logged. */
    private void recordDecision( ReviewDecision d, ReviewResult r ) {
        try {
            audit.decision( d.item().question(), d, r ) ;
        }
        catch( Exception e ) {
            log.error( "{}: could not record the decision in the audit log",
                       d.item(), e ) ;
        }
    }

    @Override
    public ReviewCriteria criteria() {
        return criteria ;
    }

    /** Use {@code criteria} from now on, and save them for the next run. */
    @Override
    public void setCriteria( ReviewCriteria criteria ) throws IOException {

        log.info( "Review criteria: {}, {} per fetch", criteria.describe(),
                  criteria.limit() ) ;
        this.criteria = criteria ;
        criteriaStore.save( criteria ) ;
    }

    @Override
    public List<String> syllabusNames() {
        return SUBJECTS.stream().map( Subject::getSyllabusName ).toList() ;
    }

    @Override
    public List<Topic> topics( String syllabusName ) {

        return SUBJECTS.stream()
                       .filter( s -> s.getSyllabusName().equals( syllabusName ) )
                       .findFirst()
                       .map( topicRepo::getTopics )
                       .orElse( List.of() ) ;
    }

    @Override
    public List<RateLimitNotice> rateLimitNotices() {
        return aiInvoker.rateLimitNotices() ;
    }

    @Override
    public TaskScheduler.Counts queueCounts() {
        return scheduler.counts() ;
    }

    @Override
    public String scope() {

        return criteria.describe() + " (" + criteria.limit() + " per fetch) - " +
               config.getOpenRouter().getModel() + " @ " + config.getServerUrl() ;
    }

    @Override
    public void shutdown() {
        log.debug( "Shutting down the task scheduler" ) ;
        stopRequested = true ;
        scheduler.shutdown() ;
    }
}
