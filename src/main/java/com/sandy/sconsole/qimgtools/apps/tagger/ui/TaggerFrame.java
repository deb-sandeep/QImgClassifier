package com.sandy.sconsole.qimgtools.apps.tagger.ui;

import com.sandy.sconsole.ai.HubStatus;
import com.sandy.sconsole.ai.ui.ModelsPanel;
import com.sandy.sconsole.qimgtools.apps.tagger.model.Axis;
import com.sandy.sconsole.qimgtools.apps.tagger.model.CatalogueTag;
import com.sandy.sconsole.qimgtools.apps.tagger.model.HandOffReason;
import com.sandy.sconsole.qimgtools.apps.tagger.model.NewTag;
import com.sandy.sconsole.qimgtools.apps.tagger.model.Scope;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagClass;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagDecision;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagItem;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagJudgement;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagOpinions;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagReply.GapProposal;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TaggerStatus;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TierReply;
import com.sandy.sconsole.qimgtools.apps.tagger.pipeline.TaggerListener;
import com.sandy.sconsole.qimgtools.apps.tagger.prompt.PromptReview;
import com.sandy.sconsole.qimgtools.apps.tagger.prompt.PromptStore;
import com.sandy.sconsole.qimgtools.apps.tagger.prompt.TagPrompter.Assembly;
import com.sandy.sconsole.qimgtools.apps.tagger.server.RefreshResult;
import com.sandy.sconsole.qimgtools.apps.tagger.ui.TagBadge.Payload;
import com.sandy.sconsole.qimgtools.apps.tagger.ui.TagTableModel.Row;
import com.sandy.sconsole.qimgtools.apps.tagger.workspace.PendingTagging;
import com.sandy.sconsole.qimgtools.core.model.AttemptRequest;
import com.sandy.sconsole.qimgtools.core.model.ProcessingState;
import com.sandy.sconsole.qimgtools.core.model.RateLimitNotice;
import com.sandy.sconsole.qimgtools.core.model.Topic;
import com.sandy.sconsole.qimgtools.core.scheduler.TaskScheduler;
import com.sandy.sconsole.qimgtools.core.server.TopicDependency;
import com.sandy.sconsole.qimgtools.core.ui.HeaderFilterController;
import com.sandy.sconsole.qimgtools.core.ui.ImagePreview;
import com.sandy.sconsole.qimgtools.core.ui.RateLimitStrip;
import com.sandy.sconsole.qimgtools.core.ui.Spinner;
import com.sandy.sconsole.qimgtools.core.ui.ToolIcon;
import com.sandy.sconsole.qimgtools.core.ui.TranscriptPanel;
import com.sandy.sconsole.qimgtools.core.util.ModelNames;
import com.sandy.sconsole.qimgtools.core.workspace.TranscriptStore;

import lombok.extern.slf4j.Slf4j;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.border.Border;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.ItemEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The tagger's window, the reviewer's in layout and habits, for one pass
 * ({@link TagClass}), which the title and a badge at the left of the status
 * bar show. The table lists the scoped topic's questions awaiting the pass,
 * then (concept) its dependents, with each one's key, the hand tags already
 * on it and the two tiers' answers and tags (setup: tier 1's tags alone,
 * the key and tier 2 columns hidden); Load fetches the next ones after the
 * scope's cursor (Load from start brings back every one still pending),
 * Start sends those not yet judged to tier 1, and Stop winds that down. A
 * setup reply, or a concept tier whose answer matches the key, has its tags
 * accepted, to be saved; the rest are handed to the user, who decides on
 * them with keys 1-5, the {@link AnswerStrip} or the row menu. Each row has
 * a chosen set, the tags it is to be saved with: a choice sets it whole (a
 * tier's tags, the hand tags), and the {@link ChosenTagsBar} edits it a tag
 * or topic at a time, dragged from the strip or the {@link TopicTagsPanel}
 * and taken out with a ×, for every selected row at once. A gap
 * proposed in Review gaps mode pauses the run until the {@link GapDialog}
 * settles it. Decisions stay in the table until Save
 * sends them, with the accepted rows, to the server off the EDT. Every
 * command is an {@code Action} on the menu bar, the main ones also as icon
 * buttons ({@link ToolIcon}) on the toolbar.
 * <p>
 * Implements {@link TaggerListener}; its calls arrive on worker threads and
 * are moved to the EDT, where all row state lives.
 */
@Slf4j
@SuppressWarnings( "serial" )
public class TaggerFrame extends JFrame
        implements TaggerListener {

    /** What the frame needs from the tagger. */
    public interface Controller {

        /** The pass the tagger runs. */
        TagClass tagClass() ;

        /** The scope in effect; null before one is set. */
        Scope scope() ;

        /** The scope's prompt in use; null before a scope is set. */
        Assembly assembly() ;

        /**
         * Use {@code scope} from now on, and save it. Blocking (it fetches
         * the topic's catalogue); called off the EDT.
         *
         * @throws Exception if its topic has no approved, current prompt,
         *         or the server can't be reached
         */
        void setScope( Scope scope ) throws Exception ;

        /** The syllabuses whose topics can be scoped. */
        List<String> syllabusNames() ;

        /** Every topic of syllabus {@code syllabusName}. */
        List<Topic> topics( String syllabusName ) ;

        /** Topic {@code id}; null if there is none. */
        Topic topic( int id ) ;

        /** {@code topic}'s prompt at a glance, from its files. */
        PromptStore.Summary promptSummary( Topic topic ) ;

        /** The folder of {@code topic}'s prompt files. */
        File promptDir( Topic topic ) ;

        /**
         * Write {@code topic}'s base prompt from its catalogue. Blocking;
         * called off the EDT.
         *
         * @return true if it was also copied to {@code tags.reviewed.prompt}
         */
        boolean generatePrompt( Topic topic ) throws Exception ;

        /**
         * {@code topic}'s prompt as the models would get it. Blocking;
         * called off the EDT.
         */
        PromptReview reviewPrompt( Topic topic ) throws Exception ;

        /** Approve the prompt {@code review} showed. */
        void approvePrompt( PromptReview review ) throws Exception ;

        /**
         * The next {@code limit} questions of the scope after its cursors
         * ({@code fromStart}: from the first), with their images located.
         * Blocking; called off the EDT.
         */
        Loaded load( int limit, boolean fromStart ) throws Exception ;

        /**
         * The replies stored for {@code item} by an earlier run, if its key
         * and topic are unchanged since; null otherwise.
         */
        PendingTagging.Stored stored( TagItem item ) ;

        /**
         * Send {@code items} to tier 1, in order: start a run, or add them
         * to the one going. Ignored while a run is stopping.
         */
        void start( List<TagItem> items ) ;

        /**
         * Send {@code items} to {@code model} alone, as tier {@code tier}:
         * no other model is tried. As tier 2 (the concept pass) each item
         * keeps {@code tier1}'s reply for it as tier 1's; as tier 1 a miss
         * still goes on to tier 2. Otherwise as {@link #start(List)}.
         */
        void start( List<TagItem> items, int tier, String model,
                    Map<TagItem,TierReply> tier1 ) ;

        /** The models a question may be sent to by hand, in config order. */
        List<String> models() ;

        /** Wind the run down: no new requests, running ones finish. */
        void stop() ;

        /**
         * True while a run is going; a run's end reported after the next
         * run started is then out of date.
         */
        boolean isRunning() ;

        /** While {@code paused}, no new request goes out. */
        void setPaused( boolean paused ) ;

        /**
         * Save the tags of {@code items} on the server. Blocking; called
         * off the EDT. A failed request comes back as rejected results.
         *
         * @return the server's result for each, in the same order
         */
        List<RefreshResult> save( List<SaveItem> items ) ;

        /** Note {@code item} as deferred, {@code reason} its hand-off. */
        void defer( TagItem item, String reason, String note ) ;

        /** Drop {@code item}'s deferral. */
        void undefer( TagItem item ) ;

        /**
         * Create {@code tag} on the server, add it to the scope's prompt
         * and approve that again. Blocking; called off the EDT.
         */
        CatalogueTag createTag( NewTag tag ) throws Exception ;

        /**
         * Note the fate of {@code gap}, proposed by {@code model} on
         * {@code item}; {@code opinions} as they now stand.
         */
        void gapSettled( TagItem item, TagOpinions opinions, String model,
                         GapProposal gap, String fate, String tag ) ;

        List<RateLimitNotice> rateLimitNotices() ;

        /** The AI hub's state now, for the Models tab. */
        HubStatus hubStatus() ;

        /**
         * Why no more questions can be tagged today: a root whose every
         * model, fallbacks included, is held for the day; null otherwise.
         */
        String heldForTheDay() ;

        /** Set a model to pass its questions through, from the Models tab. */
        void setPassthrough( String modelId, boolean on ) ;

        /** Terminate job {@code jobId}'s request, from the Models tab. */
        void abortRequest( long jobId ) ;

        /** The model queue: questions waiting, running and done. */
        TaskScheduler.Counts queueCounts() ;

        /**
         * Epoch ms from which a model may take its next request; now or
         * earlier if it may now.
         */
        long nextRequestAt() ;

        /** What is tagged, and by whom, for the title. */
        String describe() ;

        void shutdown() ;
    }

    /**
     * The questions a Load brought, and how many of the topic's own and of
     * its dependents await tags on the server.
     */
    public record Loaded( List<TagItem> items, int ownPending,
                          int depPending ) {}

    /** A question to save and the decision to save on it. */
    public record SaveItem( TagItem item, TagDecision decision ) {}

    private static final double SPLIT_RATIO          = 0.64 ;
    private static final int    PREVIEW_PANEL_HEIGHT = 300 ;
    private static final int    PREVIEW_IMAGE_WIDTH  = 800 ;
    private static final int    ROW_HEIGHT           = 20 ;
    private static final int    DETAIL_ROWS          = 1 ;

    /** Seconds an unattended run waits between batches, to be stopped. */
    private static final int UNATTENDED_PAUSE_SECONDS = 5 ;

    /** The most loads an unattended run may be given. */
    private static final int MAX_UNATTENDED_LOADS = 999 ;

    private static final String RUN_UNATTENDED  = "Run unattended" ;
    private static final String STOP_UNATTENDED = "Stop unattended processing" ;

    /** In {@link #COLUMN_WIDTHS}: as wide as a row is high, not resizable. */
    private static final int SQUARE = 0 ;

    /**
     * Preferred width of each table column in pixels, in the order of the
     * columns of {@link TagTableModel}; edit to taste.
     */
    private static final int[] COLUMN_WIDTHS = {
             50,    // Id
            130,    // Source
            110,    // Question
             40,    // Type
             50,    // Key
            400,    // Tags
            150,    // Status
            SQUARE  // saved dot (untitled)
    } ;

    /**
     * The columns sized to their widest content, leaving the rest of the
     * width to Tags.
     */
    private static final int[] FITTED_COLUMNS = {
            TagTableModel.COL_ID,
            TagTableModel.COL_SOURCE,
            TagTableModel.COL_QUESTION,
            TagTableModel.COL_TYPE,
            TagTableModel.COL_KEY,
            TagTableModel.COL_STATUS
    } ;

    /** Room around a fitted column's cell text: insets and borders. */
    private static final int CELL_PADDING = 12 ;

    /** Room around a fitted column's title: sort arrow and borders. */
    private static final int HEADER_PADDING = 24 ;

    /** Row background per status; a status not listed keeps the table's. */
    private static final Map<TaggerStatus,Color> STATUS_BG =
            new EnumMap<>( Map.ofEntries(
                    Map.entry( TaggerStatus.QUEUED,       new Color( 0xEEF4FA ) ),
                    Map.entry( TaggerStatus.RUNNING_T1,   new Color( 0xFFFAD9 ) ),
                    Map.entry( TaggerStatus.RUNNING_T2,   new Color( 0xFFF0C2 ) ),
                    Map.entry( TaggerStatus.ACCEPTED_T1,  new Color( 0xE2F1E3 ) ),
                    Map.entry( TaggerStatus.ACCEPTED_T2,  new Color( 0xD5EBD7 ) ),
                    Map.entry( TaggerStatus.GAP_PROPOSED, new Color( 0xEDE3F5 ) ),
                    Map.entry( TaggerStatus.NEEDS_HUMAN,  new Color( 0xFFE6CC ) ),
                    Map.entry( TaggerStatus.DECIDED,      new Color( 0xF6E0EA ) ),
                    Map.entry( TaggerStatus.DEFERRED,     new Color( 0xFCEFC5 ) ),
                    Map.entry( TaggerStatus.SAVING,       new Color( 0xE8E3F2 ) ),
                    Map.entry( TaggerStatus.SAVED,        new Color( 0xD8E8F7 ) ),
                    Map.entry( TaggerStatus.FAILED,       new Color( 0xF9E6E8 ) ),
                    Map.entry( TaggerStatus.ERROR,        new Color( 0xF4D2D2 ) ),
                    Map.entry( TaggerStatus.NO_IMAGES,    new Color( 0xEEEEEE ) ),
                    Map.entry( TaggerStatus.UNSUPPORTED,  new Color( 0xEEEEEE ) ) ) ) ;

    /** The Tags cell of a chosen set, and a Decided row's Status cell. */
    private static final Color DECIDED_CELL_BG = new Color( 0xB9E4BB ) ;

    /** The Tags cell of a chosen set that breaks the count rules. */
    private static final Color COUNT_PROBLEM_BG = new Color( 0xF4C7C3 ) ;

    /** How much darker every other row is, for a faint stripe. */
    private static final double STRIPE_SHADE = 0.965 ;

    /** The Tags cell's text while it shows a proposal, not a chosen set. */
    private static final Color PROPOSAL_FG = new Color( 0x757575 ) ;

    /** The Status cell of a row handed to the user. */
    private static final Color HAND_OFF_CELL_BG = new Color( 0xD32F2F ) ;

    /** The Status cell of a row whose gap proposal waits. */
    private static final Color GAP_CELL_BG = new Color( 0x6A1B9A ) ;

    // The queue section's background: idle, requests in flight, and
    // questions waiting with none in flight (e.g. held by a rate limit).
    private static final Color QUEUE_IDLE    = new Color( 0xC8E6C9 ) ;
    private static final Color QUEUE_ACTIVE  = new Color( 0xFFCC80 ) ;
    private static final Color QUEUE_WAITING = new Color( 0xF8BBD0 ) ;

    // The queue section's labels are as wide as these, so changing numbers
    // don't shift the status bar.
    private static final String WIDEST_COUNTS =
            "Models:  99999 processed,  99 running,  9999 in queue" ;
    private static final String WIDEST_PACE = "next request in 59 min 59 s" ;

    /** Which rows the table shows. */
    private enum Show {

        ALL        ( "All questions",   r -> true ),
        HAND_OFFS  ( "To decide",       r -> r.status == TaggerStatus.NEEDS_HUMAN
                                             || r.status
                                                == TaggerStatus.GAP_PROPOSED ),
        ACCEPTED   ( "Accepted",        r -> r.status.isAccepted() ),
        UNSAVED    ( "Unsaved",         r -> r.status == TaggerStatus.DECIDED ),
        DEFERRED   ( "Deferred",        r -> r.status == TaggerStatus.DEFERRED ),
        DEPENDENTS ( "Dependents",      r -> r.item.dependent() ),
        PROBLEMS   ( "Failed / errors", r -> r.status == TaggerStatus.FAILED
                                             || r.status == TaggerStatus.ERROR
                                             || r.status == TaggerStatus.NO_IMAGES
                                             || r.status
                                                == TaggerStatus.UNSUPPORTED ) ;

        final String         label ;
        final Predicate<Row> test ;

        Show( String label, Predicate<Row> test ) {
            this.label = label ;
            this.test  = test ;
        }

        @Override
        public String toString() { return label ; }
    }

    // The pass badge's backgrounds, one per pass so they are told apart.
    private static final Map<TagClass,Color> PASS_BG = new EnumMap<>( Map.of(
            TagClass.SETUP,   new Color( 0x1565C0 ),
            TagClass.CONCEPT, new Color( 0x6A1B9A ) ) ) ;

    private Controller controller ;

    /** The pass the tagger runs. */
    private final TagClass pass ;

    /** The names models are shown by. */
    private final ModelNames names ;

    private final TagTableModel    model ;
    private final JTable           table ;
    private final TableRowSorter<TagTableModel> sorter ;
    private final HeaderFilterController<Row> headerFilters ;
    private final TranscriptPanel  transcriptPanel ;
    private final ModelsPanel      modelsPanel = new ModelsPanel(
            "Question", ( id, on ) -> setPassthrough( id, on ),
            this::abortRequest ) ;
    private final PromptPanel      promptPanel = new PromptPanel() ;
    private final TopicTagsPanel   topicTags ;
    private final ChosenTagsBar    chosenBar ;
    private final ImagePreview     preview =
            new ImagePreview( PREVIEW_PANEL_HEIGHT, PREVIEW_IMAGE_WIDTH ) ;
    private final RateLimitStrip   rateLimitStrip ;
    private final AnswerStrip      answerStrip ;
    private final JTextArea        detail    = new JTextArea( DETAIL_ROWS, 20 ) ;
    private final JLabel           statusBar = new JLabel( " " ) ;
    private final JPanel           queueBar  = new JPanel( new BorderLayout() ) ;
    private final JLabel           queueText = new JLabel( " " ) ;
    private final JLabel           paceText  = new JLabel( " " ) ;

    // The menus and the toolbar share these actions, and so their state.

    private final Action scopeAction = action( "Scope…", ToolIcon.CRITERIA,
            shortcut( KeyEvent.VK_K ), "Choose the topic, the batch size " +
            "and the tag mode (clears the table)", () -> editScope( null ) ) ;
    private final Action loadAction = action( "Load", ToolIcon.LOAD,
            shortcut( KeyEvent.VK_L ), "Fetch the next questions awaiting " +
            "tags after those loaded before (replaces the table)",
            () -> load( false ) ) ;
    private final Action loadFromStartAction = action( "Load from start",
            null, KeyStroke.getKeyStroke( KeyEvent.VK_L,
                    Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()
                    | InputEvent.SHIFT_DOWN_MASK ),
            "Fetch the first questions awaiting tags, those handed over " +
            "before included", () -> load( true ) ) ;
    private final Action generateAction = action( "Generate topic prompt…",
            null, null, "Write a topic's base prompt from its tags on the " +
            "server", this::generatePrompt ) ;
    private final Action reviewPromptAction = action( "Review topic prompt…",
            null, null, "See a topic's prompt as the models get it, and " +
            "approve it", this::reviewPrompt ) ;
    private final Action startAction = action( "Start", ToolIcon.START,
            shortcut( KeyEvent.VK_R ), "Send the pending questions to tier 1 " +
            "(right-click rows to send those)", this::start ) ;
    private final Action stopAction = action( "Stop", ToolIcon.STOP,
            shortcut( KeyEvent.VK_PERIOD ), "Stop sending questions to the " +
            "models; requests under way finish", this::stop ) ;
    private final Action sendAction = action( "Send selected to the model",
            null, null, "Send the selected questions that are pending, " +
            "failed or undecided to tier 1", this::sendSelected ) ;
    private final Action requeueFailedAction = action(
            "Re-queue failed questions", ToolIcon.REQUEUE, null,
            "Send every failed question to tier 1 again",
            this::requeueFailed ) ;
    private final Action gapAction = action( "Review gap proposal…", null,
            null, "Decide on the tag a model proposed; the run waits",
            this::reviewNextGap ) ;
    private final Action saveAction = action( "Save", ToolIcon.SAVE,
            shortcut( KeyEvent.VK_S ), "Send the accepted tags and the " +
            "decisions made to the server (red while any wait)",
            this::saveAll ) ;
    private final Action clearAction = action( "Clear decisions…",
            ToolIcon.CLEAR, null, "Forget the unsaved decisions; the " +
            "models' replies stay", this::clearAll ) ;
    private final Action selectAllAction = action( "Select all shown rows",
            null, shortcut( KeyEvent.VK_A ), null, this::selectAllRows ) ;

    /** Named "Stop unattended processing" while the loop runs. */
    private final Action unattendedAction = action( RUN_UNATTENDED, null,
            shortcut( KeyEvent.VK_U ), "Load, tag, save the accepted " +
            "questions, and repeat up to a given number of loads",
            this::toggleUnattended ) ;

    /** The decision keys 1-5 as menu items; the keys are the table's. */
    private final Action[] decideActions = {
            action( "1  Use tier 1's tags", null, null, null,
                    () -> pressKey( 1 ) ),
            action( "2  Use tier 2's tags", null, null, null,
                    () -> pressKey( 2 ) ),
            action( "3  Keep the existing tags", null, null, null,
                    () -> pressKey( 3 ) ),
            action( "4  Defer…", null, null, null,
                    () -> pressKey( 4 ) ),
            action( "5  Skip to the next to decide", null, null, null,
                    () -> pressKey( 5 ) ) } ;

    /** The number of decision keys; the last one skips. */
    private static final int DECISION_KEYS = 5 ;

    /** The toolbar's Load and Save buttons spin while their call runs. */
    private final JButton loadButton = toolButton( loadAction ) ;
    private final JButton saveButton = toolButton( saveAction ) ;

    private final JCheckBox loadAfterSaveBox =
            new JCheckBox( "then load again", true ) ;
    private final JCheckBoxMenuItem rateLimitItem =
            new JCheckBoxMenuItem( "Show rate limit bar", true ) ;
    private final JComboBox<Show> showBox = new JComboBox<>( Show.values() ) ;

    private ProcessingState procState = ProcessingState.STOPPED ;
    private boolean         loading ;

    /** True while the scope is being set (the catalogue fetched). */
    private boolean scoping ;

    /**
     * The topic's own questions and its dependents awaiting tags on the
     * server, as of the last fetch less those saved since; -1 before the
     * first Load.
     */
    private int ownPending = -1 ;
    private int depPending = -1 ;

    /**
     * The rows whose gap proposal waits for the user, in the order they
     * came; the run is paused while any does.
     */
    private final Deque<Row> gaps = new ArrayDeque<>() ;

    /** True while the gap dialog (or the tag it creates) is under way. */
    private boolean gapOpen ;

    /** A {@link #fitColumns} is queued on the EDT. */
    private boolean fitPending ;

    /**
     * The rows whose chosen set the user is editing: shown whatever the
     * filters say, so a row doesn't vanish at its first edit, until the
     * selection leaves them.
     */
    private final Set<Row> pinned = new HashSet<>() ;

    /** True while Run unattended loops: load, tag, save. */
    private boolean unattended ;

    /**
     * The questions the unattended run has asked the server for, by every
     * load and refill; at most {@link #maxLoads} batches of the scope's
     * size.
     */
    private int asked ;

    /**
     * The loads the unattended run may make; asked for as it starts, and
     * offered again the next time.
     */
    private int maxLoads = 10 ;

    /**
     * What the unattended run is doing, or why it ended, for the status
     * bar; null before the first.
     */
    private String unattendedStep ;

    /**
     * True from the time the unattended batch is loaded until it is judged,
     * so the end of its model run is taken for the batch's once only.
     */
    private boolean judging ;

    /**
     * True while the unattended run saves the questions its batch has
     * settled and fetches more, as its model run goes on.
     */
    private boolean refilling ;

    /**
     * True once a refill found no question to add: the batch runs out, and
     * is then saved whole.
     */
    private boolean drained ;

    /** Questions the batch's refills saved, and dropped from the table. */
    private long savedEarlier ;

    /** Counts down the pause before the next unattended batch. */
    private Timer pauseTimer ;

    /** The rate limits the unattended run has logged, so each logs once. */
    private final Set<String> loggedLimits = new HashSet<>() ;

    /**
     * @param pass        the pass the tagger runs
     * @param transcripts source of the transcripts shown on the right
     * @param names       the names models are shown by
     */
    public TaggerFrame( TagClass pass, TranscriptStore transcripts,
                        ModelNames names ) {

        super( title( pass, null ) ) ;
        this.pass            = pass ;
        this.names           = names ;
        this.model           = new TagTableModel( names ) ;
        this.table           = new JTable( model ) ;
        this.sorter          = new TableRowSorter<>( model ) ;
        this.headerFilters   = new HeaderFilterController<>( table, model,
                                                           this::applyFilter ) ;
        this.transcriptPanel = new TranscriptPanel( transcripts, names ) ;
        this.topicTags       = new TopicTagsPanel( pass ) ;
        this.chosenBar       = new ChosenTagsBar( this::topicName,
                                                  p -> editChosen( p, true ),
                                                  p -> editChosen( p, false ) ) ;
        this.rateLimitStrip  = new RateLimitStrip( names ) ;
        this.answerStrip     = new AnswerStrip( pass, names, this::topicName,
                                                this::decideByClick ) ;

        buildTable() ;

        loadAfterSaveBox.setToolTipText( "After Save, load the next " +
                                         "questions awaiting tags" ) ;
        loadAfterSaveBox.setFocusable( false ) ;
        showBox.addActionListener( e -> {
            applyFilter() ;
            table.requestFocusInWindow() ;
        } ) ;
        setJMenuBar( buildMenuBar() ) ;

        JToolBar toolbar = new JToolBar() ;
        toolbar.setFloatable( false ) ;
        toolbar.add( toolButton( scopeAction ) ) ;
        toolbar.add( loadButton ) ;
        toolbar.addSeparator() ;
        toolbar.add( toolButton( startAction ) ) ;
        toolbar.add( toolButton( stopAction ) ) ;
        toolbar.add( toolButton( requeueFailedAction ) ) ;
        toolbar.addSeparator() ;
        toolbar.add( saveButton ) ;
        toolbar.add( loadAfterSaveBox ) ;
        toolbar.add( toolButton( clearAction ) ) ;
        toolbar.addSeparator() ;
        toolbar.add( new JLabel( "Show: " ) ) ;
        // As wide as its longest label; the toolbar would stretch it.
        showBox.setMaximumSize( showBox.getPreferredSize() ) ;
        toolbar.add( showBox ) ;

        // Table, preview, answer strip, topic tags and the chosen set on the
        // left; the tabs and the rate limit strip on the right.
        JPanel left = new JPanel( new BorderLayout() ) ;
        left.add( new JScrollPane( table ), BorderLayout.CENTER ) ;

        detail.setEditable( false ) ;
        detail.setFocusable( false ) ;
        detail.setBackground( left.getBackground() ) ;
        detail.setBorder( BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder( 1, 0, 1, 0, Color.GRAY ),
                BorderFactory.createEmptyBorder( 4, 8, 4, 8 ) ) ) ;

        JPanel question = new JPanel( new BorderLayout() ) ;
        question.add( preview, BorderLayout.CENTER ) ;
        question.add( answerStrip, BorderLayout.SOUTH ) ;

        JPanel below = new JPanel( new BorderLayout() ) ;
        below.add( detail, BorderLayout.NORTH ) ;
        below.add( question, BorderLayout.CENTER ) ;
        below.add( topicTags, BorderLayout.EAST ) ;
        below.add( chosenBar, BorderLayout.SOUTH ) ;
        left.add( below, BorderLayout.SOUTH ) ;

        JPanel right = new JPanel( new BorderLayout() ) ;
        right.add( tabs(), BorderLayout.CENTER ) ;
        right.add( rateLimitStrip, BorderLayout.SOUTH ) ;

        JSplitPane split = new JSplitPane( JSplitPane.HORIZONTAL_SPLIT,
                                           left, right ) ;
        split.setResizeWeight( SPLIT_RATIO ) ;
        split.setContinuousLayout( true ) ;

        statusBar.setBorder( BorderFactory.createEmptyBorder( 4, 8, 4, 8 ) ) ;
        queueText.setBorder( BorderFactory.createEmptyBorder( 4, 12, 4, 12 ) ) ;
        paceText.setBorder( BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder( 0, 1, 0, 0, Color.GRAY ),
                BorderFactory.createEmptyBorder( 4, 12, 4, 12 ) ) ) ;
        fixWidth( queueText, WIDEST_COUNTS ) ;
        fixWidth( paceText, WIDEST_PACE ) ;
        queueBar.setBorder(
                BorderFactory.createMatteBorder( 0, 1, 0, 0, Color.GRAY ) ) ;
        queueBar.add( queueText, BorderLayout.CENTER ) ;
        queueBar.add( paceText, BorderLayout.EAST ) ;

        JLabel badge = new JLabel( pass.wire().toUpperCase() ) ;
        badge.setOpaque( true ) ;
        badge.setBackground( PASS_BG.get( pass ) ) ;
        badge.setForeground( Color.WHITE ) ;
        badge.setFont( badge.getFont().deriveFont( Font.BOLD ) ) ;
        badge.setBorder( BorderFactory.createEmptyBorder( 4, 12, 4, 12 ) ) ;
        badge.setToolTipText( pass + " tagging pass; restart QTagger to " +
                              "run the other" ) ;

        JPanel footer = new JPanel( new BorderLayout() ) ;
        footer.add( badge, BorderLayout.WEST ) ;
        footer.add( statusBar, BorderLayout.CENTER ) ;
        footer.add( queueBar, BorderLayout.EAST ) ;

        setLayout( new BorderLayout() ) ;
        add( toolbar, BorderLayout.NORTH ) ;
        add( split, BorderLayout.CENTER ) ;
        add( footer, BorderLayout.SOUTH ) ;

        // The whole screen, menu bar and dock included; not maximised,
        // which would leave them out.
        setBounds( GraphicsEnvironment.getLocalGraphicsEnvironment()
                                      .getDefaultScreenDevice()
                                      .getDefaultConfiguration()
                                      .getBounds() ) ;
        setDefaultCloseOperation( WindowConstants.DO_NOTHING_ON_CLOSE ) ;
        addWindowListener( new WindowAdapter() {
            @Override public void windowOpened( WindowEvent e ) {
                split.setDividerLocation( SPLIT_RATIO ) ;
            }

            @Override public void windowClosing( WindowEvent e ) {
                if( !discardUnsaved( "Quit" ) ) return ;
                if( controller != null ) controller.shutdown() ;
                dispose() ;
                System.exit( 0 ) ;
            }
        } ) ;

        new Timer( 1000, e -> {
            refreshRateLimitStrip() ;
            refreshQueueBar() ;
            refreshModels() ;
        } ).start() ;
        updateControls() ;
    }

    /**
     * Talk to {@code controller}; the commands that need one are enabled
     * now, as the window was built before it was set.
     */
    public void setController( Controller controller ) {
        this.controller = controller ;
        updateControls() ;
    }

    /**
     * The saved scope was put in effect at startup, or, with a
     * {@code problem}, could not be: the Scope dialog then opens.
     */
    public void scopeRestored( String problem ) {
        showScope() ;
        if( problem != null ) editScope( problem ) ;
    }

    /** Show the scope in the title and the Prompt tab. */
    private void showScope() {
        setTitle( title( pass, controller.describe() ) ) ;
        promptPanel.show( controller.assembly() ) ;
        topicTags.show( controller.assembly() ) ;
        showChosen() ;
        updateControls() ;
    }

    /**
     * "QTagger [SETUP] - <scope>": the pass first, so a title cut short
     * still shows it.
     */
    private static String title( TagClass pass, String scope ) {
        return "QTagger [" + pass.wire().toUpperCase() + "]" +
               ( scope != null ? " - " + scope : "" ) ;
    }

    /** An action named {@code name} that runs {@code run}. */
    private static Action action( String name, Icon icon, KeyStroke key,
                                  String tip, Runnable run ) {

        Action a = new AbstractAction( name, icon ) {
            @Override public void actionPerformed( ActionEvent e ) {
                run.run() ;
            }
        } ;
        a.putValue( Action.ACCELERATOR_KEY, key ) ;
        a.putValue( Action.SHORT_DESCRIPTION, tip ) ;
        return a ;
    }

    /** {@code key} with the menu shortcut modifier: Cmd on the Mac. */
    private static KeyStroke shortcut( int key ) {
        return KeyStroke.getKeyStroke( key,
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() ) ;
    }

    /**
     * An icon-only toolbar button for {@code action}, its name in the
     * tooltip; it never takes the focus, so the keys stay with the table.
     */
    private static JButton toolButton( Action action ) {

        JButton b = new JButton( action ) ;

        b.setHideActionText( true ) ;
        b.setFocusable( false ) ;
        b.setToolTipText( "<html><b>" + action.getValue( Action.NAME ) +
                          "</b><br>" +
                          action.getValue( Action.SHORT_DESCRIPTION ) ) ;
        return b ;
    }

    /**
     * File (scope, loads, unattended, exit), Prompts (generate, review),
     * Run (start, stop, send, re-queue), Decisions (the decision keys, gap
     * review, saving, clearing) and View (status filter, header filters,
     * select all, rate limit bar). The toolbar shares their actions.
     */
    private JMenuBar buildMenuBar() {

        JMenu     file = new JMenu( "File" ) ;
        JMenuItem exit = new JMenuItem( "Exit" ) ;

        exit.addActionListener( e -> dispatchEvent(
                new WindowEvent( this, WindowEvent.WINDOW_CLOSING ) ) ) ;
        file.add( scopeAction ) ;
        file.add( loadAction ) ;
        file.add( loadFromStartAction ) ;
        file.add( unattendedAction ) ;
        file.addSeparator() ;
        file.add( exit ) ;

        JMenu prompts = new JMenu( "Prompts" ) ;
        prompts.add( generateAction ) ;
        prompts.add( reviewPromptAction ) ;

        JMenu run = new JMenu( "Run" ) ;
        run.add( startAction ) ;
        run.add( stopAction ) ;
        run.addSeparator() ;
        run.add( sendAction ) ;
        run.add( requeueFailedAction ) ;
        refreshOnOpen( run ) ;

        JMenu             decisions = new JMenu( "Decisions" ) ;
        JCheckBoxMenuItem loadAfter = new JCheckBoxMenuItem(
                "Load again after saving" ) ;

        // One model, so the menu item and the toolbar's box stay in step.
        loadAfter.setModel( loadAfterSaveBox.getModel() ) ;
        for( Action a : decideActions ) decisions.add( a ) ;
        decisions.addSeparator() ;
        decisions.add( gapAction ) ;
        decisions.addSeparator() ;
        decisions.add( saveAction ) ;
        decisions.add( loadAfter ) ;
        decisions.add( clearAction ) ;
        refreshOnOpen( decisions ) ;

        JMenu     view         = new JMenu( "View" ) ;
        JMenuItem clearFilters = new JMenuItem( "Clear all column filters" ) ;

        clearFilters.addActionListener( e -> headerFilters.clearAll() ) ;
        rateLimitItem.addActionListener( e -> refreshRateLimitStrip() ) ;
        view.add( buildShowMenu() ) ;
        view.add( clearFilters ) ;
        view.addSeparator() ;
        view.add( selectAllAction ) ;
        view.addSeparator() ;
        view.add( rateLimitItem ) ;

        JMenuBar bar = new JMenuBar() ;
        bar.add( file ) ;
        bar.add( prompts ) ;
        bar.add( run ) ;
        bar.add( decisions ) ;
        bar.add( view ) ;
        return bar ;
    }

    /** The status filter as radio items, in step with the toolbar's. */
    private JMenu buildShowMenu() {

        JMenu       menu  = new JMenu( "Show" ) ;
        ButtonGroup group = new ButtonGroup() ;

        for( Show show : Show.values() ) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(
                    show.label, show == showBox.getSelectedItem() ) ;

            item.addActionListener( e -> showBox.setSelectedItem( show ) ) ;
            showBox.addItemListener( e -> {
                if( e.getItem() == show
                    && e.getStateChange() == ItemEvent.SELECTED ) {
                    item.setSelected( true ) ;
                }
            } ) ;
            group.add( item ) ;
            menu.add( item ) ;
        }
        return menu ;
    }

    /** Bring the selection's actions up to date as {@code menu} opens. */
    private void refreshOnOpen( JMenu menu ) {
        menu.addMenuListener( new MenuListener() {
            @Override public void menuSelected( MenuEvent e ) {
                updateSelectionActions() ;
            }

            @Override public void menuDeselected( MenuEvent e ) {}

            @Override public void menuCanceled( MenuEvent e ) {}
        } ) ;
    }

    /** Enable the actions on the selected rows as the selection allows. */
    private void updateSelectionActions() {

        sendAction.setEnabled( canSend() && !sendableSelection().isEmpty() ) ;
        for( int n = 1 ; n <= DECISION_KEYS ; n++ ) {
            decideActions[n - 1].setEnabled( canPress( n ) ) ;
        }
    }

    /** True if decision key {@code n} applies to any selected row. */
    private boolean canPress( int n ) {

        List<Row> rows = selectedRows() ;
        return switch( n ) {
            case 1  -> rows.stream().anyMatch( r -> r.canUse( 1 ) ) ;
            case 2  -> pass.solves()
                       && rows.stream().anyMatch( r -> r.canUse( 2 ) ) ;
            case 3  -> rows.stream().anyMatch( Row::canKeepExisting ) ;
            case 4  -> rows.stream().anyMatch( Row::isOpen ) ;
            case 5  -> table.getRowCount() > 0 ;
            default -> false ;
        } ;
    }

    /**
     * A click on the answer strip: decide as key {@code n} does, then give
     * the table the focus back so the keys go on working.
     */
    private void decideByClick( int n ) {
        pressKey( n ) ;
        table.requestFocusInWindow() ;
    }

    /**
     * Decision key {@code n}, for the selected rows it applies to: 1 use
     * tier 1's tags, 2 tier 2's, 3 keep the hand tags, 4 defer; 5 skips to
     * the next question to decide.
     */
    private void pressKey( int n ) {

        if( !canPress( n ) ) return ;
        List<Row> rows = selectedRows() ;
        switch( n ) {
            case 1, 2 -> useTier( rows, n ) ;
            case 3    -> keepExisting( rows ) ;
            case 4    -> defer( rows ) ;
            case 5    -> select( nextToDecide( List.of() ) ) ;
            default   -> { }
        }
    }

    private void buildTable() {

        // Re-filter as statuses change, so a decided row leaves "To decide".
        sorter.setSortsOnUpdates( true ) ;
        // When an update drops or moves rows, JTable repaints only the
        // updated row's old strip; repaint the lot, once the table has
        // taken the new view in.
        sorter.addRowSorterListener( e -> {
            table.revalidate() ;
            table.repaint() ;
        } ) ;
        table.setRowSorter( sorter ) ;
        table.setSelectionMode(
                ListSelectionModel.MULTIPLE_INTERVAL_SELECTION ) ;
        table.setFillsViewportHeight( true ) ;
        table.setDefaultRenderer( Object.class, new RowRenderer() ) ;
        table.setDefaultRenderer( Integer.class, new RowRenderer() ) ;
        table.setDefaultRenderer( Boolean.class, new RowRenderer() ) ;
        table.setRowHeight( ROW_HEIGHT ) ;

        setColumnWidths() ;
        // The setup pass has no key to check.
        if( !pass.solves() ) hideColumn( TagTableModel.COL_KEY ) ;
        fitColumns() ;
        model.addTableModelListener( e -> fitColumnsLater() ) ;
        table.getSelectionModel().addListSelectionListener( e -> {
            if( !e.getValueIsAdjusting() ) showSelection() ;
        } ) ;
        bindDecisionKeys() ;
        addRowMenu() ;
    }

    /** Size the table's columns as {@link #COLUMN_WIDTHS} says. */
    private void setColumnWidths() {

        int n = Math.min( COLUMN_WIDTHS.length, table.getColumnCount() ) ;

        for( int c = 0 ; c < n ; c++ ) {
            TableColumn column = table.getColumnModel().getColumn( c ) ;
            if( COLUMN_WIDTHS[c] == SQUARE ) {
                column.setMinWidth( table.getRowHeight() ) ;
                column.setMaxWidth( table.getRowHeight() ) ;
                column.setResizable( false ) ;
            }
            else {
                column.setPreferredWidth( COLUMN_WIDTHS[c] ) ;
            }
        }
    }

    /** Fit the columns once the current burst of model changes is over. */
    private void fitColumnsLater() {

        if( fitPending ) return ;
        fitPending = true ;
        SwingUtilities.invokeLater( () -> {
            fitPending = false ;
            fitColumns() ;
        } ) ;
    }

    /**
     * Fix each of {@link #FITTED_COLUMNS} shown to the width of its widest
     * content (header or any row, filtered out or not), so the rest of the
     * table's width goes to the Tags column.
     */
    private void fitColumns() {

        FontMetrics fm  = table.getFontMetrics( table.getFont() ) ;
        // A filtered header is bold, with a "*".
        FontMetrics hfm = table.getFontMetrics(
                table.getTableHeader().getFont().deriveFont( Font.BOLD ) ) ;

        for( int col : FITTED_COLUMNS ) {
            int view = table.convertColumnIndexToView( col ) ;
            if( view < 0 ) continue ;      // hidden

            int width = hfm.stringWidth( model.getColumnName( col ) + " *" )
                        + HEADER_PADDING ;
            for( int r = 0 ; r < model.getRowCount() ; r++ ) {
                Object v = model.getValueAt( r, col ) ;
                if( v != null ) {
                    width = Math.max( width, fm.stringWidth( v.toString() )
                                             + CELL_PADDING ) ;
                }
            }
            if( col == TagTableModel.COL_STATUS ) {
                width += Spinner.ICON.getIconWidth() + 4 ;
            }
            TableColumn column = table.getColumnModel().getColumn( view ) ;
            column.setMinWidth( width ) ;
            column.setMaxWidth( width ) ;
            column.setPreferredWidth( width ) ;
        }
    }

    /** Take model column {@code col} out of the table's view. */
    private void hideColumn( int col ) {
        table.removeColumn( table.getColumnModel().getColumn(
                table.convertColumnIndexToView( col ) ) ) ;
    }

    /**
     * Right-click on the table, for the selected rows, or the clicked row if
     * it isn't one of them: send them to the model, or to a model of the
     * user's choice, the decision keys, review a gap, and save or clear
     * their decisions.
     */
    private void addRowMenu() {

        JPopupMenu menu   = new JPopupMenu() ;
        JMenuItem  send   = new JMenuItem() ;
        JMenu      sendTo = new JMenu( "Send to model" ) ;
        JMenuItem  gap    = new JMenuItem( "Review gap proposal…" ) ;
        JMenuItem  save   = new JMenuItem() ;
        JMenuItem  clear  = new JMenuItem() ;

        send.addActionListener( e -> sendSelected() ) ;
        gap.addActionListener( e -> reviewGap( selectedRow() ) ) ;
        save.addActionListener( e -> save( selectedRows(), null, () -> {} ) ) ;
        clear.addActionListener( e -> clear( selectedRows() ) ) ;
        menu.add( send ) ;
        menu.add( sendTo ) ;
        menu.addSeparator() ;
        for( int i = 0 ; i < DECISION_KEYS - 1 ; i++ ) {
            menu.add( decideActions[i] ) ;
        }
        menu.add( gap ) ;
        menu.addSeparator() ;
        menu.add( save ) ;
        menu.add( clear ) ;

        table.addMouseListener( new MouseAdapter() {
            @Override public void mousePressed( MouseEvent e ) { popup( e ) ; }

            @Override public void mouseReleased( MouseEvent e ) { popup( e ) ; }

            private void popup( MouseEvent e ) {

                if( !e.isPopupTrigger() ) return ;
                int view = table.rowAtPoint( e.getPoint() ) ;
                if( view < 0 ) return ;

                if( !table.isRowSelected( view ) ) {
                    table.setRowSelectionInterval( view, view ) ;
                }
                List<Row> sel       = selectedRows() ;
                int       n         = sendableSelection().size() ;
                int       clearable = count( sel, TaggerFrame::isClearable ) ;
                int       savable   = count( sel, Row::isSavable ) ;
                Row       one       = sel.size() == 1 ? sel.get( 0 ) : null ;

                send.setText( "Send " + plural( n, "question" ) +
                              " to the model" ) ;
                send.setEnabled( n > 0 && canSend() ) ;
                fillSendTo( sendTo ) ;
                updateSelectionActions() ;
                gap.setEnabled( one != null && !gapOpen
                                && one.status == TaggerStatus.GAP_PROPOSED ) ;
                save.setText( "Save " + plural( savable, "question" ) ) ;
                save.setEnabled( savable > 0 && controller != null ) ;
                clear.setText( "Clear " + plural( clearable, "decision" ) ) ;
                clear.setEnabled( clearable > 0 ) ;
                menu.show( table, e.getX(), e.getY() ) ;
            }
        } ) ;
    }

    /**
     * The "Send to model" submenu: every usable model, for the sendable
     * selection; in the concept pass under "As tier 1" (a miss still goes
     * on to tier 2) and "As tier 2" (the rows with a tier 1 reply, which
     * they keep).
     */
    private void fillSendTo( JMenu sendTo ) {

        sendTo.removeAll() ;
        List<String> models = controller == null ? List.of()
                                                 : controller.models() ;
        int n1 = sendableSelection().size() ;
        int n2 = sendableForTier2().size() ;

        if( !pass.solves() ) {
            addModels( sendTo, models, 1 ) ;
        }
        else {
            JMenu t1 = new JMenu( "As tier 1 (" + plural( n1, "question" ) +
                                  ")" ) ;
            JMenu t2 = new JMenu( "As tier 2 (" + plural( n2, "question" ) +
                                  ", tier 1's reply kept)" ) ;
            addModels( t1, models, 1 ) ;
            addModels( t2, models, 2 ) ;
            t1.setEnabled( n1 > 0 ) ;
            t2.setEnabled( n2 > 0 ) ;
            sendTo.add( t1 ) ;
            sendTo.add( t2 ) ;
        }
        sendTo.setEnabled( n1 > 0 && canSend() && !models.isEmpty() ) ;
    }

    /** An item per model in {@code menu}, sending as tier {@code tier}. */
    private void addModels( JMenu menu, List<String> models, int tier ) {

        for( String id : models ) {
            JMenuItem item = new JMenuItem( names.of( id ) ) ;
            item.setToolTipText( id + " alone: its retries, no fallback" ) ;
            item.addActionListener( e -> sendSelected( tier, id ) ) ;
            menu.add( item ) ;
        }
    }

    private static int count( List<Row> rows, Predicate<Row> test ) {
        return (int) rows.stream().filter( test ).count() ;
    }

    private static String plural( int n, String noun ) {
        return n + " " + noun + ( n == 1 ? "" : "s" ) ;
    }

    /** The selected rows, in table order. */
    private List<Row> selectedRows() {

        List<Row> rows = new ArrayList<>() ;
        for( int view : table.getSelectedRows() ) rows.add( rowAt( view ) ) ;
        return rows ;
    }

    /**
     * Where {@code row} is in the table: its view index, else (filtered out)
     * after every shown row, in load order.
     */
    private int viewOrder( Row row ) {

        int index = model.indexOf( row ) ;
        int view  = table.convertRowIndexToView( index ) ;

        return view >= 0 ? view : table.getRowCount() + index ;
    }

    private Row rowAt( int view ) {
        return model.getRow( table.convertRowIndexToModel( view ) ) ;
    }

    /**
     * True if rows may be sent to the model now: with a scope, not while a
     * run is stopping, and in an unattended run only while its model run
     * is going, which takes them in; between its runs a Load would replace
     * them.
     */
    private boolean canSend() {
        return controller != null && controller.assembly() != null
               && ( unattended ? procState == ProcessingState.RUNNING
                               : procState != ProcessingState.STOPPING ) ;
    }

    /** The selected rows that may be sent to the model, in table order. */
    private List<Row> sendableSelection() {
        return selectedRows().stream()
                             .filter( r -> r.item.isSendable()
                                           && r.status.isRunnable() )
                             .toList() ;
    }

    /**
     * The selected rows that may be sent straight to tier 2: sendable, with
     * a tier 1 reply to keep.
     */
    private List<Row> sendableForTier2() {
        return sendableSelection().stream()
                                  .filter( r -> r.opinions != null
                                                && r.opinions.tier1() != null )
                                  .toList() ;
    }

    /**
     * Select every row the filter shows: View's Cmd+A (Ctrl+A off the Mac),
     * unless the focused component uses the key itself.
     */
    private void selectAllRows() {
        table.selectAll() ;
        table.requestFocusInWindow() ;
    }

    /**
     * Keys 1-5 on the table (main row or keypad), for every selected row: 1
     * use tier 1's tags, 2 tier 2's, 3 keep the hand tags, 4 defer; 5 skips
     * to the next question to decide. The Decisions menu's items show them;
     * they aren't its accelerators, which would take the digits from the
     * whole window.
     */
    private void bindDecisionKeys() {

        InputMap  keys    = table.getInputMap( JComponent.WHEN_FOCUSED ) ;
        ActionMap actions = table.getActionMap() ;

        for( int n = 1 ; n <= DECISION_KEYS ; n++ ) {
            String name = "decision-" + n ;
            int    key  = n ;

            keys.put( KeyStroke.getKeyStroke( KeyEvent.VK_0 + n, 0 ), name ) ;
            keys.put( KeyStroke.getKeyStroke( KeyEvent.VK_NUMPAD0 + n, 0 ), name ) ;
            // Not the menu's action: its enabled state is only brought up
            // to date as a menu opens.
            actions.put( name, new AbstractAction() {
                @Override public void actionPerformed( ActionEvent e ) {
                    pressKey( key ) ;
                }
            } ) ;
        }
    }

    // ---- scope and prompts ---------------------------------------------

    /**
     * Let the user choose the scope, then put it in effect off the EDT; a
     * scope that can't be used is reported and asked for again. Not while a
     * run is going; asks before losing unsaved decisions, and clears the
     * table.
     *
     * @param problem why it is asked for; null if the user asked
     */
    private void editScope( String problem ) {

        if( controller == null || scoping || loading
            || procState != ProcessingState.STOPPED ) return ;
        if( problem == null && !discardUnsaved( "Scope" ) ) return ;

        Scope chosen = ScopeDialog.ask( this, pass, controller.scope(), problem,
                                        controller.syllabusNames(),
                                        controller::topics,
                                        controller::promptSummary ) ;
        if( chosen == null ) return ;
        setScope( chosen ) ;
    }

    /** Put {@code scope} in effect off the EDT; on failure ask again. */
    private void setScope( Scope scope ) {

        scoping = true ;
        statusBar.setText( "Fetching the topic's tags…" ) ;
        updateControls() ;
        background( () -> {
            controller.setScope( scope ) ;
            return null ;
        }, ok -> {
            scoping = false ;
            clearTable() ;
            ownPending = -1 ;
            depPending = -1 ;
            showScope() ;
            statusBar.setText( "Scope set - press Load" ) ;
        }, e -> {
            scoping = false ;
            updateControls() ;
            editScope( "That scope can't be used: " + e.getMessage() ) ;
        } ) ;
    }

    /** Write a topic's base prompt, reporting the outcome. */
    private void generatePrompt() {

        Topic topic = pickTopic( "Generate topic prompt" ) ;
        if( topic == null ) return ;

        generateAction.setEnabled( false ) ;
        background( () -> controller.generatePrompt( topic ), created -> {
            generateAction.setEnabled( true ) ;
            File dir = controller.promptDir( topic ) ;
            String generated = PromptStore.generatedName( pass ) ;
            String reviewed  = PromptStore.reviewedName( pass ) ;
            JOptionPane.showMessageDialog( this, "Wrote " +
                    generated + " in\n" + dir + "\n\n" +
                    ( created ? "It was copied to " + reviewed +
                                " as a draft: tune it, then review and " +
                                "approve it (Prompts > Review topic prompt)."
                              : reviewed + " is unchanged; compare " +
                                "the two files to see what the server's " +
                                "tags changed." ),
                    "Generate topic prompt", JOptionPane.INFORMATION_MESSAGE ) ;
        }, e -> {
            generateAction.setEnabled( true ) ;
            error( "Generate topic prompt", "Could not generate the prompt", e ) ;
        } ) ;
    }

    /**
     * Show a topic's prompt as the models would get it, and approve it if
     * the user says so; approving the scope's own topic puts the new
     * version in use once no run is going.
     */
    private void reviewPrompt() {

        Topic topic = pickTopic( "Review topic prompt" ) ;
        if( topic == null ) return ;

        reviewPromptAction.setEnabled( false ) ;
        background( () -> controller.reviewPrompt( topic ), review -> {
            reviewPromptAction.setEnabled( true ) ;
            if( !PromptDialogs.review( this, pass, review ) ) return ;
            try {
                controller.approvePrompt( review ) ;
            }
            catch( Exception e ) {
                error( "Approve", "Could not approve the prompt", e ) ;
                return ;
            }
            Scope s = controller.scope() ;
            if( s != null && s.topicId() == topic.getId()
                && procState == ProcessingState.STOPPED ) {
                setScope( s ) ;
            }
            JOptionPane.showMessageDialog( this, "Approved the " +
                    pass.wire() + " prompt of " + topic.getTopicName() +
                    ". Commit " + PromptStore.reviewedName( pass ) +
                    " in\n" + controller.promptDir( topic ),
                    "Review topic prompt", JOptionPane.INFORMATION_MESSAGE ) ;
        }, e -> {
            reviewPromptAction.setEnabled( true ) ;
            error( "Review topic prompt", "Could not read the prompt", e ) ;
        } ) ;
    }

    /** A topic picked by the user, starting on the scope's; null if none. */
    private Topic pickTopic( String title ) {

        if( controller == null ) return null ;
        Scope s = controller.scope() ;
        return PromptDialogs.pickTopic( this, title, controller.syllabusNames(),
                controller::topics, controller::promptSummary,
                s == null ? null : controller.topic( s.topicId() ) ) ;
    }

    /** The name of topic {@code id}, or its id if unknown. */
    private String topicName( int id ) {
        Topic t = controller == null ? null : controller.topic( id ) ;
        return t != null ? t.getTopicName() : String.valueOf( id ) ;
    }

    /**
     * Run {@code work} off the EDT, then {@code done} with its result or
     * {@code failed} with what it threw, on the EDT.
     */
    private <T> void background( Callable<T> work, Consumer<T> done,
                                 Consumer<Throwable> failed ) {

        new SwingWorker<T,Void>() {
            @Override protected T doInBackground() throws Exception {
                return work.call() ;
            }

            @Override protected void done() {

                T result ;
                try {
                    result = get() ;
                }
                catch( Exception e ) {
                    failed.accept( e.getCause() != null ? e.getCause() : e ) ;
                    return ;
                }
                done.accept( result ) ;
            }
        }.execute() ;
    }

    /** Log {@code e} and report it in a dialog titled {@code title}. */
    private void error( String title, String what, Throwable e ) {
        log.error( "{}: {}", what, e.getMessage(), e ) ;
        JOptionPane.showMessageDialog( this, what + ":\n" + e.getMessage(),
                                       title, JOptionPane.ERROR_MESSAGE ) ;
    }

    // ---- toolbar actions -----------------------------------------------

    /**
     * Fetch the next questions of the scope off the EDT ({@code fromStart}:
     * the first) and show them in place of the rows loaded so far.
     */
    private void load( boolean fromStart ) {

        if( controller == null || loading ) return ;
        if( controller.scope() == null ) {
            editScope( "Choose a scope first" ) ;
            return ;
        }
        if( !discardUnsaved( fromStart ? "Load from start" : "Load" ) ) return ;
        fetch( batchSize(), fromStart, this::showItems ) ;
    }

    /**
     * Fetch {@code limit} questions without asking about unsaved decisions
     * and hand them to {@code onLoaded}; a failure is reported instead, and
     * ends an unattended run.
     */
    private void fetch( int limit, boolean fromStart,
                        Consumer<Loaded> onLoaded ) {

        loading = true ;
        statusBar.setText( "Loading the questions awaiting tags…" ) ;
        Spinner.busy( loadButton ) ;
        updateControls() ;

        background( () -> controller.load( limit, fromStart ), loaded -> {
            loading = false ;
            Spinner.idle( loadButton ) ;
            updateControls() ;
            onLoaded.accept( loaded ) ;
        }, e -> {
            loading = false ;
            Spinner.idle( loadButton ) ;
            updateControls() ;
            endUnattended( "could not load the questions" ) ;
            error( "Load", "Could not load the questions", e ) ;
        } ) ;
    }

    /**
     * Fill the table with {@code loaded}, with the replies stored by
     * earlier runs; the rows shown before are dropped.
     */
    private void showItems( Loaded loaded ) {

        ownPending = loaded.ownPending() ;
        depPending = loaded.depPending() ;
        clearTable() ;
        model.setRows( rows( loaded.items() ) ) ;
        restore( model.rows() ) ;
        updateControls() ;
        // Every question already judged: start on the first to decide.
        if( model.rows().stream().noneMatch( TaggerFrame::isPending ) ) {
            selectFirstToDecide() ;
        }
    }

    /** Empty the table and what shows its selection; no gap waits. */
    private void clearTable() {

        model.setRows( List.of() ) ;
        gaps.clear() ;
        pinned.clear() ;
        if( controller != null ) controller.setPaused( false ) ;
        answerStrip.show( null ) ;
        showChosen() ;
        showDetail( null ) ;
        preview.show( null ) ;
    }

    /** A row for each of {@code items}, with its hand tags. */
    private List<Row> rows( List<TagItem> items ) {

        Assembly a = controller.assembly() ;
        return items.stream()
                    .map( i -> new Row( i, i.existing( a.offered() ),
                                        pass.solves() ) )
                    .toList() ;
    }

    /**
     * Give {@code rows} the replies earlier runs stored, judged again here,
     * and their deferrals.
     */
    private void restore( List<Row> rows ) {

        Assembly a = controller.assembly() ;
        for( Row row : rows ) {
            if( !row.item.isSendable() ) continue ;
            PendingTagging.Stored st = controller.stored( row.item ) ;
            if( st == null ) continue ;

            row.opinions = st.opinions() ;
            judge( row, TagJudgement.of( row.item, st.opinions(), a.mode(),
                                         a.offered(), st.gapSettled() ) ) ;
            if( st.deferral() != null ) {
                row.undecidedStatus  = row.status ;
                row.undecidedMessage = row.message ;
                row.status           = TaggerStatus.DEFERRED ;
                row.deferNote        = st.deferral() ;
                row.message          = "Deferred: " + st.deferral() ;
                gaps.remove( row ) ;
            }
            model.changed( row ) ;
        }
        nextGap() ;
    }

    /** Send every pending row (sendable, never sent) to tier 1. */
    private void start() {

        List<Row> rows = model.rows().stream()
                              .filter( TaggerFrame::isPending )
                              .toList() ;
        if( rows.isEmpty() ) {
            statusBar.setText( "Nothing to send to the models" ) ;
            return ;
        }
        send( rows ) ;
    }

    private static boolean isPending( Row row ) {
        return row.item.isSendable() && row.status == TaggerStatus.PENDING ;
    }

    /**
     * Wind the run down: no new requests, running ones finish. Ends an
     * unattended run too.
     */
    private void stop() {
        if( unattended ) endUnattended( "stopped by the user" ) ;
        else if( controller != null ) controller.stop() ;
    }

    /**
     * Send every failed row to tier 1 again, in the table's order; also
     * while a run is going, which takes them in after the rows it has.
     */
    private void requeueFailed() {

        List<Row> failed = model.rows().stream()
                                .filter( r -> r.status == TaggerStatus.FAILED
                                              && r.item.isSendable() )
                                .toList() ;
        log.info( "Re-queueing {} failed questions", failed.size() ) ;
        send( failed ) ;
    }

    /** Send the selected rows that may be sent to the model. */
    private void sendSelected() {
        send( sendableSelection() ) ;
    }

    /**
     * Send the selected rows that may go as tier {@code tier} to
     * {@code model} alone.
     */
    private void sendSelected( int tier, String model ) {
        send( tier == 2 ? sendableForTier2() : sendableSelection(), tier,
              model ) ;
    }

    private void send( List<Row> rows ) {
        send( rows, 1, null ) ;
    }

    /**
     * Queue {@code rows} for tier {@code tier} in the order the table shows
     * them (rows filtered out last, in load order), so they are taken top
     * down; on {@code modelId} alone if not null, else on the tier's root.
     * Their replies stay till new ones come (sent as tier 2, tier 1's is
     * kept); an unsaved decision or a deferral is dropped.
     */
    private void send( List<Row> rows, int tier, String modelId ) {

        if( !canSend() || rows.isEmpty() ) return ;
        rows = rows.stream().sorted( Comparator.comparingInt( this::viewOrder ) )
                   .toList() ;

        Map<TagItem,TierReply> tier1 = new HashMap<>() ;
        for( Row row : rows ) {
            if( tier == 2 ) tier1.put( row.item, row.opinions.tier1() ) ;
            if( row.status == TaggerStatus.DEFERRED ) {
                controller.undefer( row.item ) ;
            }
            row.status    = TaggerStatus.QUEUED ;
            row.message   = null ;
            row.decision  = null ;
            row.base      = null ;
            row.deferNote = null ;
            gaps.remove( row ) ;
        }
        model.changedAll() ;
        showChosen() ;

        List<TagItem> items = rows.stream().map( r -> r.item ).toList() ;
        if( modelId == null ) controller.start( items ) ;
        else controller.start( items, tier, modelId, tier1 ) ;
        nextGap() ;
    }

    /** Save every accepted and decided row, then load again if asked to. */
    private void saveAll() {

        boolean reload = loadAfterSaveBox.isSelected() ;
        save( model.rows(), saveButton, () -> {
            // Loading replaces the rows, so not while the models have some.
            if( reload && ownPending + depPending > 0
                && procState == ProcessingState.STOPPED ) {
                load( false ) ;
            }
        } ) ;
    }

    /**
     * Save the decisions of {@code rows} on the server, then run
     * {@code after} (at once if there are none); {@code button}, if not
     * null, spins meanwhile. Each accepted row among them is first decided
     * on its accepted tier's tags. A set the user made or edited that
     * breaks the count rules is held back, and says so.
     */
    private void save( List<Row> rows, JButton button, Runnable after ) {

        if( controller == null ) return ;
        for( Row row : rows ) {
            if( !row.status.isAccepted() ) continue ;
            decide( row, TagDecision.of( row.judgement.accepted(), false ) ) ;
        }

        List<Row> held = rows.stream()
                             .filter( r -> r.isUnsaved() && breaksCounts( r ) )
                             .toList() ;
        for( Row row : held ) {
            row.message = "Not saved, it breaks the count rules: " +
                          row.countProblem( pass ) ;
            rowChanged( row ) ;
        }
        if( !held.isEmpty() ) {
            log.warn( "{} held back: their tags break the count rules",
                      plural( held.size(), "question" ) ) ;
        }

        List<SaveItem> items = rows.stream()
                                   .filter( r -> r.isUnsaved()
                                                 && !held.contains( r ) )
                                   .map( r -> new SaveItem( r.item,
                                                            r.decision ) )
                                   .toList() ;
        if( items.isEmpty() ) {
            after.run() ;
            return ;
        }
        confirm( items, button, after ) ;
    }

    /** True if {@code row} has a decision or a deferral to clear. */
    private static boolean isClearable( Row row ) {
        return row.isUnsaved() && row.status != TaggerStatus.ERROR
                   && row.decision.human()
               || row.status == TaggerStatus.DEFERRED ;
    }

    private void clearAll() {

        long n = model.count( TaggerFrame::isClearable ) ;
        if( n == 0 ) return ;

        int answer = JOptionPane.showConfirmDialog( this,
                "Clear " + plural( (int) n, "decision" ) +
                " (deferrals included)?\nThe models' replies stay.",
                "Clear decisions", JOptionPane.OK_CANCEL_OPTION ) ;
        if( answer == JOptionPane.OK_OPTION ) clear( model.rows() ) ;
    }

    /**
     * Forget the unsaved decisions and the deferrals of {@code rows}: each
     * goes back to what it was before, with its replies.
     */
    private void clear( List<Row> rows ) {

        for( Row row : rows ) {
            if( !isClearable( row ) ) continue ;
            if( row.status == TaggerStatus.DEFERRED ) {
                controller.undefer( row.item ) ;
            }
            row.status    = row.undecidedStatus ;
            row.message   = row.undecidedMessage ;
            row.decision  = null ;
            row.base      = null ;
            row.deferNote = null ;

            model.changed( row ) ;
        }
        showChosen() ;
        updateControls() ;
    }

    /**
     * True if there are no unsaved decisions, or the user agrees to lose
     * them; {@code title} names what would lose them.
     */
    private boolean discardUnsaved( String title ) {

        long n = model.count( r -> r.isUnsaved() && r.decision.human() ) ;
        if( n == 0 ) return true ;
        return JOptionPane.showConfirmDialog( this,
                plural( (int) n, "decision" ) + " not saved yet will be " +
                "lost. Continue?", title, JOptionPane.OK_CANCEL_OPTION )
               == JOptionPane.OK_OPTION ;
    }

    // ---- unattended runs -----------------------------------------------

    /** File > Run unattended, or Stop unattended processing while on. */
    private void toggleUnattended() {
        if( unattended ) endUnattended( "stopped by the user" ) ;
        else startUnattended() ;
    }

    /**
     * Loop until stopped, logging each step: load a batch after the
     * cursor, send its pending questions to tier 1, save the accepted ones
     * (refilling as half a batch settles, {@link #refillIfDue}), and after
     * {@link #UNATTENDED_PAUSE_SECONDS} load the next. Hand-offs are left
     * for an attended Load from start; a gap proposal pauses the loop with
     * the run until it is decided. The loop ends by itself once it has
     * asked for as many batches of questions as the user gives as it starts
     * (refills included), or when nothing more awaits tags after the
     * cursors.
     */
    private void startUnattended() {

        if( controller == null || loading || controller.scope() == null
            || procState != ProcessingState.STOPPED ) return ;
        if( !askMaxLoads() ) return ;
        if( !discardUnsaved( RUN_UNATTENDED ) ) return ;

        unattended = true ;
        judging    = false ;
        refilling  = false ;
        asked      = 0 ;

        loggedLimits.clear() ;

        unattendedAction.putValue( Action.NAME, STOP_UNATTENDED ) ;
        log.info( "[unattended] Started, at most {} loads: {}", maxLoads,
                  controller.describe() ) ;
        nextBatch() ;
    }

    /**
     * Ask how many loads the unattended run may make, into
     * {@link #maxLoads}.
     *
     * @return false if the user cancelled
     */
    private boolean askMaxLoads() {

        JSpinner spinner = new JSpinner( new SpinnerNumberModel(
                maxLoads, 1, MAX_UNATTENDED_LOADS, 1 ) ) ;
        JPanel   panel   = new JPanel( new FlowLayout( FlowLayout.LEFT,
                                                       8, 0 ) ) ;

        // Wide enough for three digits, at its natural height.
        ( (JSpinner.DefaultEditor) spinner.getEditor() ).getTextField()
                                                        .setColumns( 3 ) ;
        panel.add( new JLabel( "Stop after this many loads:" ) ) ;
        panel.add( spinner ) ;

        int answer = JOptionPane.showConfirmDialog( this, panel,
                RUN_UNATTENDED, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE ) ;
        if( answer != JOptionPane.OK_OPTION ) return false ;
        maxLoads = (Integer) spinner.getValue() ;
        return true ;
    }

    /**
     * End the unattended run, if one is going, saying {@code why}; a model
     * run under way winds down, a save under way finishes, and the table is
     * left as it is for the user to take over.
     */
    private void endUnattended( String why ) {

        if( !unattended ) return ;
        unattended     = false ;
        refilling      = false ;
        unattendedStep = "ended, " + why ;

        if( pauseTimer != null ) pauseTimer.stop() ;
        pauseTimer = null ;
        unattendedAction.putValue( Action.NAME, RUN_UNATTENDED ) ;
        if( procState == ProcessingState.RUNNING ) controller.stop() ;
        log.info( "[unattended] Ended in batch {}: {}", batch(), why ) ;
        updateControls() ;
    }

    private void nextBatch() {

        if( !unattended ) return ;
        refreshRateLimitStrip() ;
        if( !unattended ) return ;      // the daily limit is reached

        int limit = Math.min( batchSize(), allowance() - asked ) ;

        asked += limit ;
        log.info( "[unattended] Batch {}: loading", batch() ) ;
        unattendedStep = "loading" ;
        fetch( limit, false, loaded -> {
            showItems( loaded ) ;
            batchLoaded() ;
        } ) ;
    }

    /** The batch is shown: send its pending questions to tier 1. */
    private void batchLoaded() {

        if( !unattended ) return ;
        drained      = false ;
        savedEarlier = 0 ;
        if( model.getRowCount() == 0 ) {
            endUnattended( "no questions await tags after the cursors" ) ;
            return ;
        }

        List<Row> pending = model.rows().stream()
                                 .filter( TaggerFrame::isPending )
                                 .toList() ;

        judging = true ;
        log.info( "[unattended] Batch {}: {} loaded, {} to send to the " +
                  "models; {} own and {} dependents await tags", batch(),
                  model.getRowCount(), pending.size(), ownPending,
                  depPending ) ;
        if( pending.isEmpty() ) {
            batchJudged() ;
            return ;
        }
        step( "tagging " + plural( pending.size(), "question" ) ) ;
        send( pending ) ;
    }

    /**
     * The models are done with the batch (or none of it needed sending):
     * save the accepted questions; the hand-offs wait.
     */
    private void batchJudged() {

        // A refill under way takes the run's end up once it is done.
        if( !unattended || !judging || refilling ) return ;
        judging = false ;

        for( Row row : model.rows() ) logJudgement( row ) ;
        log.info( "[unattended] Batch {}: saving", batch() ) ;
        step( "saving" ) ;
        save( model.rows(), saveButton, this::batchSaved ) ;
    }

    /** Log what the models made of {@code row}. */
    private void logJudgement( Row row ) {

        String q = row.questionLabel() ;
        if( row.status.isAccepted() ) {
            TierReply t = row.judgement.accepted() ;
            log.info( "[unattended]   {}: accepted tier {} ({}) - {}", q,
                      t.tier(), names.of( t.model() ),
                      String.join( ", ", t.reply().tags() ) ) ;
        }
        else {
            log.info( "[unattended]   {}: {}{}", q,
                      TagTableModel.statusText( row ),
                      row.message != null ? " - " + row.message : "" ) ;
        }
    }

    /**
     * The batch is saved: log the outcome, and pause before the next one,
     * unless nothing is left.
     */
    private void batchSaved() {

        if( !unattended ) return ;
        for( Row row : model.rows() ) {
            if( row.status != TaggerStatus.ERROR ) continue ;
            log.warn( "[unattended]   {}: not saved - {}",
                      row.questionLabel(), row.message ) ;
        }
        log.info( "[unattended] Batch {} saved: {} saved ({} earlier), {} " +
                  "to decide, {} failed, {} errors, {} unsupported or " +
                  "without images; {} own and {} dependents await tags",
                  batch(), model.count( TaggerStatus.SAVED ), savedEarlier,
                  model.count( TaggerStatus.NEEDS_HUMAN ),
                  model.count( TaggerStatus.FAILED ),
                  model.count( TaggerStatus.ERROR ),
                  model.count( TaggerStatus.UNSUPPORTED )
                  + model.count( TaggerStatus.NO_IMAGES ),
                  ownPending, depPending ) ;

        if( asked >= allowance() ) {
            endUnattended( "all " + maxLoads + " loads done" ) ;
        }
        else pauseBeforeNextBatch() ;
    }

    /**
     * While the unattended batch is tagged: once half a batch is accepted,
     * save those questions and fetch as many more after the cursor, sending
     * them to the model run under way, so tier 1 never runs dry while tier
     * 2 catches up. The questions saved are dropped from the table. Refills
     * count as loads; once none is left, or one brings nothing, the batch
     * runs out and is saved whole as before.
     */
    private void refillIfDue() {

        if( !unattended || !judging || refilling || drained || loading
            || procState != ProcessingState.RUNNING ) return ;

        int       half = Math.max( 1, batchSize() / 2 ) ;
        int       more = Math.min( half, allowance() - asked ) ;
        List<Row> done = model.rows().stream()
                              .filter( r -> r.status.isAccepted() )
                              .toList() ;

        if( done.size() < half || more <= 0 ) return ;
        refreshRateLimitStrip() ;
        if( !unattended ) return ;      // the daily limit is reached

        refilling = true ;
        for( Row row : done ) logJudgement( row ) ;
        log.info( "[unattended] Batch {}: saving {} accepted questions " +
                  "while the models work on the rest", batch(), done.size() ) ;
        save( done, saveButton, () -> refillSaved( done, more ) ) ;
    }

    /**
     * The accepted questions {@code saved} are saved: drop those the server
     * took, and fetch {@code more} questions after the cursor.
     */
    private void refillSaved( List<Row> saved, int more ) {

        if( !unattended ) return ;
        for( Row row : saved ) {
            if( row.status != TaggerStatus.ERROR ) continue ;
            log.warn( "[unattended]   {}: not saved - {}",
                      row.questionLabel(), row.message ) ;
        }
        savedEarlier += model.removeIf( r -> r.status == TaggerStatus.SAVED ) ;
        log.info( "[unattended] Batch {} partly saved: {} saved so far; {} " +
                  "own and {} dependents await tags", batch(), savedEarlier,
                  ownPending, depPending ) ;

        if( ownPending + depPending <= 0 ) {
            drained = true ;
            refillDone() ;
            return ;
        }
        asked += more ;
        log.info( "[unattended] Batch {}: fetching {} more questions",
                  batch(), more ) ;
        step( "tagging, fetching " + plural( more, "more question" ) ) ;
        fetch( more, false, this::refillLoaded ) ;
    }

    /**
     * Add the questions of {@code loaded} not in the table yet, and send
     * those pending to the model run.
     */
    private void refillLoaded( Loaded loaded ) {

        if( !unattended ) return ;
        List<TagItem> added = loaded.items().stream()
                                    .filter( i -> !model.has( i.id() ) )
                                    .toList() ;
        List<Row>     rows  = rows( added ) ;

        ownPending = loaded.ownPending() ;
        depPending = loaded.depPending() ;
        model.addRows( rows ) ;
        restore( rows ) ;

        List<Row> pending = rows.stream().filter( TaggerFrame::isPending )
                                .toList() ;

        log.info( "[unattended] Batch {}: {} added, {} to send to the " +
                  "models", batch(), added.size(), pending.size() ) ;
        if( added.isEmpty() ) drained = true ;
        step( "tagging " + plural( model.getRowCount(), "question" ) ) ;
        send( pending ) ;
        refillDone() ;
    }

    /**
     * The refill is over: if the model run ended meanwhile, take the batch
     * as judged; else see whether another is due.
     */
    private void refillDone() {

        refilling = false ;
        updateControls() ;
        if( procState == ProcessingState.STOPPED
            && !controller.isRunning() ) batchJudged() ;
        else refillIfDue() ;
    }

    /**
     * Count down {@link #UNATTENDED_PAUSE_SECONDS} in the status bar, the
     * user's chance to stop, then load the next batch.
     */
    private void pauseBeforeNextBatch() {

        int[] left = { UNATTENDED_PAUSE_SECONDS } ;

        log.info( "[unattended] Next batch in {} s - File > {} to take over",
                  left[0], STOP_UNATTENDED ) ;
        step( "next batch in " + left[0] + " s" ) ;
        pauseTimer = new Timer( 1000, e -> {
            if( --left[0] > 0 ) {
                step( "next batch in " + left[0] + " s" ) ;
                return ;
            }
            ( (Timer) e.getSource() ).stop() ;
            pauseTimer = null ;
            nextBatch() ;
        } ) ;
        pauseTimer.start() ;
    }

    /** The questions a batch has: the scope's batch size. */
    private int batchSize() {
        return controller.scope().batchSize() ;
    }

    /** The questions the unattended run may ask for: its loads' worth. */
    private int allowance() {
        return maxLoads * batchSize() ;
    }

    /**
     * The batch the unattended run is in, from 1: the batch-sizes of
     * questions asked for so far, a part one counted whole.
     */
    private int batch() {
        return Math.max( 1, ( asked + batchSize() - 1 ) / batchSize() ) ;
    }

    /** Show {@code what} the unattended run is doing in the status bar. */
    private void step( String what ) {
        unattendedStep = what ;
        updateControls() ;
    }

    // ---- decisions -----------------------------------------------------

    /** Give the rows tier {@code tier}'s tags. */
    private void useTier( List<Row> rows, int tier ) {
        decide( rows, r -> r.canUse( tier ),
                r -> TagDecision.of( r.tier( tier ), true ) ) ;
    }

    /** Keep the rows' hand tags, their dependencies as they are. */
    private void keepExisting( List<Row> rows ) {
        decide( rows, Row::canKeepExisting,
                r -> TagDecision.existing( r.existing ) ) ;
    }

    /**
     * Defer the open rows with one note: they stay pending on the server,
     * and the note is kept to come back to.
     */
    private void defer( List<Row> rows ) {

        List<Row> open = rows.stream().filter( Row::isOpen ).toList() ;
        if( open.isEmpty() ) return ;

        String note = JOptionPane.showInputDialog( this, "Why defer " +
                ( open.size() == 1 ? open.get( 0 ).questionLabel()
                                   : plural( open.size(), "question" ) ) +
                " (e.g. the key to check)?", "Defer",
                JOptionPane.QUESTION_MESSAGE ) ;
        if( note == null ) return ;
        Row next = nextToDecide( open ) ;

        for( Row row : open ) {
            if( row.status != TaggerStatus.DEFERRED ) {
                if( row.decision == null ) {
                    row.undecidedStatus  = row.status ;
                    row.undecidedMessage = row.message ;
                }
                controller.defer( row.item, row.reason != null
                                            ? row.reason.name() : null,
                                  note ) ;
            }
            row.status    = TaggerStatus.DEFERRED ;
            row.decision  = null ;
            row.base      = null ;
            row.deferNote = note ;
            row.message   = "Deferred: " + note ;
            model.changed( row ) ;
        }
        select( next ) ;
        showChosen() ;
        updateControls() ;
    }

    /**
     * Decide, for each of {@code rows} that {@code applies} to, on the
     * decision {@code decisionOf} gives it; the selection then moves on to
     * the next row to decide. Every choice made with the keys, the answer
     * strip or the row menu ends here.
     */
    private void decide( List<Row> rows, Predicate<Row> applies,
                         Function<Row,TagDecision> decisionOf ) {

        List<Row> chosen = rows.stream().filter( applies ).toList() ;
        if( chosen.isEmpty() ) return ;
        Row next = nextToDecide( chosen ) ;

        for( Row row : chosen ) {
            if( row.status == TaggerStatus.DEFERRED ) {
                controller.undefer( row.item ) ;
                row.status   = row.undecidedStatus ;
                row.decision = null ;
            }
            decide( row, decisionOf.apply( row ) ) ;
        }
        select( next ) ;
        showChosen() ;
        updateControls() ;
    }

    /** Choose {@code d} for {@code row}, as the set its edits start from. */
    private void decide( Row row, TagDecision d ) {
        stage( row, d, d ) ;
    }

    /**
     * Note decision {@code d} on {@code row}, unsaved, replacing any unsaved
     * one, with {@code base} the set it started from; what the row was
     * before it is kept for {@link #clear}.
     */
    private void stage( Row row, TagDecision base, TagDecision d ) {

        if( row.decision == null ) {
            row.undecidedStatus  = row.status ;
            row.undecidedMessage = row.message ;
        }
        row.base     = base ;
        row.decision = d ;
        row.status   = TaggerStatus.DECIDED ;
        row.message  = "Decided: " + describe( d ) ;

        model.changed( row ) ;
    }

    /**
     * Add {@code p} (a tag or a topic depended on) to the chosen set of
     * every open row selected, or ({@code add} false) take it out; a row it
     * can't go on (another pass's axis, a dependent's setup or math tag or
     * dependency), or that has it already, is left as it is. The edited
     * rows are decided by the user, each part of the set still credited to
     * its model while it is as the model gave it; they stay shown, and
     * selected, until the selection moves on.
     */
    private void editChosen( Payload p, boolean add ) {

        Assembly a = controller == null ? null : controller.assembly() ;
        if( a == null ) return ;
        int edited = 0 ;

        for( Row row : editableSelection() ) {
            TagDecision base = row.base != null    ? row.base
                             : row.chosen() != null ? row.chosen()
                             : TagDecision.empty() ;
            TagDecision now  = row.chosen() != null ? row.chosen() : base ;
            TagDecision next = p.isTag() ? withTag( row, now, p.tag(), add, a )
                                         : withTopic( row, now, base,
                                                      p.topicId(), add, a ) ;
            if( next == null ) continue ;

            stage( row, base, TagDecision.edited( base, next.tags(),
                                                  next.dependencies() ) ) ;
            pinned.add( row ) ;
            edited++ ;
        }
        if( edited == 0 ) {
            statusBar.setText( p.isTag()
                    ? p.tag() + ( add ? " can't go on, or is already on, "
                                      : " is not on " ) + "the selection"
                    : "Dependencies " + ( add ? "can't be added to "
                                              : "are not on " ) +
                      "the selection" ) ;
            return ;
        }
        showChosen() ;
        answerStrip.refresh( selectedRow() ) ;
        updateControls() ;
    }

    /**
     * {@code now} with {@code tag} added (or taken out), in offered order;
     * null if it can't be, or is so already.
     */
    private TagDecision withTag( Row row, TagDecision now, String tag,
                                 boolean add, Assembly a ) {

        Axis axis = Axis.of( tag ) ;
        if( add && ( !a.offered().contains( tag ) || axis == null
                     || !pass.tags( axis )
                     || axis.max( row.item.dependent() ) == 0 ) ) return null ;
        if( now.tags().contains( tag ) == add ) return null ;

        Set<String> tags = new LinkedHashSet<>( now.tags() ) ;
        if( add ) tags.add( tag ) ;
        else tags.remove( tag ) ;

        List<String> order = a.offered().all().stream()
                              .map( CatalogueTag::tagText ).toList() ;
        List<String> sorted = tags.stream()
                                  .sorted( Comparator.comparingInt( t -> {
                                      int i = order.indexOf( t ) ;
                                      return i < 0 ? order.size() : i ;
                                  } ) )
                                  .toList() ;
        return new TagDecision( sorted, now.dependencies(), now.source(),
                                null, null, true, true, null ) ;
    }

    /**
     * {@code now} with topic {@code id} added to its dependencies (or taken
     * out); null if it can't be (the setup pass, a dependent, a topic not
     * offered), or is so already. Dependencies left as they are on the
     * server stay so while they come back to that.
     */
    private TagDecision withTopic( Row row, TagDecision now, TagDecision base,
                                   int id, boolean add, Assembly a ) {

        if( !pass.hasDependents() || row.item.dependent()
            || add && !a.topicIds().contains( id ) ) return null ;

        List<Integer> current = dependencies( row, now ) ;
        if( current.contains( id ) == add ) return null ;

        List<Integer> deps = new ArrayList<>( current ) ;
        if( add ) deps.add( id ) ;
        else deps.remove( Integer.valueOf( id ) ) ;

        List<Integer> onServer = serverDependencies( row ) ;
        boolean       asServer = base.dependencies() == null
                                 && deps.size() == onServer.size()
                                 && deps.containsAll( onServer ) ;
        return new TagDecision( now.tags(), asServer ? null : deps,
                                now.source(), null, null, true, true, null ) ;
    }

    /** The topics {@code row} is to depend on with {@code d}. */
    private static List<Integer> dependencies( Row row, TagDecision d ) {
        return d.dependencies() != null ? d.dependencies()
                                        : serverDependencies( row ) ;
    }

    /** The topics {@code row}'s question depends on now, on the server. */
    private static List<Integer> serverDependencies( Row row ) {

        List<TopicDependency> deps = row.item.question().getTopicDependencies() ;
        return deps == null ? List.of()
                            : deps.stream().map( TopicDependency::topicId )
                                  .toList() ;
    }

    /** The selected rows whose chosen set may be edited, in table order. */
    private List<Row> editableSelection() {
        return selectedRows().stream().filter( Row::isOpen ).toList() ;
    }

    /**
     * True if {@code row}'s set is the user's own or edited, and breaks the
     * count rules: Save holds it back. A tier's set, or the hand tags as
     * they are, is saved as before.
     */
    private boolean breaksCounts( Row row ) {

        TagDecision d = row.decision ;
        return d != null && ( d.edited()
                              || d.source() == TagDecision.Source.USER )
               && row.countProblem( pass ) != null ;
    }

    /**
     * Show the chosen set of the open rows selected in the chosen tags bar
     * (for several, what they share) and fade its tags in the topic tags
     * panel.
     */
    private void showChosen() {

        List<Row> rows = editableSelection() ;
        if( rows.isEmpty() ) {
            chosenBar.show( null ) ;
            topicTags.fade( Set.of(), Set.of() ) ;
            return ;
        }

        List<String>  tags = null ;
        List<Integer> deps = null ;
        for( Row row : rows ) {
            TagDecision   d = row.chosen() ;
            List<String>  t = d == null ? List.of() : d.tags() ;
            tags = tags == null ? t
                                : tags.stream().filter( t::contains ).toList() ;
            if( !pass.hasDependents() || row.item.dependent() ) continue ;
            List<Integer> r = d == null ? serverDependencies( row )
                                        : dependencies( row, d ) ;
            deps = deps == null ? r
                                : deps.stream().filter( r::contains ).toList() ;
        }

        Row    one     = rows.size() == 1 ? rows.get( 0 ) : null ;
        long   broken  = rows.stream()
                             .filter( r -> r.countProblem( pass ) != null )
                             .count() ;
        String problem = one != null ? one.countProblem( pass )
                       : broken > 0 ? plural( (int) broken, "question" ) +
                                      " break the count rules"
                       : null ;
        String from    = one != null ? provenance( one )
                       : rows.size() + " questions: the tags they share" ;

        chosenBar.show( new ChosenTagsBar.View( tags, deps, from, problem ) ) ;
        topicTags.fade( new HashSet<>( tags ),
                        deps == null ? Set.of() : new HashSet<>( deps ) ) ;
    }

    /**
     * "tier 1 (Nemotron), edited · tags: user · dependencies: AI": where
     * {@code row}'s chosen set came from and who each part is credited to.
     */
    private String provenance( Row row ) {

        TagDecision d = row.chosen() ;
        if( d == null ) return "Nothing chosen yet" ;

        TagDecision base = row.base != null ? row.base : d ;
        String      from = d.source() + ( base.model() != null
                                          ? " (" + names.of( base.model() ) + ")"
                                          : "" ) +
                           ( d.edited() ? ", edited" : "" ) ;
        String      tags = "tags: " + credit( d.model() ) ;

        if( !pass.hasDependents() || row.item.dependent() ) {
            return from + "  ·  " + tags ;
        }
        return from + "  ·  " + tags + "  ·  dependencies: " +
               ( d.dependencies() == null ? "as on the server"
                                          : credit( d.dependencyModel() ) ) ;
    }

    /** "AI (Nemotron)" for a model, else "user". */
    private String credit( String model ) {
        return model != null ? "AI (" + names.of( model ) + ")" : "user" ;
    }

    /**
     * "[tier 1, Nemotron, edited] s.a, c.b": the tags and where they come
     * from.
     */
    private String describe( TagDecision d ) {

        String from = d.source() + ( d.model() != null
                                     ? ", " + names.of( d.model() ) : "" ) +
                      ( d.edited() ? ", edited" : "" ) ;
        return "[" + from + "] " + ( d.tags().isEmpty()
                                     ? "no tags" : String.join( ", ", d.tags() ) ) ;
    }

    /**
     * The next row shown, after the last one selected and wrapping round,
     * to decide (handed over, or a gap waiting); failing that, the next one
     * accepted or refused by the server; null if there is none. Rows in
     * {@code skip} don't count.
     */
    private Row nextToDecide( List<Row> skip ) {

        int count    = table.getRowCount() ;
        int from     = table.getSelectionModel().getMaxSelectionIndex() ;
        Row fallback = null ;

        for( int k = 1 ; k <= count ; k++ ) {
            Row row = rowAt( ( from + k ) % count ) ;
            if( skip.contains( row ) ) continue ;
            if( toDecide( row ) ) return row ;
            if( fallback == null && ( row.status.isAccepted()
                                      || row.status == TaggerStatus.ERROR ) ) {
                fallback = row ;
            }
        }
        return fallback ;
    }

    private static boolean toDecide( Row row ) {
        return row.status == TaggerStatus.NEEDS_HUMAN
               || row.status == TaggerStatus.GAP_PROPOSED ;
    }

    /** Select the first row shown to decide, if any. */
    private void selectFirstToDecide() {

        for( int view = 0 ; view < table.getRowCount() ; view++ ) {
            Row row = rowAt( view ) ;
            if( toDecide( row ) ) {
                select( row ) ;
                return ;
            }
        }
    }

    /**
     * Select only {@code row}, if shown, scroll to it and give the table the
     * focus, so the decision keys go to it; null leaves the selection.
     */
    private void select( Row row ) {

        int view = row == null ? -1
                               : table.convertRowIndexToView(
                                       model.indexOf( row ) ) ;
        if( view < 0 ) return ;

        table.setRowSelectionInterval( view, view ) ;
        table.scrollRectToVisible( table.getCellRect( view, 0, true ) ) ;
        table.requestFocusInWindow() ;
    }

    /**
     * Send {@code items} off the EDT, then show the server's answers and
     * run {@code after}. {@code button}, if not null, spins meanwhile; the
     * rows being saved spin in their Status cell either way.
     */
    private void confirm( List<SaveItem> items, JButton button,
                          Runnable after ) {

        for( SaveItem s : items ) {
            Row row = model.rowOf( s.item() ) ;
            if( row != null ) setSaving( row ) ;
        }
        if( button != null ) Spinner.busy( button ) ;

        new SwingWorker<List<RefreshResult>,Void>() {
            @Override protected List<RefreshResult> doInBackground() {
                return controller.save( items ) ;
            }

            @Override protected void done() {

                List<RefreshResult> results ;

                if( button != null ) Spinner.idle( button ) ;
                try {
                    results = get() ;
                }
                catch( Exception e ) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e ;
                    log.error( "Could not save {} questions", items.size(),
                               cause ) ;
                    results = items.stream()
                                   .map( s -> RefreshResult.failed(
                                           s.item().id(),
                                           String.valueOf( cause ) ) )
                                   .toList() ;
                }
                for( int i = 0 ; i < items.size() ; i++ ) {
                    Row row = model.rowOf( items.get( i ).item() ) ;
                    if( row == null ) continue ;
                    showResult( row, results.get( i ) ) ;
                    rowChanged( row ) ;
                }
                after.run() ;
            }
        }.execute() ;
    }

    /** Show on {@code row} the server's answer {@code r} to its save. */
    private void showResult( Row row, RefreshResult r ) {

        // A refused decision stays, to save again or clear.
        if( !r.applied() ) {
            row.status  = TaggerStatus.ERROR ;
            row.message = "Not saved: " + ( r.message() != null
                                            ? r.message()
                                            : "refused by the server" ) ;
            return ;
        }
        boolean still = r.stillNeeded( pass ) ;

        row.status  = TaggerStatus.SAVED ;
        row.message = "Saved: " + describe( row.decision ) +
                      ( still ? " - still awaiting " + pass.wire() +
                                " tags on the server" : "" ) ;
        if( pass == TagClass.SETUP ) {
            row.item.question().setAutoSetupTaggingNeeded( still ) ;
        }
        else {
            row.item.question().setAutoConceptTaggingNeeded( still ) ;
        }
        if( !still ) {
            if( row.item.dependent() ) depPending = Math.max( 0, depPending - 1 ) ;
            else ownPending = Math.max( 0, ownPending - 1 ) ;
        }
    }

    private void setSaving( Row row ) {
        row.status  = TaggerStatus.SAVING ;
        row.message = "Saving: " + describe( row.decision ) ;
        rowChanged( row ) ;
    }

    // ---- gap proposals -------------------------------------------------

    /**
     * Queue {@code row}'s gap proposal and pause the run; the gap dialog
     * opens for the first one waiting.
     */
    private void queueGap( Row row ) {
        if( !gaps.contains( row ) ) gaps.add( row ) ;
        controller.setPaused( true ) ;
    }

    /**
     * Open the gap dialog for the next proposal waiting, if no dialog is
     * open; with none waiting, let the run go on.
     */
    private void nextGap() {

        gaps.removeIf( r -> r.status != TaggerStatus.GAP_PROPOSED
                            || model.rowOf( r.item ) != r ) ;
        if( gaps.isEmpty() ) {
            if( controller != null ) controller.setPaused( false ) ;
            updateControls() ;
            return ;
        }
        controller.setPaused( true ) ;
        updateControls() ;
        if( !gapOpen ) SwingUtilities.invokeLater( () -> reviewGap( gaps.peek() ) ) ;
    }

    /** Decisions > Review gap proposal: the selected row's, else the next. */
    private void reviewNextGap() {

        Row row = selectedRow() ;
        reviewGap( row != null && row.status == TaggerStatus.GAP_PROPOSED
                   ? row : gaps.peek() ) ;
    }

    /**
     * Ask what becomes of {@code row}'s gap proposal: accepted (the tag is
     * created first, off the EDT), mapped to an offered tag, or rejected;
     * "Later" leaves it waiting and the run paused.
     */
    private void reviewGap( Row row ) {

        if( row == null || gapOpen || row.status != TaggerStatus.GAP_PROPOSED
            || model.rowOf( row.item ) != row ) return ;

        TierReply t = row.judgement.accepted() ;
        gapOpen = true ;
        select( row ) ;

        GapDialog.Choice c = GapDialog.ask( this, row.questionLabel(),
                names.of( t.model() ), t.reply().gap(),
                controller.assembly().offered() ) ;
        if( c == null ) {
            gapOpen = false ;
            statusBar.setText( "Paused: the gap proposed on " +
                               row.questionLabel() + " waits (Decisions > " +
                               "Review gap proposal…)" ) ;
            return ;
        }

        switch( c.fate() ) {
            case REJECTED -> settleGap( row, t, null, c.fate() ) ;
            case MAPPED   -> settleGap( row, t, c.mapTo(), c.fate() ) ;
            case ACCEPTED -> {
                statusBar.setText( "Creating tag " + c.tag().tagText() + "…" ) ;
                background( () -> controller.createTag( c.tag() ), tag -> {
                    promptPanel.show( controller.assembly() ) ;
                    showScope() ;
                    settleGap( row, t, tag.tagText(), c.fate() ) ;
                }, e -> {
                    gapOpen = false ;
                    error( "Gap proposed", "Could not create tag " +
                           c.tag().tagText(), e ) ;
                    nextGap() ;
                } ) ;
                return ;
            }
        }
    }

    /**
     * Settle {@code row}'s gap: with {@code tag} (accepted or mapped) the
     * question takes it on top of tier {@code t}'s tags, decided; without
     * (rejected) it is judged again on its replies, the gap set aside. A
     * reply left with no concept tag either way (its concept gap rejected,
     * or settled on a setup tag) is handed to the user undecided.
     */
    private void settleGap( Row row, TierReply t, String tag,
                            GapDialog.Fate fate ) {

        TierReply   updated = tag == null ? t
                            : new TierReply( t.tier(), t.model(),
                                             t.reply().withTag( tag ) ) ;
        TagOpinions o       = row.opinions ;
        TagOpinions now     = t.tier() == 1
                            ? new TagOpinions( updated, o.tier2(),
                                               o.tier2Failure() )
                            : new TagOpinions( o.tier1(), updated,
                                               o.tier2Failure() ) ;
        Assembly    a       = controller.assembly() ;

        controller.gapSettled( row.item, now, t.model(), t.reply().gap(),
                               fate.label, tag ) ;
        TagJudgement j = TagJudgement.of( row.item, now, a.mode(),
                                          a.offered(), true ) ;
        row.opinions = now ;
        judge( row, j ) ;
        // Still no concept tag (the gap settled on a setup tag): no decision
        // to stage, the user chooses one.
        if( tag != null && j.reason() != HandOffReason.NO_CONCEPT ) {
            decide( row, TagDecision.of( updated, true ) ) ;
        }

        gapOpen = false ;
        rowChanged( row ) ;
        nextGap() ;
    }

    // ---- TaggerListener (worker threads) -------------------------------

    @Override
    public void itemStarted( TagItem item ) {
        onRow( item, row -> row.status = TaggerStatus.RUNNING_T1 ) ;
    }

    @Override
    public void itemAttempt( TagItem item, String model, int attempt,
                             int tier ) {
        onRow( item, row -> {
            row.status         = tier == 1 ? TaggerStatus.RUNNING_T1
                                           : TaggerStatus.RUNNING_T2 ;
            row.model          = model ;
            row.attempts       = attempt ;
            row.inFlight       = true ;
            row.message        = ( tier == 1 ? "Asking tier 1, "
                                             : "Tier 1 missed the key - " +
                                               "asking tier 2, " ) +
                                 "attempt " + attempt + " on " +
                                 names.of( model ) ;
        } ) ;
    }

    /** Show tier 1's reply while tier 2 is asked. */
    @Override
    public void tier1Missed( TagItem item, TierReply reply ) {
        onRow( item, row -> row.opinions = TagOpinions.of( reply ) ) ;
    }

    @Override
    public void requestSent( AttemptRequest request ) {
        SwingUtilities.invokeLater( () -> transcriptPanel.requestSent( request ) ) ;
    }

    @Override
    public void attemptFinished( TagItem item ) {
        SwingUtilities.invokeLater(
                () -> transcriptPanel.attemptFinished( item.task() ) ) ;
        onRow( item, row -> row.inFlight = false ) ;
    }

    @Override
    public void judged( TagItem item, TagOpinions opinions,
                        TagJudgement judgement ) {
        onRow( item, row -> {
            row.inFlight       = false ;
            row.opinions       = opinions ;
            judge( row, judgement ) ;
            if( row.status == TaggerStatus.GAP_PROPOSED ) nextGap() ;
        } ) ;
        SwingUtilities.invokeLater( this::refillIfDue ) ;
    }

    @Override
    public void invalidOutput( TagItem item, int attempts, String reason ) {
        onRow( item, row -> {
            row.inFlight       = false ;
            row.attempts       = attempts ;
            judge( row, TagJudgement.invalidOutput() ) ;
            row.message += ": " + reason ;
        } ) ;
    }

    @Override
    public void itemFailed( TagItem item, int attempts, String reason ) {
        onRow( item, row -> {
            row.status         = TaggerStatus.FAILED ;
            row.attempts       = attempts ;
            row.inFlight       = false ;
            row.message        = "Failed after " +
                                 plural( attempts, "attempt" ) + ": " + reason ;
        } ) ;
    }

    @Override
    public void processingStateChanged( ProcessingState state ) {

        SwingUtilities.invokeLater( () -> {
            // Rows sent since started a new run, whose RUNNING follows.
            if( state == ProcessingState.STOPPED && controller != null
                && controller.isRunning() ) return ;
            procState = state ;
            if( state == ProcessingState.STOPPED ) {
                // Rows never started go back to what they were: unjudged,
                // or judged before they were sent again.
                for( Row row : model.rows() ) {
                    if( row.status != TaggerStatus.QUEUED ) continue ;
                    if( row.judgement != null ) judge( row, row.judgement ) ;
                    else {
                        row.status  = TaggerStatus.PENDING ;
                        row.message = null ;
                    }
                }
                model.changedAll() ;
                // Only a selection the user hasn't made is moved.
                if( table.getSelectedRowCount() == 0 ) selectFirstToDecide() ;
            }
            updateControls() ;
            if( state == ProcessingState.STOPPED ) batchJudged() ;
        } ) ;
    }

    /** On the EDT: apply {@code update} to {@code item}'s row, if loaded. */
    private void onRow( TagItem item, Consumer<Row> update ) {

        SwingUtilities.invokeLater( () -> {
            Row row = model.rowOf( item ) ;
            if( row == null ) return ;
            update.accept( row ) ;
            rowChanged( row ) ;
        } ) ;
    }

    /**
     * Record judgement {@code j} on {@code row}: accepted, handed over (with
     * why), or a gap to decide, which pauses the run.
     */
    private void judge( Row row, TagJudgement j ) {

        row.judgement = j ;
        row.reason    = j.reason() ;
        row.status    = j.status() ;
        row.message   = j.reason() != null ? "Needs a human: " + j.reason()
                      : j.status() == TaggerStatus.GAP_PROPOSED
                        ? "Tier " + j.accepted().tier() + " proposes a new " +
                          "tag: " + j.accepted().reply().gap().tag()
                      : null ;
        if( j.status() == TaggerStatus.GAP_PROPOSED ) queueGap( row ) ;
    }

    // ---- display -------------------------------------------------------

    private void rowChanged( Row row ) {

        model.changed( row ) ;
        answerStrip.refresh( row ) ;
        if( table.getSelectedRowCount() == 1 && selectedRow() == row ) {
            showDetail( row ) ;
        }
        if( table.getSelectedRowCount() > 0 && selectedRows().contains( row ) ) {
            showChosen() ;
        }
        updateControls() ;
    }

    private void showSelection() {

        Row       row = selectedRow() ;
        Row       one = table.getSelectedRowCount() == 1 ? row : null ;
        List<Row> sel = selectedRows() ;

        // The rows edited stay shown only while selected.
        if( !pinned.isEmpty() && !sel.containsAll( pinned ) ) {
            pinned.clear() ;
            SwingUtilities.invokeLater( sorter::allRowsChanged ) ;
        }
        showChosen() ;
        answerStrip.show( one ) ;
        showDetail( one ) ;
        preview.show( row != null && row.item.hasImages()
                      ? row.item.task().getImgFiles() : null ) ;
        if( row != null && row.item.hasImages() ) {
            transcriptPanel.showIfOther( row.item.task() ) ;
        }
    }

    /**
     * Show what is going on with {@code row} above the preview, on one
     * line: its status, the attempt under way, or what failed (the whole
     * text in the tooltip, for a line cut short); blank for null.
     */
    private void showDetail( Row row ) {

        if( row == null ) {
            detail.setText( "" ) ;
            detail.setToolTipText( null ) ;
            return ;
        }
        String text = row.questionLabel() + ": " + statusLabel( row ) ;
        if( row.message != null ) text += "  ·  " + row.message ;
        detail.setText( text ) ;
        detail.setToolTipText( text ) ;
        detail.setCaretPosition( 0 ) ;
    }

    /** The row's status, with the attempt number while running. */
    private static String statusLabel( Row row ) {
        return row.status.isRunning() && row.attempts > 1
               ? row.status.getLabel() + " (" + row.attempts + ")"
               : TagTableModel.statusText( row ) ;
    }

    private Row selectedRow() {
        int view = table.getSelectedRow() ;
        return view < 0 ? null
                        : model.getRow( table.convertRowIndexToModel( view ) ) ;
    }

    private void applyFilter() {

        Show show = (Show) showBox.getSelectedItem() ;
        sorter.setRowFilter( new RowFilter<TagTableModel,Integer>() {
            @Override
            public boolean include(
                    Entry<? extends TagTableModel,? extends Integer> e ) {
                Row row = model.getRow( e.getIdentifier() ) ;
                return pinned.contains( row )
                       || show.test.test( row ) && headerFilters.accepts( row ) ;
            }
        } ) ;
    }

    private void updateControls() {

        boolean stopped = procState == ProcessingState.STOPPED ;
        boolean saving  = model.count( TaggerStatus.SAVING ) > 0 ;
        boolean scoped  = controller != null && controller.assembly() != null ;
        boolean savable = model.rows().stream().anyMatch( Row::isSavable ) ;

        boolean idle    = stopped && !loading && !saving && !scoping ;
        boolean manual  = !unattended ;

        // An unattended run does the loading, tagging and saving itself.
        scopeAction.setEnabled( manual && idle && controller != null ) ;
        loadAction.setEnabled( manual && idle && scoped ) ;
        loadFromStartAction.setEnabled( manual && idle && scoped ) ;
        generateAction.setEnabled( controller != null ) ;
        reviewPromptAction.setEnabled( controller != null ) ;
        startAction.setEnabled( manual && stopped && !loading && scoped
                                && model.rows().stream().anyMatch(
                                        TaggerFrame::isPending ) ) ;
        stopAction.setEnabled( unattended
                               || procState == ProcessingState.RUNNING ) ;
        unattendedAction.setEnabled( unattended || idle && scoped ) ;
        // Its icon is red while enabled, i.e. while decisions wait.
        saveAction.setEnabled( manual && savable && controller != null ) ;
        clearAction.setEnabled( manual
                                && model.count( TaggerFrame::isClearable ) > 0 ) ;
        gapAction.setEnabled( !gapOpen
                && model.count( TaggerStatus.GAP_PROPOSED ) > 0 ) ;
        requeueFailedAction.setEnabled( canSend()
                && model.count( TaggerStatus.FAILED ) > 0 ) ;
        if( saving ) Spinner.spin( table ) ;
        else Spinner.halt( table ) ;
        if( !loading && !scoping ) refreshStatusBar() ;
    }

    private void refreshStatusBar() {

        String text = String.format(
                "%d loaded · %d accepted · %d to decide · %d gaps · " +
                "%d decided · %d saved · %d deferred · %d failed · %d errors",
                model.getRowCount(),
                model.count( r -> r.status.isAccepted() ),
                model.count( TaggerStatus.NEEDS_HUMAN ),
                model.count( TaggerStatus.GAP_PROPOSED ),
                model.count( TaggerStatus.DECIDED ),
                model.count( TaggerStatus.SAVED ),
                model.count( TaggerStatus.DEFERRED ),
                model.count( TaggerStatus.FAILED ),
                model.count( TaggerStatus.ERROR ) ) ;
        if( ownPending >= 0 ) {
            text += " · awaiting " + pass.wire() + " tags: " + ownPending +
                    ( pass.hasDependents() ? " own, " + depPending +
                                             " dependents" : "" ) ;
        }
        // Running and queued counts are in the queue section on the right.
        if( procState == ProcessingState.STOPPING ) text += "   |   stopping" ;
        if( !gaps.isEmpty() ) text += "   |   paused: a gap proposal waits" ;
        // Kept after the run ends, to say why it did.
        if( unattendedStep != null && controller.scope() != null ) {
            text = "Unattended, batch " + batch() + " of " + maxLoads + ": " +
                   unattendedStep + "   |   " + text ;
        }
        statusBar.setText( text ) ;
    }

    /**
     * Show the model queue's counts: green when nothing waits or runs,
     * orange while requests are in flight, pink when questions wait but
     * none is in flight. Beside them, a countdown to the next request, in
     * red, while questions wait for the rate limits.
     */
    private void refreshQueueBar() {

        if( controller == null ) return ;

        TaskScheduler.Counts c    = controller.queueCounts() ;
        long                 wait = controller.nextRequestAt()
                                    - System.currentTimeMillis() ;

        boolean              held = c.queued() > 0 && wait >= 1000 ;

        queueText.setText( "Models:  " + c.processed() + " processed,  " +
                           c.running() + " running,  " + c.queued() +
                           " in queue" ) ;
        paceText.setText( held ? "next request in " + countdown( wait )
                               : "next request: now" ) ;
        paceText.setForeground( held ? Color.RED.darker()
                                     : queueText.getForeground() ) ;
        queueBar.setBackground( c.running() > 0 ? QUEUE_ACTIVE
                              : c.queued()  > 0 ? QUEUE_WAITING
                              : QUEUE_IDLE ) ;
    }

    /** Keep {@code label} as wide as {@code widest} in its font. */
    private static void fixWidth( JLabel label, String widest ) {

        Insets    in = label.getInsets() ;
        int       w  = label.getFontMetrics( label.getFont() )
                            .stringWidth( widest ) + in.left + in.right ;
        Dimension d  = new Dimension( w, label.getPreferredSize().height ) ;

        label.setPreferredSize( d ) ;
        label.setMinimumSize( d ) ;
    }

    /** {@code ms} as "42 s", or "3 min 05 s" from a minute up. */
    private static String countdown( long ms ) {

        long s = ( ms + 999 ) / 1000 ;
        return s < 60 ? s + " s"
                      : String.format( "%d min %02d s", s / 60, s % 60 ) ;
    }

    /** The transcripts, the Models and the Prompt tabs. */
    private JTabbedPane tabs() {

        JTabbedPane tabs = new JTabbedPane() ;
        tabs.addTab( "Transcripts", transcriptPanel ) ;
        tabs.addTab( "Models", modelsPanel ) ;
        tabs.addTab( "Prompt", promptPanel ) ;
        tabs.setSelectedComponent( modelsPanel ) ;
        tabs.addChangeListener( e -> refreshModels() ) ;
        return tabs ;
    }

    /** Show the hub's state in the Models tab, if it is showing. */
    private void refreshModels() {
        if( controller == null || !modelsPanel.isShowing() ) return ;
        modelsPanel.update( controller.hubStatus() ) ;
    }

    /** From the Models tab: a model is to pass its questions through or not. */
    private void setPassthrough( String modelId, boolean on ) {
        if( controller == null ) return ;
        controller.setPassthrough( modelId, on ) ;
        refreshModels() ;
    }

    /** From the Models tab: a request in flight is to be terminated. */
    private void abortRequest( long jobId ) {
        if( controller == null ) return ;
        controller.abortRequest( jobId ) ;
        refreshModels() ;
    }

    private void refreshRateLimitStrip() {

        List<RateLimitNotice> notices = controller != null
                                      ? controller.rateLimitNotices()
                                      : List.of() ;

        rateLimitStrip.update( notices, rateLimitItem.isSelected() ) ;
        if( unattended ) watchRateLimits( notices ) ;
    }

    /**
     * During an unattended run: end it once nothing more can be tagged
     * today, every model of a tier, fallbacks included, being held for the
     * day. Any other limit is logged as it starts; the scheduler waits it
     * out, or falls back, and goes on.
     */
    private void watchRateLimits( List<RateLimitNotice> notices ) {

        String held = controller.heldForTheDay() ;
        if( held != null ) {
            long resets = notices.stream()
                                 .filter( RateLimitNotice::daily )
                                 .mapToLong( RateLimitNotice::until )
                                 .max().orElse( 0 ) ;
            endUnattended( held + ( resets > 0 ? ", it resets at " +
                                                 clock( resets ) : "" ) ) ;
            return ;
        }
        for( RateLimitNotice n : notices ) {
            String key = n.agent() + "/" + n.model() + "@" + n.until() ;
            if( !loggedLimits.add( key ) ) continue ;
            log.info( "[unattended] {} rate limited ({}) - {} till {}, " +
                      "then going on", n.model() != null
                                       ? names.of( n.model() )
                                       : n.agent().getDisplayName(),
                      n.reason(), n.daily() ? "its fallback takes over"
                                            : "waiting",
                      clock( n.until() ) ) ;
        }
    }

    /** Epoch ms {@code at} as local HH:mm:ss. */
    private static String clock( long at ) {
        return LocalTime.ofInstant( Instant.ofEpochMilli( at ),
                                    ZoneId.systemDefault() )
                        .truncatedTo( ChronoUnit.SECONDS ).toString() ;
    }

    /**
     * Tints rows by status; the Tags cell of a chosen set (and a Decided
     * row's Status cell) green, red if the set breaks the count rules, and
     * a proposal's text grey; the Status cell of a hand-off red, of a gap
     * waiting purple; frames the Tags cell in red while a request is in
     * flight; spins in the Status cell of a row being saved; and shows the
     * last column as a dot: green once saved on the server, red until then.
     */
    private final class RowRenderer extends DefaultTableCellRenderer {

        private static final Border IN_FLIGHT_BORDER =
                BorderFactory.createLineBorder( new Color( 0xD32F2F ), 2 ) ;

        @Override
        public Component getTableCellRendererComponent( JTable t, Object value,
                boolean selected, boolean focus, int viewRow, int column ) {

            int     col = t.convertColumnIndexToModel( column ) ;
            boolean dot = col == TagTableModel.COL_SAVED ;

            // setForeground sticks to the renderer, which paints every
            // cell: clear it so each cell starts with the table's colour.
            setForeground( null ) ;
            Component c    = super.getTableCellRendererComponent(
                    t, dot ? null : value, selected, focus, viewRow, column ) ;
            Row       row  = model.getRow( t.convertRowIndexToModel( viewRow ) ) ;
            boolean   spin = row.status == TaggerStatus.SAVING
                             && col == TagTableModel.COL_STATUS ;

            boolean tags = col == TagTableModel.COL_TAGS ;
            if( tags && row.inFlight ) setBorder( IN_FLIGHT_BORDER ) ;

            setHorizontalAlignment( dot ? CENTER : LEADING ) ;
            if( dot ) {
                setIcon( (Boolean) value ? Dot.SAVED : Dot.UNSAVED ) ;
            }
            else {
                setIcon( spin ? Spinner.ICON : null ) ;
            }

            boolean status = col == TagTableModel.COL_STATUS ;
            if( tags && TagTableModel.showsProposal( row ) ) {
                c.setForeground( PROPOSAL_FG ) ;
            }
            if( tags && row.chosen() != null ) {
                c.setBackground( row.countProblem( pass ) != null
                                 ? COUNT_PROBLEM_BG : DECIDED_CELL_BG ) ;
                c.setForeground( t.getForeground() ) ;
                return c ;
            }
            if( status && row.status == TaggerStatus.DECIDED ) {
                c.setBackground( DECIDED_CELL_BG ) ;
                c.setForeground( t.getForeground() ) ;
                return c ;
            }
            if( status && ( row.status == TaggerStatus.NEEDS_HUMAN
                            || row.status == TaggerStatus.GAP_PROPOSED ) ) {
                c.setBackground( row.status == TaggerStatus.NEEDS_HUMAN
                                 ? HAND_OFF_CELL_BG : GAP_CELL_BG ) ;
                c.setForeground( Color.WHITE ) ;
                return c ;
            }
            if( selected ) return c ;
            c.setBackground( stripe( STATUS_BG.getOrDefault( row.status,
                                                             t.getBackground() ),
                                     viewRow ) ) ;
            return c ;
        }

        /** {@code bg}, a shade darker on every other row. */
        private static Color stripe( Color bg, int viewRow ) {

            if( viewRow % 2 == 0 ) return bg ;
            return new Color( (int) ( bg.getRed() * STRIPE_SHADE ),
                              (int) ( bg.getGreen() * STRIPE_SHADE ),
                              (int) ( bg.getBlue() * STRIPE_SHADE ) ) ;
        }
    }

    /** A small filled circle. */
    private record Dot( Color color ) implements Icon {

        static final Dot SAVED   = new Dot( new Color( 0x2E7D32 ) ) ;
        static final Dot UNSAVED = new Dot( new Color( 0xD32F2F ) ) ;

        private static final int SIZE = 6 ;

        @Override
        public void paintIcon( Component c, Graphics g, int x, int y ) {

            Graphics2D g2 = (Graphics2D) g.create() ;

            g2.setRenderingHint( RenderingHints.KEY_ANTIALIASING,
                                 RenderingHints.VALUE_ANTIALIAS_ON ) ;
            g2.setColor( color ) ;
            g2.fillOval( x, y, SIZE, SIZE ) ;
            g2.dispose() ;
        }

        @Override
        public int getIconWidth() { return SIZE ; }

        @Override
        public int getIconHeight() { return SIZE ; }
    }
}
