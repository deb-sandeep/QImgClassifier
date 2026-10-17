package com.sandy.sconsole.qimgtools.apps.topicreviewer.ui;

import com.sandy.sconsole.qimgtools.core.model.AttemptRequest;
import com.sandy.sconsole.qimgtools.core.model.ProcessingState;
import com.sandy.sconsole.qimgtools.core.model.RateLimitNotice;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewCriteria;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewDecision;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewItem;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewResult;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewStatus;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewVerdict;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ServerQuestion;
import com.sandy.sconsole.qimgtools.core.model.Topic;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.review.ReviewListener;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.ui.ReviewTableModel.Row;
import com.sandy.sconsole.qimgtools.core.workspace.TranscriptStore;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.InputMap;
import javax.swing.JTable;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import com.sandy.sconsole.qimgtools.core.ui.ImagePreview;
import com.sandy.sconsole.qimgtools.core.ui.RateLimitStrip;
import com.sandy.sconsole.qimgtools.core.ui.TranscriptPanel;
import com.sandy.sconsole.qimgtools.core.util.ModelNames;

/**
 * The topic reviewer's window. The table lists questions of the server's
 * review queue; Load fetches its first questions in place of those shown,
 * Start sends those not yet judged to the review model, and Stop winds that down. Selecting a row shows its image, its AI
 * transcripts and a {@link ReviewDecisionPanel} to keep the server's topic,
 * use the model's, or choose another, for every selected row. Decisions
 * stay in the table, where they can be changed or cleared, until Save
 * decisions sends them to the server off the EDT, with their provenance
 * from {@link ReviewDecision#of}; it accepts every Agreed question it
 * covers that has no decision. The decision keys and buttons,
 * sending, saving and clearing are also on the table's right-click menu.
 * <p>
 * Implements {@link ReviewListener}; its calls arrive on worker threads and
 * are moved to the EDT, where all row state lives.
 */
@Slf4j
@SuppressWarnings( "serial" )
public class TopicReviewFrame extends JFrame
        implements ReviewListener, ReviewDecisionPanel.Actions {

    /** What the frame needs from the reviewer. */
    public interface Controller {

        /**
         * Fetch the first questions of the review queue matching the
         * criteria in effect and locate each one's images. Blocking;
         * called off the EDT.
         */
        Loaded load() throws Exception ;

        /** The criteria in effect: the last saved, or the configured ones. */
        ReviewCriteria criteria() ;

        /**
         * Use {@code criteria} for the next Load, and save
         * them for the next run.
         *
         * @throws Exception if they could not be saved; they are used anyway
         */
        void setCriteria( ReviewCriteria criteria ) throws Exception ;

        /** The syllabuses that can be reviewed, e.g. "IIT Physics". */
        List<String> syllabusNames() ;

        /**
         * The verdict stored for {@code item} by an earlier run, if the
         * server's topic is unchanged since; null otherwise.
         */
        ReviewVerdict storedVerdict( ReviewItem item ) ;

        /**
         * Send {@code items} to the review model, in order: start a run, or
         * add them to the one going. Ignored while a run is stopping.
         */
        void start( List<ReviewItem> items ) ;

        /** Wind the run down: no new requests, running ones finish. */
        void stop() ;

        /**
         * Send the user's {@code decisions} to the server. Blocking; called
         * off the EDT. A failed request comes back as rejected results.
         *
         * @return the server's result for each decision, in the same order
         */
        List<ReviewResult> confirm( List<ReviewDecision> decisions ) ;

        /** Every topic of syllabus {@code syllabusName}. */
        List<Topic> topics( String syllabusName ) ;

        List<RateLimitNotice> rateLimitNotices() ;

        /** What is reviewed, for the title, e.g. "Physics, Maths". */
        String scope() ;

        void shutdown() ;
    }

    /**
     * The first questions of the review queue, and how many questions
     * matching the criteria await review on the server.
     */
    public record Loaded( List<ReviewItem> items, int flagged ) {}

    private static final double SPLIT_RATIO          = 0.72 ;
    private static final int    PREVIEW_PANEL_HEIGHT = 300 ;
    private static final int    ROW_HEIGHT           = 20 ;

    /** In {@link #COLUMN_WIDTHS}: as wide as a row is high, not resizable. */
    private static final int SQUARE = 0 ;

    /**
     * Preferred width of each table column in pixels, in the order of the
     * columns of {@link ReviewTableModel}; edit to taste.
     */
    private static final int[] COLUMN_WIDTHS = {
             50,    // Id
            140,    // Source
             80,    // Question
            200,    // Server topic
            100,    // Associated by
            200,    // Model's topic
             60,    // Status
            350,    // Result
            SQUARE  // saved dot (untitled)
    } ;

    /** Row background per status; a status not listed keeps the table's. */
    private static final Map<ReviewStatus,Color> STATUS_BG =
            new EnumMap<>( Map.ofEntries(
                    Map.entry( ReviewStatus.QUEUED,        new Color( 0xEEF4FA ) ),
                    Map.entry( ReviewStatus.RUNNING,       new Color( 0xFFFAD9 ) ),
                    Map.entry( ReviewStatus.AGREED,        new Color( 0xE2F1E3 ) ),
                    Map.entry( ReviewStatus.DIFFERS,       new Color( 0xFFE6CC ) ),
                    Map.entry( ReviewStatus.FAILED,        new Color( 0xF9E6E8 ) ),
                    Map.entry( ReviewStatus.NO_IMAGES,     new Color( 0xEEEEEE ) ),
                    Map.entry( ReviewStatus.DECIDED,       new Color( 0xF6E0EA ) ),
                    Map.entry( ReviewStatus.SAVING,        new Color( 0xE8E3F2 ) ),
                    Map.entry( ReviewStatus.KEPT,          new Color( 0xDCEFED ) ),
                    Map.entry( ReviewStatus.OVERRIDDEN,    new Color( 0xD8E8F7 ) ),
                    Map.entry( ReviewStatus.STILL_FLAGGED, new Color( 0xFCEFC5 ) ),
                    Map.entry( ReviewStatus.ERROR,         new Color( 0xF4D2D2 ) ) ) ) ;

    /** Which rows the table shows. */
    private enum Show {

        ALL      ( "All questions",   s -> true ),
        UNDECIDED( "Not yet decided", s -> !s.isDecided() ),
        DIFFERS  ( "Differs",         s -> s == ReviewStatus.DIFFERS ),
        AGREED   ( "Agreed",          s -> s == ReviewStatus.AGREED ),
        UNSAVED  ( "Unsaved",         s -> s == ReviewStatus.DECIDED ),
        PROBLEMS ( "Failed / errors", s -> s == ReviewStatus.FAILED
                                           || s == ReviewStatus.ERROR
                                           || s == ReviewStatus.NO_IMAGES ) ;

        final String                  label ;
        final Predicate<ReviewStatus> test ;

        Show( String label, Predicate<ReviewStatus> test ) {
            this.label = label ;
            this.test  = test ;
        }

        @Override
        public String toString() { return label ; }
    }

    @Setter
    private Controller controller ;

    /** The names models are shown by. */
    private final ModelNames names ;

    private final ReviewTableModel    model ;
    private final JTable              table ;
    private final TableRowSorter<ReviewTableModel> sorter ;
    private final TranscriptPanel     transcriptPanel ;
    private final ReviewDecisionPanel decisionPanel ;
    private final ImagePreview        preview =
            new ImagePreview( PREVIEW_PANEL_HEIGHT ) ;
    private final RateLimitStrip      rateLimitStrip ;
    private final JLabel              statusBar = new JLabel( " " ) ;

    private final JButton   searchButton = new JButton( "Criteria…" ) ;
    private final JButton   loadButton   = new JButton( "Load" ) ;
    private final JButton   startButton  = new JButton( "Start" ) ;
    private final JButton   stopButton   = new JButton( "Stop" ) ;
    private final JButton   saveButton   = new JButton( "Save decisions" ) ;
    private final JButton   clearButton  = new JButton( "Clear decisions" ) ;
    private final JButton   useAllButton =
            new JButton( "Use AI reco for all differing" ) ;
    private final JCheckBox loadAfterSaveBox =
            new JCheckBox( "then load again", true ) ;
    private final JCheckBox rateLimitBox =
            new JCheckBox( "Show rate limit bar", true ) ;
    private final JComboBox<Show> showBox = new JComboBox<>( Show.values() ) ;

    /** Save button text colour while decisions are unsaved, and otherwise. */
    private static final Color UNSAVED_FG = new Color( 0xC62828 ) ;
    private final Color        savedFg    =
            UIManager.getColor( "Button.foreground" ) ;

    private ProcessingState procState = ProcessingState.STOPPED ;
    private boolean         loading ;

    /**
     * Questions awaiting review on the server, as of the last fetch less
     * those confirmed since; -1 before the first Load.
     */
    private int flagged = -1 ;

    /** True once the user was told the review model isn't a strong one. */
    private boolean weakModelWarned ;

    /**
     * @param transcripts source of the transcripts shown on the right
     * @param names       the names models are shown by
     */
    public TopicReviewFrame( TranscriptStore transcripts, ModelNames names ) {

        super( "QTopicReviewer" ) ;
        this.names           = names ;
        this.model           = new ReviewTableModel( names ) ;
        this.table           = new JTable( model ) ;
        this.sorter          = new TableRowSorter<>( model ) ;
        this.transcriptPanel = new TranscriptPanel( transcripts, names ) ;
        this.decisionPanel   = new ReviewDecisionPanel( this, names ) ;
        this.rateLimitStrip  = new RateLimitStrip( names ) ;

        buildTable() ;

        JToolBar toolbar = new JToolBar() ;
        toolbar.setFloatable( false ) ;
        searchButton.setToolTipText( "Choose the syllabuses, topics and " +
                                     "batch size Load uses" ) ;
        loadButton.setToolTipText( "Fetch the first questions awaiting " +
                                   "review that match the criteria " +
                                   "(replaces the table)" ) ;
        startButton.setToolTipText( "Send the pending questions to the " +
                                    "review model (right-click rows to " +
                                    "send those)" ) ;
        saveButton.setToolTipText( "Send the decisions made to the server, " +
                                   "accepting every question the model " +
                                   "agreed with (red while any wait)" ) ;
        useAllButton.setToolTipText( "Use the model's top topic for every " +
                                     "undecided Differs row; nothing is " +
                                     "sent until Save decisions" ) ;
        clearButton.setToolTipText( "Forget the unsaved decisions; the " +
                                    "model's verdicts stay" ) ;
        loadAfterSaveBox.setToolTipText( "After Save decisions, load the " +
                                         "first questions awaiting review " +
                                         "again" ) ;
        searchButton.addActionListener( e -> editCriteria() ) ;
        loadButton.addActionListener( e -> load() ) ;
        startButton.addActionListener( e -> start() ) ;
        stopButton.addActionListener( e -> {
            if( controller != null ) controller.stop() ;
        } ) ;
        saveButton.addActionListener( e -> saveAll() ) ;
        clearButton.addActionListener( e -> clearAll() ) ;
        useAllButton.addActionListener( e -> useModelForDiffering() ) ;
        showBox.addActionListener( e -> {
            applyFilter() ;
            table.requestFocusInWindow() ;
        } ) ;
        rateLimitBox.addActionListener( e -> refreshRateLimitStrip() ) ;

        toolbar.add( searchButton ) ;
        toolbar.add( loadButton ) ;
        toolbar.add( startButton ) ;
        toolbar.add( stopButton ) ;
        toolbar.addSeparator() ;
        toolbar.add( useAllButton ) ;
        toolbar.add( saveButton ) ;
        toolbar.add( loadAfterSaveBox ) ;
        toolbar.add( clearButton ) ;
        toolbar.addSeparator() ;
        toolbar.add( new JLabel( "Show: " ) ) ;
        toolbar.add( showBox ) ;
        toolbar.addSeparator() ;
        toolbar.add( rateLimitBox ) ;

        // Table and preview on the left; decision, transcripts and the rate
        // limit strip on the right.
        JPanel left = new JPanel( new BorderLayout() ) ;
        left.add( new JScrollPane( table ), BorderLayout.CENTER ) ;
        left.add( preview, BorderLayout.SOUTH ) ;

        JPanel right = new JPanel( new BorderLayout() ) ;
        right.add( decisionPanel, BorderLayout.NORTH ) ;
        right.add( transcriptPanel, BorderLayout.CENTER ) ;
        right.add( rateLimitStrip, BorderLayout.SOUTH ) ;

        JSplitPane split = new JSplitPane( JSplitPane.HORIZONTAL_SPLIT,
                                           left, right ) ;
        split.setResizeWeight( SPLIT_RATIO ) ;
        split.setContinuousLayout( true ) ;

        statusBar.setBorder( BorderFactory.createEmptyBorder( 4, 8, 4, 8 ) ) ;
        setLayout( new BorderLayout() ) ;
        add( toolbar, BorderLayout.NORTH ) ;
        add( split, BorderLayout.CENTER ) ;
        add( statusBar, BorderLayout.SOUTH ) ;

        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                                              .getMaximumWindowBounds() ;
        setBounds( screen ) ;
        setExtendedState( MAXIMIZED_BOTH ) ;
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

        new Timer( 1000, e -> refreshRateLimitStrip() ).start() ;
        updateControls() ;
    }

    /** Show what is reviewed in the title. Call once the controller is set. */
    public void showScope() {
        setTitle( "QTopicReviewer - " + controller.scope() ) ;
    }

    private void buildTable() {

        // Re-filter as statuses change, so a decided row leaves "Differs".
        sorter.setSortsOnUpdates( true ) ;
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
        bindDecisionKeys() ;
        bindSelectAll() ;
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
     * it isn't one of them: send them to the model, the decisions of the
     * decision panel, and save or clear their unsaved decisions.
     */
    private void addRowMenu() {

        JPopupMenu  menu   = new JPopupMenu() ;
        JMenuItem   send   = new JMenuItem() ;
        JMenuItem   save   = new JMenuItem() ;
        JMenuItem   clear  = new JMenuItem() ;
        JMenuItem[] decide = {
                new JMenuItem( "1  Use model's topic" ),
                new JMenuItem( "2  Keep server's topic" ),
                new JMenuItem( "3  Choose topic…" ) } ;

        send.addActionListener( e -> send( sendableSelection() ) ) ;
        save.addActionListener( e -> save( selectedRows(), null, () -> {} ) ) ;
        clear.addActionListener( e -> clear( selectedRows() ) ) ;
        menu.add( send ) ;
        menu.addSeparator() ;
        for( int i = 0 ; i < decide.length ; i++ ) {
            int n = i + 1 ;
            decide[i].addActionListener( e -> decisionPanel.press( n ) ) ;
            menu.add( decide[i] ) ;
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
                List<Row> sel     = selectedRows() ;
                int       n       = sendableSelection().size() ;
                int       unsaved = count( sel, Row::isUnsaved ) ;
                int       savable = count( sel, TopicReviewFrame::isSavable ) ;

                send.setText( "Send " + plural( n, "question" ) +
                              " to the model" ) ;
                send.setEnabled( n > 0 && controller != null
                                 && procState != ProcessingState.STOPPING ) ;
                for( int i = 0 ; i < decide.length ; i++ ) {
                    decide[i].setEnabled( decisionPanel.canPress( i + 1 ) ) ;
                }
                save.setText( "Save " + plural( savable, "decision" ) ) ;
                save.setEnabled( savable > 0 && controller != null ) ;
                clear.setText( "Clear " + plural( unsaved, "decision" ) ) ;
                clear.setEnabled( unsaved > 0 ) ;
                menu.show( table, e.getX(), e.getY() ) ;
            }
        } ) ;
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

    /** The selected rows that may be sent to the model, in table order. */
    private List<Row> sendableSelection() {
        return selectedRows().stream()
                             .filter( r -> r.item.hasImages()
                                           && r.status.isRunnable() )
                             .toList() ;
    }

    /**
     * Cmd+A (Ctrl+A off the Mac) anywhere in the window selects every row the
     * filter shows, unless the focused component uses the key itself (a text
     * area selects its text).
     */
    private void bindSelectAll() {

        KeyStroke key = KeyStroke.getKeyStroke( KeyEvent.VK_A,
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() ) ;
        JComponent root = getRootPane() ;

        root.getInputMap( JComponent.WHEN_IN_FOCUSED_WINDOW )
            .put( key, "select-all-rows" ) ;
        root.getActionMap().put( "select-all-rows", new AbstractAction() {
            @Override public void actionPerformed( ActionEvent e ) {
                table.selectAll() ;
                table.requestFocusInWindow() ;
            }
        } ) ;
    }

    /**
     * Keys 1-4 on the table (main row or keypad), for every selected row: 1
     * use the model's topic, 2 keep the server's, 3 choose a topic; 4 skips
     * to the next question that differs.
     */
    private void bindDecisionKeys() {

        InputMap  keys    = table.getInputMap( JComponent.WHEN_FOCUSED ) ;
        ActionMap actions = table.getActionMap() ;

        for( int n = 1 ; n <= 4 ; n++ ) {
            String name = "decision-" + n ;
            int    key  = n ;

            keys.put( KeyStroke.getKeyStroke( KeyEvent.VK_0 + n, 0 ), name ) ;
            keys.put( KeyStroke.getKeyStroke( KeyEvent.VK_NUMPAD0 + n, 0 ), name ) ;
            actions.put( name, new AbstractAction() {
                @Override public void actionPerformed( ActionEvent e ) {
                    if( key == 4 ) select( nextDiffering( List.of() ) ) ;
                    else decisionPanel.press( key ) ;
                }
            } ) ;
        }
    }

    // ---- toolbar actions -----------------------------------------------

    /**
     * Fetch the first questions of the review queue off the EDT and show
     * them in place of the rows loaded so far, which are forgotten.
     */
    private void load() {

        if( controller == null || loading ) return ;
        if( !discardUnsaved( "Load" ) ) return ;

        loading = true ;
        statusBar.setText( "Loading the review queue…" ) ;
        Spinner.busy( loadButton ) ;
        updateControls() ;

        new SwingWorker<Loaded,Void>() {
            @Override protected Loaded doInBackground() throws Exception {
                return controller.load() ;
            }

            @Override protected void done() {

                loading = false ;
                Spinner.idle( loadButton ) ;
                try {
                    showItems( get() ) ;
                }
                catch( Exception e ) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e ;
                    log.error( "Could not load the review queue", cause ) ;
                    JOptionPane.showMessageDialog( TopicReviewFrame.this,
                            "Could not load the review queue:\n" +
                            cause.getMessage(), "Load",
                            JOptionPane.ERROR_MESSAGE ) ;
                }
                updateControls() ;
            }
        }.execute() ;
    }

    /**
     * Fill the table with {@code loaded}, with the verdicts stored by
     * earlier runs; the rows shown before are dropped.
     */
    private void showItems( Loaded loaded ) {

        flagged = loaded.flagged() ;
        model.setItems( loaded.items() ) ;
        decisionPanel.show( List.of() ) ;
        preview.show( null ) ;
        for( ReviewItem item : loaded.items() ) {
            Row row = model.rowOf( item ) ;
            if( row == null || !item.hasImages() ) continue ;
            ReviewVerdict v = controller.storedVerdict( item ) ;
            if( v == null ) continue ;
            setVerdict( row, v ) ;
            model.changed( row ) ;
        }
        updateControls() ;
        // Every question already judged: start on the first that differs.
        if( model.rows().stream().noneMatch( TopicReviewFrame::isPending ) ) {
            selectFirstDiffering() ;
        }
    }

    /**
     * Let the user change the criteria Load uses; nothing is fetched until
     * it is pressed.
     */
    private void editCriteria() {

        if( controller == null ) return ;
        ReviewCriteria chosen = ReviewCriteriaDialog.ask( this,
                controller.criteria(), controller.syllabusNames(),
                controller::topics ) ;
        if( chosen == null ) return ;

        try {
            controller.setCriteria( chosen ) ;
            statusBar.setText( "Criteria saved: " + chosen.describe() + ", " +
                               chosen.limit() + " per fetch - press Load " +
                               "to fetch them" ) ;
        }
        catch( Exception e ) {
            log.error( "Could not save the review criteria", e ) ;
            JOptionPane.showMessageDialog( this,
                    "The criteria are used, but could not be saved for the " +
                    "next run:\n" + e.getMessage(), "Criteria",
                    JOptionPane.WARNING_MESSAGE ) ;
        }
        showScope() ;
    }

    /** Send every pending row (with images, never sent) to the model. */
    private void start() {

        List<Row> rows = model.rows().stream()
                              .filter( TopicReviewFrame::isPending )
                              .toList() ;
        if( rows.isEmpty() ) {
            statusBar.setText( "Nothing to send to the model" ) ;
            return ;
        }
        send( rows ) ;
    }

    private static boolean isPending( Row row ) {
        return row.item.hasImages() && row.status == ReviewStatus.PENDING ;
    }

    /**
     * Queue {@code rows} for the model; a verdict they have stays till then,
     * an unsaved decision is dropped.
     */
    private void send( List<Row> rows ) {

        if( controller == null || rows.isEmpty() ) return ;
        for( Row row : rows ) {
            row.status   = ReviewStatus.QUEUED ;
            row.result   = null ;
            row.decision = null ;
        }
        model.changedAll() ;
        decisionPanel.show( selectedRows() ) ;
        controller.start( rows.stream().map( r -> r.item ).toList() ) ;
    }

    /**
     * True if Save decisions sends {@code row}: it has an unsaved decision,
     * or the model agreed with it and the user made none (it is accepted).
     */
    private static boolean isSavable( Row row ) {
        return row.isUnsaved() || row.status == ReviewStatus.AGREED ;
    }

    /**
     * Send every unsaved decision, accepting the Agreed rows, then load
     * again if asked to.
     */
    private void saveAll() {

        boolean reload = loadAfterSaveBox.isSelected() ;
        save( model.rows(), saveButton, () -> {
            // Loading replaces the rows, so not while the model has some.
            if( reload && flagged > 0
                && procState == ProcessingState.STOPPED ) {
                load() ;
            }
        } ) ;
    }

    /**
     * Send the unsaved decisions of {@code rows} to the server, then run
     * {@code after}; {@code button}, if not null, spins meanwhile. Each
     * Agreed row among them is first decided as accepted: its server topic,
     * the model's best, so it is sent as the model's.
     */
    private void save( List<Row> rows, JButton button, Runnable after ) {

        if( controller == null ) return ;
        for( Row row : rows ) {
            if( row.status != ReviewStatus.AGREED ) continue ;
            decide( row, ReviewDecision.of( row.item, row.verdict.top(),
                                            row.verdict ) ) ;
        }

        List<ReviewDecision> decisions = rows.stream()
                                             .filter( Row::isUnsaved )
                                             .map( r -> r.decision )
                                             .toList() ;
        if( decisions.isEmpty() ) return ;
        confirm( decisions, button, after ) ;
    }

    /** After confirmation, forget every unsaved decision. */
    /**
     * Give every undecided Differs row the model's best topic (staged, as
     * key 1 would).
     */
    private void useModelForDiffering() {
        useModel( differingUndecided(), null ) ;
    }

    /** The Differs rows that may still be given the model's topic. */
    private List<Row> differingUndecided() {
        return model.rows().stream()
                    .filter( r -> r.status == ReviewStatus.DIFFERS
                                  && r.canUseModel() )
                    .toList() ;
    }

    private void clearAll() {

        long n = model.unsavedCount() ;
        if( n == 0 ) return ;

        int answer = JOptionPane.showConfirmDialog( this,
                "Clear the " + plural( (int) n, "unsaved decision" ) +
                "?\nThe model's verdicts stay.", "Clear decisions",
                JOptionPane.OK_CANCEL_OPTION ) ;
        if( answer == JOptionPane.OK_OPTION ) clear( model.rows() ) ;
    }

    /**
     * Forget the unsaved decisions of {@code rows}: each goes back to what
     * it was before, with its verdict.
     */
    private void clear( List<Row> rows ) {

        for( Row row : rows ) {
            if( !row.isUnsaved() ) continue ;
            row.status   = row.undecidedStatus ;
            row.result   = row.undecidedResult ;
            row.decision = null ;

            model.changed( row ) ;
        }
        decisionPanel.show( selectedRows() ) ;
        updateControls() ;
    }

    /**
     * True if there are no unsaved decisions, or the user agrees to lose
     * them; {@code title} names what would lose them.
     */
    private boolean discardUnsaved( String title ) {

        long n = model.unsavedCount() ;
        if( n == 0 ) return true ;
        return JOptionPane.showConfirmDialog( this,
                plural( (int) n, "decision" ) + " not saved yet will be " +
                "lost. Continue?", title, JOptionPane.OK_CANCEL_OPTION )
               == JOptionPane.OK_OPTION ;
    }

    // ---- decisions -----------------------------------------------------

    @Override
    public void useModel( List<Row> rows, Topic topic ) {
        decide( rows, Row::canUseModel,
                r -> topic != null ? topic : r.verdict.top() ) ;
    }

    @Override
    public void keep( List<Row> rows ) {
        decide( rows, Row::canKeep, this::serverTopic ) ;
    }

    /**
     * Decide, for each of {@code rows} that {@code applies} to, on the topic
     * {@code topicOf} gives it; the selection then moves on to the next row
     * that differs. Every decision made with the keys, the decision panel
     * or the row menu ends here.
     */
    private void decide( List<Row> rows, Predicate<Row> applies,
                         Function<Row,Topic> topicOf ) {

        List<Row> chosen = rows.stream().filter( applies ).toList() ;
        if( chosen.isEmpty() ) return ;
        Row next = nextDiffering( chosen ) ;

        for( Row row : chosen ) {
            Topic topic = topicOf.apply( row ) ;
            if( topic != null ) {
                decide( row, ReviewDecision.of( row.item, topic, row.verdict ) ) ;
            }
        }
        select( next ) ;
        decisionPanel.show( selectedRows() ) ;
        updateControls() ;
    }

    /**
     * Note decision {@code d} on {@code row}, unsaved, replacing any unsaved
     * one; what the row was before it is kept for {@link #clear}.
     */
    private void decide( Row row, ReviewDecision d ) {

        if( row.decision == null ) {
            row.undecidedStatus = row.status ;
            row.undecidedResult = row.result ;
        }
        row.decision = d ;
        row.status   = ReviewStatus.DECIDED ;
        row.result   = describe( d ) ;

        model.changed( row ) ;
    }

    /**
     * The topic decision {@code d} gives, tagged with whose it is: "[ai]
     * Work" for the model's top pick, "[server] Kinematics" for keeping the
     * server's topic against the model, "[user] Work" for any other choice.
     */
    private static String describe( ReviewDecision d ) {

        String by = d.isAI()        ? "ai"
                  : d.keepsTopic() ? "server"
                  :                  "user" ;

        return "[" + by + "] " + d.topic().getTopicName() ;
    }

    /**
     * Let the user pick one topic of the rows' syllabus, for all of the open
     * ones, from a list tall enough to show them all; for a single row the
     * server's and the model's topics are marked. Enter or a double-click
     * picks. Rows of different syllabuses can't share a topic.
     */
    @Override
    public void chooseTopic( List<Row> rows ) {

        List<Row> open = rows.stream().filter( Row::isOpen ).toList() ;
        if( open.isEmpty() ) return ;

        Set<String> syllabuses = open.stream()
                                     .map( r -> r.question.getSyllabusName() )
                                     .collect( Collectors.toSet() ) ;
        if( syllabuses.size() > 1 ) {
            JOptionPane.showMessageDialog( this,
                    "The selected questions are of " + syllabuses.size() +
                    " syllabuses.\nChoose a topic for each syllabus " +
                    "separately.", "Choose topic",
                    JOptionPane.INFORMATION_MESSAGE ) ;
            return ;
        }

        Row         row    = open.size() == 1 ? open.get( 0 ) : null ;
        List<Topic> topics = controller.topics( syllabuses.iterator().next() ) ;

        if( topics.isEmpty() ) return ;

        Map<Integer,Integer> modelPct = new HashMap<>() ;
        if( row != null && row.verdict != null ) {
            row.verdict.topics().forEach( t -> modelPct.put(
                    t.getId(), Math.round( t.getMatchProbability() * 100 ) ) ) ;
        }
        int serverId = row == null || row.question.getTopicId() == null
                     ? -1 : row.question.getTopicId() ;

        JList<Topic> list = new JList<>( topics.toArray( new Topic[0] ) ) ;
        list.setSelectionMode( ListSelectionModel.SINGLE_SELECTION ) ;
        list.setVisibleRowCount( topics.size() ) ;
        list.setCellRenderer( new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> l, Object value, int index,
                    boolean selected, boolean focus ) {

                Topic         t    = (Topic) value ;
                StringBuilder text = new StringBuilder( t.getSection() )
                        .append( "  ·  " ).append( t.getTopicName() ) ;

                if( t.getId() == serverId ) text.append( "   [server]" ) ;
                Integer pct = modelPct.get( t.getId() ) ;
                if( pct != null ) text.append( "   [model " + pct + "%]" ) ;
                return super.getListCellRendererComponent(
                        l, text.toString(), index, selected, focus ) ;
            }
        } ) ;

        Topic first = row != null && row.verdict != null
                      && !row.verdict.agreesWith( serverId )
                    ? row.verdict.top() : null ;
        for( int i = 0 ; i < topics.size() && row != null ; i++ ) {
            int id = topics.get( i ).getId() ;
            if( first != null ? id == first.getId() : id == serverId ) {
                list.setSelectedIndex( i ) ;
            }
        }

        JOptionPane pane = new JOptionPane( new JScrollPane( list ),
                JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION ) ;
        JDialog dialog = pane.createDialog( this, "Topic for " +
                ( row != null ? row.questionLabel()
                              : plural( open.size(), "question" ) ) ) ;

        list.addMouseListener( new MouseAdapter() {
            @Override public void mouseClicked( MouseEvent e ) {
                if( e.getClickCount() == 2 ) pane.setValue( JOptionPane.OK_OPTION ) ;
            }
        } ) ;
        list.getInputMap().put( KeyStroke.getKeyStroke( KeyEvent.VK_ENTER, 0 ),
                                "pick" ) ;
        list.getActionMap().put( "pick", new AbstractAction() {
            @Override public void actionPerformed( ActionEvent e ) {
                pane.setValue( JOptionPane.OK_OPTION ) ;
            }
        } ) ;
        dialog.addWindowListener( new WindowAdapter() {
            @Override public void windowOpened( WindowEvent e ) {
                list.requestFocusInWindow() ;
                list.ensureIndexIsVisible( Math.max( list.getSelectedIndex(), 0 ) ) ;
            }
        } ) ;
        dialog.setVisible( true ) ;
        dialog.dispose() ;

        Topic chosen = list.getSelectedValue() ;
        if( !Integer.valueOf( JOptionPane.OK_OPTION ).equals( pane.getValue() )
            || chosen == null ) return ;
        decide( open, r -> true, r -> chosen ) ;
    }

    /**
     * The next row shown, after the last one selected and wrapping round,
     * that differs; failing that, the next one agreed or refused by the
     * server; null if there is none. Rows in {@code skip} don't count.
     */
    private Row nextDiffering( List<Row> skip ) {

        int count    = table.getRowCount() ;
        int from     = table.getSelectionModel().getMaxSelectionIndex() ;
        Row fallback = null ;

        for( int k = 1 ; k <= count ; k++ ) {
            Row row = rowAt( ( from + k ) % count ) ;
            if( skip.contains( row ) ) continue ;
            if( row.status == ReviewStatus.DIFFERS ) return row ;
            if( fallback == null && ( row.status == ReviewStatus.AGREED
                                      || row.status == ReviewStatus.ERROR ) ) {
                fallback = row ;
            }
        }
        return fallback ;
    }

    /** Select the first row shown that differs, if any. */
    private void selectFirstDiffering() {

        for( int view = 0 ; view < table.getRowCount() ; view++ ) {
            Row row = rowAt( view ) ;
            if( row.status == ReviewStatus.DIFFERS ) {
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
     * The row's server topic, from its syllabus' topic list; null if it has
     * none.
     */
    private Topic serverTopic( Row row ) {

        Integer id = row.question.getTopicId() ;
        if( id == null ) return null ;
        return controller.topics( row.question.getSyllabusName() ).stream()
                         .filter( t -> t.getId() == id )
                         .findFirst()
                         .orElseGet( () -> {
                             Topic t = new Topic() ;
                             t.setId( id ) ;
                             t.setTopicName( row.question.getTopicName() ) ;
                             return t ;
                         } ) ;
    }

    /**
     * Send {@code decisions} off the EDT, then show the server's answers and
     * run {@code after}. {@code button}, if not null, spins meanwhile; the
     * rows being saved spin in their Status cell either way.
     */
    private void confirm( List<ReviewDecision> decisions, JButton button,
                          Runnable after ) {

        for( ReviewDecision d : decisions ) {
            Row row = model.rowOf( d.item() ) ;
            if( row != null ) setSaving( row ) ;
        }
        if( button != null ) Spinner.busy( button ) ;

        new SwingWorker<List<ReviewResult>,Void>() {
            @Override protected List<ReviewResult> doInBackground() {
                return controller.confirm( decisions ) ;
            }

            @Override protected void done() {

                List<ReviewResult> results ;

                if( button != null ) Spinner.idle( button ) ;
                try {
                    results = get() ;
                }
                catch( Exception e ) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e ;
                    log.error( "Could not confirm {} decisions",
                               decisions.size(), cause ) ;
                    results = decisions.stream()
                                       .map( d -> ReviewResult.failed(
                                               d.item().id(),
                                               String.valueOf( cause ) ) )
                                       .toList() ;
                }
                for( int i = 0 ; i < decisions.size() ; i++ ) {
                    Row row = model.rowOf( decisions.get( i ).item() ) ;
                    if( row == null ) continue ;
                    showResult( row, decisions.get( i ), results.get( i ) ) ;
                    rowChanged( row ) ;
                }
                warnIfWeakModel( decisions, results ) ;
                after.run() ;
            }
        }.execute() ;
    }

    /**
     * Show on {@code row} the server's answer {@code r} to decision
     * {@code d}. The server doesn't send the question back, so a decision it
     * took is applied to the row's copy here.
     */
    private void showResult( Row row, ReviewDecision d, ReviewResult r ) {

        // A refused decision stays, to save again or clear.
        if( !r.accepted() ) {
            row.status = ReviewStatus.ERROR ;
            row.result = r.message() != null ? r.message()
                                             : "refused by the server" ;
            return ;
        }

        ServerQuestion q     = row.question ;
        boolean        keeps = d.keepsTopic() ;

        row.result   = describe( d ) ;
        row.decision = null ;

        if( q.isTopicReviewNeeded() && !r.stillFlagged() && flagged > 0 ) {
            flagged-- ;
        }
        q.setTopicId( d.topic().getId() ) ;
        q.setTopicName( d.topic().getTopicName() ) ;
        q.setTopicAssocActor( d.actor() ) ;
        q.setTopicAssocAiModel( d.model() ) ;
        q.setTopicAssocConfidence( d.confidence() ) ;
        q.setTopicReviewNeeded( r.stillFlagged() ) ;

        if( r.stillFlagged() ) {
            row.status  = ReviewStatus.STILL_FLAGGED ;
            row.result += " - still flagged on the server" ;
        }
        else {
            row.status = keeps ? ReviewStatus.KEPT : ReviewStatus.OVERRIDDEN ;
        }
    }

    /**
     * Once a session: explain why a decision sent as the model's left its
     * question flagged.
     */
    private void warnIfWeakModel( List<ReviewDecision> decisions,
                                  List<ReviewResult> results ) {

        if( weakModelWarned ) return ;
        for( int i = 0 ; i < decisions.size() ; i++ ) {
            ReviewDecision d = decisions.get( i ) ;
            if( !d.isAI() || !results.get( i ).accepted()
                || !results.get( i ).stillFlagged() ) continue ;

            weakModelWarned = true ;
            JOptionPane.showMessageDialog( this,
                    names.of( d.model() ) +
                    " is not in the server's strong-model list " +
                    "(NVP TopicReview / strongModels),\nso the questions it " +
                    "reviewed stay flagged and come back in the next fetch.",
                    "Still flagged", JOptionPane.WARNING_MESSAGE ) ;
            return ;
        }
    }

    private void setSaving( Row row ) {
        row.status = ReviewStatus.SAVING ;
        row.result = null ;
        rowChanged( row ) ;
    }

    // ---- ReviewListener (worker threads) -------------------------------

    @Override
    public void itemStarted( ReviewItem item ) {
        onRow( item, row -> row.status = ReviewStatus.RUNNING ) ;
    }

    @Override
    public void itemAttempt( ReviewItem item, String model, int attempt ) {
        onRow( item, row -> {
            row.status   = ReviewStatus.RUNNING ;
            row.model    = model ;
            row.attempts = attempt ;
        } ) ;
    }

    @Override
    public void requestSent( AttemptRequest request ) {
        SwingUtilities.invokeLater( () -> transcriptPanel.requestSent( request ) ) ;
    }

    @Override
    public void attemptFinished( ReviewItem item ) {
        SwingUtilities.invokeLater(
                () -> transcriptPanel.attemptFinished( item.task() ) ) ;
    }

    @Override
    public void verdictReached( ReviewItem item, ReviewVerdict verdict ) {
        onRow( item, row -> setVerdict( row, verdict ) ) ;
    }

    @Override
    public void itemFailed( ReviewItem item, int attempts, String reason ) {
        onRow( item, row -> {
            row.status   = ReviewStatus.FAILED ;
            row.attempts = attempts ;
            row.result   = reason ;
        } ) ;
    }

    @Override
    public void processingStateChanged( ProcessingState state ) {

        SwingUtilities.invokeLater( () -> {
            procState = state ;
            if( state == ProcessingState.STOPPED ) {
                // Rows never started go back to what they were: unjudged,
                // or judged before they were sent again.
                for( Row row : model.rows() ) {
                    if( row.status != ReviewStatus.QUEUED ) continue ;
                    if( row.verdict != null ) setVerdict( row, row.verdict ) ;
                    else row.status = ReviewStatus.PENDING ;
                }
                model.changedAll() ;
                // Only a selection the user hasn't made is moved.
                if( table.getSelectedRowCount() == 0 ) selectFirstDiffering() ;
            }
            updateControls() ;
        } ) ;
    }

    /** On the EDT: apply {@code update} to {@code item}'s row, if loaded. */
    private void onRow( ReviewItem item,
                        Consumer<Row> update ) {

        SwingUtilities.invokeLater( () -> {
            Row row = model.rowOf( item ) ;
            if( row == null ) return ;
            update.accept( row ) ;
            rowChanged( row ) ;
        } ) ;
    }

    /** Record {@code verdict} on {@code row}, unless it is already decided. */
    private void setVerdict( Row row, ReviewVerdict verdict ) {

        row.verdict = verdict ;
        if( row.status.isDecided() ) return ;
        row.status = verdict.agreesWith( row.question.getTopicId() )
                   ? ReviewStatus.AGREED : ReviewStatus.DIFFERS ;
        row.result = null ;
    }

    // ---- display -------------------------------------------------------

    private void rowChanged( Row row ) {
        model.changed( row ) ;
        decisionPanel.refresh( row ) ;
        updateControls() ;
    }

    private void showSelection() {

        Row row = selectedRow() ;
        decisionPanel.show( selectedRows() ) ;
        preview.show( row != null && row.item.hasImages()
                      ? row.item.task().getImgFiles() : null ) ;
        if( row != null && row.item.hasImages() ) {
            transcriptPanel.showIfOther( row.item.task() ) ;
        }
    }

    private Row selectedRow() {
        int view = table.getSelectedRow() ;
        return view < 0 ? null
                        : model.getRow( table.convertRowIndexToModel( view ) ) ;
    }

    private void applyFilter() {

        Show show = (Show) showBox.getSelectedItem() ;
        sorter.setRowFilter( new RowFilter<ReviewTableModel,Integer>() {
            @Override
            public boolean include(
                    Entry<? extends ReviewTableModel,? extends Integer> e ) {
                return show.test.test( model.getRow( e.getIdentifier() ).status ) ;
            }
        } ) ;
    }

    private void updateControls() {

        boolean stopped = procState == ProcessingState.STOPPED ;
        boolean saving  = model.count( ReviewStatus.SAVING ) > 0 ;
        long    unsaved = model.unsavedCount() ;
        boolean savable = model.rows().stream()
                               .anyMatch( TopicReviewFrame::isSavable ) ;

        loadButton.setEnabled( stopped && !loading && !saving ) ;
        startButton.setEnabled( stopped && !loading
                                && model.rows().stream().anyMatch(
                                        TopicReviewFrame::isPending ) ) ;
        stopButton.setEnabled( procState == ProcessingState.RUNNING ) ;
        saveButton.setEnabled( savable && controller != null ) ;
        saveButton.setForeground( savable ? UNSAVED_FG : savedFg ) ;
        saveButton.setFont( saveButton.getFont().deriveFont(
                savable ? Font.BOLD : Font.PLAIN ) ) ;
        clearButton.setEnabled( unsaved > 0 ) ;
        useAllButton.setEnabled( !differingUndecided().isEmpty() ) ;
        if( saving ) Spinner.spin( table ) ;
        else Spinner.halt( table ) ;
        if( !loading ) refreshStatusBar() ;
    }

    private void refreshStatusBar() {

        String text = String.format(
                "%d loaded · %d differ · %d agreed · %d unsaved · " +
                "%d kept · %d overridden · %d still flagged · %d failed · " +
                "%d without images · %d errors",
                model.getRowCount(),
                model.count( ReviewStatus.DIFFERS ),
                model.count( ReviewStatus.AGREED ),
                model.unsavedCount(),
                model.count( ReviewStatus.KEPT ),
                model.count( ReviewStatus.OVERRIDDEN ),
                model.count( ReviewStatus.STILL_FLAGGED ),
                model.count( ReviewStatus.FAILED ),
                model.count( ReviewStatus.NO_IMAGES ),
                model.count( ReviewStatus.ERROR ) ) ;
        if( flagged >= 0 ) {
            text += " · " + flagged + " awaiting review on the server" ;
        }
        if( procState != ProcessingState.STOPPED ) {
            text += "   |   " + ( procState == ProcessingState.RUNNING
                                  ? "running" : "stopping" ) + ": " +
                    model.count( ReviewStatus.RUNNING ) + " running, " +
                    model.count( ReviewStatus.QUEUED ) + " queued" ;
        }
        statusBar.setText( text ) ;
    }

    private void refreshRateLimitStrip() {
        rateLimitStrip.update( controller != null ? controller.rateLimitNotices()
                                                  : List.of(),
                               rateLimitBox.isSelected() ) ;
    }

    /**
     * Tints rows by status, spins in the Status cell of a row being saved on
     * the server, and shows the last column as a dot: green once the
     * decision is saved on the server, red until then.
     */
    private final class RowRenderer extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent( JTable t, Object value,
                boolean selected, boolean focus, int viewRow, int column ) {

            int       col  = t.convertColumnIndexToModel( column ) ;
            boolean   dot  = col == ReviewTableModel.COL_SAVED ;
            Component c    = super.getTableCellRendererComponent(
                    t, dot ? null : value, selected, focus, viewRow, column ) ;
            Row       row  = model.getRow( t.convertRowIndexToModel( viewRow ) ) ;
            boolean   spin = row.status == ReviewStatus.SAVING
                             && col == ReviewTableModel.COL_STATUS ;

            setHorizontalAlignment( dot ? CENTER : LEADING ) ;
            if( dot ) {
                setIcon( (Boolean) value ? Dot.SAVED : Dot.UNSAVED ) ;
            }
            else {
                setIcon( spin ? Spinner.ICON : null ) ;
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
