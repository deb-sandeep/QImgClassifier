package com.sandy.sconsole.qimgclassifier.ui;

import com.sandy.sconsole.qimgclassifier.model.AttemptRequest;
import com.sandy.sconsole.qimgclassifier.model.ProcessingState;
import com.sandy.sconsole.qimgclassifier.model.RateLimitNotice;
import com.sandy.sconsole.qimgclassifier.model.ReviewCriteria;
import com.sandy.sconsole.qimgclassifier.model.ReviewDecision;
import com.sandy.sconsole.qimgclassifier.model.ReviewItem;
import com.sandy.sconsole.qimgclassifier.model.ReviewResult;
import com.sandy.sconsole.qimgclassifier.model.ReviewStatus;
import com.sandy.sconsole.qimgclassifier.model.ReviewVerdict;
import com.sandy.sconsole.qimgclassifier.model.ServerQuestion;
import com.sandy.sconsole.qimgclassifier.model.Topic;
import com.sandy.sconsole.qimgclassifier.review.ReviewListener;
import com.sandy.sconsole.qimgclassifier.ui.ReviewTableModel.Row;
import com.sandy.sconsole.qimgclassifier.workspace.TranscriptStore;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
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
import javax.swing.WindowConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
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
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The topic reviewer's window. The table lists questions of the server's
 * review queue; Load fetches its first questions, Fetch more adds those
 * that have moved up since, Start sends those not yet judged to the review
 * model, and Stop winds that down. Selecting a row shows its image, its AI
 * transcripts and a {@link ReviewDecisionPanel} to keep the server's topic,
 * use the model's, or choose another; "Accept all agreed" confirms every
 * question the model agreed with. Decisions go to the server off the EDT,
 * with their provenance from {@link ReviewDecision#of}.
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
         * criteria in effect and locate each one's images: all of them if
         * {@code more} is false, else only those not loaded yet. Blocking;
         * called off the EDT.
         */
        Loaded load( boolean more ) throws Exception ;

        /** The criteria in effect: the last saved, or the configured ones. */
        ReviewCriteria criteria() ;

        /**
         * Use {@code criteria} for the next Load and Fetch more, and save
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

        /** Send {@code items} to the review model, in order. */
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
     * Questions of the review queue not loaded before, and how many questions
     * matching the criteria await review on the server.
     */
    public record Loaded( List<ReviewItem> items, int flagged ) {}

    private static final double SPLIT_RATIO          = 0.72 ;
    private static final int    PREVIEW_PANEL_HEIGHT = 300 ;

    /** Row background per status; a status not listed keeps the table's. */
    private static final Map<ReviewStatus,Color> STATUS_BG =
            new EnumMap<>( Map.ofEntries(
                    Map.entry( ReviewStatus.QUEUED,        new Color( 0xE3F2FD ) ),
                    Map.entry( ReviewStatus.RUNNING,       new Color( 0xFFF59D ) ),
                    Map.entry( ReviewStatus.AGREED,        new Color( 0xC8E6C9 ) ),
                    Map.entry( ReviewStatus.DIFFERS,       new Color( 0xFFCC80 ) ),
                    Map.entry( ReviewStatus.FAILED,        new Color( 0xFFCDD2 ) ),
                    Map.entry( ReviewStatus.NO_IMAGES,     new Color( 0xE0E0E0 ) ),
                    Map.entry( ReviewStatus.SAVING,        new Color( 0xD1C4E9 ) ),
                    Map.entry( ReviewStatus.KEPT,          new Color( 0xB2DFDB ) ),
                    Map.entry( ReviewStatus.OVERRIDDEN,    new Color( 0x90CAF9 ) ),
                    Map.entry( ReviewStatus.STILL_FLAGGED, new Color( 0xFFE082 ) ),
                    Map.entry( ReviewStatus.ERROR,         new Color( 0xEF9A9A ) ) ) ) ;

    /** Which rows the table shows. */
    private enum Show {

        ALL      ( "All questions",   s -> true ),
        UNDECIDED( "Not yet decided", s -> !s.isDecided() ),
        DIFFERS  ( "Differs",         s -> s == ReviewStatus.DIFFERS ),
        AGREED   ( "Agreed",          s -> s == ReviewStatus.AGREED ),
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

    private final ReviewTableModel    model = new ReviewTableModel() ;
    private final JTable              table = new JTable( model ) ;
    private final TableRowSorter<ReviewTableModel> sorter =
            new TableRowSorter<>( model ) ;
    private final TranscriptPanel     transcriptPanel ;
    private final ReviewDecisionPanel decisionPanel ;
    private final ImagePreview        preview =
            new ImagePreview( PREVIEW_PANEL_HEIGHT ) ;
    private final RateLimitStrip      rateLimitStrip = new RateLimitStrip() ;
    private final JLabel              statusBar = new JLabel( " " ) ;

    private final JButton   searchButton = new JButton( "Criteria…" ) ;
    private final JButton   loadButton   = new JButton( "Load" ) ;
    private final JButton   moreButton   = new JButton( "Fetch more" ) ;
    private final JButton   startButton  = new JButton( "Start" ) ;
    private final JButton   stopButton   = new JButton( "Stop" ) ;
    private final JButton   acceptButton = new JButton( "Accept all agreed" ) ;
    private final JCheckBox fetchAfterAcceptBox =
            new JCheckBox( "then fetch more", true ) ;
    private final JCheckBox rateLimitBox =
            new JCheckBox( "Show rate limit bar", true ) ;
    private final JComboBox<Show> showBox = new JComboBox<>( Show.values() ) ;

    private ProcessingState procState = ProcessingState.STOPPED ;
    private boolean         loading ;

    /**
     * Questions awaiting review on the server, as of the last fetch less
     * those confirmed since; -1 before the first Load.
     */
    private int flagged = -1 ;

    /** True once the user was told the review model isn't a strong one. */
    private boolean weakModelWarned ;

    public TopicReviewFrame( TranscriptStore transcripts ) {

        super( "QTopicReviewer" ) ;
        this.transcriptPanel = new TranscriptPanel( transcripts ) ;
        this.decisionPanel   = new ReviewDecisionPanel( this ) ;

        buildTable() ;

        JToolBar toolbar = new JToolBar() ;
        toolbar.setFloatable( false ) ;
        searchButton.setToolTipText( "Choose the syllabuses, topics and " +
                                     "batch size Load and Fetch more use" ) ;
        loadButton.setToolTipText( "Fetch the first questions awaiting " +
                                   "review that match the criteria " +
                                   "(replaces the table)" ) ;
        moreButton.setToolTipText( "Fetch the first questions awaiting " +
                                   "review again, and add those not loaded" ) ;
        startButton.setToolTipText( "Send the questions not yet judged to " +
                                    "the review model" ) ;
        acceptButton.setToolTipText( "Confirm the server topic of every " +
                                     "question the model agreed with" ) ;
        fetchAfterAcceptBox.setToolTipText( "After Accept all agreed, fetch " +
                                            "the questions that moved up" ) ;
        searchButton.addActionListener( e -> editCriteria() ) ;
        loadButton.addActionListener( e -> load( false ) ) ;
        moreButton.addActionListener( e -> load( true ) ) ;
        startButton.addActionListener( e -> start() ) ;
        stopButton.addActionListener( e -> {
            if( controller != null ) controller.stop() ;
        } ) ;
        acceptButton.addActionListener( e -> acceptAllAgreed() ) ;
        showBox.addActionListener( e -> applyFilter() ) ;
        rateLimitBox.addActionListener( e -> refreshRateLimitStrip() ) ;

        toolbar.add( searchButton ) ;
        toolbar.add( loadButton ) ;
        toolbar.add( moreButton ) ;
        toolbar.add( startButton ) ;
        toolbar.add( stopButton ) ;
        toolbar.addSeparator() ;
        toolbar.add( acceptButton ) ;
        toolbar.add( fetchAfterAcceptBox ) ;
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
        table.setSelectionMode( ListSelectionModel.SINGLE_SELECTION ) ;
        table.setFillsViewportHeight( true ) ;
        table.setDefaultRenderer( Object.class, new RowRenderer() ) ;
        table.setDefaultRenderer( Integer.class, new RowRenderer() ) ;

        int[] widths = { 50, 110, 100, 300, 150, 320, 100, 260 } ;
        for( int c = 0 ; c < widths.length ; c++ ) {
            table.getColumnModel().getColumn( c ).setPreferredWidth( widths[c] ) ;
        }
        table.getSelectionModel().addListSelectionListener( e -> {
            if( !e.getValueIsAdjusting() ) showSelection() ;
        } ) ;
        bindDecisionKeys() ;
    }

    /**
     * Keys 1-4 on the table (main row or keypad): 1 use the model's topic,
     * 2 keep the server's, 3 choose a topic, 4 skip to the next question
     * awaiting a decision.
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
                    if( key == 4 ) selectNextUndecided() ;
                    else decisionPanel.press( key ) ;
                }
            } ) ;
        }
    }

    // ---- toolbar actions -----------------------------------------------

    /**
     * Fetch the first questions of the review queue off the EDT, replacing
     * the table, or ({@code more}) adding those not loaded to it.
     */
    private void load( boolean more ) {

        if( controller == null || loading ) return ;
        loading = true ;
        statusBar.setText( "Loading the review queue…" ) ;
        updateControls() ;

        new SwingWorker<Loaded,Void>() {
            @Override protected Loaded doInBackground() throws Exception {
                return controller.load( more ) ;
            }

            @Override protected void done() {
                loading = false ;
                try {
                    showItems( get(), more ) ;
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
     * Fill the table with {@code loaded}, or append it if {@code more}, with
     * the verdicts stored by earlier runs.
     */
    private void showItems( Loaded loaded, boolean more ) {

        if( more ) {
            model.addItems( loaded.items() ) ;
        }
        else {
            model.setItems( loaded.items() ) ;
            decisionPanel.show( null ) ;
            preview.show( null ) ;
        }
        for( ReviewItem item : loaded.items() ) {
            Row row = model.rowOf( item ) ;
            if( row == null || !item.hasImages() ) continue ;
            ReviewVerdict v = controller.storedVerdict( item ) ;
            if( v == null ) continue ;
            setVerdict( row, v ) ;
            model.changed( row ) ;
        }
        flagged = loaded.flagged() ;
        updateControls() ;

        if( more && loaded.items().isEmpty() && flagged > 0 ) {
            JOptionPane.showMessageDialog( this,
                    "No new questions: the first ones awaiting review are " +
                    "already loaded.\nConfirm some of them, then fetch again.",
                    "Fetch more", JOptionPane.INFORMATION_MESSAGE ) ;
        }
    }

    /**
     * Let the user change the criteria Load and Fetch more use; nothing is
     * fetched until one of them is pressed.
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

    /** Queue every row that has images and no verdict (or a failed one). */
    private void start() {

        if( controller == null ) return ;
        List<ReviewItem> items = new ArrayList<>() ;

        for( Row row : model.rows() ) {
            if( row.item.hasImages() && row.status.isRunnable() ) {
                row.status = ReviewStatus.QUEUED ;
                row.result = null ;
                items.add( row.item ) ;
            }
        }
        if( items.isEmpty() ) {
            statusBar.setText( "Nothing to send to the model" ) ;
            return ;
        }
        model.fireTableDataChanged() ;
        controller.start( items ) ;
    }

    /**
     * After confirmation, confirm the server topic of every Agreed row: the
     * model's best topic, so it goes to the server as the model's.
     */
    private void acceptAllAgreed() {

        List<ReviewDecision> decisions = model.rows().stream()
                .filter( r -> r.status == ReviewStatus.AGREED )
                .map( r -> ReviewDecision.of( r.item, r.verdict.top(),
                                              r.verdict ) )
                .toList() ;
        if( controller == null || decisions.isEmpty() ) return ;

        int answer = JOptionPane.showConfirmDialog( this,
                "Confirm the server topic of the " + decisions.size() +
                " question(s) the model agreed with?\nThe server records " +
                "each as the review model's association.",
                "Accept all agreed", JOptionPane.OK_CANCEL_OPTION ) ;
        if( answer != JOptionPane.OK_OPTION ) return ;

        boolean fetch = fetchAfterAcceptBox.isSelected() ;
        confirm( decisions, () -> {
            if( fetch && flagged > 0 ) load( true ) ;
        } ) ;
    }

    // ---- decisions -----------------------------------------------------

    @Override
    public void keep( Row row ) {

        Topic current = serverTopic( row ) ;
        if( current != null ) override( row, current ) ;
    }

    /** Every decision ends here; the selection moves on from its row. */
    @Override
    public void override( Row row, Topic topic ) {

        confirm( List.of( ReviewDecision.of( row.item, topic, row.verdict ) ),
                 () -> {} ) ;
        if( row == selectedRow() ) selectNextUndecided() ;
    }

    /**
     * Let the user pick one topic of the row's syllabus from a list tall
     * enough to show them all; the server's and the model's topics are
     * marked. Enter or a double-click picks.
     */
    @Override
    public void chooseTopic( Row row ) {

        List<Topic> topics = controller.topics( row.question.getSyllabusName() ) ;
        if( topics.isEmpty() ) return ;

        Map<Integer,Integer> modelPct = new HashMap<>() ;
        if( row.verdict != null ) {
            row.verdict.topics().forEach( t -> modelPct.put(
                    t.getId(), Math.round( t.getMatchProbability() * 100 ) ) ) ;
        }
        int serverId = row.question.getTopicId() == null
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

        Topic first = row.verdict != null && !row.verdict.agreesWith( serverId )
                    ? row.verdict.top() : null ;
        for( int i = 0 ; i < topics.size() ; i++ ) {
            int id = topics.get( i ).getId() ;
            if( first != null ? id == first.getId() : id == serverId ) {
                list.setSelectedIndex( i ) ;
            }
        }

        JOptionPane pane = new JOptionPane( new JScrollPane( list ),
                JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION ) ;
        JDialog dialog = pane.createDialog( this,
                "Topic for " + row.questionLabel() ) ;

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
        override( row, chosen ) ;
    }

    /**
     * Select the next row, after the selected one and wrapping round, that
     * awaits the user's decision; failing that, the next row.
     */
    private void selectNextUndecided() {

        int count = table.getRowCount() ;
        if( count == 0 ) return ;

        int from = table.getSelectedRow() ;
        int next = -1 ;

        for( int k = 1 ; k <= count && next < 0 ; k++ ) {
            int          view = ( from + k ) % count ;
            ReviewStatus st   = model.getRow(
                    table.convertRowIndexToModel( view ) ).status ;
            if( st == ReviewStatus.AGREED || st == ReviewStatus.DIFFERS
                || st == ReviewStatus.ERROR ) next = view ;
        }
        if( next < 0 ) next = Math.min( from + 1, count - 1 ) ;

        table.setRowSelectionInterval( next, next ) ;
        table.scrollRectToVisible( table.getCellRect( next, 0, true ) ) ;
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
     * run {@code after}.
     */
    private void confirm( List<ReviewDecision> decisions, Runnable after ) {

        for( ReviewDecision d : decisions ) {
            Row row = model.rowOf( d.item() ) ;
            if( row != null ) setSaving( row ) ;
        }

        new SwingWorker<List<ReviewResult>,Void>() {
            @Override protected List<ReviewResult> doInBackground() {
                return controller.confirm( decisions ) ;
            }

            @Override protected void done() {

                List<ReviewResult> results ;
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

        if( !r.accepted() ) {
            row.status = ReviewStatus.ERROR ;
            row.result = r.message() != null ? r.message()
                                             : "refused by the server" ;
            return ;
        }

        ServerQuestion q      = row.question ;
        String         before = q.getTopicName() ;
        boolean        keeps  = d.keepsTopic() ;
        String         by     = d.isAI() ? "AI " + d.model() : "User" ;

        if( q.isTopicReviewNeeded() && !r.stillFlagged() && flagged > 0 ) {
            flagged-- ;
        }
        q.setTopicId( d.topic().getId() ) ;
        q.setTopicName( d.topic().getTopicName() ) ;
        q.setTopicAssocActor( d.actor() ) ;
        q.setTopicAssocAiModel( d.model() ) ;
        q.setTopicAssocConfidence( d.confidence() ) ;
        q.setTopicReviewNeeded( r.stillFlagged() ) ;

        row.result = ( keeps ? "kept " + before
                             : before + " → " + d.topic().getTopicName() ) +
                     " (" + by + ")" ;
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
                    d.model() + " is not in the server's strong-model list " +
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
                // Rows never started stay unjudged.
                for( Row row : model.rows() ) {
                    if( row.status == ReviewStatus.QUEUED ) {
                        row.status = ReviewStatus.PENDING ;
                    }
                }
                model.fireTableDataChanged() ;
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
        decisionPanel.show( row ) ;
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

        loadButton.setEnabled( stopped && !loading && !saving ) ;
        moreButton.setEnabled( !loading && flagged > 0 ) ;
        startButton.setEnabled( stopped && !loading
                                && model.rows().stream().anyMatch(
                                        r -> r.item.hasImages()
                                             && r.status.isRunnable() ) ) ;
        stopButton.setEnabled( procState == ProcessingState.RUNNING ) ;
        acceptButton.setEnabled( model.count( ReviewStatus.AGREED ) > 0 ) ;
        if( !loading ) refreshStatusBar() ;
    }

    private void refreshStatusBar() {

        String text = String.format(
                "%d loaded · %d differ · %d agreed · %d kept · " +
                "%d overridden · %d still flagged · %d failed · " +
                "%d without images · %d errors",
                model.getRowCount(),
                model.count( ReviewStatus.DIFFERS ),
                model.count( ReviewStatus.AGREED ),
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

    /** Tints rows by status. */
    private final class RowRenderer extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent( JTable t, Object value,
                boolean selected, boolean focus, int viewRow, int column ) {

            Component c = super.getTableCellRendererComponent(
                    t, value, selected, focus, viewRow, column ) ;
            if( selected ) return c ;

            Row row = model.getRow( t.convertRowIndexToModel( viewRow ) ) ;
            c.setBackground( STATUS_BG.getOrDefault( row.status,
                                                     t.getBackground() ) ) ;
            return c ;
        }
    }
}
