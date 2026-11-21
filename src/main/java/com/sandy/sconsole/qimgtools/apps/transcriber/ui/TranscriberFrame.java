package com.sandy.sconsole.qimgtools.apps.transcriber.ui;

import com.sandy.sconsole.ai.HubStatus;
import com.sandy.sconsole.ai.ui.ModelsPanel;
import com.sandy.sconsole.qimgtools.apps.transcriber.gate.WellFormed;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.Passage;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.SaveResult;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.Saving;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.Transcription;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.TranscriptionCriteria;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.TranscriptionItem;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.TranscriptionRoute;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.TranscriptionStatus;
import com.sandy.sconsole.qimgtools.apps.transcriber.pipeline.TranscriberListener;
import com.sandy.sconsole.qimgtools.apps.transcriber.pipeline.TranscriptionJob;
import com.sandy.sconsole.qimgtools.apps.transcriber.server.StoredTranscription;
import com.sandy.sconsole.qimgtools.apps.transcriber.server.TranscriptionSearch;
import com.sandy.sconsole.qimgtools.apps.transcriber.ui.TranscriberTableModel.Row;
import com.sandy.sconsole.qimgtools.apps.transcriber.workspace.StagedTranscriptions.Staged;
import com.sandy.sconsole.qimgtools.apps.transcriber.workspace.TranscriptionAuditLog;
import com.sandy.sconsole.qimgtools.core.model.AttemptRequest;
import com.sandy.sconsole.qimgtools.core.model.ProcessingState;
import com.sandy.sconsole.qimgtools.core.model.RateLimitNotice;
import com.sandy.sconsole.qimgtools.core.model.Topic;
import com.sandy.sconsole.qimgtools.core.scheduler.TaskScheduler;
import com.sandy.sconsole.qimgtools.core.server.SConsoleServer.ServerException;
import com.sandy.sconsole.qimgtools.core.server.ServerQuestion;
import com.sandy.sconsole.qimgtools.core.ui.HeaderFilterController;
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
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ItemEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The transcriber's window (feature-design.md §9): the pending questions
 * in a table, each with how its transcription came about, Qwen's C and
 * where it stands; on the right the selected question's Check tab (its
 * image above its transcription), the models' transcripts and the Models
 * tab.
 * <p>
 * Load fetches the pending questions matching the criteria after the
 * cursor, in place of those shown; Start sends them to the models; each
 * transcription is staged as it comes. The user spot-checks them in check
 * order: correct (1), faulty, to the next model (2), a faulty LCT passage
 * (3), edit (4), skip (5). Save stores every transcribed question. Run
 * unattended loops: load, transcribe, save, pause. Every command is an
 * {@link Action} shared by the menus and the toolbar. Row state lives in
 * {@link TranscriberTableModel}, on the EDT; {@link TranscriberListener}
 * calls arrive on worker threads.
 */
@Slf4j
@SuppressWarnings( "serial" )
public class TranscriberFrame extends JFrame
        implements TranscriberListener, CheckPanel.Marks {

    /** What the window asks of the app. */
    public interface Controller {

        List<String> syllabusNames() ;

        List<Topic> topics( String syllabusName ) ;

        /** The criteria in effect. */
        TranscriptionCriteria criteria() ;

        /** Put {@code criteria} in effect, and save them for next time. */
        void setCriteria( TranscriptionCriteria criteria ) throws IOException ;

        /**
         * The first {@code batchSize} pending questions matching the
         * criteria after server id {@code afterId} (null: from the first),
         * with their images found and their staged transcriptions. Blocks;
         * called off the EDT.
         */
        Loaded load( int batchSize, Integer afterId ) throws ServerException ;

        /** Why {@code item} (with images) can't be sent; null if it can. */
        String unsupported( TranscriptionItem item ) ;

        /**
         * Send {@code items} to the models: start a run, or add them to the
         * one going. May read passages from the server: call off the EDT.
         */
        void transcribe( List<TranscriptionItem> items ) ;

        /**
         * Send {@code items} again to the root after {@code faultyModel}'s;
         * with {@code passage}, their groups' passages instead.
         */
        void resend( List<TranscriptionItem> items, String faultyModel,
                     boolean passage ) ;

        /**
         * The root a transcription by {@code model} marked faulty goes to;
         * null if no model is left to ask.
         */
        String nextRoot( String model ) ;

        /**
         * Send {@code items} to {@code model} alone (its retries, no
         * fallback); an LCT question only if its passage is known.
         */
        void transcribeWith( List<TranscriptionItem> items, String model ) ;

        /** The models a question may be sent to by hand, usable ones. */
        List<String> models() ;

        /**
         * The transcriptions models gave {@code item} (an LCT question's
         * part), oldest first, as the audit log keeps them.
         */
        List<TranscriptionAuditLog.Given> given( TranscriptionItem item ) ;

        /** True while a model run is going. */
        boolean isRunning() ;

        /** Drop the jobs not sent yet; requests in flight finish. */
        void stop() ;

        /** Keep {@code s} as {@code item}'s staged transcription. */
        void stage( TranscriptionItem item, Staged s ) ;

        /** Hold {@code p} as its group's passage, not on the server. */
        void holdPassage( Passage p ) ;

        /** LCT group {@code lctCtxId}'s passage; null if not known. */
        Passage passage( String lctCtxId ) ;

        /**
         * The last reply the gate refused for LCT group {@code lctCtxId}'s
         * passage, this session; null if none.
         */
        String rejectedPassage( String lctCtxId ) ;

        /**
         * The last reply the gate refused for {@code item} (an LCT
         * question's part only), this session; null if none.
         */
        String rejectedQuestion( TranscriptionItem item ) ;

        /** Note the user's mark in the audit log. */
        void mark( TranscriptionItem item, String mark,
                   TranscriptionRoute route ) ;

        /** Note a hand edit in the audit log. */
        void edited( TranscriptionItem item, String what, String before,
                     String after ) ;

        /**
         * Store {@code savings} on the server; a request that fails refuses
         * all of its items, with the reason. Blocks; called off the EDT.
         *
         * @return the server's result for each, in the same order
         */
        List<SaveResult> save( List<Saving> savings ) ;

        /** A question's stored transcription. Blocks. */
        StoredTranscription stored( int id ) throws ServerException ;

        /**
         * The transcriptions the server took at or after {@code since},
         * from the audit log, the latest of each question.
         */
        List<TranscriptionAuditLog.Saved> savedSince( Instant since )
                throws IOException ;

        /**
         * {@code saved} with their transcriptions as stored now, to check in
         * the table. Blocks; called off the EDT.
         */
        Loaded loadSaved( List<TranscriptionAuditLog.Saved> saved )
                throws ServerException ;

        /**
         * Page {@code page} (from 0) of the questions whose stored
         * transcription matches {@code text}, a Lucene query; empty
         * {@code topicIds} or {@code types} mean any. Blocks; called off
         * the EDT.
         */
        TranscriptionSearch search( String text, List<Integer> topicIds,
                                    List<String> types, int page )
                throws ServerException ;

        /**
         * {@code questions}, found by a search, with their transcriptions
         * as stored now, to check in the table. Blocks; called off the EDT.
         */
        Loaded loadFound( List<ServerQuestion> questions )
                throws ServerException ;

        List<RateLimitNotice> rateLimitNotices() ;

        /** The AI hub's state now, for the Models tab. */
        HubStatus hubStatus() ;

        /** Why Qwen can't be asked now; null if it can. */
        String qwenDown() ;

        /**
         * Why an unattended run must stop: Qwen down, its questions able
         * to go to Nemotron, and the free one held for the day; null
         * otherwise.
         */
        String unattendedStop() ;

        void setPassthrough( String modelId, boolean on ) ;

        void abortRequest( long jobId ) ;

        TaskScheduler.Counts queueCounts() ;

        long nextRequestAt() ;

        void shutdown() ;
    }

    /**
     * A load: its questions, the staged transcriptions restored for them
     * by server id, and how many pending questions match the criteria;
     * with {@code saved}, saved questions to check, their transcriptions
     * as stored and {@code pending} -1.
     */
    public record Loaded( List<TranscriptionItem> items,
                          Map<Integer,Staged> staged, int pending,
                          boolean saved ) {}

    /** One of a question's transcriptions, as a Check tab shows it. */
    private record Alt( Transcription t, TranscriptionRoute route,
                        String label ) {}

    /** A tab's time: when the transcription was given. */
    private static final DateTimeFormatter TAB_TIME =
            DateTimeFormatter.ofPattern( "dd MMM HH:mm" )
                             .withZone( ZoneId.systemDefault() ) ;

    private static final double SPLIT_RATIO = 0.5 ;
    private static final int    ROW_HEIGHT  = 20 ;
    private static final int    DETAIL_ROWS = 3 ;

    /** Seconds an unattended run waits between batches, to be stopped. */
    private static final int UNATTENDED_PAUSE_SECONDS = 5 ;

    /** The most loads an unattended run may be given. */
    private static final int MAX_UNATTENDED_LOADS = 999 ;

    private static final String RUN_UNATTENDED  = "Run unattended" ;
    private static final String STOP_UNATTENDED = "Stop unattended processing" ;

    // The queue section's background: idle, requests in flight, and
    // jobs waiting with none in flight (e.g. held by a rate limit).
    private static final Color QUEUE_IDLE    = new Color( 0xC8E6C9 ) ;
    private static final Color QUEUE_ACTIVE  = new Color( 0xFFCC80 ) ;
    private static final Color QUEUE_WAITING = new Color( 0xF8BBD0 ) ;

    // The queue section's labels are as wide as these, so changing numbers
    // don't shift the status bar.
    private static final String WIDEST_COUNTS =
            "Models:  99999 processed,  99 running,  9999 in queue" ;
    private static final String WIDEST_PACE = "next request in 59 min 59 s" ;

    /** In {@link #COLUMN_WIDTHS}: as wide as a row is high, not resizable. */
    private static final int SQUARE = 0 ;

    /**
     * Preferred width of each table column in pixels, in the order of the
     * columns of {@link TranscriberTableModel}; edit to taste.
     */
    private static final int[] COLUMN_WIDTHS = {
             50,    // Id
            150,    // Source
             90,    // Question
             40,    // Type
            150,    // Route
             30,    // C
            110,    // Status
             50,    // Checked
            SQUARE  // saved dot (untitled)
    } ;

    /** Row background per status; a status not listed keeps the table's. */
    private static final Map<TranscriptionStatus,Color> STATUS_BG =
            new EnumMap<>( Map.ofEntries(
                    Map.entry( TranscriptionStatus.NO_IMAGES,
                               new Color( 0xEEEEEE ) ),
                    Map.entry( TranscriptionStatus.WAITING_PASSAGE,
                               new Color( 0xF3EEDB ) ),
                    Map.entry( TranscriptionStatus.TRANSCRIBING,
                               new Color( 0xEDE3F5 ) ),
                    Map.entry( TranscriptionStatus.TRANSCRIBED,
                               new Color( 0xE2F1E3 ) ),
                    Map.entry( TranscriptionStatus.UNCONFIRMED,
                               new Color( 0xFFE6CC ) ),
                    Map.entry( TranscriptionStatus.FAULTY,
                               new Color( 0xF6D5D5 ) ),
                    Map.entry( TranscriptionStatus.FAILED,
                               new Color( 0xF9E6E8 ) ),
                    Map.entry( TranscriptionStatus.SAVING,
                               new Color( 0xE8E3F2 ) ),
                    Map.entry( TranscriptionStatus.SAVED,
                               new Color( 0xDCEFED ) ),
                    Map.entry( TranscriptionStatus.ERROR,
                               new Color( 0xF4D2D2 ) ) ) ) ;

    /** Which rows the table shows. */
    private enum Show {

        ALL     ( "All questions", r -> true ),
        TO_CHECK( "To check",      r -> r.checkRank() < 9 && r.isMarkable() ),
        UNSAVED ( "Unsaved",       r -> r.staged != null && !r.saved ),
        PROBLEMS( "Problems",      r -> r.status == TranscriptionStatus.FAILED
                                        || r.status == TranscriptionStatus.ERROR
                                        || r.status == TranscriptionStatus.FAULTY
                                        || r.status == TranscriptionStatus.NO_IMAGES ) ;

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

    private final ModelNames                         names ;
    private final TranscriberTableModel              model ;
    private final JTable                             table ;
    private final TableRowSorter<TranscriberTableModel> sorter ;
    private final HeaderFilterController<Row>        headerFilters ;
    private final CheckPanel                         checkPanel ;
    private final JTextArea                          detail    =
            new JTextArea( DETAIL_ROWS, 20 ) ;
    private final JLabel                             statusBar =
            new JLabel( " " ) ;
    private final TranscriptPanel                    transcriptPanel ;
    private final RateLimitStrip                     rateLimitStrip ;
    private final ModelsPanel                        modelsPanel = new ModelsPanel(
            "Question", this::setPassthrough, this::abortRequest ) ;

    /** The selected row's transcriptions, as its Check tabs show them. */
    private List<Alt> choiceAlts = List.of() ;

    private final JPanel queueBar  = new JPanel( new BorderLayout() ) ;
    private final JLabel queueText = new JLabel( " " ) ;
    private final JLabel paceText  = new JLabel( " " ) ;

    // The menus and the toolbar share these actions, and so their state.

    private final Action criteriaAction = action( "Criteria…",
            ToolIcon.CRITERIA, null, "Choose the syllabuses or topics to " +
            "load, and how many per load", this::chooseCriteria ) ;
    private final Action loadAction = action( "Load", ToolIcon.LOAD,
            shortcut( KeyEvent.VK_L ), "Fetch the next pending questions " +
            "after the last loaded (replaces the table)", this::load ) ;
    private final Action loadFromStartAction = action( "Load from start",
            null, KeyStroke.getKeyStroke( KeyEvent.VK_L,
                    Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()
                    | KeyEvent.SHIFT_DOWN_MASK ),
            "Forget where the last load ended, and load the first pending " +
            "questions", this::loadFromStart ) ;
    private final Action startAction = action( "Start", ToolIcon.START,
            shortcut( KeyEvent.VK_R ), "Send every pending question to the " +
            "models, in table order", this::startAll ) ;
    private final Action stopAction = action( "Stop", ToolIcon.STOP,
            shortcut( KeyEvent.VK_PERIOD ), "Drop the questions not sent " +
            "yet; requests in flight finish", this::stop ) ;
    private final Action requeueAction = action( "Re-queue failed questions",
            ToolIcon.REQUEUE, null, "Send every failed question to the " +
            "models again", this::requeueFailed ) ;
    private final Action saveAction = action( "Save", ToolIcon.SAVE,
            shortcut( KeyEvent.VK_S ), "Store every transcribed question on " +
            "the server (red while any wait)", this::saveAll ) ;
    private final Action spotCheckAction = action(
            "Spot-check saved questions…", null, null, "Check the " +
            "transcriptions saved in the last hours (replaces the table)",
            this::spotCheck ) ;
    private final Action searchAction = action(
            "Search transcriptions", null, shortcut( KeyEvent.VK_F ),
            "Go to the toolbar's search over the stored transcriptions",
            this::focusSearch ) ;
    private final Action selectAllAction = action( "Select all shown rows",
            null, shortcut( KeyEvent.VK_A ), null, this::selectAllRows ) ;

    /** Named "Stop unattended processing" while the loop runs. */
    private final Action unattendedAction = action( RUN_UNATTENDED, null,
            shortcut( KeyEvent.VK_U ), "Load, transcribe, save, and repeat " +
            "up to a given number of loads", this::toggleUnattended ) ;

    private final Action editPassageAction = action( "Edit passage", null,
            null, "Edit the passage of the selected LCT question's group",
            this::editPassage ) ;

    /** The mark keys 1-5 as menu items; the keys are the table's. */
    private final Action[] markActions = {
            action( "1  Correct", null, null, null, () -> pressKey( 1 ) ),
            action( "2  Faulty: send to the next model", null, null, null,
                    () -> pressKey( 2 ) ),
            action( "3  Faulty passage: send it to the next model", null,
                    null, null, () -> pressKey( 3 ) ),
            action( "4  Edit", null, null, null, () -> pressKey( 4 ) ),
            action( "5  Skip to the next to check", null, null, null,
                    () -> pressKey( 5 ) ) } ;

    /** The toolbar's Load and Save buttons spin while their call runs. */
    private final JButton loadButton = toolButton( loadAction ) ;
    private final JButton saveButton = toolButton( saveAction ) ;

    private final JCheckBox loadAfterSaveBox =
            new JCheckBox( "then load again", true ) ;
    private final JCheckBoxMenuItem rateLimitItem =
            new JCheckBoxMenuItem( "Show rate limit bar", true ) ;
    private final JComboBox<Show> showBox = new JComboBox<>( Show.values() ) ;

    /** The toolbar's search over the stored transcriptions. */
    private final SearchBar searchBar = new SearchBar( this::search ) ;

    /** True while a Load's call runs. */
    private boolean loading ;

    /** True while Start hands questions to the controller. */
    private boolean sending ;

    /** The model run's state, as last reported. */
    private ProcessingState runState = ProcessingState.STOPPED ;

    /**
     * Pending questions matching the criteria, as of the last load less
     * those saved since; -1 before the first Load.
     */
    private int pending = -1 ;

    /**
     * The cursor: Load fetches the questions after this server id, the
     * last one loaded; null to fetch from the first. Reset by new criteria
     * and Load from start.
     */
    private Integer afterId ;

    /**
     * What the search whose questions the table shows found, for the
     * status bar; null once anything else is loaded.
     */
    private String searchNote ;

    /** True while Run unattended loops: load, transcribe, save. */
    private boolean unattended ;

    /**
     * The questions the unattended run has asked the server for, by every
     * load and refill; at most {@link #maxLoads} batches.
     */
    private int asked ;

    /** True while the unattended batch is transcribed and refilled. */
    private boolean judging ;

    /** True while a refill saves, fetches and sends. */
    private boolean refilling ;

    /**
     * True once a refill found no question to add, or none is allowed: the
     * batch runs out, and is saved whole.
     */
    private boolean drained ;

    /** Questions the batch's refills saved, and dropped from the table. */
    private long savedEarlier ;

    /** The loads the unattended run may make, asked for as it starts. */
    private int maxLoads = 10 ;

    /** What the unattended run is doing, or why it ended; null before. */
    private String unattendedStep ;

    /** Counts down the pause before the next unattended batch. */
    private Timer pauseTimer ;

    /**
     * @param serverUrl   the SConsole server worked against, for the title
     * @param transcripts source of the transcripts shown on the right
     * @param names       the names models are shown by
     */
    public TranscriberFrame( String serverUrl, TranscriptStore transcripts,
                             ModelNames names ) {

        super( "QTranscriber - " + serverUrl ) ;
        this.names           = names ;
        this.model           = new TranscriberTableModel( names ) ;
        this.table           = new JTable( model ) ;
        this.sorter          = new TableRowSorter<>( model ) ;
        this.headerFilters   = new HeaderFilterController<>( table, model,
                                                           this::applyFilter ) ;
        this.checkPanel      = new CheckPanel( this ) ;
        this.transcriptPanel = new TranscriptPanel( transcripts, names ) ;
        this.rateLimitStrip  = new RateLimitStrip( names ) ;

        buildTable() ;

        loadAfterSaveBox.setToolTipText( "After Save, load the next " +
                                         "pending questions" ) ;
        loadAfterSaveBox.setFocusable( false ) ;
        showBox.addActionListener( e -> {
            applyFilter() ;
            table.requestFocusInWindow() ;
        } ) ;
        setJMenuBar( buildMenuBar() ) ;

        JToolBar toolbar = new JToolBar() ;
        toolbar.setFloatable( false ) ;
        toolbar.add( toolButton( criteriaAction ) ) ;
        toolbar.add( loadButton ) ;
        toolbar.addSeparator() ;
        toolbar.add( toolButton( startAction ) ) ;
        toolbar.add( toolButton( stopAction ) ) ;
        toolbar.add( toolButton( requeueAction ) ) ;
        toolbar.addSeparator() ;
        toolbar.add( saveButton ) ;
        toolbar.add( loadAfterSaveBox ) ;
        toolbar.addSeparator() ;
        toolbar.add( new JLabel( "Show: " ) ) ;
        // As wide as its longest label; the toolbar would stretch it.
        showBox.setMaximumSize( showBox.getPreferredSize() ) ;
        toolbar.add( showBox ) ;
        toolbar.addSeparator() ;
        toolbar.add( searchBar ) ;

        // On the left the table; on the right the selected row's detail
        // above the Check, Transcripts and Models tabs and the rate limit
        // strip.
        detail.setEditable( false ) ;
        detail.setLineWrap( true ) ;
        detail.setWrapStyleWord( true ) ;
        detail.setFocusable( false ) ;
        detail.setBorder( BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder( 0, 0, 1, 0, Color.GRAY ),
                BorderFactory.createEmptyBorder( 4, 8, 4, 8 ) ) ) ;

        JPanel right = new JPanel( new BorderLayout() ) ;
        detail.setBackground( right.getBackground() ) ;
        right.add( detail, BorderLayout.NORTH ) ;
        right.add( tabs(), BorderLayout.CENTER ) ;
        right.add( rateLimitStrip, BorderLayout.SOUTH ) ;
        // Else its minimum is its widest content's (the Check tab's button
        // row), and the divider can't widen the table past it.
        right.setMinimumSize( new Dimension( 0, 0 ) ) ;

        JSplitPane split = new JSplitPane( JSplitPane.HORIZONTAL_SPLIT,
                                           new JScrollPane( table ), right ) ;
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
        footer.setBorder(
                BorderFactory.createMatteBorder( 1, 0, 0, 0, Color.GRAY ) ) ;
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
        updateTitle() ;
        updateControls() ;
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
     * File (criteria, load, unattended, exit), Transcribe (start, stop,
     * re-queue), Check (the mark keys), Save, and View (status filter,
     * header filters, select all, rate limit bar). The toolbar shares
     * their actions.
     */
    private JMenuBar buildMenuBar() {

        JMenu     file = new JMenu( "File" ) ;
        JMenuItem exit = new JMenuItem( "Exit" ) ;

        exit.addActionListener( e -> dispatchEvent(
                new WindowEvent( this, WindowEvent.WINDOW_CLOSING ) ) ) ;
        file.add( criteriaAction ) ;
        file.add( loadAction ) ;
        file.add( loadFromStartAction ) ;
        file.add( unattendedAction ) ;
        file.add( spotCheckAction ) ;
        file.add( searchAction ) ;
        file.addSeparator() ;
        file.add( exit ) ;

        JMenu transcribe = new JMenu( "Transcribe" ) ;
        transcribe.add( startAction ) ;
        transcribe.add( stopAction ) ;
        transcribe.add( requeueAction ) ;
        transcribe.addSeparator() ;
        transcribe.add( markActions[3] ) ;
        transcribe.add( editPassageAction ) ;
        refreshOnOpen( transcribe ) ;

        JMenu check = new JMenu( "Check" ) ;
        for( Action a : markActions ) check.add( a ) ;
        refreshOnOpen( check ) ;

        JMenu             save      = new JMenu( "Save" ) ;
        JCheckBoxMenuItem loadAfter = new JCheckBoxMenuItem(
                "Load again after saving" ) ;

        // One model, so the menu item and the toolbar's box stay in step.
        loadAfter.setModel( loadAfterSaveBox.getModel() ) ;
        save.add( saveAction ) ;
        save.add( loadAfter ) ;

        JMenu     view         = new JMenu( "View" ) ;
        JMenuItem clearFilters = new JMenuItem( "Clear all column filters" ) ;

        clearFilters.addActionListener( e -> headerFilters.clearAll() ) ;
        rateLimitItem.addActionListener( e -> refreshRateLimitStrip() ) ;
        view.add( buildShowMenu() ) ;
        view.add( clearFilters ) ;
        view.addSeparator() ;
        view.add( selectAllAction ) ;
        view.add( rateLimitItem ) ;

        JMenuBar bar = new JMenuBar() ;
        bar.add( file ) ;
        bar.add( transcribe ) ;
        bar.add( check ) ;
        bar.add( save ) ;
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

    /** Enable the marks as they apply to the selected rows. */
    private void updateSelectionActions() {

        for( int n = 1 ; n <= 4 ; n++ ) {
            markActions[n - 1].setEnabled( canPress( n ) ) ;
        }
        markActions[4].setEnabled( table.getRowCount() > 0 ) ;
        editPassageAction.setEnabled( canEditPassage() ) ;
        checkPanel.refreshButtons() ;
    }

    private void buildTable() {

        sorter.setSortsOnUpdates( true ) ;
        // When an update drops or moves rows, JTable repaints only the
        // updated row's old strip; repaint the lot.
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
        table.setRowHeight( ROW_HEIGHT ) ;

        setColumnWidths() ;
        table.getSelectionModel().addListSelectionListener( e -> {
            if( !e.getValueIsAdjusting() ) showSelection() ;
        } ) ;
        bindKeys() ;
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

    /**
     * Right-click on the table, for the selected rows, or the clicked row if
     * it isn't one of them: the marks, sending (to the models, or to one
     * model chosen) and saving.
     */
    private void addRowMenu() {

        JPopupMenu menu   = new JPopupMenu() ;
        JMenuItem  send   = new JMenuItem() ;
        JMenu      sendTo = new JMenu( "Transcribe with" ) ;
        JMenuItem  save   = new JMenuItem() ;

        send.addActionListener( e -> transcribe( selectedRows().stream()
                .filter( this::isRequeueable ).toList() ) ) ;
        save.addActionListener( e -> save( selectedRows(), null, () -> {} ) ) ;
        for( Action a : markActions ) {
            if( a != markActions[4] ) menu.add( a ) ;
        }
        menu.addSeparator() ;
        menu.add( send ) ;
        menu.add( sendTo ) ;
        menu.add( save ) ;

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
                List<Row> sel      = selectedRows() ;
                int       sendable = count( sel, TranscriberFrame.this
                                                         ::isRequeueable ) ;
                int       savable  = count( sel, Row::isSavable ) ;

                updateSelectionActions() ;
                send.setText( "Transcribe " + plural( sendable, "question" ) ) ;
                send.setEnabled( sendable > 0 && canSend() ) ;
                fillSendTo( sendTo, sel ) ;
                save.setText( "Save " + plural( savable, "transcription" ) ) ;
                save.setEnabled( savable > 0 && !unattended && !saving()
                                 && controller != null ) ;
                menu.show( table, e.getX(), e.getY() ) ;
            }
        } ) ;
    }

    /**
     * The "Transcribe with" submenu: every usable model, for the selected
     * rows that can go to one.
     */
    private void fillSendTo( JMenu sendTo, List<Row> sel ) {

        List<Row>    rows   = sel.stream().filter( this::isChoosable )
                                 .toList() ;
        List<String> models = controller == null ? List.of()
                                                 : controller.models() ;

        sendTo.removeAll() ;
        for( String id : models ) {
            JMenuItem item = new JMenuItem( names.of( id ) ) ;
            item.setToolTipText( id + " alone: its retries, no fallback" ) ;
            item.addActionListener( e -> transcribeWith( rows, id ) ) ;
            sendTo.add( item ) ;
        }
        sendTo.setText( "Transcribe " + plural( rows.size(), "question" ) +
                        " with" ) ;
        sendTo.setEnabled( !rows.isEmpty() && !models.isEmpty()
                           && canSend() && !checkPanel.isEditing() ) ;
    }

    /**
     * True if {@code row} may be sent to a model chosen: it has images, its
     * type is supported, it isn't with a model or being saved, and for an
     * LCT question its passage is known.
     */
    private boolean isChoosable( Row row ) {
        return row.item.hasImages() && !row.unsupported
               && row.status != TranscriptionStatus.TRANSCRIBING
               && row.status != TranscriptionStatus.WAITING_PASSAGE
               && row.status != TranscriptionStatus.SAVING
               && ( !row.item.isLct()
                    || controller.passage( row.item.lctCtxId() ) != null ) ;
    }

    /** Send {@code rows} to {@code model} alone. */
    private void transcribeWith( List<Row> rows, String model ) {

        if( rows.isEmpty() || !canSend() ) return ;
        for( Row row : rows ) {
            row.status  = TranscriptionStatus.TRANSCRIBING ;
            row.message = "Sent to " + names.of( model ) + " alone" ;
            rowChanged( row ) ;
        }
        controller.transcribeWith( rows.stream().map( r -> r.item ).toList(),
                                   model ) ;
        updateControls() ;
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

    private Row rowAt( int view ) {
        return model.getRow( table.convertRowIndexToModel( view ) ) ;
    }

    /** Select every row the filter shows. */
    private void selectAllRows() {
        table.selectAll() ;
        table.requestFocusInWindow() ;
    }

    /**
     * Keys on the table (main row or keypad), for every selected row: 1
     * correct, 2 faulty (to the next model), 3 faulty passage, 4 edit, 5
     * skip to the next to check. The Check menu's items show them; they
     * aren't its accelerators, which would take the keys from the window.
     */
    private void bindKeys() {

        InputMap  keys    = table.getInputMap( JComponent.WHEN_FOCUSED ) ;
        ActionMap actions = table.getActionMap() ;

        for( int n = 1 ; n <= 5 ; n++ ) {
            String name = "mark-" + n ;
            int    key  = n ;

            keys.put( KeyStroke.getKeyStroke( KeyEvent.VK_0 + n, 0 ), name ) ;
            keys.put( KeyStroke.getKeyStroke( KeyEvent.VK_NUMPAD0 + n, 0 ),
                      name ) ;
            actions.put( name, new AbstractAction() {
                @Override public void actionPerformed( ActionEvent e ) {
                    pressKey( key ) ;
                }
            } ) ;
        }
    }

    // ---- criteria and loading ------------------------------------------

    private void chooseCriteria() {

        if( controller == null || busy() || unattended ) return ;
        TranscriptionCriteria chosen = CriteriaDialog.ask(
                this, controller.criteria(), controller.syllabusNames(),
                controller::topics ) ;
        if( chosen == null ) return ;

        afterId = null ;
        try {
            controller.setCriteria( chosen ) ;
        }
        catch( IOException e ) {
            log.error( "Could not save the criteria", e ) ;
            JOptionPane.showMessageDialog( this, "The criteria are in " +
                    "effect, but could not be saved:\n" + e.getMessage(),
                    "Criteria", JOptionPane.WARNING_MESSAGE ) ;
        }
        log.info( "Criteria: {}, {} per load", chosen.describe(),
                  chosen.batchSize() ) ;
        updateTitle() ;
        updateControls() ;
    }

    private void updateTitle() {

        String base = getTitle().replaceFirst( "  \\[.*\\]$", "" ) ;
        if( controller == null ) return ;
        TranscriptionCriteria c = controller.criteria() ;
        setTitle( base + "  [" + c.shortDescription() + ", " +
                  c.batchSize() + " per load]" ) ;
    }

    /**
     * File > Spot-check saved questions…: choose saved transcriptions and
     * load them into the table, in place of its rows, to check; a mark or
     * an edit stages a new one, which Save stores over the old. The cursor
     * stays.
     */
    private void spotCheck() {

        if( controller == null || busy() || unattended ) return ;
        List<TranscriptionAuditLog.Saved> chosen = SavedCheckDialog.ask(
                this, controller::savedSince, names ) ;
        if( chosen == null || !discardUnsaved( "Spot-check" ) ) return ;
        loadToCheck( "Spot-check", "the saved transcriptions",
                     () -> controller.loadSaved( chosen ), loaded -> {} ) ;
    }

    /** File > Search transcriptions: go to the toolbar's search. */
    private void focusSearch() {
        searchBar.focusQuery() ;
    }

    /**
     * The toolbar's search: load into the table, in place of its rows,
     * page {@code req.page()} of the questions whose stored transcription
     * matches, to check as a spot check's are; nothing found leaves the
     * table as it is. The cursor stays.
     */
    private void search( SearchBar.Request req ) {

        if( controller == null || busy() || unattended ) return ;
        if( !discardUnsaved( "Search" ) ) return ;

        List<Integer>         topics = req.inCriteria() ? criteriaTopicIds()
                                                        : List.of() ;
        TranscriptionSearch[] found  = new TranscriptionSearch[1] ;

        loadToCheck( "Search", "the questions found", () -> {
            found[0] = controller.search( req.text(), topics, req.types(),
                                          req.page() ) ;
            return found[0].questions().isEmpty()
                   ? null : controller.loadFound( found[0].questions() ) ;
        }, loaded -> {
            searchBar.searched( req, found[0] ) ;
            if( loaded == null ) {
                JOptionPane.showMessageDialog( this, "Nothing matches " +
                        req.describe() + ".", "Search",
                        JOptionPane.INFORMATION_MESSAGE ) ;
                return ;
            }
            searchNote = req.summary( found[0] ) ;
            refreshStatusBar() ;
        } ) ;
    }

    /**
     * The topics of the criteria in effect: those chosen, else every topic
     * of the syllabuses chosen.
     */
    private List<Integer> criteriaTopicIds() {

        TranscriptionCriteria c = controller.criteria() ;
        if( !c.topicIds().isEmpty() ) return c.topicIds() ;

        List<Integer> ids = new ArrayList<>() ;
        for( String syllabus : c.syllabusNames() ) {
            controller.topics( syllabus ).forEach( t -> ids.add( t.getId() ) ) ;
        }
        return ids ;
    }

    /**
     * Run {@code load} off the EDT and show what it brings in place of the
     * table's rows (null: nothing, the table left as it is), then pass it
     * to {@code then}; {@code title} names the command in an error dialog,
     * {@code what} the questions in the status bar.
     */
    private void loadToCheck( String title, String what,
                              Callable<Loaded> load,
                              Consumer<Loaded> then ) {

        loading = true ;
        statusBar.setText( "Loading " + what + "…" ) ;
        Spinner.busy( loadButton ) ;
        updateControls() ;

        new SwingWorker<Loaded,Void>() {
            @Override protected Loaded doInBackground() throws Exception {
                return load.call() ;
            }

            @Override protected void done() {

                loading = false ;
                Spinner.idle( loadButton ) ;
                updateControls() ;
                Loaded loaded ;
                try {
                    loaded = get() ;
                }
                catch( Exception e ) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e ;
                    log.error( "Could not load {}", what, cause ) ;
                    JOptionPane.showMessageDialog( TranscriberFrame.this,
                            "Could not load " + what + ":\n" +
                            cause.getMessage(), title,
                            JOptionPane.ERROR_MESSAGE ) ;
                    return ;
                }
                if( loaded != null ) showItems( loaded ) ;
                then.accept( loaded ) ;
            }
        }.execute() ;
    }

    /** Reset the cursor, and load the first pending questions. */
    private void loadFromStart() {

        if( controller == null || busy() ) return ;
        if( !discardUnsaved( "Load from start" ) ) return ;
        afterId = null ;
        log.info( "Loading from the start" ) ;
        fetch( batchSize(), this::showItems ) ;
    }

    /** Fetch the next pending questions after the cursor, replacing the rows. */
    private void load() {
        if( controller == null || busy() ) return ;
        if( !discardUnsaved( "Load" ) ) return ;
        fetch( batchSize(), this::showItems ) ;
    }

    /**
     * Fetch the first {@code limit} pending questions after the cursor,
     * without asking about unsaved transcriptions, move the cursor past
     * them, and hand them to {@code onLoaded}; a failure is reported
     * instead, and ends an unattended run.
     */
    private void fetch( int limit, Consumer<Loaded> onLoaded ) {

        Integer after = afterId ;

        loading = true ;
        statusBar.setText( "Loading the pending questions…" ) ;
        Spinner.busy( loadButton ) ;
        updateControls() ;

        new SwingWorker<Loaded,Void>() {
            @Override protected Loaded doInBackground() throws Exception {
                return controller.load( limit, after ) ;
            }

            @Override protected void done() {

                Loaded loaded = null ;

                loading = false ;
                Spinner.idle( loadButton ) ;
                try {
                    loaded = get() ;
                }
                catch( Exception e ) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e ;
                    log.error( "Could not load the questions", cause ) ;
                    endUnattended( "could not load the questions" ) ;
                    JOptionPane.showMessageDialog( TranscriberFrame.this,
                            "Could not load the questions:\n" +
                            cause.getMessage(), "Load",
                            JOptionPane.ERROR_MESSAGE ) ;
                }
                updateControls() ;
                if( loaded == null ) return ;
                loaded.items().stream().mapToInt( TranscriptionItem::id ).max()
                      .ifPresent( id -> afterId = id ) ;
                onLoaded.accept( loaded ) ;
            }
        }.execute() ;
    }

    /** Fill the table with {@code loaded}; the rows shown before are dropped. */
    private void showItems( Loaded loaded ) {

        searchNote = null ;
        if( !loaded.saved() ) pending = loaded.pending() ;
        setUp( model.setItems( loaded.items() ), loaded ) ;
        checkPanel.show( null, null, null, 0, null, null ) ;
        showDetail( null ) ;
        updateControls() ;
        if( model.rows().stream().noneMatch( Row::isSendable ) ) {
            select( nextToCheck( null ) ) ;
        }
    }

    /**
     * Give {@code rows}, just added, their restored transcriptions, and
     * fail those that can't be sent.
     */
    private void setUp( List<Row> rows, Loaded loaded ) {

        for( Row row : rows ) {
            Staged s = loaded.staged().get( row.item.id() ) ;
            if( s != null && loaded.saved() ) {
                row.staged  = s ;
                row.saved   = true ;
                row.status  = TranscriptionStatus.SAVED ;
                row.message = "As stored: " + describe( row ) ;
            }
            else if( s != null ) {
                row.staged  = s ;
                row.status  = row.restingStatus() ;
                row.message = "Restored from the workspace: " +
                              describe( row ) ;
            }
            else if( row.item.hasImages() ) {
                String why = controller.unsupported( row.item ) ;
                if( why != null ) {
                    row.unsupported = true ;
                    row.status      = TranscriptionStatus.FAILED ;
                    row.message     = "Can't be sent: " + why ;
                }
            }
            model.changed( row ) ;
        }
    }

    // ---- transcribing --------------------------------------------------

    /** Send every pending question, in table order. */
    private void startAll() {
        transcribe( tableOrder().stream().filter( Row::isSendable ).toList() ) ;
    }

    /** The rows in table order: those shown, then those filtered out. */
    private List<Row> tableOrder() {

        List<Row> rows = new ArrayList<>() ;
        for( int view = 0 ; view < table.getRowCount() ; view++ ) {
            rows.add( rowAt( view ) ) ;
        }
        model.rows().stream().filter( r -> !rows.contains( r ) )
             .forEach( rows::add ) ;
        return rows ;
    }

    /** True if {@code row} may be sent (again): pending or failed. */
    private boolean isRequeueable( Row row ) {
        return row.item.hasImages() && !row.unsupported
               && ( row.status == TranscriptionStatus.PENDING
                    || row.status == TranscriptionStatus.FAILED ) ;
    }

    private void requeueFailed() {
        transcribe( tableOrder().stream()
                                .filter( r -> r.status
                                              == TranscriptionStatus.FAILED )
                                .filter( this::isRequeueable ).toList() ) ;
    }

    /**
     * Mark {@code rows} as with the models and hand them to the
     * controller off the EDT (it may read passages from the server).
     */
    private void transcribe( List<Row> rows ) {

        if( rows.isEmpty() || !maySend() ) return ;
        for( Row row : rows ) {
            row.status  = TranscriptionStatus.TRANSCRIBING ;
            row.message = row.item.isLct()
                          && controller.passage( row.item.lctCtxId() ) == null
                          ? "Looking for the passage" : "Waiting for a model" ;
            rowChanged( row ) ;
        }

        List<TranscriptionItem> items = rows.stream().map( r -> r.item )
                                            .toList() ;
        sending = true ;
        updateControls() ;
        new SwingWorker<Void,Void>() {
            @Override protected Void doInBackground() {
                controller.transcribe( items ) ;
                return null ;
            }

            @Override protected void done() {

                sending = false ;
                try {
                    get() ;
                }
                catch( Exception e ) {
                    log.error( "Could not send the questions", e ) ;
                }
                // Refused (a run was stopping): they are pending again.
                if( !controller.isRunning() ) settleUnsent() ;
                updateControls() ;
            }
        }.execute() ;
    }

    private void stop() {
        if( controller != null ) controller.stop() ;
    }

    /**
     * The rows still waiting for a model or a passage once the run is
     * over go back to where their transcription leaves them.
     */
    private void settleUnsent() {
        for( Row row : model.rows() ) {
            if( !row.isBusy() ) continue ;
            row.status  = row.restingStatus() ;
            row.message = row.staged == null ? "Not sent: the run stopped"
                                             : describe( row ) ;
            rowChanged( row ) ;
        }
    }

    /** Run {@code update} on {@code item}'s row on the EDT, if it's loaded. */
    private void onRow( TranscriptionItem item, Consumer<Row> update ) {
        SwingUtilities.invokeLater( () -> {
            Row row = model.rowOf( item ) ;
            if( row == null ) return ;
            update.accept( row ) ;
            rowChanged( row ) ;
        } ) ;
    }

    /** Run {@code update} on the rows of LCT group {@code lctCtxId} on the EDT. */
    private void onGroup( String lctCtxId, Consumer<Row> update ) {
        SwingUtilities.invokeLater( () -> {
            for( Row row : model.group( lctCtxId ) ) {
                update.accept( row ) ;
                rowChanged( row ) ;
            }
        } ) ;
    }

    @Override
    public void jobStarted( TranscriptionJob job ) {}

    @Override
    public void jobAttempt( TranscriptionJob job, String model, int attempt ) {

        String asking = "Asking " + names.of( model ) +
                        ( attempt > 1 ? ", attempt " + attempt : "" ) ;
        if( job.kind() == TranscriptionJob.Kind.PASSAGE ) {
            onGroup( job.lctCtxId(), row -> {
                if( row.isBusy() ) row.message = "Passage: " + asking ;
            } ) ;
            return ;
        }
        onRow( job.item(), row -> {
            row.status  = TranscriptionStatus.TRANSCRIBING ;
            row.message = asking ;
        } ) ;
    }

    @Override
    public void waitingForPassage( TranscriptionItem item ) {
        onRow( item, row -> {
            row.status  = TranscriptionStatus.WAITING_PASSAGE ;
            row.message = "Waiting for the passage of " + item.lctCtxId() ;
        } ) ;
    }

    @Override
    public void requestSent( AttemptRequest request ) {
        SwingUtilities.invokeLater(
                () -> transcriptPanel.requestSent( request ) ) ;
    }

    @Override
    public void attemptFinished( TranscriptionJob job ) {
        SwingUtilities.invokeLater(
                () -> transcriptPanel.attemptFinished( job.task() ) ) ;
    }

    /** The row's transcription is the new one, staged unchecked. */
    @Override
    public void transcribed( TranscriptionJob job, Transcription t,
                             TranscriptionRoute route, boolean provisional ) {
        onRow( job.item(), row -> {
            row.staged  = new Staged( t, route, false, provisional ) ;
            row.saved   = false ;
            row.status  = row.restingStatus() ;
            row.message = describe( row ) ;
        } ) ;
        SwingUtilities.invokeLater( this::checkBatch ) ;
    }

    /**
     * A question failed, or kept its earlier transcription if it was a
     * re-send; a passage's group fails with it, unless re-sent.
     */
    @Override
    public void jobFailed( TranscriptionJob job, int attempts, String reason ) {

        String why = plural( attempts, "attempt" ) + ": " + reason ;
        if( job.kind() == TranscriptionJob.Kind.PASSAGE ) {
            onGroup( job.lctCtxId(), row -> {
                if( job.resent() ) {
                    row.message = "The passage could not be transcribed " +
                                  "again after " + why ;
                }
                else if( row.status == TranscriptionStatus.WAITING_PASSAGE ) {
                    row.status  = TranscriptionStatus.FAILED ;
                    row.message = "The passage could not be transcribed " +
                                  "after " + why ;
                }
            } ) ;
            return ;
        }
        onRow( job.item(), row -> {
            if( row.staged != null ) {
                row.status  = row.restingStatus() ;
                row.message = "Not transcribed again after " + why +
                              "; the earlier one stands" ;
                return ;
            }
            row.status  = TranscriptionStatus.FAILED ;
            row.message = "No transcription after " + why ;
        } ) ;
    }

    /**
     * Repaint the group's rows with the passage; a new passage, not on the
     * server, makes its saved questions unsaved, to store it with them.
     */
    @Override
    public void passageKnown( Passage passage ) {
        onGroup( passage.lctCtxId(), row -> {
            if( passage.stored() || !row.saved || row.staged == null ) return ;
            row.saved   = false ;
            row.status  = row.restingStatus() ;
            row.message = "The passage changed: save again" ;
        } ) ;
    }

    /**
     * Note the run's state; once it ends, the rows it dropped go back to
     * where they were, and an unattended batch waiting for it is saved.
     */
    @Override
    public void processingStateChanged( ProcessingState state ) {
        SwingUtilities.invokeLater( () -> {
            runState = state ;
            if( state == ProcessingState.STOPPED && !sending ) settleUnsent() ;
            updateControls() ;
            if( state == ProcessingState.STOPPED ) checkBatch() ;
        } ) ;
    }

    // ---- marks ---------------------------------------------------------

    /**
     * Mark key {@code n}: 1 correct, 2 faulty, 3 faulty passage, 4 edit,
     * 5 skip to the next to check.
     */
    private void pressKey( int n ) {

        if( n == 5 ) {
            select( nextToCheck( selectedRow() ) ) ;
            return ;
        }
        if( unattended || !canPress( n ) ) return ;
        press( n ) ;
    }

    @Override
    public void press( int n ) {

        List<Row> rows = selectedRows() ;
        switch( n ) {
            case 1 -> markCorrect( rows ) ;
            case 2 -> markFaulty( rows ) ;
            case 3 -> markFaultyPassage( rows ) ;
            case 4 -> edit( rows ) ;
            default -> { }
        }
        updateControls() ;
    }

    @Override
    public boolean canPress( int n ) {

        if( controller == null || unattended
            || checkPanel.isEditing() ) return false ;
        List<Row> rows = selectedRows() ;
        return switch( n ) {
            case 1 -> rows.stream().anyMatch( Row::isMarkable ) ;
            case 2 -> canSend() && rows.stream().anyMatch( Row::isMarkable ) ;
            case 3 -> canSend() && rows.stream().anyMatch(
                    r -> r.item.isLct() && r.isMarkable()
                         && controller.passage( r.item.lctCtxId() ) != null ) ;
            case 4 -> rows.size() == 1
                      && ( rows.get( 0 ).isMarkable()
                           || canType( rows.get( 0 ) ) ) ;
            default -> false ;
        } ;
    }

    /**
     * Key 1: the transcriptions of {@code rows} are correct; an unconfirmed
     * one is accepted. Then on to the next to check.
     */
    private void markCorrect( List<Row> rows ) {

        Row last = null ;
        for( Row row : rows ) {
            if( !row.isMarkable() ) continue ;
            row.staged = new Staged( row.transcription(), row.route(), true,
                                     false ) ;
            if( !row.saved ) {
                row.status = row.restingStatus() ;
                controller.stage( row.item, row.staged ) ;
            }
            controller.mark( row.item, "checked", row.route() ) ;
            rowChanged( row ) ;
            last = row ;
        }
        if( last != null ) select( nextToCheck( last ) ) ;
    }

    /**
     * Key 2: the transcriptions of {@code rows} are faulty: each goes to
     * the model after the one that wrote it; with none left it is Faulty,
     * to edit. The earlier transcription stays until the new one comes.
     */
    private void markFaulty( List<Row> rows ) {

        Map<String,List<TranscriptionItem>> byModel = new LinkedHashMap<>() ;
        Row                                 last    = null ;

        for( Row row : rows ) {
            if( !row.isMarkable() ) continue ;
            String faulty = faultyModel( row.route() ) ;
            String root   = controller.nextRoot( faulty ) ;

            controller.mark( row.item, "faulty", row.route() ) ;
            last = row ;
            if( root == null ) {
                row.status  = TranscriptionStatus.FAULTY ;
                row.message = "Faulty, and no model is left to ask: edit " +
                              "it (key 4) to save it" ;
            }
            else {
                row.status  = TranscriptionStatus.TRANSCRIBING ;
                row.message = "Faulty: sending it to the " + root + " model" ;
                byModel.computeIfAbsent( faulty, k -> new ArrayList<>() )
                       .add( row.item ) ;
            }
            rowChanged( row ) ;
        }
        byModel.forEach( ( m, items ) -> controller.resend( items, m, false ) ) ;
        if( last != null ) select( nextToCheck( last ) ) ;
    }

    /**
     * The model whose transcription {@code route} is, for choosing the
     * next: for an edit, the model whose text was edited.
     */
    private static String faultyModel( TranscriptionRoute route ) {
        return route.model() ;
    }

    /**
     * Key 3: the passages of the LCT groups of {@code rows} are faulty:
     * each goes to the model after the one that wrote it; the group's rows
     * are rebuilt from the new one when it comes.
     */
    private void markFaultyPassage( List<Row> rows ) {

        Map<String,Row> groups = new LinkedHashMap<>() ;
        for( Row row : rows ) {
            if( row.item.isLct() && row.isMarkable() ) {
                groups.putIfAbsent( row.item.lctCtxId(), row ) ;
            }
        }

        for( Row row : groups.values() ) {
            Passage p = controller.passage( row.item.lctCtxId() ) ;
            if( p == null ) continue ;

            String faulty = faultyModel( p.route() ) ;
            String root   = controller.nextRoot( faulty ) ;

            controller.mark( row.item, "faultyPassage", p.route() ) ;
            if( root == null ) {
                showGroupMessage( row.item.lctCtxId(), "The passage is " +
                        "faulty, and no model is left to ask: edit it" ) ;
                continue ;
            }
            showGroupMessage( row.item.lctCtxId(), "Faulty passage: sending " +
                              "it to the " + root + " model" ) ;
            controller.resend( List.of( row.item ), faulty, true ) ;
        }
    }

    private void showGroupMessage( String lctCtxId, String message ) {
        for( Row row : model.group( lctCtxId ) ) {
            row.message = message ;
            rowChanged( row ) ;
        }
    }

    /**
     * True if {@code row}, with no transcription, may be typed by hand:
     * pending or failed, not with a model or being saved, and for an LCT
     * question only once its passage is known (the edit is its part).
     */
    private boolean canType( Row row ) {
        return row.staged == null
               && ( row.status == TranscriptionStatus.PENDING
                    || row.status == TranscriptionStatus.FAILED )
               && ( !row.item.isLct()
                    || controller.passage( row.item.lctCtxId() ) != null ) ;
    }

    /**
     * Key 4: edit the selected row's transcription by hand beside its
     * rendering (for an LCT question, its question part). Done stages the
     * edit, checked, its route {@code Edited} with the model whose text it
     * was; it is saved with {@code aiModel} {@code user}. A row with no
     * transcription (every reply failed) is typed from the last reply the
     * gate refused, else from nothing, route {@code Typed}.
     */
    private void edit( List<Row> rows ) {

        if( rows.size() != 1 ) return ;
        Row                row    = rows.get( 0 ) ;
        Transcription      t      = row.transcription() ;
        TranscriptionRoute before = row.route() ;

        if( t == null ) {
            if( canType( row ) ) type( row ) ;
            return ;
        }
        startEdit( t.content(), false, text -> {
            if( model.rowOf( row.item ) != row ) return ;
            row.staged  = new Staged(
                    Transcription.unscored( text, t.model() ),
                    new TranscriptionRoute( TranscriptionRoute.Kind.EDITED,
                                            before.model(), null ),
                    true, false ) ;
            row.saved   = false ;
            row.status  = row.restingStatus() ;
            row.message = "Edited: " + describe( row ) ;
            controller.stage( row.item, row.staged ) ;
            controller.edited( row.item, "question", t.content(), text ) ;
            rowChanged( row ) ;
            select( nextToCheck( row ) ) ;
        } ) ;
    }

    /**
     * Type {@code row}'s transcription by hand, starting from the last
     * reply the gate refused for it, else from nothing; staged checked,
     * route {@code Typed}, saved with {@code aiModel} {@code user}.
     */
    private void type( Row row ) {

        String rejected = controller.rejectedQuestion( row.item ) ;
        String start    = rejected == null ? "" : rejected ;

        startEdit( start, true, text -> {
            if( model.rowOf( row.item ) != row ) return ;
            row.staged  = new Staged(
                    Transcription.unscored( text, null ),
                    new TranscriptionRoute( TranscriptionRoute.Kind.EDITED,
                                            null, null ),
                    true, false ) ;
            row.saved   = false ;
            row.status  = row.restingStatus() ;
            row.message = "Typed by hand" ;
            controller.stage( row.item, row.staged ) ;
            controller.edited( row.item, "question", start, text ) ;
            rowChanged( row ) ;
            select( nextToCheck( row ) ) ;
        } ) ;
    }

    /**
     * Edit the passage of the selected LCT row's group. Done holds the
     * edited passage, to be saved with the group's next question
     * ({@code lctCtx.aiModel} {@code user}); the group's saved questions
     * become unsaved, to carry it. A passage not known yet (its replies
     * all refused, say) is written from the last reply the gate refused,
     * else from nothing; the group's failed questions then go back to
     * Pending, to be sent against it.
     */
    @Override
    public void editPassage() {

        if( !canEditPassage() ) return ;
        Row     row      = selectedRow() ;
        String  lctCtxId = row.item.lctCtxId() ;
        Passage p        = controller.passage( lctCtxId ) ;

        if( p == null ) {
            String rejected = controller.rejectedPassage( lctCtxId ) ;
            String start    = rejected == null ? "" : rejected ;

            startEdit( start, true, text -> {
                Passage typed = new Passage(
                        lctCtxId, Transcription.unscored( text, null ),
                        new TranscriptionRoute(
                                TranscriptionRoute.Kind.EDITED, null, null ),
                        false ) ;

                controller.holdPassage( typed ) ;
                controller.edited( row.item, "passage", start, text ) ;
                passageKnown( typed ) ;
                for( Row r : model.group( lctCtxId ) ) {
                    if( r.status == TranscriptionStatus.FAILED
                        && r.staged == null ) {
                        r.status = r.restingStatus() ;
                    }
                    r.message = "The passage was written by hand" ;
                    rowChanged( r ) ;
                }
            } ) ;
            return ;
        }
        startEdit( p.content(), false, text -> {
            Passage edited = new Passage(
                    p.lctCtxId(),
                    Transcription.unscored( text, p.transcription().model() ),
                    new TranscriptionRoute( TranscriptionRoute.Kind.EDITED,
                                            p.route().model(), null ),
                    false ) ;

            controller.holdPassage( edited ) ;
            controller.edited( row.item, "passage", p.content(), text ) ;
            passageKnown( edited ) ;
            showGroupMessage( p.lctCtxId(), "The passage was edited" ) ;
        } ) ;
    }

    @Override
    public boolean canEditPassage() {

        if( controller == null || unattended || checkPanel.isEditing()
            || table.getSelectedRowCount() != 1 ) {
            return false ;
        }
        Row row = selectedRow() ;
        if( row == null || !row.item.isLct() ) return false ;

        return model.group( row.item.lctCtxId() ).stream()
                    .noneMatch( r -> r.isBusy()
                                     || r.status == TranscriptionStatus.SAVING ) ;
    }

    /**
     * Open {@code text} in the Check tab's editor; the table is disabled
     * meanwhile, so the selection stays. Done runs {@code keep} with the
     * edited text, if it is well formed and changed, or if
     * {@code keepUnchanged}.
     */
    private void startEdit( String text, boolean keepUnchanged,
                            Consumer<String> keep ) {

        table.setEnabled( false ) ;
        updateControls() ;
        checkPanel.edit( text, new CheckPanel.Editing() {
            @Override public List<String> problems( String edited ) {
                List<String> found = new ArrayList<>(
                        WellFormed.problems( edited, null ) ) ;
                found.addAll( WellFormed.appProblems( edited ) ) ;
                return found ;
            }

            @Override public void done( String edited ) {
                endEdit() ;
                if( keepUnchanged
                    || !edited.strip().equals( text.strip() ) ) {
                    keep.accept( edited.stripTrailing() ) ;
                }
                showSelection() ;
            }

            @Override public void cancelled() {
                endEdit() ;
                showSelection() ;
            }
        } ) ;
    }

    private void endEdit() {
        table.setEnabled( true ) ;
        table.requestFocusInWindow() ;
        updateControls() ;
    }

    /**
     * The next row shown to check after {@code from} in check order,
     * wrapping round; the first if {@code from} is null or not to check;
     * null if none is left.
     */
    private Row nextToCheck( Row from ) {

        List<Row> shown = new ArrayList<>() ;
        for( int view = 0 ; view < table.getRowCount() ; view++ ) {
            Row row = rowAt( view ) ;
            if( row.isMarkable() && row.checkRank() < 9 ) shown.add( row ) ;
        }
        if( shown.isEmpty() ) return null ;

        shown.sort( TranscriberTableModel.CHECK_ORDER ) ;
        int at = from == null ? -1 : shown.indexOf( from ) ;
        if( at < 0 ) return shown.get( 0 ) ;
        return shown.size() == 1 ? null
                                 : shown.get( ( at + 1 ) % shown.size() ) ;
    }

    /**
     * Select only {@code row}, if shown, scroll to it and give the table the
     * focus, so the keys go to it; null leaves the selection.
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

    // ---- saving --------------------------------------------------------

    /** Save every transcribed question, then load again if asked to. */
    private void saveAll() {

        boolean reload = loadAfterSaveBox.isSelected() ;
        save( model.rows(), saveButton, () -> {
            if( reload && pending > 0 && !unattended && !busy() ) load() ;
        } ) ;
    }

    /**
     * Store the transcriptions of {@code rows} the server doesn't hold,
     * then run {@code after} (at once if there are none); {@code button},
     * if not null, spins meanwhile, and the rows being saved spin in their
     * Status cell.
     */
    private void save( List<Row> rows, JButton button, Runnable after ) {

        if( controller == null ) return ;
        List<Row> savable = rows.stream().filter( Row::isSavable ).toList() ;
        if( savable.isEmpty() ) {
            after.run() ;
            return ;
        }

        List<Saving> savings = savable.stream()
                                      .map( r -> new Saving( r.item,
                                              r.transcription(), r.route() ) )
                                      .toList() ;

        for( Row row : savable ) {
            row.status  = TranscriptionStatus.SAVING ;
            row.message = "Saving" ;
            rowChanged( row ) ;
        }
        if( button != null ) Spinner.busy( button ) ;

        new SwingWorker<List<SaveResult>,Void>() {
            @Override protected List<SaveResult> doInBackground() {
                return controller.save( savings ) ;
            }

            @Override protected void done() {

                List<SaveResult> results ;

                if( button != null ) Spinner.idle( button ) ;
                try {
                    results = get() ;
                }
                catch( Exception e ) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e ;
                    log.error( "Could not save {} transcriptions",
                               savings.size(), cause ) ;
                    results = savings.stream()
                                     .map( s -> SaveResult.failed(
                                             s.item().id(),
                                             String.valueOf( cause ) ) )
                                     .toList() ;
                }
                for( int i = 0 ; i < savable.size() ; i++ ) {
                    Row row = savable.get( i ) ;
                    if( model.rowOf( row.item ) != row ) continue ;
                    showResult( row, results.get( i ) ) ;
                    rowChanged( row ) ;
                }
                updateControls() ;
                after.run() ;
            }
        }.execute() ;
    }

    /** Show on {@code row} the server's answer {@code r} to its save. */
    private void showResult( Row row, SaveResult r ) {

        if( r == null || !r.accepted() ) {
            row.status  = TranscriptionStatus.ERROR ;
            row.message = "Not saved: " + ( r != null && r.message() != null
                                            ? r.message()
                                            : "refused by the server" ) ;
            return ;
        }
        if( !row.saved && pending > 0 ) pending-- ;
        row.saved   = true ;
        row.status  = TranscriptionStatus.SAVED ;
        row.message = "Saved: " + describe( row ) ;
    }

    /**
     * True if there are no transcriptions not saved, or the user agrees to
     * leave them; {@code title} names what would. They stay staged in the
     * workspace and come back when the question is loaded again.
     */
    private boolean discardUnsaved( String title ) {

        long n = model.unsavedCount() ;
        if( n == 0 ) return true ;
        return JOptionPane.showConfirmDialog( this,
                plural( (int) n, "transcription" ) + " not saved yet will " +
                "leave the table (they stay staged, and come back when the " +
                "questions are loaded again). Continue?", title,
                JOptionPane.OK_CANCEL_OPTION ) == JOptionPane.OK_OPTION ;
    }

    // ---- the hub -------------------------------------------------------

    /** The Check, Transcripts and Models tabs. */
    private JTabbedPane tabs() {

        JTabbedPane tabs = new JTabbedPane() ;
        tabs.addTab( "Check", checkPanel ) ;
        tabs.addTab( "Transcripts", transcriptPanel ) ;
        tabs.addTab( "Models", modelsPanel ) ;
        tabs.setSelectedComponent( checkPanel ) ;
        tabs.addChangeListener( e -> refreshModels() ) ;
        return tabs ;
    }

    /** Show the hub's state in the Models tab, if it is showing. */
    private void refreshModels() {
        if( controller == null || !modelsPanel.isShowing() ) return ;
        modelsPanel.update( controller.hubStatus() ) ;
    }

    private void setPassthrough( String modelId, boolean on ) {
        if( controller == null ) return ;
        controller.setPassthrough( modelId, on ) ;
        refreshModels() ;
    }

    private void abortRequest( long jobId ) {
        if( controller == null ) return ;
        controller.abortRequest( jobId ) ;
        refreshModels() ;
    }

    /**
     * Show the rate limits in the strip; end an unattended run once Qwen
     * is down and the free Nemotron is held for the day.
     */
    private void refreshRateLimitStrip() {

        List<RateLimitNotice> notices = controller != null
                                      ? controller.rateLimitNotices()
                                      : List.of() ;

        rateLimitStrip.update( notices, rateLimitItem.isSelected() ) ;
        if( !unattended ) return ;

        String why = controller.unattendedStop() ;
        if( why != null ) endUnattended( why ) ;
    }

    /**
     * Show the model queue's counts: green when nothing waits or runs,
     * orange while requests are in flight, pink when jobs wait but none is
     * in flight. Beside them, a countdown to the next request, in red,
     * while jobs wait for the rate limits.
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
        refreshStatusBar() ;
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

    // ---- unattended runs -----------------------------------------------

    /** File > Run unattended, or Stop unattended processing while on. */
    private void toggleUnattended() {
        if( unattended ) endUnattended( "stopped by the user" ) ;
        else startUnattended() ;
    }

    /**
     * Loop until stopped, logging each step: load a batch and send it;
     * each time half the table is transcribed, save those and fetch half a
     * batch more ({@link #refillIfDue}); once the run ends, save the rest
     * and after {@link #UNATTENDED_PAUSE_SECONDS} load the next. Unconfirmed
     * and failed questions are left for the user. The loop ends by itself
     * after its loads, when nothing is pending, on a failed load, when a
     * batch saves nothing, or when Qwen is down and the free Nemotron is
     * held for the day.
     */
    private void startUnattended() {

        if( controller == null || busy() ) return ;
        if( !askMaxLoads() ) return ;
        if( !discardUnsaved( RUN_UNATTENDED ) ) return ;

        unattended = true ;
        judging    = false ;
        refilling  = false ;
        asked      = 0 ;

        unattendedAction.putValue( Action.NAME, STOP_UNATTENDED ) ;
        log.info( "[unattended] Started, at most {} loads of {}", maxLoads,
                  batchSize() ) ;
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
     * End the unattended run, if one is going, saying {@code why}; the
     * model run winds down (its answers still land), a step under way
     * finishes, and the table is left for the user to take over.
     */
    private void endUnattended( String why ) {

        if( !unattended ) return ;
        unattended     = false ;
        judging        = false ;
        refilling      = false ;
        unattendedStep = "ended, " + why ;

        if( pauseTimer != null ) pauseTimer.stop() ;
        pauseTimer = null ;
        if( runState == ProcessingState.RUNNING ) controller.stop() ;
        unattendedAction.putValue( Action.NAME, RUN_UNATTENDED ) ;
        log.info( "[unattended] Ended in batch {}: {}", batch(), why ) ;
        updateControls() ;
    }

    private void nextBatch() {

        if( !unattended ) return ;
        String why = controller.unattendedStop() ;
        if( why != null ) {
            endUnattended( why ) ;
            return ;
        }

        int limit = Math.min( batchSize(), allowance() - asked ) ;
        asked += limit ;
        log.info( "[unattended] Batch {}: loading", batch() ) ;
        step( "loading" ) ;
        fetch( limit, loaded -> {
            showItems( loaded ) ;
            batchLoaded() ;
        } ) ;
    }

    /** The batch is shown: send its pending questions. */
    private void batchLoaded() {

        if( !unattended ) return ;
        drained      = false ;
        savedEarlier = 0 ;
        if( model.getRowCount() == 0 ) {
            endUnattended( "no questions are pending after the cursor" ) ;
            return ;
        }
        judging = true ;
        log.info( "[unattended] Batch {}: {} loaded, {} pending on the " +
                  "server", batch(), model.getRowCount(), pending ) ;
        step( "transcribing " + plural( model.getRowCount(), "question" ) ) ;

        List<Row> rows = tableOrder().stream().filter( Row::isSendable )
                                     .toList() ;
        if( rows.isEmpty() ) checkBatch() ;
        else transcribe( rows ) ;
    }

    /**
     * While the unattended batch is transcribed: refill if due; else, once
     * the run is over, save it whole.
     */
    private void checkBatch() {

        if( !unattended || !judging || refilling || loading
            || sending ) return ;
        if( refillIfDue() ) return ;
        if( runState == ProcessingState.STOPPED
            && !controller.isRunning() ) batchJudged() ;
    }

    /**
     * Once half the table is transcribed, save those questions, drop them
     * from the table and fetch half a batch more, sending them to the run
     * under way, so the models never run dry. Refills count towards the
     * loads; once none is left, or one brings nothing, the batch runs out
     * and is saved whole.
     *
     * @return true if a refill started
     */
    private boolean refillIfDue() {

        int       more = Math.min( Math.max( 1, batchSize() / 2 ),
                                   allowance() - asked ) ;
        List<Row> done = model.rows().stream().filter( Row::isSavable )
                              .toList() ;
        int       half = Math.max( 1, ( model.getRowCount() + 1 ) / 2 ) ;

        if( drained || more <= 0 || done.size() < half
            || runState != ProcessingState.RUNNING ) return false ;

        refilling = true ;
        for( Row row : done ) logRow( row ) ;
        log.info( "[unattended] Batch {}: saving {} transcribed questions " +
                  "while the models work on the rest", batch(), done.size() ) ;
        save( done, saveButton, () -> refillSaved( more ) ) ;
        return true ;
    }

    /** The transcribed questions are saved: drop them, fetch {@code more}. */
    private void refillSaved( int more ) {

        if( !unattended ) return ;
        logErrors() ;
        savedEarlier += model.removeIf( r -> r.saved ) ;
        log.info( "[unattended] Batch {} partly saved: {} saved so far; {} " +
                  "pending", batch(), savedEarlier, pending ) ;

        if( pending == 0 ) {
            drained = true ;
            refillDone() ;
            return ;
        }
        asked += more ;
        log.info( "[unattended] Batch {}: fetching {} more questions",
                  batch(), more ) ;
        step( "transcribing, fetching " + plural( more, "more question" ) ) ;
        fetch( more, this::refillLoaded ) ;
    }

    /** Add the questions of {@code loaded} not in the table, and send them. */
    private void refillLoaded( Loaded loaded ) {

        if( !unattended ) return ;
        List<TranscriptionItem> added = loaded.items().stream()
                                              .filter( i -> !model.has( i.id() ) )
                                              .toList() ;
        List<Row>               rows  = model.addItems( added ) ;

        pending = loaded.pending() ;
        setUp( rows, loaded ) ;
        log.info( "[unattended] Batch {}: {} added", batch(), added.size() ) ;
        if( added.isEmpty() ) drained = true ;
        step( "transcribing " + plural( model.getRowCount(), "question" ) ) ;

        List<Row> send = rows.stream().filter( Row::isSendable ).toList() ;
        refilling = false ;
        if( !send.isEmpty() && controller.isRunning() ) transcribe( send ) ;
        else checkBatch() ;
        updateControls() ;
    }

    /** The refill is over: see whether another is due, or the batch done. */
    private void refillDone() {
        refilling = false ;
        updateControls() ;
        checkBatch() ;
    }

    /** The run is over: save the transcribed questions. */
    private void batchJudged() {

        judging = false ;
        for( Row row : model.rows() ) logRow( row ) ;
        log.info( "[unattended] Batch {}: saving", batch() ) ;
        step( "saving" ) ;
        save( model.rows(), saveButton, this::batchSaved ) ;
    }

    /** Log where {@code row} stands. */
    private void logRow( Row row ) {
        log.info( "[unattended]   {}: {} {}{}", row.questionLabel(),
                  row.status.getLabel(), model.routeText( row ),
                  row.message != null ? " - " + row.message : "" ) ;
    }

    /** Log the rows the server refused. */
    private void logErrors() {
        for( Row row : model.rows() ) {
            if( row.status != TranscriptionStatus.ERROR ) continue ;
            log.warn( "[unattended]   {}: not saved - {}",
                      row.questionLabel(), row.message ) ;
        }
    }

    /**
     * The batch is saved: log the outcome, and pause before the next one,
     * unless nothing is left, nothing was saved, or the loads are used up.
     */
    private void batchSaved() {

        if( !unattended ) return ;
        long saved = model.rows().stream().filter( r -> r.saved ).count()
                     + savedEarlier ;

        logErrors() ;
        log.info( "[unattended] Batch {} saved: {} saved ({} earlier), {} " +
                  "unconfirmed, {} failed, {} errors, {} without images; {} " +
                  "pending", batch(), saved, savedEarlier,
                  model.count( TranscriptionStatus.UNCONFIRMED ),
                  model.count( TranscriptionStatus.FAILED ),
                  model.count( TranscriptionStatus.ERROR ),
                  model.count( TranscriptionStatus.NO_IMAGES ), pending ) ;

        if( pending == 0 ) endUnattended( "no questions are pending" ) ;
        else if( saved == 0 ) endUnattended( "the batch saved nothing" ) ;
        else if( asked >= allowance() ) {
            endUnattended( "all " + maxLoads + " loads done" ) ;
        }
        else pauseBeforeNextBatch() ;
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

    /** The questions a Load fetches: the criteria's batch size. */
    private int batchSize() {
        return controller == null ? 1 : controller.criteria().batchSize() ;
    }

    /** The questions the unattended run may ask for: its loads' worth. */
    private int allowance() {
        return maxLoads * batchSize() ;
    }

    /** The batch the unattended run is in, from 1. */
    private int batch() {
        return Math.max( 1, ( asked + batchSize() - 1 ) / batchSize() ) ;
    }

    /** Show {@code what} the unattended run is doing in the status bar. */
    private void step( String what ) {
        unattendedStep = what ;
        updateControls() ;
    }

    // ---- display -------------------------------------------------------

    private void rowChanged( Row row ) {
        model.changed( row ) ;
        if( table.getSelectedRowCount() == 1 && selectedRow() == row ) {
            showRow( row ) ;
        }
        updateControls() ;
    }

    private void showSelection() {
        Row row = table.getSelectedRowCount() == 1 ? selectedRow() : null ;
        showRow( row ) ;
        if( row != null && row.item.hasImages() ) {
            transcriptPanel.show( row.item.task() ) ;
        }
        updateSelectionActions() ;
    }

    /** Show {@code row} in the detail line and the Check tab; null clears. */
    private void showRow( Row row ) {

        showDetail( row ) ;
        if( row == null ) {
            checkPanel.show( null, null, null, 0, null, null ) ;
            choiceAlts = List.of() ;
            return ;
        }
        Transcription t     = row.transcription() ;
        String        text  = content( row ) ;
        int           shift = text == null || t == null ? 0
                              : text.length() - t.content().length() ;

        checkPanel.show( images( row ), text,
                         t == null ? List.of() : t.contests(), shift,
                         row.status.getLabel() +
                         ( row.message == null ? "" : ": " + row.message ),
                         choices( row ) ) ;
    }

    /**
     * The transcriptions {@code row}'s question was given, as tabs (noted
     * in {@link #choiceAlts}, in the same order): each text once, at its
     * latest, oldest first, and the one in use if it isn't one of them (an
     * edit, a stored one); none if there is only one.
     */
    private List<CheckPanel.Choice> choices( Row row ) {

        Map<String,Alt> byText = new LinkedHashMap<>() ;
        Transcription   cur    = row.transcription() ;

        for( TranscriptionAuditLog.Given g : controller.given( row.item ) ) {
            String text = g.transcription().content() ;
            byText.remove( text ) ;
            byText.put( text, new Alt( g.transcription(), g.route(),
                                       model.shortName( g.transcription()
                                                         .model() ) + " " +
                                       TAB_TIME.format( g.at() ) ) ) ;
        }
        if( cur != null && !byText.containsKey( cur.content() ) ) {
            byText.put( cur.content(),
                        new Alt( cur, row.route(),
                                 model.routeText( row.route() ) ) ) ;
        }

        choiceAlts = List.copyOf( byText.values() ) ;
        List<CheckPanel.Choice> choices = new ArrayList<>() ;
        for( Alt a : choiceAlts ) {
            boolean current = cur != null
                              && a.t().content().equals( cur.content() ) ;
            choices.add( new CheckPanel.Choice(
                    a.label(), whole( row, a.t().content() ), current ) ) ;
        }
        return choices ;
    }

    /**
     * {@code text} as it would be saved for {@code row}: for an LCT
     * question, after its group's passage and the marker.
     */
    private String whole( Row row, String text ) {

        if( !row.item.isLct() ) return text ;
        Passage p = controller.passage( row.item.lctCtxId() ) ;
        return p == null ? text
                         : Transcription.lctContent( p.content(), text ) ;
    }

    /**
     * The Check tab's Use this one: the selected row takes the
     * transcription of tab {@code index} instead, checked, if it is well
     * formed.
     */
    @Override
    public void choose( int index ) {

        Row row = table.getSelectedRowCount() == 1 ? selectedRow() : null ;
        if( row == null || !canChoose() || index >= choiceAlts.size() ) {
            return ;
        }
        Alt          a        = choiceAlts.get( index ) ;
        List<String> problems = new ArrayList<>(
                WellFormed.problems( a.t().content(), null ) ) ;

        problems.addAll( WellFormed.appProblems( a.t().content() ) ) ;
        if( !problems.isEmpty() ) {
            row.message = "Can't use " + a.label() + ": " +
                          String.join( "; ", problems ) ;
            rowChanged( row ) ;
            showDetail( row ) ;
            return ;
        }

        row.staged  = new Staged( a.t(), a.route(), true, false ) ;
        row.saved   = false ;
        row.status  = row.restingStatus() ;
        row.message = "Using " + a.label() + ", checked" ;
        controller.stage( row.item, row.staged ) ;
        controller.mark( row.item, "chose", a.route() ) ;
        rowChanged( row ) ;
        showRow( row ) ;
        updateControls() ;
    }

    @Override
    public boolean canChoose() {

        Row row = table.getSelectedRowCount() == 1 ? selectedRow() : null ;
        return row != null && controller != null && !unattended
               && !checkPanel.isEditing() && row.isMarkable() ;
    }

    /** The whole question's images: an LCT group's passage first. */
    private static List<File> images( Row row ) {

        if( !row.item.hasImages() ) return null ;
        List<File> files = new ArrayList<>( row.item.contextFiles() ) ;
        files.addAll( row.item.task().getImgFiles() ) ;
        return files ;
    }

    /**
     * The row's content as it would be saved: for an LCT question, its
     * group's passage, the marker and its part; null without one.
     */
    private String content( Row row ) {

        Transcription t = row.transcription() ;
        if( t == null ) return null ;
        if( !row.item.isLct() ) return t.content() ;

        Passage p = controller.passage( row.item.lctCtxId() ) ;
        return p == null ? t.content()
                         : Transcription.lctContent( p.content(),
                                                     t.content() ) ;
    }

    /**
     * Show what is going on with {@code row} above the Check tab: its
     * status, route and C, and the latest message; blank for null.
     */
    private void showDetail( Row row ) {

        if( row == null ) {
            detail.setText( "" ) ;
            return ;
        }
        String text = row.questionLabel() + " (" + row.question.getId() +
                      "): " + row.status.getLabel() ;
        if( row.staged != null ) text += "\n" + describe( row ) ;
        if( row.message != null ) text += "\n" + row.message ;
        detail.setText( text ) ;
        detail.setCaretPosition( 0 ) ;
    }

    /** "Qwen → Nemotron, C 2 (why); checked" for a row's transcription. */
    private String describe( Row row ) {

        TranscriptionRoute r = row.route() ;
        if( r == null ) return "" ;

        Transcription t    = row.transcription() ;
        String        text = model.routeText( row ) + ", " + t.describeC() ;
        if( r.reason() != null ) text += " (" + r.reason() + ")" ;
        if( row.staged.provisional() ) text += "; awaiting your check" ;
        if( row.checked() ) text += "; checked" ;
        return text ;
    }

    private Row selectedRow() {
        int view = table.getSelectedRow() ;
        return view < 0 ? null
                        : model.getRow( table.convertRowIndexToModel( view ) ) ;
    }

    private void applyFilter() {

        Show show = (Show) showBox.getSelectedItem() ;
        sorter.setRowFilter( new RowFilter<TranscriberTableModel,Integer>() {
            @Override
            public boolean include( Entry<? extends TranscriberTableModel,
                                          ? extends Integer> e ) {
                Row row = model.getRow( e.getIdentifier() ) ;
                return show.test.test( row ) && headerFilters.accepts( row ) ;
            }
        } ) ;
    }

    /** True while a load, a save or a model run is going. */
    private boolean busy() {
        return runState != ProcessingState.STOPPED || loading || sending
               || saving() || checkPanel.isEditing() ;
    }

    private boolean saving() {
        return model.count( TranscriptionStatus.SAVING ) > 0 ;
    }

    /**
     * True if the user may send questions to the models now: they can be
     * sent ({@link #maySend}) and no unattended run goes, which sends its
     * own.
     */
    private boolean canSend() {
        return maySend() && !unattended ;
    }

    /**
     * True if questions can be sent to the models now, by the user or an
     * unattended run: no load runs and the run isn't stopping.
     */
    private boolean maySend() {
        return controller != null && !loading
               && runState != ProcessingState.STOPPING ;
    }

    private void updateControls() {

        boolean idle    = controller != null && !busy() ;
        boolean manual  = !unattended ;
        boolean savable = model.rows().stream().anyMatch( Row::isSavable ) ;

        criteriaAction.setEnabled( manual && idle ) ;
        loadAction.setEnabled( manual && idle ) ;
        loadFromStartAction.setEnabled( manual && idle ) ;
        spotCheckAction.setEnabled( manual && idle ) ;
        searchAction.setEnabled( manual && idle ) ;
        searchBar.setEnabled( manual && idle ) ;
        unattendedAction.setEnabled( unattended || idle ) ;
        startAction.setEnabled( canSend() && !sending && model.rows().stream()
                                         .anyMatch( Row::isSendable ) ) ;
        stopAction.setEnabled( runState == ProcessingState.RUNNING ) ;
        requeueAction.setEnabled( canSend() && model.rows().stream().anyMatch(
                r -> r.status == TranscriptionStatus.FAILED
                     && isRequeueable( r ) ) ) ;
        // Its icon is red while enabled, i.e. while transcriptions wait.
        saveAction.setEnabled( manual && savable && !saving() && !loading
                               && controller != null ) ;
        updateSelectionActions() ;
        if( saving() ) Spinner.spin( table ) ;
        else Spinner.halt( table ) ;
        if( !loading ) refreshStatusBar() ;
    }

    private void refreshStatusBar() {

        String text = String.format(
                "%d loaded · %d transcribed · %d unconfirmed · %d faulty · " +
                "%d saved · %d failed · %d errors · %d without images",
                model.getRowCount(),
                model.count( TranscriptionStatus.TRANSCRIBED ),
                model.count( TranscriptionStatus.UNCONFIRMED ),
                model.count( TranscriptionStatus.FAULTY ),
                model.count( TranscriptionStatus.SAVED ),
                model.count( TranscriptionStatus.FAILED ),
                model.count( TranscriptionStatus.ERROR ),
                model.count( TranscriptionStatus.NO_IMAGES ) ) ;
        if( pending >= 0 ) text += " · " + pending + " pending on the server" ;
        if( afterId != null ) text += " · next load after id " + afterId ;
        if( searchNote != null ) text = searchNote + "   |   " + text ;
        if( runState == ProcessingState.STOPPING ) text += "   |   stopping" ;

        String down = controller == null ? null : controller.qwenDown() ;
        if( unattendedStep != null ) {
            text = "Unattended, batch " + batch() + " of " + maxLoads + ": " +
                   unattendedStep + "   |   " + text ;
        }
        if( down != null ) {
            statusBar.setText( "<html><font color='#C62828'><b>" + down +
                               "</b></font>   |   " + text + "</html>" ) ;
            return ;
        }
        statusBar.setText( text ) ;
    }

    /**
     * Tints rows by status, spins in the Status cell of a row being saved,
     * and shows the last column as a dot: green once the transcription is
     * saved on the server, red until then.
     */
    private final class RowRenderer extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent( JTable t, Object value,
                boolean selected, boolean focus, int viewRow, int column ) {

            int       col = t.convertColumnIndexToModel( column ) ;
            boolean   dot = col == TranscriberTableModel.COL_SAVED ;

            setForeground( null ) ;
            Component c   = super.getTableCellRendererComponent(
                    t, dot ? null : value, selected, focus, viewRow, column ) ;
            Row       row = model.getRow( t.convertRowIndexToModel( viewRow ) ) ;
            boolean   num = col == TranscriberTableModel.COL_C ;

            setHorizontalAlignment( dot ? CENTER : num ? RIGHT : LEADING ) ;
            if( dot ) setIcon( (Boolean) value ? Dot.SAVED : Dot.UNSAVED ) ;
            else {
                setIcon( col == TranscriberTableModel.COL_STATUS
                         && row.status == TranscriptionStatus.SAVING
                         ? Spinner.ICON : null ) ;
            }
            if( selected ) return c ;
            c.setBackground( STATUS_BG.getOrDefault( row.status,
                                                     t.getBackground() ) ) ;
            return c ;
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
