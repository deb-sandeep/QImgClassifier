package com.sandy.sconsole.qimgtools.apps.tagger;

import com.sandy.sconsole.ai.AIHub;
import com.sandy.sconsole.ai.HubStatus;
import com.sandy.sconsole.ai.RoutingPolicy;
import com.sandy.sconsole.ai.config.AIConfig;
import com.sandy.sconsole.ai.config.ModelInfo;
import com.sandy.sconsole.qimgtools.apps.tagger.model.CatalogueTag;
import com.sandy.sconsole.qimgtools.apps.tagger.model.Scope;
import com.sandy.sconsole.qimgtools.apps.tagger.model.ServerTag;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagCatalogue;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagItem;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagReply;
import com.sandy.sconsole.qimgtools.apps.tagger.pipeline.TaggerListener;
import com.sandy.sconsole.qimgtools.apps.tagger.pipeline.TaggerOutcomes;
import com.sandy.sconsole.qimgtools.apps.tagger.prompt.TagPrompter;
import com.sandy.sconsole.qimgtools.apps.tagger.server.RefreshItem;
import com.sandy.sconsole.qimgtools.apps.tagger.server.RefreshResult;
import com.sandy.sconsole.qimgtools.apps.tagger.server.TaggerServerClient;
import com.sandy.sconsole.qimgtools.apps.tagger.ui.TaggerFrame;
import com.sandy.sconsole.qimgtools.apps.tagger.ui.TaggerFrame.Loaded;
import com.sandy.sconsole.qimgtools.apps.tagger.ui.TaggerFrame.SaveItem;
import com.sandy.sconsole.qimgtools.apps.tagger.util.TaggerConfig;
import com.sandy.sconsole.qimgtools.apps.tagger.workspace.PendingTagging;
import com.sandy.sconsole.qimgtools.apps.tagger.workspace.ScopeStore;
import com.sandy.sconsole.qimgtools.apps.tagger.workspace.TagAuditLog;
import com.sandy.sconsole.qimgtools.apps.tagger.workspace.TaggerNotes;
import com.sandy.sconsole.qimgtools.core.model.ProcessingState;
import com.sandy.sconsole.qimgtools.core.model.QTask;
import com.sandy.sconsole.qimgtools.core.model.RateLimitNotice;
import com.sandy.sconsole.qimgtools.core.model.Subject;
import com.sandy.sconsole.qimgtools.core.model.Topic;
import com.sandy.sconsole.qimgtools.core.review.QuestionLocator;
import com.sandy.sconsole.qimgtools.core.scheduler.TaskScheduler;
import com.sandy.sconsole.qimgtools.core.server.PendingResult;
import com.sandy.sconsole.qimgtools.core.server.SConsoleServer;
import com.sandy.sconsole.qimgtools.core.server.SConsoleServer.ServerException;
import com.sandy.sconsole.qimgtools.core.server.ServerQuestion;
import com.sandy.sconsole.qimgtools.core.util.ImagePrep;
import com.sandy.sconsole.qimgtools.core.util.ModelNames;
import com.sandy.sconsole.qimgtools.core.workspace.TranscriptStore;
import com.sandy.sconsole.qimgtools.core.workspace.UsageLog;

import lombok.extern.slf4j.Slf4j;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Entry point and controller of the tagger: tags the questions of one topic
 * (the scope) with that topic's tags. Load fetches the topic's questions
 * pending auto tagging after the scope's cursor; decisions are saved on the
 * server ({@code POST /Master/TagAssociation/Refresh}) and recorded in the
 * {@link TagAuditLog}.
 * <p>
 * The models' side is not connected yet: {@link #canTag} is false until the
 * topic's regression model and LLM prompt are read, so for now questions can
 * only be loaded and looked at: they stay Pending, which can't be decided on.
 * Settings come from {@code qtagger.properties} ({@link TaggerConfig}).
 */
@Slf4j
public class QTagger implements TaggerFrame.Controller {

    /** The subjects whose topics can be scoped. */
    private static final List<Subject> SUBJECTS =
            List.of( Subject.PHYSICS, Subject.CHEMISTRY, Subject.MATHS ) ;

    private final TaggerConfig            config ;
    private final TaggerServerClient      server ;
    private final PendingTagging          pending ;
    private final TagAuditLog             audit ;
    private final TaggerNotes             notes ;
    private final ScopeStore              scopeStore ;
    private final QuestionLocator         locator ;
    private final TaggerOutcomes          outcomes ;
    private final TaskScheduler<TagReply> scheduler ;
    private final TaggerListener          listener ;

    /** The topics of each syllabus, by syllabus name, in server order. */
    private final Map<String,List<Topic>> topics ;

    /** True while a batch thread is alive. */
    private final AtomicBoolean processing = new AtomicBoolean( false ) ;

    /** Set by {@link #stop()}; queued questions are then dropped. */
    private volatile boolean stopRequested ;

    /** The scope in effect, with its cursor; null until one is set. */
    private volatile Scope scope ;

    /** The scoped topic and its tags; null until a scope is set. */
    private volatile TagCatalogue catalogue ;

    public QTagger( TaggerConfig config, TaggerServerClient server,
                    Map<String,List<Topic>> topics, TaggerFrame frame ) {

        this.config     = config ;
        this.server     = server ;
        this.topics     = topics ;
        this.listener   = frame ;
        this.pending    = new PendingTagging( config.getPendingTaggingFile() ) ;
        this.audit      = new TagAuditLog( config.getTagAuditFile() ) ;
        this.notes      = new TaggerNotes( config.getTaggerNotesFile() ) ;
        this.scopeStore = new ScopeStore( config.getScopeFile() ) ;
        this.locator    = new QuestionLocator( config.getQBankDir() ) ;

        AIHub hub = AIHub.create( config.getAi(), new AIHub.Options(
                config.getRequestHistoryFile(), RoutingPolicy.inOrder(),
                "qtagger" ) ) ;

        ImagePrep prep = new ImagePrep( config.getImagePrepWidth(),
                                        config.isImagePrepGreyscale() ) ;

        this.outcomes  = new TaggerOutcomes(
                frame, pending, audit,
                new TranscriptStore( config.getWorkspaceDir() ),
                new UsageLog( config.getUsageLogFile() ),
                () -> scope.topicId() ) ;
        this.scheduler = new TaskScheduler<>( hub, new TagPrompter( prep ),
                                              outcomes ) ;
    }

    public static void main( String[] args ) {

        TaggerConfig config ;
        try {
            config = TaggerConfig.load() ;
            AIConfig ai = config.getAi() ;
            if( !ai.isRootUsable( TaggerOutcomes.LLM ) ) {
                throw new IllegalStateException( ai.appLocation() + ": root '" +
                        TaggerOutcomes.LLM +
                        "' is missing or has no model enabled" ) ;
            }
        }
        catch( IllegalStateException e ) {
            fail( "Invalid configuration", e.getMessage(), e ) ;
            return ;
        }

        File dir = config.getWorkspaceDir() ;
        if( !dir.isDirectory() && !dir.mkdirs() ) {
            log.error( "Could not create the workspace folder {}", dir ) ;
        }

        TaggerServerClient server = new TaggerServerClient(
                new SConsoleServer( config.getServerUrl(),
                                    config.getServerConnectTimeout(),
                                    config.getServerRequestTimeout() ) ) ;

        Map<String,List<Topic>> topics ;
        try {
            topics = loadTopics( server ) ;
        }
        catch( ServerException e ) {
            fail( "SConsole server", "Could not fetch the topic list from " +
                  config.getServerUrl() + ":\n" + e.getMessage(), e ) ;
            return ;
        }

        QTagger[]     app   = new QTagger[1] ;
        TaggerFrame[] frame = new TaggerFrame[1] ;
        try {
            SwingUtilities.invokeAndWait( () -> {
                frame[0] = new TaggerFrame(
                        new TranscriptStore( config.getWorkspaceDir() ),
                        ModelNames.of( config.getAi() ) ) ;
                app[0] = new QTagger( config, server, topics, frame[0] ) ;
                frame[0].setController( app[0] ) ;
            } ) ;
        }
        catch( Exception e ) {
            log.error( "Could not start UI", e ) ;
            return ;
        }

        // The saved scope needs the server's tags: off the EDT.
        String problem = app[0].restoreScope() ;
        SwingUtilities.invokeLater( () -> {
            frame[0].setVisible( true ) ;
            frame[0].scopeRestored( problem ) ;
        } ) ;
    }

    /** Report a startup failure and exit. */
    private static void fail( String title, String message, Exception e ) {
        log.error( "{}: {}", title, message, e ) ;
        JOptionPane.showMessageDialog( null, message, title,
                                       JOptionPane.ERROR_MESSAGE ) ;
        System.exit( 1 ) ;
    }

    /** The server's topics of every subject, by syllabus. */
    private static Map<String,List<Topic>> loadTopics(
            TaggerServerClient server ) throws ServerException {

        Map<String,List<Topic>> topics = new LinkedHashMap<>() ;
        int                     count  = 0 ;

        for( Subject subject : SUBJECTS ) {
            List<Topic> ts = server.topics( subject.getSyllabusName() ) ;
            if( ts.isEmpty() ) {
                throw new ServerException( "the server has no topics for " +
                                           subject.getSyllabusName() ) ;
            }
            topics.put( subject.getSyllabusName(), ts ) ;
            count += ts.size() ;
        }
        log.info( "Loaded {} topics from the server", count ) ;
        return topics ;
    }

    /**
     * Put the saved scope in effect, if there is one.
     *
     * @return why it could not be, for the Scope dialog; null if it is
     */
    String restoreScope() {

        Scope saved = scopeStore.load( TaggerServerClient.MAX_LIMIT ) ;
        if( saved == null ) return "No scope chosen yet" ;
        try {
            setScope( saved ) ;
            return null ;
        }
        catch( Exception e ) {
            log.warn( "Could not restore the saved scope: {}", e.getMessage() ) ;
            return "The saved scope can't be used: " + e.getMessage() ;
        }
    }

    // ---- scope ---------------------------------------------------------

    @Override
    public Scope scope() {
        return scope ;
    }

    @Override
    public TagCatalogue catalogue() {
        return catalogue ;
    }

    /**
     * False for now: true once the topic's regression model and LLM prompt
     * are loaded, the next step of the rework.
     */
    @Override
    public boolean canTag() {
        return false ;
    }

    /**
     * Use {@code s} from now on: fetch its topic's tags and save the scope.
     *
     * @throws Exception if the topic is unknown or has no tags, or the server
     *         could not be reached; the scope in effect stays
     */
    @Override
    public void setScope( Scope s ) throws Exception {

        Topic topic = topic( s.topicId() ) ;
        if( topic == null ) {
            throw new IllegalStateException( "no topic " + s.topicId() ) ;
        }
        List<CatalogueTag> tags = server.tags( topic.getId() ) ;
        if( tags.isEmpty() ) {
            throw new IllegalStateException( topic.getTopicName() +
                                             " has no tags on the server" ) ;
        }

        catalogue = new TagCatalogue( topic, tags ) ;
        scope     = s ;

        saveScope() ;
        log.info( "Scope: topic {} {}, {} per load, {} tags", topic.getId(),
                  topic.getTopicName(), s.batchSize(), tags.size() ) ;
    }

    private void saveScope() {
        try {
            scopeStore.save( scope ) ;
        }
        catch( IOException e ) {
            log.error( "Could not save the scope", e ) ;
        }
    }

    @Override
    public List<String> syllabusNames() {
        return List.copyOf( topics.keySet() ) ;
    }

    @Override
    public List<Topic> topics( String syllabusName ) {
        return topics.getOrDefault( syllabusName, List.of() ) ;
    }

    private Topic topic( int id ) {
        return topics.values().stream().flatMap( List::stream )
                     .filter( t -> t.getId() == id )
                     .findFirst().orElse( null ) ;
    }

    // ---- loading -------------------------------------------------------

    /**
     * The next {@code limit} questions of the scope's topic pending auto
     * tagging, after the cursor; {@code fromStart} first puts the cursor
     * back. The cursor moves on past the questions returned, and is saved
     * with the scope.
     */
    @Override
    public Loaded load( int limit, boolean fromStart ) throws Exception {

        Scope s = fromStart ? scope.fromStart() : scope ;
        int   n = Math.min( Math.max( limit, 1 ),
                            TaggerServerClient.MAX_LIMIT ) ;

        PendingResult        found = server.pending( s.topicId(), n,
                                                     s.afterId() ) ;
        List<ServerQuestion> qs    = found.getQuestions() ;

        Map<Integer,List<ServerTag>> tags = server.questionTags(
                qs.stream().map( ServerQuestion::getId ).toList() ) ;

        List<TagItem> items = new ArrayList<>() ;
        for( ServerQuestion q : qs ) {
            QuestionLocator.Located l = locator.locate( q ) ;
            items.add( new TagItem( q, l.task(), l.problem(),
                                    tags.getOrDefault( q.getId(),
                                                       List.of() ) ) ) ;
        }

        scope = s.withCursor( qs.stream().map( ServerQuestion::getId )
                                .max( Integer::compare )
                                .orElse( s.afterId() ) ) ;
        saveScope() ;
        log.info( "Loaded {} questions{}; {} await tags", qs.size(),
                  fromStart ? " from the start" : "",
                  found.getTotalResults() ) ;
        return new Loaded( items, found.getTotalResults() ) ;
    }

    @Override
    public PendingTagging.Stored stored( TagItem item ) {
        return scope == null ? null
                             : pending.stored( item.question(), scope.topicId() ) ;
    }

    // ---- running -------------------------------------------------------

    /**
     * Send {@code items} to the models: start a run, or add them to the one
     * going. Ignored while a run is stopping.
     */
    @Override
    public synchronized void start( List<TagItem> items ) {
        start( items, null ) ;
    }

    /**
     * Send {@code items} to {@code model} alone, if not null: it retries
     * there and never falls back. Otherwise as {@link #start(List)}.
     */
    @Override
    public synchronized void start( List<TagItem> items, String model ) {

        if( stopRequested ) return ;
        List<QTask> tasks = new ArrayList<>() ;

        for( TagItem item : items ) {
            outcomes.track( item ) ;
            tasks.add( item.task() ) ;
        }

        TaskScheduler.Route route = model == null ? null
                : new TaskScheduler.Route( TaggerOutcomes.LLM, model ) ;

        log.info( "Sending {} questions to the models{}", tasks.size(),
                  model == null ? "" : ": " + model + " alone" ) ;
        if( processing.get() ) {
            scheduler.submitBatch( tasks, route, this::isProcessing ) ;
            return ;
        }
        processing.set( true ) ;
        listener.processingStateChanged( ProcessingState.RUNNING ) ;
        scheduler.submitBatch( tasks, route, this::isProcessing ) ;
        new Thread( this::runBatch, "qtagger-batch" ).start() ;
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
        log.info( "Tagging run stopped" ) ;
    }

    /**
     * End the run unless questions were added since the batch was last
     * found done; under the lock {@link #start} adds them with, so the
     * listener hears of the end before a run {@link #start} begins next.
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

    @Override
    public boolean isRunning() {
        return processing.get() ;
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

    // ---- saving --------------------------------------------------------

    /**
     * Send {@code items}' decisions to the server, at most
     * {@link TaggerServerClient#MAX_REFRESHES} per request, and note each
     * one applied in the audit log, dropping its stored reply. Each request
     * is built on the questions' tags read just before it, so a tag of
     * another topic put on since the Load is kept. A request that fails, or
     * whose tags can't be read, rejects all of its items, with the reason.
     *
     * @return the server's result for each item, in the same order
     */
    @Override
    public List<RefreshResult> save( List<SaveItem> items ) {

        List<RefreshResult> results = new ArrayList<>() ;
        int                 size    = TaggerServerClient.MAX_REFRESHES ;

        for( int i = 0 ; i < items.size() ; i += size ) {
            results.addAll( saveBatch( items.subList(
                    i, Math.min( items.size(), i + size ) ) ) ) ;
        }
        return results ;
    }

    private List<RefreshResult> saveBatch( List<SaveItem> batch ) {

        TagCatalogue        c = catalogue ;
        List<RefreshItem>   sent ;
        List<RefreshResult> results ;

        try {
            Map<Integer,List<ServerTag>> now = server.questionTags(
                    batch.stream().map( s -> s.item().id() ).toList() ) ;
            sent    = batch.stream()
                           .map( s -> refreshItem( s, c, now.getOrDefault(
                                   s.item().id(), List.of() ) ) )
                           .toList() ;
            results = server.refresh( sent ) ;
        }
        catch( ServerException e ) {
            log.error( "Could not save {} questions' tags: {}", batch.size(),
                       e.getMessage() ) ;
            return batch.stream()
                        .map( s -> RefreshResult.failed( s.item().id(),
                                                         e.getMessage() ) )
                        .toList() ;
        }

        List<ServerQuestion> saved = new ArrayList<>() ;
        int                  sc    = c.topic().getId() ;

        for( int i = 0 ; i < batch.size() ; i++ ) {
            SaveItem      s = batch.get( i ) ;
            RefreshResult r = results.get( i ) ;

            log.info( "{}: {} ({}) - {}{}", s.item(), s.decision().tags(),
                      s.decision().source(), r.status(),
                      r.applied() ? "" : ": " + r.message() ) ;
            if( !r.applied() ) continue ;

            saved.add( s.item().question() ) ;
            try {
                audit.decision( s.item().question(), sc, s.decision(),
                                sent.get( i ), r ) ;
            }
            catch( Exception e ) {
                log.error( "{}: could not record the decision in the audit " +
                           "log", s.item(), e ) ;
            }
        }
        try {
            pending.remove( saved ) ;
        }
        catch( Exception e ) {
            log.error( "Could not drop {} saved questions' replies",
                       saved.size(), e ) ;
        }
        return results ;
    }

    /**
     * The refresh of one question: its explicit tags now ({@code tags}) that
     * are not the scoped topic's, kept, and the decided tags.
     */
    private static RefreshItem refreshItem( SaveItem s, TagCatalogue c,
                                            List<ServerTag> tags ) {

        Set<Integer> ids = new LinkedHashSet<>() ;

        tags.stream()
            .filter( t -> t.explicit() && !c.contains( t.tagText() ) )
            .forEach( t -> ids.add( t.id() ) ) ;
        for( String text : s.decision().tags() ) {
            CatalogueTag t = c.get( text ) ;
            if( t == null ) {
                throw new IllegalStateException( s.item() + ": " + text +
                                                 " is not in the topic's tags" ) ;
            }
            ids.add( t.id() ) ;
        }
        return new RefreshItem( s.item().id(), List.copyOf( ids ) ) ;
    }

    // ---- deferrals -----------------------------------------------------

    @Override
    public void defer( TagItem item, String note ) {
        try {
            pending.defer( item.question(), note ) ;
            notes.deferred( item.question(), scope.topicId(), note ) ;
            log.info( "{}: deferred - {}", item, note ) ;
        }
        catch( IOException e ) {
            log.error( "{}: could not note the deferral", item, e ) ;
        }
    }

    @Override
    public void undefer( TagItem item ) {
        try {
            pending.defer( item.question(), null ) ;
        }
        catch( IOException e ) {
            log.error( "{}: could not drop the deferral", item, e ) ;
        }
    }

    // ---- the hub -------------------------------------------------------

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
    public TaskScheduler.Counts queueCounts() {
        return scheduler.counts() ;
    }

    @Override
    public long nextRequestAt() {
        return scheduler.nextRequestAt() ;
    }

    /** "Laws of Motion (104), 50 per load - Nemotron @ server". */
    @Override
    public String describe() {

        Scope s = scope ;
        if( s == null ) return "no scope" ;
        Topic t = topic( s.topicId() ) ;

        return ( t != null ? t.getTopicName() : "topic" ) + " (" +
               s.topicId() + "), " + s.batchSize() + " per load - " +
               firstModel() + " @ " + config.getServerUrl() ;
    }

    /** The first model the root may use. */
    private String firstModel() {

        AIConfig ai = config.getAi() ;
        return ai.root( TaggerOutcomes.LLM ).stream()
                 .filter( ModelInfo::usable )
                 .map( m -> ai.displayName( m.id() ) )
                 .findFirst().orElse( "no model" ) ;
    }

    @Override
    public void shutdown() {
        log.debug( "Shutting down the AI hub" ) ;
        stopRequested = true ;
        scheduler.shutdown() ;
    }
}
