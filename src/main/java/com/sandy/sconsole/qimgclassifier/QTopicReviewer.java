package com.sandy.sconsole.qimgclassifier;

import com.sandy.sconsole.qimgclassifier.ai.AIInvoker;
import com.sandy.sconsole.qimgclassifier.ai.AIProcessor;
import com.sandy.sconsole.qimgclassifier.ai.OpenRouterProcessor;
import com.sandy.sconsole.qimgclassifier.classifier.TaskScheduler;
import com.sandy.sconsole.qimgclassifier.model.ProcessingState;
import com.sandy.sconsole.qimgclassifier.model.QTask;
import com.sandy.sconsole.qimgclassifier.model.RateLimitNotice;
import com.sandy.sconsole.qimgclassifier.model.ReviewCriteria;
import com.sandy.sconsole.qimgclassifier.model.ReviewDecision;
import com.sandy.sconsole.qimgclassifier.model.ReviewItem;
import com.sandy.sconsole.qimgclassifier.model.ReviewResult;
import com.sandy.sconsole.qimgclassifier.model.ReviewVerdict;
import com.sandy.sconsole.qimgclassifier.model.ServerQuestion;
import com.sandy.sconsole.qimgclassifier.model.Subject;
import com.sandy.sconsole.qimgclassifier.model.Topic;
import com.sandy.sconsole.qimgclassifier.model.TopicRepo;
import com.sandy.sconsole.qimgclassifier.review.QuestionLocator;
import com.sandy.sconsole.qimgclassifier.review.ReviewListener;
import com.sandy.sconsole.qimgclassifier.review.ReviewOutcomes;
import com.sandy.sconsole.qimgclassifier.server.PendingResult;
import com.sandy.sconsole.qimgclassifier.server.SConsoleServerClient;
import com.sandy.sconsole.qimgclassifier.server.SConsoleServerClient.ServerException;
import com.sandy.sconsole.qimgclassifier.ui.TopicReviewFrame;
import com.sandy.sconsole.qimgclassifier.util.ReviewConfig;
import com.sandy.sconsole.qimgclassifier.workspace.ReviewCriteriaStore;
import com.sandy.sconsole.qimgclassifier.workspace.ReviewLog;
import com.sandy.sconsole.qimgclassifier.workspace.TranscriptStore;
import com.sandy.sconsole.qimgclassifier.workspace.UsageLog;

import lombok.extern.slf4j.Slf4j;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Entry point and controller of the topic reviewer: re-checks the topic
 * associations awaiting review on the SConsole server with a stronger
 * OpenRouter model, and records the user's decisions back on the server.
 * <p>
 * Load fetches the first questions of the review queue
 * ({@code review.syllabus.names} or {@code review.topic.ids}), Fetch more
 * fetches them again and adds those not loaded yet, and each question's
 * images are found in the local question bank. Start sends the questions
 * without a verdict to the model through the classifier's
 * {@link TaskScheduler} (retries, backup models, rate limits); each reply is
 * compared with the server's topic and stored in the {@link ReviewLog}. The
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

    private final ReviewConfig         config ;
    private final SConsoleServerClient server ;
    private final TopicRepo            topicRepo ;
    private final ReviewLog            reviewLog ;
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
     * What Load and Fetch more ask for: as last saved in the criteria
     * dialog, else as configured.
     */
    private volatile ReviewCriteria criteria ;

    private final ReviewCriteriaStore criteriaStore ;

    /** Server ids of the questions loaded since the last fresh Load. */
    private final Set<Integer> loaded = ConcurrentHashMap.newKeySet() ;

    public QTopicReviewer( ReviewConfig config, SConsoleServerClient server,
                           TopicRepo topicRepo, String apiKey,
                           TopicReviewFrame frame ) {

        this.config    = config ;
        this.server    = server ;
        this.topicRepo = topicRepo ;
        this.listener  = frame ;
        this.reviewLog = new ReviewLog( config.getReviewLogFile() ) ;
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
                                        topicRepo ) ;
        this.outcomes  = new ReviewOutcomes(
                frame, reviewLog, new TranscriptStore( config.getWorkspaceDir() ),
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
                        new TranscriptStore( config.getWorkspaceDir() ) ) ;
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
     * effect, by id. A fresh Load ({@code more} false) forgets the questions
     * loaded so far; Fetch more keeps them and returns only the ones not
     * loaded yet. There is no cursor: confirmed questions leave the queue, so the questions after
     * them move up, while loaded ones still flagged (undecided, refused, or
     * reviewed by a model the server doesn't count as strong) come back and
     * are skipped.
     */
    @Override
    public TopicReviewFrame.Loaded load( boolean more ) throws ServerException {

        ReviewCriteria criteria = this.criteria ;

        if( !more ) loaded.clear() ;

        PendingResult r = server.pending( criteria.syllabusNames(),
                                          criteria.topicIds(),
                                          criteria.limit() ) ;
        List<ServerQuestion> fresh = r.getQuestions().stream()
                                      .filter( q -> !loaded.contains( q.getId() ) )
                                      .toList() ;

        fresh.forEach( q -> loaded.add( q.getId() ) ) ;
        List<ReviewItem> items = fresh.stream().map( locator::locate ).toList() ;
        long located = items.stream().filter( ReviewItem::hasImages ).count() ;

        log.info( "Loaded {} new of {} questions fetched ({} with local " +
                  "images); {} await review", items.size(),
                  r.getQuestions().size(), located, r.getTotalResults() ) ;
        return new TopicReviewFrame.Loaded( items, r.getTotalResults() ) ;
    }

    @Override
    public ReviewVerdict storedVerdict( ReviewItem item ) {
        return reviewLog.verdict( item.question() ) ;
    }

    @Override
    public void start( List<ReviewItem> items ) {

        if( !processing.compareAndSet( false, true ) ) return ;
        stopRequested = false ;
        listener.processingStateChanged( ProcessingState.RUNNING ) ;

        List<QTask> tasks = new ArrayList<>() ;
        for( ReviewItem item : items ) {
            outcomes.track( item ) ;
            tasks.add( item.task() ) ;
        }
        new Thread( () -> runBatch( tasks ), "qtopicreview-batch" ).start() ;
    }

    /** Batch thread body: queue the tasks and wait until they are done. */
    private void runBatch( List<QTask> tasks ) {

        try {
            log.info( "Sending {} questions to the review model", tasks.size() ) ;
            scheduler.submitBatch( tasks, this::isProcessing ) ;
            scheduler.awaitBatch() ;
        }
        catch( InterruptedException e ) {
            Thread.currentThread().interrupt() ;
        }
        finally {
            stopRequested = false ;
            processing.set( false ) ;
            listener.processingStateChanged( ProcessingState.STOPPED ) ;
            log.info( "Review run stopped" ) ;
        }
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

        for( int i = 0 ; i < batch.size() ; i++ ) {
            ReviewDecision d    = batch.get( i ) ;
            ReviewResult   r    = results.get( i ) ;
            String         note = !r.accepted()    ? ": " + r.message()
                                : r.stillFlagged() ? ", still flagged" : "" ;

            log.info( "{}: topic {} ({} {}) - {}{}", d.item(),
                      d.topic().getId(), d.actor(),
                      d.model() != null ? d.model() : "", r.status(), note ) ;
            if( r.accepted() ) recordDecision( d, r ) ;
        }
        return results ;
    }

    /** Note the decision in the review log; a failure is only logged. */
    private void recordDecision( ReviewDecision d, ReviewResult r ) {
        try {
            reviewLog.saveDecision( d.item().question(), d, r ) ;
        }
        catch( Exception e ) {
            log.error( "{}: could not record the decision in the review log",
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
