package com.sandy.sconsole.qimgtools.apps.tagger.ui;

import com.sandy.sconsole.ai.HubStatus;
import com.sandy.sconsole.ai.ui.ModelsPanel;
import com.sandy.sconsole.qimgtools.apps.tagger.model.CatalogueTag;
import com.sandy.sconsole.qimgtools.apps.tagger.model.Scope;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagCatalogue;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagDecision;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagItem;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagProposal;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TaggerSettings;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TaggerStatus;
import com.sandy.sconsole.qimgtools.apps.tagger.pipeline.TaggerListener;
import com.sandy.sconsole.qimgtools.apps.tagger.regression.Assessment;
import com.sandy.sconsole.qimgtools.apps.tagger.regression.TopicFileException;
import com.sandy.sconsole.qimgtools.apps.tagger.server.RefreshResult;
import com.sandy.sconsole.qimgtools.apps.tagger.ui.TagTableModel.Llm;
import com.sandy.sconsole.qimgtools.apps.tagger.ui.TagTableModel.Row;
import com.sandy.sconsole.qimgtools.apps.tagger.workspace.PendingTagging;
import com.sandy.sconsole.qimgtools.core.model.AttemptRequest;
import com.sandy.sconsole.qimgtools.core.model.ProcessingState;
import com.sandy.sconsole.qimgtools.core.model.RateLimitNotice;
import com.sandy.sconsole.qimgtools.core.model.Topic;
import com.sandy.sconsole.qimgtools.core.scheduler.TaskScheduler;
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
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The tagger's window, the reviewer's in layout and habits. The table lists
 * the scoped topic's questions pending auto tagging, with the tags already on
 * each and what the regression made of it; Load fetches the next ones after
 * the scope's cursor (Load from start brings back every one still pending).
 * Tags the regression accepts stand, with what it left undecided dropped; a
 * question with none goes to the LLM once read, if the settings say so, or on
 * Start, and the tags of its reply are accepted. Invoke LLM asks about one
 * question no model has answered, adding the reply's tags to its set; the row
 * menu sends a question to another model, whose reply overrides the last one.
 * Stop winds that down. The user decides with keys 1-4, the {@link TagStrip}
 * or the row menu. Each row has a chosen set, the tags it is to be saved with:
 * a choice sets it whole (the automatic tags, the existing ones), the
 * ticks on an undecided tag put it in or turn it down, and the
 * {@link ChosenTagsBar} edits it a tag at a time, dragged from the strip or
 * the {@link TopicTagsPanel} (or clicked there or among the LLM's tags, a
 * chosen one taken out again) and taken out with a ×, for every selected
 * row at once. Decisions stay in the table until Save sends
 * them to the server off the EDT. Every command is an {@code Action} on the
 * menu bar, the main ones also as icon buttons ({@link ToolIcon}) on the
 * toolbar.
 * <p>
 * Start and sending to a model need a scope ({@link Controller#canTag}).
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

        /** The scope in effect; null before one is set. */
        Scope scope() ;

        /** The scoped topic and its tags; null before a scope is set. */
        TagCatalogue catalogue() ;

        /** True if questions can be sent to the models. */
        boolean canTag() ;

        /**
         * Read {@code item}'s text and score it. Blocking, thread-safe and
         * never throws; called off the EDT with a scope in effect.
         */
        Assessment assess( TagItem item ) ;

        /** The tagger's settings. */
        TaggerSettings settings() ;

        /** Use {@code settings} from now on, and save them. */
        void applySettings( TaggerSettings settings ) throws IOException ;

        /** What the topic's model doesn't cover; null if nothing. */
        String topicFilesNotice() ;

        /**
         * Use {@code scope} from now on, and save it. Blocking (it fetches
         * the topic's tags); called off the EDT.
         *
         * @throws TopicFileException if its topic has no usable model or prompt:
         *         no scope is in effect any more
         * @throws Exception if its topic has no tags, or the server can't be
         *         reached: the scope in effect stays
         */
        void setScope( Scope scope ) throws Exception ;

        /**
         * The topics that can be scoped, those with a regression model and an
         * LLM prompt in the tag workspace now, by syllabus; a syllabus with
         * none is left out.
         */
        Map<String,List<Topic>> scopeableTopics() ;

        /** Where the topics' models and prompts are looked for. */
        File tagWorkspaceDir() ;

        /**
         * The next {@code limit} questions of the scope after its cursor
         * ({@code fromStart}: from the first), with their images located.
         * Blocking; called off the EDT.
         */
        Loaded load( int limit, boolean fromStart ) throws Exception ;

        /**
         * The scoped topic's escalated questions awaiting the user's tags.
         * Blocking; called off the EDT. The cursor stays where it is.
         */
        Loaded loadEscalated() throws Exception ;

        /** How many of the scoped topic's questions are escalated. */
        int escalatedCount() ;

        /**
         * The reply stored for {@code item} by an earlier run, if its topic
         * is unchanged since; null otherwise.
         */
        PendingTagging.Stored stored( TagItem item ) ;

        /**
         * Send {@code items} to the models, in order: start a run, or add
         * them to the one going. Ignored while a run is stopping.
         */
        void start( List<TagItem> items ) ;

        /**
         * Send {@code items} to {@code model} alone: no other model is
         * tried. Otherwise as {@link #start(List)}.
         */
        void start( List<TagItem> items, String model ) ;

        /** The models a question may be sent to by hand, in config order. */
        List<String> models() ;

        /** Wind the run down: no new requests, running ones finish. */
        void stop() ;

        /**
         * True while a run is going; a run's end reported after the next
         * run started is then out of date.
         */
        boolean isRunning() ;

        /**
         * Save the tags of {@code items} on the server. Blocking; called
         * off the EDT. A failed request comes back as rejected results.
         *
         * @return the server's result for each, in the same order
         */
        List<RefreshResult> save( List<SaveItem> items ) ;

        /** Note {@code item} as deferred. */
        void defer( TagItem item, String note ) ;

        /** Drop {@code item}'s deferral. */
        void undefer( TagItem item ) ;

        List<RateLimitNotice> rateLimitNotices() ;

        /** The AI hub's state now, for the Models tab. */
        HubStatus hubStatus() ;

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
     * The questions a Load brought, and how many of the topic's await tags
     * on the server.
     */
    public record Loaded( List<TagItem> items, int pending ) {}

    /** A question to save and the decision to save on it. */
    public record SaveItem( TagItem item, TagDecision decision,
                            Assessment assessment, TagProposal proposal ) {}

    private static final double SPLIT_RATIO          = 0.64 ;
    private static final int    PREVIEW_PANEL_HEIGHT = 300 ;
    private static final int    PREVIEW_IMAGE_WIDTH  = 800 ;
    private static final int    ROW_HEIGHT           = 20 ;
    private static final int    DETAIL_ROWS          = 1 ;

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
             50,    // From
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
            TagTableModel.COL_FROM,
            TagTableModel.COL_STATUS
    } ;

    /** Room around a fitted column's cell text: insets and borders. */
    private static final int CELL_PADDING = 12 ;

    /** Room around a fitted column's title: sort arrow and borders. */
    private static final int HEADER_PADDING = 24 ;

    /** Row background per status; a status not listed keeps the table's. */
    private static final Map<TaggerStatus,Color> STATUS_BG =
            new EnumMap<>( Map.ofEntries(
                    Map.entry( TaggerStatus.READING,     new Color( 0xEEF4FA ) ),
                    Map.entry( TaggerStatus.ACCEPTED,    new Color( 0xE2F1E3 ) ),
                    Map.entry( TaggerStatus.AWAITING_LLM,
                               new Color( 0xFFF3D1 ) ),
                    Map.entry( TaggerStatus.NEEDS_HUMAN, new Color( 0xFFE6CC ) ),
                    Map.entry( TaggerStatus.ESCALATED,   new Color( 0xFFD9B3 ) ),
                    Map.entry( TaggerStatus.DEFERRED,    new Color( 0xFCEFC5 ) ),
                    Map.entry( TaggerStatus.SAVING,      new Color( 0xE8E3F2 ) ),
                    Map.entry( TaggerStatus.SAVED,       new Color( 0xD8E8F7 ) ),
                    Map.entry( TaggerStatus.ERROR,       new Color( 0xF4D2D2 ) ),
                    Map.entry( TaggerStatus.NO_IMAGES,   new Color( 0xEEEEEE ) ) ) ) ;

    /** The Tags cell of a chosen set. */
    private static final Color DECIDED_CELL_BG = new Color( 0xB9E4BB ) ;

    /** How much darker every other row is, for a faint stripe. */
    private static final double STRIPE_SHADE = 0.965 ;

    /** The Tags cell's text while it shows a proposal, not a chosen set. */
    private static final Color PROPOSAL_FG = new Color( 0x757575 ) ;

    /** The Status cell of a row handed to the user. */
    private static final Color HAND_OFF_CELL_BG = new Color( 0xD32F2F ) ;

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

        ALL       ( "All questions",   r -> true ),
        HAND_OFFS ( "To decide",       TaggerFrame::toDecide ),
        ACCEPTED  ( "Accepted",        r -> r.status.isAccepted() ),
        PARTIAL   ( "Accepted, partial",
                    r -> isFrom( r, TagTableModel.FROM_PARTIAL ) ),
        BY_LLM    ( "Accepted, LLM",   r -> isFrom( r, TagTableModel.FROM_LLM ) ),
        BY_USER   ( "Accepted, user",  r -> isFrom( r, TagTableModel.FROM_USER ) ),
        FOR_LLM   ( "Awaiting LLM",    r -> r.status == TaggerStatus.AWAITING_LLM ),
        LOW       ( "Low confidence",  Row::isLowConfidence ),
        ESCALATED ( "Escalated",       r -> r.status == TaggerStatus.ESCALATED ),
        EXISTING  ( "Has existing tags", r -> !r.existing.isEmpty() ),
        DEFERRED  ( "Deferred",        r -> r.status == TaggerStatus.DEFERRED ),
        PROBLEMS  ( "Failed / errors", r -> r.llmFailure != null
                                            || r.status == TaggerStatus.ERROR
                                            || r.status == TaggerStatus.NO_IMAGES ) ;

        final String         label ;
        final Predicate<Row> test ;

        Show( String label, Predicate<Row> test ) {
            this.label = label ;
            this.test  = test ;
        }

        @Override
        public String toString() { return label ; }
    }

    private Controller controller ;

    /** The names models are shown by. */
    private final ModelNames names ;

    private final TagTableModel    model ;
    private final JTable           table ;
    private final TableRowSorter<TagTableModel> sorter ;
    private final HeaderFilterController<Row> headerFilters ;
    private final TranscriptPanel  transcriptPanel ;
    private final RegressionPanel  regressionPanel = new RegressionPanel() ;
    private final ModelsPanel      modelsPanel = new ModelsPanel(
            "Question", ( id, on ) -> setPassthrough( id, on ),
            this::abortRequest ) ;
    private final TopicTagsPanel   topicTags ;
    private final ChosenTagsBar    chosenBar ;
    private final ImagePreview     preview =
            new ImagePreview( PREVIEW_PANEL_HEIGHT, PREVIEW_IMAGE_WIDTH ) ;
    private final RateLimitStrip   rateLimitStrip ;
    private final TagStrip      tagStrip ;
    private final JTextArea        detail    = new JTextArea( DETAIL_ROWS, 20 ) ;
    private final JLabel           statusBar = new JLabel( " " ) ;
    private final JPanel           queueBar  = new JPanel( new BorderLayout() ) ;
    private final JLabel           queueText = new JLabel( " " ) ;
    private final JLabel           paceText  = new JLabel( " " ) ;

    // The menus and the toolbar share these actions, and so their state.

    private final Action scopeAction = action( "Scope…", ToolIcon.CRITERIA,
            shortcut( KeyEvent.VK_K ), "Choose the topic and the batch size " +
            "(clears the table)", () -> editScope( null ) ) ;
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
    private final Action loadEscalatedAction = action( "Load escalated",
            null, null, "Fetch the questions no tag fits, to tag by hand",
            this::loadEscalated ) ;
    private final Action settingsAction = action( "Settings…", null, null,
            "The tagger's settings", this::editSettings ) ;
    private final Action startAction = action( "Start", ToolIcon.START,
            shortcut( KeyEvent.VK_R ), "Ask the LLM about the questions " +
            "awaiting it (Invoke LLM asks about one)",
            this::start ) ;
    private final Action stopAction = action( "Stop", ToolIcon.STOP,
            shortcut( KeyEvent.VK_PERIOD ), "Stop asking the LLM; requests " +
            "under way finish", this::stop ) ;
    private final Action sendAction = action( "Ask the LLM about the selected",
            null, null, "Ask the LLM about the selected questions no model " +
            "has answered",
            this::sendSelected ) ;
    private final Action requeueFailedAction = action(
            "Re-queue failed questions", ToolIcon.REQUEUE, null,
            "Ask the LLM again about every question it failed on that no " +
            "model has answered",
            this::requeueFailed ) ;
    private final Action saveAction = action( "Save", ToolIcon.SAVE,
            shortcut( KeyEvent.VK_S ), "Send the accepted questions' tags to " +
            "the server (red while any wait)",
            this::saveAll ) ;
    private final Action clearAction = action( "Clear decisions…",
            ToolIcon.CLEAR, null, "Forget the unsaved decisions; the " +
            "LLM's replies stay", this::clearAll ) ;
    private final Action selectAllAction = action( "Select all shown rows",
            null, shortcut( KeyEvent.VK_A ), null, this::selectAllRows ) ;

    /** The decision keys 1-4 as menu items; the keys are the table's. */
    private final Action[] decideActions = {
            action( "1  Use the proposed tags", null, null, null,
                    () -> pressKey( 1 ) ),
            action( "2  Keep the existing tags", null, null, null,
                    () -> pressKey( 2 ) ),
            action( "3  Defer…", null, null, null,
                    () -> pressKey( 3 ) ),
            action( "4  Skip to the next to decide", null, null, null,
                    () -> pressKey( 4 ) ) } ;

    /** The number of decision keys; the last one skips. */
    private static final int DECISION_KEYS = 4 ;

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

    /** True while the scope is being set (the topic's tags fetched). */
    private boolean scoping ;

    /** True while the questions loaded are being read and scored. */
    private boolean assessing ;

    /** The threads questions are read and scored on. */
    private final ExecutorService assessPool ;

    /** The scope in effect before one was unloaded; null if none was. */
    private Scope lastScope ;

    /**
     * The topic's questions awaiting tags on the server, as of the last
     * fetch less those saved since; -1 before the first Load.
     */
    private int pending = -1 ;

    /** A {@link #fitColumns} is queued on the EDT. */
    private boolean fitPending ;

    /**
     * The rows whose chosen set the user is editing: shown whatever the
     * filters say, so a row doesn't vanish at its first edit, until the
     * selection leaves them.
     */
    private final Set<Row> pinned = new HashSet<>() ;

    /**
     * @param transcripts source of the transcripts shown on the right
     * @param names       the names models are shown by
     * @param ocrThreads  questions read at once after a Load
     */
    public TaggerFrame( TranscriptStore transcripts, ModelNames names,
                        int ocrThreads ) {

        super( title( null ) ) ;
        this.names           = names ;
        this.model           = new TagTableModel() ;
        this.table           = new JTable( model ) ;
        this.sorter          = new TableRowSorter<>( model ) ;
        this.headerFilters   = new HeaderFilterController<>( table, model,
                                                           this::applyFilter ) ;
        this.transcriptPanel = new TranscriptPanel( transcripts, names ) ;
        this.topicTags       = new TopicTagsPanel( this::editChosen ) ;
        this.chosenBar       = new ChosenTagsBar( t -> editChosen( t, true ),
                                                  t -> editChosen( t, false ) ) ;
        this.rateLimitStrip  = new RateLimitStrip( names ) ;
        this.tagStrip     = new TagStrip( names, stripActions() ) ;
        this.assessPool   = assessPool( ocrThreads ) ;

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

        // Table, preview, tag strip, topic tags and the chosen set on the
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
        question.add( tagStrip, BorderLayout.SOUTH ) ;

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

        JPanel footer = new JPanel( new BorderLayout() ) ;
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

    /** Show the scope in the title and the topic tags panel. */
    private void showScope() {
        setTitle( title( controller.describe() ) ) ;
        topicTags.show( controller.catalogue() ) ;
        showChosen() ;
        updateControls() ;
    }

    /** "QTagger - <scope>". */
    private static String title( String scope ) {
        return "QTagger" + ( scope != null ? " - " + scope : "" ) ;
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
     * File (scope, loads, settings, exit), Run (start, stop, send, re-queue), Decisions
     * (the decision keys, saving, clearing) and View (status filter, header
     * filters, select all, rate limit bar). The toolbar shares their actions.
     */
    private JMenuBar buildMenuBar() {

        JMenu     file = new JMenu( "File" ) ;
        JMenuItem exit = new JMenuItem( "Exit" ) ;

        exit.addActionListener( e -> dispatchEvent(
                new WindowEvent( this, WindowEvent.WINDOW_CLOSING ) ) ) ;
        file.add( scopeAction ) ;
        file.add( loadAction ) ;
        file.add( loadFromStartAction ) ;
        file.add( loadEscalatedAction ) ;
        file.addSeparator() ;
        file.add( settingsAction ) ;
        file.addSeparator() ;
        file.add( exit ) ;

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
            case 1  -> rows.stream().anyMatch( Row::canUseProposal ) ;
            case 2  -> rows.stream().anyMatch( Row::canKeepExisting ) ;
            case 3  -> rows.stream().anyMatch( Row::isOpen ) ;
            case 4  -> table.getRowCount() > 0 ;
            default -> false ;
        } ;
    }

    /**
     * A click on the tag strip: decide as key {@code n} does, then give
     * the table the focus back so the keys go on working.
     */
    private void decideByClick( int n ) {
        pressKey( n ) ;
        table.requestFocusInWindow() ;
    }

    /** What the tag strip's clicks do; each gives the table the focus back. */
    private TagStrip.Actions stripActions() {
        return new TagStrip.Actions() {
            @Override public void decide( int key ) {
                decideByClick( key ) ;
            }

            @Override public void resolve( String tag, boolean accept ) {
                resolveUndecided( tag, accept ) ;
                table.requestFocusInWindow() ;
            }

            @Override public void toggle( String tag, boolean add ) {
                editChosen( tag, add ) ;
                table.requestFocusInWindow() ;
            }

            @Override public void invokeLlm( Row row ) {
                if( row.canAskFirst() ) send( List.of( row ) ) ;
                table.requestFocusInWindow() ;
            }

            @Override public boolean canInvokeLlm( Row row ) {
                return canSend() && row.canAskFirst() ;
            }
        } ;
    }

    /**
     * Decision key {@code n}, for the selected rows it applies to: 1 use the
     * proposed tags, 2 keep the existing ones, 3 defer; 4 skips to the next
     * question to decide.
     */
    private void pressKey( int n ) {

        if( !canPress( n ) ) return ;
        List<Row> rows = selectedRows() ;
        switch( n ) {
            case 1  -> useProposal( rows ) ;
            case 2  -> keepExisting( rows ) ;
            case 3  -> defer( rows ) ;
            case 4  -> select( nextToDecide( List.of() ) ) ;
            default -> { }
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

    /**
     * Right-click on the table, for the selected rows, or the clicked row if
     * it isn't one of them: send them to the model, or to a model of the
     * user's choice, the decision keys, and save or clear their decisions.
     */
    private void addRowMenu() {

        JPopupMenu menu   = new JPopupMenu() ;
        JMenuItem  send   = new JMenuItem() ;
        JMenu      sendTo = new JMenu( "Send to model" ) ;
        JMenuItem  save   = new JMenuItem() ;
        JMenuItem  clear  = new JMenuItem() ;

        send.addActionListener( e -> sendSelected() ) ;
        save.addActionListener( e -> save( selectedRows(), null, () -> {} ) ) ;
        clear.addActionListener( e -> clear( selectedRows() ) ) ;
        menu.add( send ) ;
        menu.add( sendTo ) ;
        menu.addSeparator() ;
        for( int i = 0 ; i < DECISION_KEYS - 1 ; i++ ) {
            menu.add( decideActions[i] ) ;
        }
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

                send.setText( "Ask the LLM about " +
                              plural( n, "question" ) ) ;
                send.setEnabled( n > 0 && canSend() ) ;
                fillSendTo( sendTo ) ;
                updateSelectionActions() ;
                save.setText( "Save " + plural( savable, "question" ) ) ;
                save.setEnabled( savable > 0 && controller != null ) ;
                clear.setText( "Clear " + plural( clearable, "decision" ) ) ;
                clear.setEnabled( clearable > 0 ) ;
                menu.show( table, e.getX(), e.getY() ) ;
            }
        } ) ;
    }

    /**
     * The "Send to model" submenu: every usable model that can take a selected
     * row, so not the one whose reply a row stands on.
     */
    private void fillSendTo( JMenu sendTo ) {

        sendTo.removeAll() ;
        List<String> models = controller == null ? List.of()
                : controller.models().stream()
                            .filter( id -> !sendableTo( id ).isEmpty() )
                            .toList() ;
        for( String id : models ) {
            JMenuItem item = new JMenuItem( names.of( id ) ) ;
            item.setToolTipText( id + " alone: its retries, no fallback" ) ;
            item.addActionListener( e -> sendSelected( id ) ) ;
            sendTo.add( item ) ;
        }
        sendTo.setEnabled( canSend() && !models.isEmpty() ) ;
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
     * True if rows may be sent to the model now: the tagger can tag, and no
     * run is stopping.
     */
    private boolean canSend() {
        return controller != null && controller.canTag()
               && procState != ProcessingState.STOPPING ;
    }

    /** The selected rows no model has answered, in table order. */
    private List<Row> sendableSelection() {
        return selectedRows().stream().filter( Row::canAskFirst ).toList() ;
    }

    /** The selected rows {@code modelId} may take, in table order. */
    private List<Row> sendableTo( String modelId ) {
        return selectedRows().stream()
                             .filter( r -> r.canAskLlm()
                                           && !r.answeredBy( modelId ) )
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
     * Keys 1-4 on the table (main row or keypad), for every selected row: 1
     * use the proposed tags, 2 keep the existing ones, 3 defer; 4 skips to
     * the next question to decide. The Decisions menu's items show them;
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

    // ---- scope ---------------------------------------------------------

    /**
     * Let the user choose the scope among the topics that have their files,
     * then put it in effect off the EDT; a scope that can't be used is
     * reported and asked for again. Not while a
     * run is going; asks before losing unsaved decisions, and clears the
     * table.
     *
     * @param problem why it is asked for; null if the user asked
     */
    private void editScope( String problem ) {

        if( controller == null || scoping || loading
            || procState != ProcessingState.STOPPED ) return ;
        if( problem == null && !discardUnsaved( "Scope" ) ) return ;

        Map<String,List<Topic>> offered = controller.scopeableTopics() ;
        if( offered.isEmpty() ) {
            JOptionPane.showMessageDialog( this, "No topic has a regression " +
                    "model and an LLM prompt in\n" +
                    controller.tagWorkspaceDir(), "Scope",
                    JOptionPane.ERROR_MESSAGE ) ;
            return ;
        }

        Scope current = controller.scope() != null ? controller.scope()
                                                   : lastScope ;
        Scope chosen  = ScopeDialog.ask( this, current, problem, offered ) ;
        if( chosen == null ) return ;
        setScope( chosen ) ;
    }

    /**
     * Put {@code scope} in effect off the EDT. A topic that can't be tagged
     * leaves no scope in effect; on any other failure ask again.
     */
    private void setScope( Scope scope ) {

        Scope before = controller.scope() ;

        scoping = true ;
        statusBar.setText( "Fetching the topic's tags…" ) ;
        updateControls() ;
        background( () -> {
            controller.setScope( scope ) ;
            return null ;
        }, ok -> {
            scoping = false ;
            clearTable() ;
            pending = -1 ;
            showScope() ;
            String notice = controller.topicFilesNotice() ;
            statusBar.setText( "Scope set - press Load" +
                               ( notice != null ? "   |   " + notice : "" ) ) ;
            statusBar.setToolTipText( notice ) ;
        }, e -> {
            scoping = false ;
            if( e instanceof TopicFileException ) {
                scopeUnloaded( before, e.getMessage() ) ;
                return ;
            }
            updateControls() ;
            editScope( "That scope can't be used: " + e.getMessage() ) ;
        } ) ;
    }

    /**
     * The topic chosen can't be tagged, so no scope is in effect now: leave
     * nothing to load from, and say why.
     *
     * @param before the scope that was in effect, offered by the Scope dialog
     *               next, so its batch size and cursor aren't lost
     */
    private void scopeUnloaded( Scope before, String why ) {

        if( before != null ) lastScope = before ;
        clearTable() ;
        pending = -1 ;
        showScope() ;
        statusBar.setText( "No scope - choose one to load questions" ) ;
        statusBar.setToolTipText( null ) ;

        JOptionPane.showMessageDialog( this, why + "\n\nThis topic can't be " +
                "tagged, so no scope is loaded. Choose another with Scope…",
                "Scope unloaded", JOptionPane.ERROR_MESSAGE ) ;
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
        int batch = controller.scope().batchSize() ;

        fetch( () -> controller.load( batch, fromStart ), this::showItems ) ;
    }

    /** Show the scoped topic's escalated questions, to tag by hand. */
    private void loadEscalated() {

        if( controller == null || loading ) return ;
        if( !discardUnsaved( "Load escalated" ) ) return ;
        fetch( controller::loadEscalated, this::showItems ) ;
    }

    /**
     * Fetch questions with {@code work} without asking about unsaved decisions
     * and hand them to {@code onLoaded}; a failure is reported instead.
     */
    private void fetch( Callable<Loaded> work, Consumer<Loaded> onLoaded ) {

        loading = true ;
        statusBar.setText( "Loading the questions awaiting tags…" ) ;
        Spinner.busy( loadButton ) ;
        updateControls() ;

        background( work, loaded -> {
            loading = false ;
            Spinner.idle( loadButton ) ;
            updateControls() ;
            onLoaded.accept( loaded ) ;
        }, e -> {
            loading = false ;
            Spinner.idle( loadButton ) ;
            updateControls() ;
            error( "Load", "Could not load the questions", e ) ;
        } ) ;
    }

    /**
     * Fill the table with {@code loaded}, with the replies stored by
     * earlier runs; the rows shown before are dropped.
     */
    private void showItems( Loaded loaded ) {

        pending = loaded.pending() ;
        clearTable() ;
        model.setRows( rows( loaded.items() ) ) ;
        restore( model.rows() ) ;
        assess( model.rows() ) ;
    }

    /** A pool of {@code threads} daemon threads to read and score on. */
    private static ExecutorService assessPool( int threads ) {

        AtomicInteger n = new AtomicInteger() ;
        return Executors.newFixedThreadPool( threads, r -> {
            Thread t = new Thread( r, "qtagger-assess-" + n.incrementAndGet() ) ;
            t.setDaemon( true ) ;
            return t ;
        } ) ;
    }

    /**
     * Read and score those of {@code rows} with images off the EDT, several at
     * once, showing each result as it comes; Load and the scope wait for it.
     * Then, if the settings say so, the rows awaiting the LLM are sent to it.
     */
    private void assess( List<Row> rows ) {

        List<Row> todo = rows.stream().filter( r -> r.item.hasImages() ).toList() ;

        for( Row row : todo ) {
            if( row.status == TaggerStatus.PENDING ) {
                row.status = TaggerStatus.READING ;
            }
        }
        model.changedAll() ;
        assessing = !todo.isEmpty() ;
        updateControls() ;
        if( todo.isEmpty() ) return ;

        new SwingWorker<Void,Assessed>() {
            @Override protected Void doInBackground() throws Exception {

                CompletionService<Assessed> done =
                        new ExecutorCompletionService<>( assessPool ) ;

                for( Row row : todo ) {
                    done.submit( () -> new Assessed(
                            row, controller.assess( row.item ) ) ) ;
                }
                for( int i = 0 ; i < todo.size() ; i++ ) {
                    publish( done.take().get() ) ;
                }
                return null ;
            }

            @Override protected void process( List<Assessed> results ) {
                results.forEach( a -> assessed( a.row(), a.assessment() ) ) ;
                refreshStatusBar() ;
            }

            @Override protected void done() {

                assessing = false ;
                try {
                    get() ;
                }
                catch( Exception e ) {
                    log.error( "Reading the questions failed", e ) ;
                }
                updateControls() ;
                // Only a selection the user hasn't made is moved.
                if( table.getSelectedRowCount() == 0 ) {
                    select( nextToDecide( List.of() ) ) ;
                    table.requestFocusInWindow() ;
                }
                if( controller.settings().autoSendToLlm() ) sendAwaiting() ;
            }
        }.execute() ;
    }

    /** A row and what reading and scoring it gave. */
    private record Assessed( Row row, Assessment assessment ) {}

    /** Note {@code a} on {@code row}, which then stands on it if it was read. */
    private void assessed( Row row, Assessment a ) {

        row.assessment = a ;
        if( row.status == TaggerStatus.READING ) row.stand() ;
        rowChanged( row ) ;
    }

    /** Empty the table and what shows its selection. */
    private void clearTable() {

        model.setRows( List.of() ) ;
        pinned.clear() ;
        tagStrip.show( null ) ;
        regressionPanel.show( null ) ;
        showChosen() ;
        showDetail( null ) ;
        preview.show( null ) ;
    }

    /** A row for each of {@code items}, with its existing tags. */
    private List<Row> rows( List<TagItem> items ) {

        TagCatalogue c = controller.catalogue() ;
        return items.stream()
                    .map( i -> new Row( i, i.existing( c ) ) )
                    .toList() ;
    }

    /** Give {@code rows} the replies earlier runs stored, and deferrals. */
    private void restore( List<Row> rows ) {

        for( Row row : rows ) {
            if( !row.item.isSendable() ) continue ;
            PendingTagging.Stored st = controller.stored( row.item ) ;
            if( st == null ) continue ;

            replied( row, st.proposal() ) ;
            if( st.deferral() != null ) {
                row.status    = TaggerStatus.DEFERRED ;
                row.deferNote = st.deferral() ;
                row.message   = "Deferred: " + st.deferral() ;
            }
            model.changed( row ) ;
        }
    }

    /** Ask the LLM about every row awaiting it. */
    private void start() {
        if( !sendAwaiting() ) statusBar.setText( "Nothing to ask the LLM" ) ;
    }

    /**
     * Send the rows awaiting the LLM to it.
     *
     * @return false if there were none
     */
    private boolean sendAwaiting() {

        List<Row> rows = model.rows().stream().filter( Row::needsLlm ).toList() ;
        send( rows ) ;
        return !rows.isEmpty() ;
    }

    /** Let the user change the settings; saved if they are changed. */
    private void editSettings() {

        TaggerSettings now  = controller.settings() ;
        TaggerSettings next = SettingsDialog.ask( this, now ) ;
        if( next == null || next.equals( now ) ) return ;
        try {
            controller.applySettings( next ) ;
        }
        catch( IOException e ) {
            error( "Settings", "The settings apply until the tagger is " +
                   "closed, but could not be saved", e ) ;
        }
    }

    /** Wind the run down: no new requests, running ones finish. */
    private void stop() {
        if( controller != null ) controller.stop() ;
    }

    /**
     * Ask the LLM again about every row it failed on, in the table's order;
     * also while a run is going, which takes them in after the rows it has.
     */
    private void requeueFailed() {

        List<Row> failed = model.rows().stream()
                                .filter( TaggerFrame::canRequeue )
                                .toList() ;
        log.info( "Re-queueing {} failed questions", failed.size() ) ;
        send( failed ) ;
    }

    private static boolean canRequeue( Row row ) {
        return row.llmFailure != null && row.canAskFirst() ;
    }

    /** Send the selected rows no model has answered. */
    private void sendSelected() {
        send( sendableSelection() ) ;
    }

    /** Send the selected rows that may be sent to {@code model} alone. */
    private void sendSelected( String model ) {
        send( sendableTo( model ), model ) ;
    }

    private void send( List<Row> rows ) {
        send( rows, null ) ;
    }

    /**
     * Queue {@code rows} in the order the table shows them (rows filtered out
     * last, in load order), so they are taken top down; on {@code modelId}
     * alone if not null. Their replies stay till new ones come; nothing else
     * about them changes, so the user can go on deciding meanwhile.
     */
    private void send( List<Row> rows, String modelId ) {

        if( !canSend() || rows.isEmpty() ) return ;
        rows = rows.stream().sorted( Comparator.comparingInt( this::viewOrder ) )
                   .toList() ;

        for( Row row : rows ) {
            row.llm        = Llm.QUEUED ;
            row.llmFailure = null ;
            tagStrip.refresh( row ) ;
        }
        model.changedAll() ;

        List<TagItem> items = rows.stream().map( r -> r.item ).toList() ;
        if( modelId == null ) controller.start( items ) ;
        else controller.start( items, modelId ) ;
    }

    /** Save every accepted and decided row, then load again if asked to. */
    private void saveAll() {

        boolean reload = loadAfterSaveBox.isSelected() ;
        save( model.rows(), saveButton, () -> {
            // Loading replaces the rows, so not while the models have some.
            if( reload && pending > 0
                && procState == ProcessingState.STOPPED ) {
                load( false ) ;
            }
        } ) ;
    }

    /**
     * Save the decisions of {@code rows} on the server, then run
     * {@code after} (at once if there are none); {@code button}, if not
     * null, spins meanwhile. Each accepted row among them with no decision is
     * first decided on the tags it stands on. A set with no tags is held back,
     * and says so: saving it would clear the question's tags on the server.
     */
    private void save( List<Row> rows, JButton button, Runnable after ) {

        if( controller == null ) return ;
        for( Row row : rows ) {
            if( !row.status.isAccepted() || row.decision != null ) continue ;
            decide( row, row.automaticSet() ) ;
        }

        List<Row> held = rows.stream()
                             .filter( r -> r.isUnsaved()
                                           && r.decision.tags().isEmpty() )
                             .toList() ;
        for( Row row : held ) {
            row.message = "Not saved: no tags chosen" ;
            rowChanged( row ) ;
        }
        if( !held.isEmpty() ) {
            log.warn( "{} held back: no tags chosen",
                      plural( held.size(), "question" ) ) ;
        }

        List<SaveItem> items = rows.stream()
                                   .filter( r -> r.isUnsaved()
                                                 && !held.contains( r ) )
                                   .map( r -> new SaveItem(
                                           r.item, r.decision, r.assessment,
                                           r.proposal ) )
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
                " (deferrals included)?\nThe LLM's replies stay.",
                "Clear decisions", JOptionPane.OK_CANCEL_OPTION ) ;
        if( answer == JOptionPane.OK_OPTION ) clear( model.rows() ) ;
    }

    /**
     * Forget the unsaved decisions and the deferrals of {@code rows}: each
     * goes back to the tags it stands on, its undecided tags open again and
     * the LLM's reply kept.
     */
    private void clear( List<Row> rows ) {

        for( Row row : rows ) {
            if( !isClearable( row ) ) continue ;
            if( row.status == TaggerStatus.DEFERRED ) {
                controller.undefer( row.item ) ;
            }
            row.decision  = null ;
            row.base      = null ;
            row.deferNote = null ;
            row.rejected.clear() ;
            row.stand() ;

            model.changed( row ) ;
            tagStrip.refresh( row ) ;
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

    // ---- decisions -----------------------------------------------------

    /**
     * Settle the rows on the proposed tags (those chosen so far if some are
     * still undecided): the undecided tags left out are turned down.
     */
    private void useProposal( List<Row> rows ) {
        decide( rows, Row::canUseProposal,
                r -> r.settleOn( r.proposedChoice() ) ) ;
    }

    /** Keep the rows' existing tags. */
    private void keepExisting( List<Row> rows ) {
        decide( rows, Row::canKeepExisting,
                r -> r.settleOn( TagDecision.existing( r.existing ) ) ) ;
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
                "?", "Defer", JOptionPane.QUESTION_MESSAGE ) ;
        if( note == null ) return ;
        Row next = nextToDecide( open ) ;

        for( Row row : open ) {
            if( row.status != TaggerStatus.DEFERRED ) {
                controller.defer( row.item, note ) ;
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
     * one, with {@code base} the set it started from.
     */
    private void stage( Row row, TagDecision base, TagDecision d ) {

        row.base     = base ;
        row.decision = d ;
        row.status   = TaggerStatus.ACCEPTED ;
        row.message  = "Accepted: " + describe( d ) ;
        model.changed( row ) ;
    }

    /**
     * The user's edit of {@code row}'s set to {@code tags}, from {@code base}.
     * Edited back to the tags it stood on automatically, it stands on them
     * again, as if never edited.
     */
    private void edit( Row row, TagDecision base, List<String> tags ) {

        TagDecision d = TagDecision.edited( base, tags ) ;

        if( !d.edited() && !base.human() ) {
            row.decision = null ;
            row.base     = null ;
            row.stand() ;
            model.changed( row ) ;
            return ;
        }
        stage( row, base, d ) ;
    }

    /**
     * An undecided tag's tick on the row shown: put {@code tag} in its chosen
     * set, or ({@code accept} false) turn it down, taking it out if it is in.
     */
    private void resolveUndecided( String tag, boolean accept ) {

        TagCatalogue c = controller == null ? null : controller.catalogue() ;
        if( c == null ) return ;

        for( Row row : editableSelection() ) {
            TagDecision  base = baseOf( row ) ;
            TagDecision  now  = row.chosen() != null ? row.chosen() : base ;
            List<String> next = withTag( now.tags(), tag, accept, c ) ;

            if( accept ) row.rejected.remove( tag ) ;
            else row.rejected.add( tag ) ;

            edit( row, base, next != null ? next : now.tags() ) ;
            pinned.add( row ) ;
        }
        showChosen() ;
        tagStrip.refresh( selectedRow() ) ;
        updateControls() ;
    }

    /** The set {@code row}'s edits start from. */
    private static TagDecision baseOf( Row row ) {
        return row.base != null     ? row.base
             : row.chosen() != null ? row.chosen()
             : row.startingSet() ;
    }

    /**
     * Add {@code tag} to the chosen set of every open row selected, or
     * ({@code add} false) take it out; a row that has it already, or a tag
     * not of the topic, is left as it is. The edited rows are decided by the
     * user; they stay shown, and selected, until the selection moves on.
     */
    private void editChosen( String tag, boolean add ) {

        TagCatalogue c = controller == null ? null : controller.catalogue() ;
        if( c == null ) return ;
        int edited = 0 ;

        for( Row row : editableSelection() ) {
            TagDecision  base = baseOf( row ) ;
            TagDecision  now  = row.chosen() != null ? row.chosen() : base ;
            List<String> next = withTag( now.tags(), tag, add, c ) ;
            if( next == null ) continue ;

            // Taken out by hand, an undecided tag is turned down.
            if( add ) row.rejected.remove( tag ) ;
            else if( row.undecided().contains( tag ) ) row.rejected.add( tag ) ;

            edit( row, base, next ) ;
            pinned.add( row ) ;
            edited++ ;
        }
        if( edited == 0 ) {
            statusBar.setText( tag + ( add ? " can't go on, or is already on, "
                                           : " is not on " ) + "the selection" ) ;
            return ;
        }
        showChosen() ;
        tagStrip.refresh( selectedRow() ) ;
        updateControls() ;
    }

    /**
     * {@code now} with {@code tag} added (or taken out), in the topic's
     * order; null if it can't be, or is so already.
     */
    private static List<String> withTag( List<String> now, String tag,
                                         boolean add, TagCatalogue c ) {

        if( add && !c.contains( tag ) ) return null ;
        if( now.contains( tag ) == add ) return null ;

        Set<String> tags = new LinkedHashSet<>( now ) ;
        if( add ) tags.add( tag ) ;
        else tags.remove( tag ) ;

        List<String> order = c.tags().stream()
                              .map( CatalogueTag::tagText ).toList() ;
        return tags.stream()
                   .sorted( Comparator.comparingInt( t -> {
                       int i = order.indexOf( t ) ;
                       return i < 0 ? order.size() : i ;
                   } ) )
                   .toList() ;
    }

    /** The selected rows whose chosen set may be edited, in table order. */
    private List<Row> editableSelection() {
        return selectedRows().stream().filter( Row::isOpen ).toList() ;
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
            topicTags.fade( Set.of() ) ;
            return ;
        }

        List<String> tags = null ;
        for( Row row : rows ) {
            TagDecision  d = row.chosen() ;
            List<String> t = d == null ? row.startingSet().tags() : d.tags() ;
            tags = tags == null ? t
                                : tags.stream().filter( t::contains ).toList() ;
        }

        String from = rows.size() == 1
                      ? provenance( rows.get( 0 ) )
                      : rows.size() + " questions: the tags they share" ;

        chosenBar.show( new ChosenTagsBar.View( tags, from ) ) ;
        topicTags.fade( new HashSet<>( tags ) ) ;
    }

    /**
     * "regression, edited": where {@code row}'s chosen set came from.
     */
    private String provenance( Row row ) {

        TagDecision d = row.chosen() ;
        if( d == null ) return "Nothing chosen yet" ;

        return d.source() + ( d.edited() ? ", edited" : "" ) ;
    }

    /**
     * "[regression, edited] a, b": the tags and where they come from.
     */
    private String describe( TagDecision d ) {

        String from = d.source() + ( d.edited() ? ", edited" : "" ) ;
        return "[" + from + "] " + ( d.tags().isEmpty()
                                     ? "no tags" : String.join( ", ", d.tags() ) ) ;
    }

    /**
     * The next row shown, after the last one selected and wrapping round,
     * to decide; failing that, the next one accepted or refused by the
     * server; null if there is none. Rows in {@code skip} don't count.
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
                                      || row.status == TaggerStatus.AWAITING_LLM
                                      || row.status == TaggerStatus.ERROR ) ) {
                fallback = row ;
            }
        }
        return fallback ;
    }

    private static boolean toDecide( Row row ) {
        return row.status == TaggerStatus.NEEDS_HUMAN
               || row.status == TaggerStatus.ESCALATED ;
    }

    private static boolean isFrom( Row row, String from ) {
        return row.status.isAccepted() && from.equals( row.from() ) ;
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
        row.status  = TaggerStatus.SAVED ;
        row.message = "Saved: " + describe( row.decision ) ;
        pending     = Math.max( 0, pending - 1 ) ;
    }

    private void setSaving( Row row ) {
        row.status  = TaggerStatus.SAVING ;
        row.message = "Saving: " + describe( row.decision ) ;
        rowChanged( row ) ;
    }

    // ---- TaggerListener (worker threads) -------------------------------

    @Override
    public void itemStarted( TagItem item ) {
        onRow( item, row -> row.llm = Llm.ASKING ) ;
    }

    @Override
    public void itemAttempt( TagItem item, String model, int attempt ) {
        onRow( item, row -> {
            row.llm      = Llm.ASKING ;
            row.model    = model ;
            row.attempts = attempt ;
            row.inFlight = true ;
        } ) ;
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
    public void proposed( TagItem item, TagProposal proposal ) {
        onRow( item, row -> {
            row.inFlight = false ;
            replied( row, proposal ) ;
        } ) ;
    }

    @Override
    public void invalidOutput( TagItem item, int attempts, String reason ) {
        onRow( item, row -> {
            row.inFlight   = false ;
            row.attempts   = attempts ;
            row.llm        = Llm.IDLE ;
            row.llmFailure = "Its reply could not be used: " + reason ;
        } ) ;
    }

    @Override
    public void itemFailed( TagItem item, int attempts, String reason ) {
        onRow( item, row -> {
            row.attempts   = attempts ;
            row.inFlight   = false ;
            row.llm        = Llm.IDLE ;
            row.llmFailure = "Failed after " +
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
                // Rows never started are no longer waiting for the LLM.
                for( Row row : model.rows() ) {
                    if( row.llm != Llm.IDLE ) row.llm = Llm.IDLE ;
                }
                model.changedAll() ;
                // Only a selection the user hasn't made is moved.
                if( table.getSelectedRowCount() == 0 ) selectFirstToDecide() ;
            }
            tagStrip.refresh( selectedRow() ) ;
            updateControls() ;
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
     * Note the LLM's reply {@code p} on {@code row}, which overrides any
     * earlier one: the row now stands on its tags. If it says no tag fits, a
     * row with nothing else to stand on is escalated, and comes back from that
     * when a later reply names tags. A set the user edited by hand, or a row
     * deferred, saved or being saved, only shows the reply.
     */
    private void replied( Row row, TagProposal p ) {

        row.proposal   = p ;
        row.llm        = Llm.IDLE ;
        row.llmFailure = null ;
        if( row.assessment == null || row.isHandEdited()
            || row.status == TaggerStatus.DEFERRED
            || row.status == TaggerStatus.SAVING
            || row.status == TaggerStatus.SAVED ) return ;

        row.decision = null ;
        row.base     = null ;
        row.stand() ;
    }

    // ---- display -------------------------------------------------------

    private void rowChanged( Row row ) {

        model.changed( row ) ;
        tagStrip.refresh( row ) ;
        regressionPanel.refresh( row ) ;
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
        tagStrip.show( one ) ;
        regressionPanel.show( one ) ;
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

    private static String statusLabel( Row row ) {
        return row.status.getLabel() ;
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
        boolean scoped  = controller != null && controller.catalogue() != null ;
        boolean savable = model.rows().stream().anyMatch( Row::isSavable ) ;
        boolean idle    = stopped && !loading && !saving && !scoping
                          && !assessing ;

        scopeAction.setEnabled( idle && controller != null ) ;
        loadAction.setEnabled( idle && scoped ) ;
        loadFromStartAction.setEnabled( idle && scoped ) ;
        loadEscalatedAction.setEnabled( idle && scoped
                                        && controller.escalatedCount() > 0 ) ;
        settingsAction.setEnabled( controller != null ) ;
        startAction.setEnabled( stopped && !loading && !assessing && canSend()
                                && model.rows().stream().anyMatch(
                                        Row::needsLlm ) ) ;
        stopAction.setEnabled( procState == ProcessingState.RUNNING ) ;
        // Its icon is red while enabled, i.e. while decisions wait.
        saveAction.setEnabled( savable && controller != null ) ;
        clearAction.setEnabled( model.count( TaggerFrame::isClearable ) > 0 ) ;
        requeueFailedAction.setEnabled( canSend()
                && model.count( TaggerFrame::canRequeue ) > 0 ) ;
        if( saving ) Spinner.spin( table ) ;
        else Spinner.halt( table ) ;
        if( !loading && !scoping ) refreshStatusBar() ;
    }

    private void refreshStatusBar() {

        String text = String.format(
                "%d loaded · %d reading · %d accepted " +
                "(%d PAR, %d LLM, %d USR) · %d awaiting LLM · %d to decide · " +
                "%d saved · %d deferred · %d failed · %d errors",
                model.getRowCount(),
                model.count( TaggerStatus.READING ),
                model.count( r -> r.status.isAccepted() ),
                model.count( r -> isFrom( r, TagTableModel.FROM_PARTIAL ) ),
                model.count( r -> isFrom( r, TagTableModel.FROM_LLM ) ),
                model.count( r -> isFrom( r, TagTableModel.FROM_USER ) ),
                model.count( TaggerStatus.AWAITING_LLM ),
                model.count( TaggerStatus.NEEDS_HUMAN ),
                model.count( TaggerStatus.SAVED ),
                model.count( TaggerStatus.DEFERRED ),
                model.count( r -> r.llmFailure != null ),
                model.count( TaggerStatus.ERROR ) ) ;
        if( pending >= 0 ) text += " · awaiting tags: " + pending ;

        int escalated = controller == null ? 0 : controller.escalatedCount() ;
        if( escalated > 0 ) text += " · escalated: " + escalated ;
        // Running and queued counts are in the queue section on the right.
        if( procState == ProcessingState.STOPPING ) text += "   |   stopping" ;

        String notice = controller == null ? null
                                           : controller.topicFilesNotice() ;
        if( notice != null ) text += "   |   " + notice ;
        statusBar.setText( text ) ;
        statusBar.setToolTipText( notice ) ;
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

    /** The transcripts and the Models tabs. */
    private JTabbedPane tabs() {

        JTabbedPane tabs = new JTabbedPane() ;
        tabs.addTab( "Transcripts", transcriptPanel ) ;
        tabs.addTab( "Models", modelsPanel ) ;
        tabs.addTab( "Regression", regressionPanel ) ;
        tabs.setSelectedComponent( regressionPanel ) ;
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
    }

    /**
     * Tints rows by status; the Tags cell of a chosen set green, and the text
     * of tags not settled grey; the Status cell
     * of a hand-off red; frames the Tags cell in red while a request is in
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
            if( tags && row.chosen() != null
                && !TagTableModel.showsProposal( row ) ) {
                c.setBackground( DECIDED_CELL_BG ) ;
                c.setForeground( t.getForeground() ) ;
                return c ;
            }
            if( status && row.status == TaggerStatus.NEEDS_HUMAN ) {
                c.setBackground( HAND_OFF_CELL_BG ) ;
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
