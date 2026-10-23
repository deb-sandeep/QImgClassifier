package com.sandy.sconsole.qimgtools.apps.tagger;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sandy.sconsole.ai.AIHub;
import com.sandy.sconsole.ai.HubStatus;
import com.sandy.sconsole.ai.config.AIConfig;
import com.sandy.sconsole.ai.config.FallbackInfo;
import com.sandy.sconsole.ai.config.ModelInfo;
import com.sandy.sconsole.qimgtools.apps.tagger.check.AnswerChecker;
import com.sandy.sconsole.qimgtools.apps.tagger.model.Axis;
import com.sandy.sconsole.qimgtools.apps.tagger.model.CatalogueTag;
import com.sandy.sconsole.qimgtools.apps.tagger.model.NewTag;
import com.sandy.sconsole.qimgtools.apps.tagger.model.Scope;
import com.sandy.sconsole.qimgtools.apps.tagger.model.ServerTag;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagCatalogue;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagClass;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagDecision;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagItem;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagMode;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagOpinions;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagReply;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagReply.GapProposal;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TierReply;
import com.sandy.sconsole.qimgtools.apps.tagger.pipeline.PausingRouting;
import com.sandy.sconsole.qimgtools.apps.tagger.pipeline.TaggerListener;
import com.sandy.sconsole.qimgtools.apps.tagger.pipeline.TaggerOutcomes;
import com.sandy.sconsole.qimgtools.apps.tagger.prompt.ModelPrices;
import com.sandy.sconsole.qimgtools.apps.tagger.prompt.PromptFile;
import com.sandy.sconsole.qimgtools.apps.tagger.prompt.PromptGenerator;
import com.sandy.sconsole.qimgtools.apps.tagger.prompt.PromptReview;
import com.sandy.sconsole.qimgtools.apps.tagger.prompt.PromptStore;
import com.sandy.sconsole.qimgtools.apps.tagger.prompt.TagPrompter;
import com.sandy.sconsole.qimgtools.apps.tagger.prompt.TagPrompter.Assembly;
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
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Entry point and controller of the tagger: tags the questions of one topic
 * (the scope) with that topic's diagnostic tags, in one of two passes
 * ({@link TagClass}), chosen as the app starts and fixed till it exits. In
 * the setup pass each question is read by the {@code tier1} models of
 * {@code qtagger.setup.ai.json} with the topic's approved setup prompt, and
 * its setup tags taken. In the concept pass it is solved blind by the
 * {@code tier1} models of {@code qtagger.concept.ai.json} with the topic's
 * approved concept prompt, and the answer checked against the key; a miss
 * goes to {@code tier2} (see {@link TaggerOutcomes}). The replies are kept
 * in {@link PendingTagging} until the user's or the models' tags are saved
 * on the server ({@code POST /Master/TagAssociation/Refresh}, for the
 * pass), and every judgement and decision in the {@link TagAuditLog}.
 * <p>
 * Load fetches the scoped topic's own questions awaiting the pass after the
 * scope's cursor (concept: those in an exam), then, in the concept pass,
 * its dependents (questions of other topics with an open dependency on it).
 * Topic prompts are generated, reviewed and approved per pass in the source
 * tree ({@link PromptStore}). Settings come from {@code qtagger.properties}
 * ({@link TaggerConfig}).
 */
@Slf4j
public class QTagger implements TaggerFrame.Controller {

    /** The subjects whose topics can be scoped. */
    private static final List<Subject> SUBJECTS =
            List.of( Subject.PHYSICS, Subject.CHEMISTRY, Subject.MATHS ) ;

    /** The property naming the pass, which skips asking for it. */
    private static final String PASS_PROPERTY = "tagger.pass" ;

    /** The format of a tag import file. */
    private static final String TAGSET_FORMAT = "sconsole-tagset" ;
    private static final int    TAGSET_FORMAT_VERSION = 2 ;

    private final TagClass           tagClass ;
    private final TaggerConfig       config ;
    private final TaggerServerClient server ;
    private final PromptStore        prompts ;
    private final PendingTagging     pending ;
    private final TagAuditLog        audit ;
    private final TaggerNotes        notes ;
    private final ScopeStore          scopeStore ;
    private final QuestionLocator    locator ;
    private final TagPrompter        prompter ;
    private final TaggerOutcomes     outcomes ;
    private final PausingRouting     routing = new PausingRouting() ;
    private final TaskScheduler<TagReply> scheduler ;
    private final TaggerListener     listener ;
    private final ModelPrices        prices ;

    /** The topics of each syllabus, by syllabus name, in server order. */
    private final Map<String,List<Topic>> topics ;

    /** True while a batch thread is alive. */
    private final AtomicBoolean processing = new AtomicBoolean( false ) ;

    /** Set by {@link #stop()}; queued questions are then dropped. */
    private volatile boolean stopRequested ;

    /** The scope in effect, with its cursors; null until one is set. */
    private volatile Scope scope ;

    public QTagger( TaggerConfig config, TaggerServerClient server,
                    Map<String,List<Topic>> topics, TaggerFrame frame ) {

        this.tagClass   = config.getTagClass() ;
        this.config     = config ;
        this.server     = server ;
        this.topics     = topics ;
        this.listener   = frame ;
        this.prompts    = new PromptStore( config.getPromptsDir(), tagClass ) ;
        this.pending    = new PendingTagging( config.getPendingTaggingFile(),
                                              tagClass.solves() ) ;
        this.audit      = new TagAuditLog( config.getTagAuditFile(),
                                           tagClass ) ;
        this.notes      = new TaggerNotes( config.getTaggerNotesFile() ) ;
        this.scopeStore = new ScopeStore( config.getScopeFile() ) ;
        this.locator    = new QuestionLocator( config.getQBankDir() ) ;
        this.prices     = new ModelPrices( config.getAi().parentLocation() ) ;

        AIHub hub = AIHub.create( config.getAi(), new AIHub.Options(
                config.getRequestHistoryFile(), routing, "qtagger" ) ) ;

        ImagePrep prep = new ImagePrep( config.getImagePrepWidth(),
                                        config.isImagePrepGreyscale() ) ;

        // The prompter asks the outcomes which item a task is, so the two
        // are tied through this lambda.
        TaggerOutcomes[] ref = new TaggerOutcomes[1] ;
        this.prompter  = new TagPrompter( t -> ref[0].item( t ), prep ) ;
        this.outcomes  = new TaggerOutcomes(
                frame, pending, audit,
                new TranscriptStore( config.getPassDir() ),
                new UsageLog( config.getUsageLogFile() ), prompter ) ;
        ref[0]         = outcomes ;
        this.scheduler = new TaskScheduler<>( hub, prompter, outcomes ) ;
    }

    public static void main( String[] args ) {

        TagClass tagClass ;
        try {
            tagClass = choosePass() ;
        }
        catch( IllegalArgumentException e ) {
            fail( "Invalid pass", "-D" + PASS_PROPERTY + ": " +
                  e.getMessage(), e ) ;
            return ;
        }
        if( tagClass == null ) {
            System.exit( 0 ) ;
            return ;
        }
        log.info( "Tagging pass: {}", tagClass ) ;

        TaggerConfig config ;
        try {
            config = TaggerConfig.load( tagClass ) ;
            AIConfig ai = config.getAi() ;
            for( String root : roots( tagClass ) ) {
                if( ai.isRootUsable( root ) ) continue ;
                throw new IllegalStateException( ai.appLocation() + ": root '" +
                        root + "' is missing or has no model enabled" ) ;
            }
        }
        catch( IllegalStateException e ) {
            fail( "Invalid configuration", e.getMessage(), e ) ;
            return ;
        }

        File passDir = config.getPassDir() ;
        if( !passDir.isDirectory() && !passDir.mkdirs() ) {
            log.error( "Could not create the workspace folder {}", passDir ) ;
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
                        tagClass,
                        new TranscriptStore( config.getPassDir() ),
                        ModelNames.of( config.getAi() ) ) ;
                app[0] = new QTagger( config, server, topics, frame[0] ) ;
                frame[0].setController( app[0] ) ;
            } ) ;
        }
        catch( Exception e ) {
            log.error( "Could not start UI", e ) ;
            return ;
        }

        // The saved scope needs the server's catalogue: off the EDT.
        String problem = app[0].restoreScope() ;
        SwingUtilities.invokeLater( () -> {
            frame[0].setVisible( true ) ;
            frame[0].scopeRestored( problem ) ;
        } ) ;
    }

    /**
     * The pass to run: the one {@code -Dtagger.pass} names, else the one the
     * user picks, setup by default (Enter takes it); null if the user
     * cancelled.
     *
     * @throws IllegalArgumentException if the property names no pass
     */
    private static TagClass choosePass() {

        String named = System.getProperty( PASS_PROPERTY ) ;
        if( named != null && !named.isBlank() ) return TagClass.of( named ) ;

        String[] options = { "Setup tagging (whole corpus)",
                             "Concept tagging (exam questions)" } ;
        int      answer  = JOptionPane.showOptionDialog( null,
                "Which tagging pass?\n\nSetup tags the setup axis of every " +
                "question, read by a cheap model.\nConcept tags the concept " +
                "and math axes and the dependencies of\nthe questions in an " +
                "exam, solved and checked against the key.",
                "QTagger", JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, options, options[0] ) ;
        return switch( answer ) {
            case 0  -> TagClass.SETUP ;
            case 1  -> TagClass.CONCEPT ;
            default -> null ;
        } ;
    }

    /** The roots pass {@code tagClass} needs usable. */
    private static List<String> roots( TagClass tagClass ) {
        return tagClass.solves()
               ? List.of( TaggerOutcomes.TIER1, TaggerOutcomes.TIER2 )
               : List.of( TaggerOutcomes.TIER1 ) ;
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
     * @return why it could not be, for the Scope dialog; null if it is, or
     *         there was none
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
    public Assembly assembly() {
        return prompter.assembly() ;
    }

    /**
     * Use {@code s} from now on: fetch its topic's catalogue, check that its
     * prompt is approved for it, assemble the prompt and save the scope.
     *
     * @throws Exception if the topic has no approved, current prompt, or
     *         the server could not be reached; the scope in effect stays
     */
    @Override
    public void setScope( Scope s ) throws Exception {

        Topic topic = topic( s.topicId() ) ;
        if( topic == null ) {
            throw new IllegalStateException( "no topic " + s.topicId() ) ;
        }
        if( topic.getId() == TagCatalogue.FOUNDATION_MATHS ) {
            throw new IllegalStateException( topic.getTopicName() +
                    " is tagged by hand: its tags are every topic's math" ) ;
        }

        TagCatalogue catalogue   = catalogue( topic ) ;
        PromptFile   topicPrompt = prompts.topicPrompt( topic ) ;

        if( topicPrompt == null ) {
            throw new IllegalStateException( topic.getTopicName() + " has no " +
                    prompts.reviewedName() + " - generate one first" ) ;
        }
        PromptStore.State state = PromptStore.state( topicPrompt, catalogue,
                                                     tagClass ) ;
        if( state != PromptStore.State.APPROVED ) {
            throw new IllegalStateException( "the " + tagClass.wire() +
                    " prompt of " + topic.getTopicName() + " is " + state +
                    " - review and approve it" ) ;
        }

        Assembly a = assemble( topic, topicPrompt, catalogue, s.mode() ) ;
        if( !a.offered().problems().isEmpty() ) {
            throw new IllegalStateException( "the prompt of " +
                    topic.getTopicName() + " has problems: " +
                    String.join( "; ", a.offered().problems() ) ) ;
        }
        prompter.use( a ) ;
        scope = s ;
        saveScope() ;
        log.info( "Scope: {} pass, topic {} {}, {} per load, {}, prompt v{} " +
                  "(template v{}), {} tags offered", tagClass.wire(),
                  topic.getId(),
                  topic.getTopicName(), s.batchSize(), s.mode(),
                  a.promptVersion(), a.templateVersion(),
                  a.offered().all().size() ) ;
    }

    private void saveScope() {
        try {
            scopeStore.save( scope ) ;
        }
        catch( IOException e ) {
            log.error( "Could not save the scope", e ) ;
        }
    }

    /**
     * The catalogue of {@code topic}: its tags, and the math tags if the
     * pass offers them.
     */
    private TagCatalogue catalogue( Topic topic ) throws ServerException {

        List<CatalogueTag> math = tagClass.tags( Axis.MATH )
                                  ? server.tags( TagCatalogue.FOUNDATION_MATHS )
                                  : List.of() ;
        return new TagCatalogue( topic.getId(), server.tags( topic.getId() ),
                                 math ) ;
    }

    /** {@code topic}'s prompt of the pass, assembled from its files now. */
    private Assembly assemble( Topic topic, PromptFile topicPrompt,
                               TagCatalogue catalogue, TagMode mode )
            throws IOException {

        return TagPrompter.assemble( tagClass, topic, prompts.template(),
                                     prompts.axes(), topicPrompt, catalogue,
                                     mode,
                                     topics.get( topic.getSyllabusName() ) ) ;
    }

    @Override
    public TagClass tagClass() {
        return tagClass ;
    }

    @Override
    public List<String> syllabusNames() {
        return List.copyOf( topics.keySet() ) ;
    }

    @Override
    public List<Topic> topics( String syllabusName ) {
        return topics.getOrDefault( syllabusName, List.of() ) ;
    }

    @Override
    public Topic topic( int id ) {
        return topics.values().stream().flatMap( List::stream )
                     .filter( t -> t.getId() == id )
                     .findFirst().orElse( null ) ;
    }

    // ---- prompts -------------------------------------------------------

    /**
     * {@code topic}'s prompt at a glance, from its files alone: an approved
     * prompt is taken as current (staleness needs the catalogue).
     */
    @Override
    public PromptStore.Summary promptSummary( Topic topic ) {
        try {
            return prompts.summary( topic ) ;
        }
        catch( IOException e ) {
            log.warn( "Could not read the prompt of topic {}: {}",
                      topic.getId(), e.getMessage() ) ;
            return PromptStore.Summary.NONE ;
        }
    }

    @Override
    public File promptDir( Topic topic ) {
        return prompts.dir( topic ) ;
    }

    /**
     * Write {@code topic}'s base prompt of the pass from its catalogue now.
     *
     * @return true if it was also copied to the pass' reviewed prompt, the
     *         topic having none
     */
    @Override
    public boolean generatePrompt( Topic topic ) throws Exception {

        TagCatalogue catalogue = catalogue( topic ) ;
        if( catalogue.tags( tagClass ).isEmpty() ) {
            throw new IllegalStateException( topic.getTopicName() +
                    " has no " + tagClass.wire() + " tag with an AI " +
                    "association hint" ) ;
        }
        return prompts.writeGenerated(
                topic, PromptGenerator.generate( catalogue, tagClass ),
                catalogue ) ;
    }

    /**
     * {@code topic}'s prompt assembled as the models would get it, checked
     * against its catalogue now, with what it would cost.
     */
    @Override
    public PromptReview reviewPrompt( Topic topic ) throws Exception {

        TagCatalogue catalogue   = catalogue( topic ) ;
        PromptFile   topicPrompt = prompts.topicPrompt( topic ) ;

        if( topicPrompt == null ) {
            throw new IllegalStateException( topic.getTopicName() + " has no " +
                    prompts.reviewedName() + " - generate one first" ) ;
        }

        Scope    s      = scope ;
        TagMode  mode   = s != null && s.topicId() == topic.getId()
                          ? s.mode() : TagMode.EXISTING_ONLY ;
        Assembly a      = assemble( topic, topicPrompt, catalogue, mode ) ;
        int      tokens = TagPrompter.estimateTokens( a.own() ) ;

        Map<String,Double> costs = new LinkedHashMap<>() ;
        for( ModelInfo m : config.getAi().models().values() ) {
            Double price = prices.input( m.id() ) ;
            if( !m.usable() || price == null ) continue ;
            costs.put( m.label(), tokens / 1e6 * price * 1000 ) ;
        }
        return new PromptReview( topic, catalogue, topicPrompt,
                                 PromptStore.state( topicPrompt, catalogue,
                                                    tagClass ),
                                 a.own(), a.dependent(),
                                 a.offered().problems(),
                                 a.offered().leftOut( catalogue ), costs ) ;
    }

    /**
     * Approve the prompt {@code review} showed, for its catalogue.
     *
     * @throws IllegalStateException if the file changed since it was
     *         reviewed, or has problems
     */
    @Override
    public void approvePrompt( PromptReview review ) throws Exception {

        if( !review.approvable() ) {
            throw new IllegalStateException( "the prompt has problems" ) ;
        }
        PromptFile now = prompts.topicPrompt( review.topic() ) ;
        if( now == null || !now.contentHash().equals(
                review.topicPrompt().contentHash() ) ) {
            throw new IllegalStateException( prompts.reviewedName() +
                    " changed since it was reviewed - review it again" ) ;
        }
        prompts.approve( review.topic(), review.catalogue() ) ;
    }

    // ---- loading -------------------------------------------------------

    /**
     * The next {@code limit} questions of the scope: its topic's own
     * awaiting the pass, after the own cursor, then, in the concept pass if
     * they run out, its dependents after theirs; {@code fromStart} first
     * puts both cursors back. The cursors move on past the questions
     * returned, and are saved with the scope.
     */
    @Override
    public Loaded load( int limit, boolean fromStart ) throws Exception {

        Scope s = fromStart ? scope.fromStart() : scope ;
        int   n = Math.min( Math.max( limit, 1 ),
                            TaggerServerClient.MAX_LIMIT ) ;

        PendingResult        own       = server.pending( tagClass, s.topicId(),
                                                         n, s.ownAfterId() ) ;
        List<ServerQuestion> ownQs     = own.getQuestions() ;
        List<ServerQuestion> depQs     = List.of() ;
        Integer              ownAfter  = last( ownQs, s.ownAfterId() ) ;
        Integer              depAfter  = s.depAfterId() ;
        int                  depTotal ;

        if( !tagClass.hasDependents() ) {
            depTotal = 0 ;
        }
        else if( ownQs.size() < n ) {
            PendingResult dep = server.dependents( s.topicId(),
                                                   n - ownQs.size(),
                                                   depAfter ) ;
            depQs    = dep.getQuestions() ;
            depAfter = last( depQs, depAfter ) ;
            depTotal = dep.getTotalResults() ;
        }
        else {
            // Only the backlog's size, for the status bar.
            depTotal = server.dependents( s.topicId(), 1, null )
                             .getTotalResults() ;
        }

        List<ServerQuestion> all = new ArrayList<>( ownQs ) ;
        all.addAll( depQs ) ;

        Map<Integer,List<ServerTag>> tags = all.isEmpty() ? Map.of()
                : server.questionTags( all.stream().map( ServerQuestion::getId )
                                          .toList() ) ;
        List<TagItem> items = new ArrayList<>() ;
        for( ServerQuestion q : all ) {
            items.add( item( q, depQs.contains( q ),
                             tags.getOrDefault( q.getId(), List.of() ) ) ) ;
        }

        scope = s.withCursors( ownAfter, depAfter ) ;
        saveScope() ;
        log.info( "Loaded {} own and {} dependent questions{}; {} own and {} " +
                  "dependents await {} tags", ownQs.size(), depQs.size(),
                  fromStart ? " from the start" : "", own.getTotalResults(),
                  depTotal, tagClass.wire() ) ;
        return new Loaded( items, own.getTotalResults(), depTotal ) ;
    }

    /** The highest id of {@code qs}; {@code dflt} if there are none. */
    private static Integer last( List<ServerQuestion> qs, Integer dflt ) {
        return qs.stream().map( ServerQuestion::getId )
                 .max( Integer::compare ).orElse( dflt ) ;
    }

    private TagItem item( ServerQuestion q, boolean dependent,
                          List<ServerTag> tags ) {

        // Only an answer the pass checks can be unsupported.
        QuestionLocator.Located l = locator.locate( q ) ;
        return new TagItem( q, l.task(), l.problem(),
                            !tagClass.solves() ? null
                            : AnswerChecker.unsupported( q.getProblemType(),
                                                         q.getAnswer() ),
                            dependent, tags ) ;
    }

    @Override
    public PendingTagging.Stored stored( TagItem item ) {
        return scope == null ? null
                             : pending.stored( item.question(), scope.topicId() ) ;
    }

    // ---- running -------------------------------------------------------

    /**
     * Send {@code items} to tier 1: start a run, or add them to the one
     * going. Ignored while a run is stopping.
     */
    @Override
    public synchronized void start( List<TagItem> items ) {
        start( items, 1, null, Map.of() ) ;
    }

    /**
     * Send {@code items} to {@code model} alone, as tier {@code tier}
     * (tier 2 only in the concept pass): it retries there and never falls
     * back. As tier 1 a miss still goes on to the tier 2 root; as tier 2
     * each item keeps its reply in {@code tier1}. Otherwise as
     * {@link #start(List)}.
     */
    @Override
    public synchronized void start( List<TagItem> items, int tier,
                                    String model,
                                    Map<TagItem,TierReply> tier1 ) {

        if( stopRequested ) return ;
        List<QTask> tasks = new ArrayList<>() ;

        for( TagItem item : items ) {
            outcomes.track( item, tier == 2 ? tier1.get( item ) : null ) ;
            tasks.add( item.task() ) ;
        }

        String root = tier == 2 ? TaggerOutcomes.TIER2 : TaggerOutcomes.TIER1 ;
        TaskScheduler.Route route = model == null ? null
                                  : new TaskScheduler.Route( root, model ) ;

        log.info( "Sending {} questions to tier {}{}", tasks.size(), tier,
                  model == null ? "" : " on " + model + " alone" ) ;
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

    /** While {@code paused}, no new request goes out (a gap waits). */
    @Override
    public void setPaused( boolean paused ) {
        if( routing.isPaused() == paused ) return ;
        routing.setPaused( paused ) ;
        log.info( paused ? "Paused: a gap proposal awaits the user"
                         : "Resumed" ) ;
    }

    // ---- saving --------------------------------------------------------

    /**
     * Send {@code items}' decisions to the server for the pass, at most
     * {@link TaggerServerClient#MAX_REFRESHES} per request, and note each
     * one applied in the audit log, dropping its stored replies. Each
     * request is built on the questions' tags read just before it, so a
     * tag put on since the Load (by the other pass, say) is kept. A request
     * that fails, or whose tags can't be read, rejects all of its items,
     * with the reason.
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

        Assembly            a  = prompter.assembly() ;
        int                 sc = a.topic().getId() ;
        List<RefreshItem>   sent ;
        List<RefreshResult> results ;

        try {
            Map<Integer,List<ServerTag>> now = server.questionTags(
                    batch.stream().map( s -> s.item().id() ).toList() ) ;
            sent    = batch.stream()
                           .map( s -> refreshItem( s, a, now.getOrDefault(
                                   s.item().id(), List.of() ) ) )
                           .toList() ;
            results = server.refresh( tagClass, sent ) ;
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
        TagAuditLog.PromptStamp stamp = outcomes.stamp() ;

        for( int i = 0 ; i < batch.size() ; i++ ) {
            SaveItem      s = batch.get( i ) ;
            RefreshResult r = results.get( i ) ;

            log.info( "{}: {} ({}) - {}{}", s.item(), s.decision().tags(),
                      s.decision().source(), r.status(),
                      r.applied() ? "" : ": " + r.message() ) ;
            if( !r.applied() ) continue ;

            saved.add( s.item().question() ) ;
            try {
                audit.decision( s.item().question(), sc, s.item().dependent(),
                                stamp, s.decision(), sent.get( i ), r ) ;
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
     * The refresh of one question: its explicit tags now ({@code tags})
     * that the run may not replace, kept, and the decided tags; in the
     * concept pass with its dependencies for a question of the scoped
     * topic, or closing its dependency on it for a dependent. The setup
     * pass leaves the dependencies alone.
     */
    private static RefreshItem refreshItem( SaveItem s, Assembly a,
                                            List<ServerTag> tags ) {

        TagItem     item      = s.item() ;
        TagDecision d         = s.decision() ;
        Set<Integer> replaced = a.offered().replaceable( item.dependent() )
                                 .stream().map( CatalogueTag::id )
                                 .collect( Collectors.toSet() ) ;
        Set<Integer> ids      = new LinkedHashSet<>() ;

        tags.stream()
            .filter( t -> t.explicit() && !replaced.contains( t.id() ) )
            .forEach( t -> ids.add( t.id() ) ) ;
        for( String text : d.tags() ) {
            CatalogueTag t = a.catalogue().get( text ) ;
            if( t == null ) {
                throw new IllegalStateException( item + ": " + text +
                                                 " is not in the catalogue" ) ;
            }
            ids.add( t.id() ) ;
        }

        if( item.dependent() ) {
            return new RefreshItem( item.id(), List.copyOf( ids ), null, null,
                                    null, a.topic().getId() ) ;
        }
        // A set that leaves the dependencies as they are (the hand tags)
        // says nothing of them, and the setup pass never touches them.
        if( d.dependencies() == null || !a.tagClass().hasDependents() ) {
            return new RefreshItem( item.id(), List.copyOf( ids ), null, null,
                                    null, null ) ;
        }
        return new RefreshItem( item.id(), List.copyOf( ids ),
                                d.dependencies(), d.dependencyActor(),
                                d.dependencyModel(), null ) ;
    }

    // ---- deferrals and gaps --------------------------------------------

    @Override
    public void defer( TagItem item, String reason, String note ) {
        try {
            pending.defer( item.question(), note ) ;
            notes.deferred( item.question(), scope.topicId(), reason, note ) ;
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

    /**
     * Create {@code tag} on the server with a one-tag import (validated
     * first), add its line to the topic's prompt and approve it again, and
     * ask with the new prompt from now on.
     *
     * @return the tag as the server now holds it
     */
    @Override
    public CatalogueTag createTag( NewTag tag ) throws Exception {

        Assembly     a     = prompter.assembly() ;
        Topic        topic = a.topic() ;
        Axis         axis  = tag.axis() ;
        String       rule  = a.catalogue().axisRule( axis ) ;
        String       hint  = rule.isEmpty() ? tag.hint().strip()
                                            : rule + " " + tag.hint().strip() ;
        ObjectNode   file  = JsonNodeFactory.instance.objectNode() ;

        file.put( "format", TAGSET_FORMAT ) ;
        file.put( "formatVersion", TAGSET_FORMAT_VERSION ) ;
        file.put( "tagSetVersion", "qtagger-gap-" + Instant.now() ) ;
        file.putObject( "topic" )
            .put( "topicId", topic.getId() )
            .put( "syllabusName", topic.getSyllabusName() )
            .put( "topicName", topic.getTopicName() ) ;
        file.putArray( "tags" ).addObject()
            .put( "tagText", tag.tagText() )
            .put( "unabbreviatedTagText", tag.fullName() )
            .put( "color", tag.color() )
            .put( "description", tag.description() )
            .put( "aiAssociationHint", hint ) ;

        server.importTags( file, topic.getId(), true ) ;
        server.importTags( file, topic.getId(), false ) ;
        log.info( "Created tag {} in topic {}", tag.tagText(), topic.getId() ) ;

        TagCatalogue catalogue = catalogue( topic ) ;
        CatalogueTag created   = catalogue.get( tag.tagText() ) ;
        if( created == null ) {
            throw new IllegalStateException( "the server took " +
                    tag.tagText() + " but doesn't list it with a hint" ) ;
        }
        PromptFile approved = prompts.appendAndApprove(
                topic, axis.getHeading(),
                "- " + created.tagText() + ": " + catalogue.ownHint( created ),
                catalogue ) ;
        prompter.use( assemble( topic, approved, catalogue, a.mode() ) ) ;
        return created ;
    }

    /**
     * Note the fate of the gap proposed on {@code item}: store its replies
     * as they now stand, and note the fate.
     *
     * @param fate {@code accepted}, {@code mapped} or {@code rejected}
     * @param tag  the tag the question took; null if rejected
     */
    @Override
    public void gapSettled( TagItem item, TagOpinions opinions, String model,
                            GapProposal gap, String fate, String tag ) {
        try {
            pending.settleGap( item.question(), opinions ) ;
            notes.gap( item.question(), scope.topicId(), model, gap, fate,
                       tag ) ;
            log.info( "{}: gap {} {}{}", item, gap.tag(), fate,
                      tag != null && !tag.equals( gap.tag() )
                      ? " as " + tag : "" ) ;
        }
        catch( IOException e ) {
            log.error( "{}: could not note the gap's fate", item, e ) ;
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
    public String heldForTheDay() {

        HubStatus status = scheduler.hubStatus() ;
        for( String root : roots( tagClass ) ) {
            if( heldForTheDay( status, root ) ) {
                return "every model of the " + root + " root, fallbacks " +
                       "included, is held for the day" ;
            }
        }
        return null ;
    }

    /**
     * True if every usable model {@code root} reaches, its own and those in
     * their fallbacks, is held for the day, by its own daily limit or its
     * gateway's.
     */
    private boolean heldForTheDay( HubStatus status, String root ) {

        AIConfig      ai   = config.getAi() ;
        Set<String>   ids  = new HashSet<>() ;
        Deque<String> next = new ArrayDeque<>() ;

        ai.root( root ).forEach( m -> next.add( m.id() ) ) ;
        while( !next.isEmpty() ) {
            String id = next.pop() ;
            if( !ids.add( id ) ) continue ;

            FallbackInfo fb = ai.model( id ).fallback() ;
            fb.guards().values().forEach( next::addAll ) ;
            next.addAll( fb.defaults() ) ;
        }

        Set<String> dayGateways = new HashSet<>() ;
        status.gateways().stream()
              .filter( HubStatus.GatewayStatus::heldForTheDay )
              .forEach( g -> dayGateways.add( g.name() ) ) ;

        return status.models().stream()
                     .filter( m -> m.usable() && ids.contains( m.id() ) )
                     .allMatch( m -> m.heldForTheDay()
                                     || dayGateways.contains( m.gateway() ) ) ;
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

    /**
     * "Laws of Motion (104), Review gaps, 50 per load - Nemotron, tier 2
     * GLM @ server"; the setup pass names no tier 2.
     */
    @Override
    public String describe() {

        Scope s = scope ;
        if( s == null ) return "no scope" ;
        Topic t = topic( s.topicId() ) ;

        return ( t != null ? t.getTopicName() : "topic" ) + " (" +
               s.topicId() + "), " + s.mode() + ", " + s.batchSize() +
               " per load - " + firstModel( TaggerOutcomes.TIER1 ) +
               ( tagClass.solves() ? ", tier 2 " +
                                     firstModel( TaggerOutcomes.TIER2 )
                                   : "" ) +
               " @ " + config.getServerUrl() ;
    }

    /** The first model root {@code root} may use. */
    private String firstModel( String root ) {
        String id = firstUsable( config.getAi(), root ) ;
        return id != null ? config.getAi().displayName( id ) : "no model" ;
    }

    /** The id of the first model {@code root} may use; null if none. */
    private static String firstUsable( AIConfig ai, String root ) {
        return ai.root( root ).stream()
                 .filter( ModelInfo::usable )
                 .map( ModelInfo::id )
                 .findFirst().orElse( null ) ;
    }

    @Override
    public void shutdown() {
        log.debug( "Shutting down the AI hub" ) ;
        stopRequested = true ;
        scheduler.shutdown() ;
    }
}
