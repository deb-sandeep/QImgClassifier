package com.sandy.sconsole.qimgclassifier.ui;

import com.sandy.sconsole.qimgclassifier.classifier.ClassificationListener;
import com.sandy.sconsole.qimgclassifier.model.AIAgent;
import com.sandy.sconsole.qimgclassifier.model.ProcessingState;
import com.sandy.sconsole.qimgclassifier.model.AttemptRequest;
import com.sandy.sconsole.qimgclassifier.model.QTask;
import com.sandy.sconsole.qimgclassifier.model.RateLimitNotice;
import com.sandy.sconsole.qimgclassifier.model.TaskStatus;
import com.sandy.sconsole.qimgclassifier.model.Topic;
import com.sandy.sconsole.qimgclassifier.util.ImageCollator;
import com.sandy.sconsole.qimgclassifier.util.net.QImgExtractorClient;
import com.sandy.sconsole.qimgclassifier.workspace.ProcessedListRepair;
import com.sandy.sconsole.qimgclassifier.workspace.QuestionTopicAssociator;
import com.sandy.sconsole.qimgclassifier.workspace.TranscriptStore;
import com.sandy.sconsole.qimgclassifier.workspace.WorkspaceTopicMap;

import com.sandy.sconsole.qimgclassifier.ui.QTaskTreeTableModel.Node;
import com.sandy.sconsole.qimgclassifier.ui.QTaskTreeTableModel.PartNode;
import com.sandy.sconsole.qimgclassifier.ui.QTaskTreeTableModel.QuestionNode;
import com.sandy.sconsole.qimgclassifier.ui.QTaskTreeTableModel.SourceNode;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.sandy.sconsole.qimgclassifier.ui.QTaskTreeTableModel.*;

/**
 * Main window: a live monitor of the classification run, shown as a tree
 * table - one node per source, one row per question beneath it, and the part
 * images beneath a multi-part question (collapsed by default). Question rows
 * move QUEUED -> RUNNING -> DONE / FAILED, or start ASSOCIATED if already in
 * the topic map. A preview of the selected question sits below the table and
 * a status bar with run totals at the bottom.
 * <p>
 * Interaction: click the triangle (or Left / Right) to collapse / expand;
 * double-click (or Enter) a question for manual topic association, or a part
 * to open its image; right-click for open / show in QImgExtractor (⌘E) /
 * re-queue / manual topic association. The last column's button opens manual
 * topic association directly. Header clicks sort and filter
 * (see {@link HeaderFilterController}).
 * <p>
 * Engine callbacks ({@link ClassificationListener}) may arrive on any thread
 * and are marshalled onto the EDT; everything else runs on the EDT.
 */
@Slf4j
@SuppressWarnings( "serial" )
public class QClassifierFrame extends JFrame implements ClassificationListener {

    /**
     * The engine operations the UI drives. Methods return promptly unless
     * marked blocking.
     */
    public interface Controller {

        /**
         * Start the batch pass (no-op if one is running); progress arrives via
         * the listener.
         */
        void startProcessing() ;

        /**
         * Ask the batch pass to stop: running tasks finish, no new ones start.
         */
        void stopProcessing() ;

        /**
         * Classify {@code task} again on the worker pool, whatever its current
         * state.
         */
        void reprocess( QTask task ) ;

        /**
         * Persist a hand-picked topic for the task, in both ai-topic-map.json
         * and question-info.json. Blocking; call off the EDT.
         *
         * @return the question-info.json outcome
         * @throws IOException if ai-topic-map.json could not be updated
         */
        QuestionTopicAssociator.Outcome manualAssociate( QTask task, Topic topic )
                throws IOException ;

        /**
         * Take a QUEUED task out of the scheduler queue while it is being
         * manually associated.
         *
         * @return false if it has already been dispatched for classification
         */
        boolean holdForManualAssociation( QTask task ) ;

        /** End a hold; a task that was not associated is scheduled again. */
        void releaseFromManualAssociation( QTask task, boolean associated ) ;

        /**
         * Re-sync the processed list with the topic maps. Blocking; call off
         * the EDT. Progress is reported on the calling thread.
         *
         * @throws IllegalStateException if processing is running
         */
        ProcessedListRepair.Report repairProcessedList(
                ProcessedListRepair.ProgressListener progress ) ;

        /**
         * The AI targets and models held back by a rate limit right now; empty
         * when none is.
         */
        List<RateLimitNotice> rateLimitNotices() ;

        /** Stop the worker pool; called when the window closes. */
        void shutdown() ;
    }

    private static final int PREVIEW_PANEL_HEIGHT = 300 ;

    /**
     * Share of the window width given to the table; the rest is transcripts.
     */
    private static final double SPLIT_RATIO = 0.6 ;

    /** Horizontal padding added to a fixed column's widest text. */
    private static final int   COL_PAD   = 16 ;
    private static final int   INDENT    = 18 ;
    private static final int   LEFT_PAD  = 4 ;
    private static final int   ICON_GAP  = 4 ;
    private static final Color SOURCE_BG = new Color( 0xE3E8EF ) ;

    /**
     * The RUNNING / DONE / FAILED shades are shared by question rows and their
     * source row.
     */
    private static final Color RUNNING_BG    = new Color( 0xFFE0B2 ) ;
    private static final Color DONE_BG       = new Color( 0xC8E6C9 ) ;
    private static final Color FAILED_BG     = new Color( 0xEF9A9A ) ;
    private static final Color REQUEUED_BG   = new Color( 0xFFF8E1 ) ;
    private static final Color ASSOCIATED_BG = new Color( 0xF0F0F0 ) ;

    /** ⌘E on macOS, Ctrl+E elsewhere. */
    private static final KeyStroke SHOW_IN_EXTRACTOR_KEY = KeyStroke.getKeyStroke(
            KeyEvent.VK_E, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() ) ;

    private final QTaskTreeTableModel model = new QTaskTreeTableModel() ;
    private final QImgExtractorClient extractorClient ;
    private final TranscriptPanel     transcriptPanel ;
    private final JTable              table ;
    private final JLabel              statusBar = new JLabel( " " ) ;
    private final RateLimitStrip      rateLimitStrip = new RateLimitStrip() ;
    private final ImagePreview        preview =
            new ImagePreview( PREVIEW_PANEL_HEIGHT ) ;

    private final JMenuItem startItem = new JMenuItem( "Start processing" ) ;
    private final JMenuItem stopItem  = new JMenuItem( "Stop processing" ) ;
    private final JMenuItem repairItem = new JMenuItem( "Repair processed list" ) ;

    /** View option: show the rate-limit strip under the transcripts. */
    private final JCheckBoxMenuItem rateLimitItem =
            new JCheckBoxMenuItem( "Show rate limit bar", true ) ;
    private boolean repairRunning ;

    /**
     * QUEUED tasks currently held out of the scheduler for manual association.
     */
    private final Set<QTask> heldTasks = new HashSet<>() ;

    /**
     * Coalesces bursts of structural changes (discovery, status moves under a
     * filter) into one rebuild.
     */
    private final Timer rebuildTimer = new Timer( 150, e -> rebuildTree() ) ;

    /**
     * Set while a rebuild restores the selection, so the preview isn't
     * reloaded.
     */
    private boolean restoringSelection ;

    private final HeaderFilterController headerFilters ;

    /** The engine; set via {@code setController} before the window is shown. */
    @Setter
    private Controller    controller ;
    private volatile String batchProgress = "" ;

    /**
     * Cumulative time spent processing (Start..Stop), plus the current run if
     * active.
     */
    private ProcessingState procState = ProcessingState.STOPPED ;
    private long    processingAccumMs = 0 ;
    private long    processingStartedMs = 0 ;

    /** @param extractorClient used for "Show in QImgExtractor" */
    /**
     * @param extractorClient used for "Show in QImgExtractor"
     * @param transcripts     source of the transcripts shown in the right panel
     */
    public QClassifierFrame( QImgExtractorClient extractorClient,
                             TranscriptStore transcripts ) {

        super( "Question Image Classifier" ) ;
        this.extractorClient = extractorClient ;
        this.transcriptPanel = new TranscriptPanel( transcripts ) ;

        table = new JTable( model ) {
            @Override
            public Component prepareRenderer( TableCellRenderer r,
                                              int row, int col ) {

                Component c = super.prepareRenderer( r, row, col ) ;
                Node n = model.nodeAt( row ) ;

                if( n instanceof SourceNode ) {
                    c.setFont( c.getFont().deriveFont( Font.BOLD ) ) ;
                }
                if( !isRowSelected( row ) ) c.setBackground( backgroundFor( n ) ) ;
                return c ;
            }
        } ;
        table.setFillsViewportHeight( true ) ;
        table.setSelectionMode( ListSelectionModel.SINGLE_SELECTION ) ;
        rebuildTimer.setRepeats( false ) ;
        headerFilters = new HeaderFilterController( table, model,
                                                    this::rebuildTree ) ;

        DefaultTableCellRenderer center = new DefaultTableCellRenderer() ;
        center.setHorizontalAlignment( SwingConstants.CENTER ) ;
        for( int c : new int[]{ COL_SUBJ, COL_STATUS, COL_ATTEMPTS, COL_TIME } ) {
            table.getColumnModel().getColumn( c ).setCellRenderer( center ) ;
        }
        TableColumnModel columns = table.getColumnModel() ;
        columns.getColumn( COL_NAME ).setCellRenderer( new TreeCellRenderer() ) ;
        columns.getColumn( COL_ASSOC ).setCellRenderer( new AssocButtonRenderer() ) ;
        columns.getColumn( COL_AGENT ).setCellRenderer( new AgentCellRenderer() ) ;
        sizeColumns() ;

        installMouseHandlers() ;
        installKeyBindings() ;

        table.getSelectionModel().addListSelectionListener( e -> {
            if( e.getValueIsAdjusting() || restoringSelection ) return ;
            showSelection( selectedNode() ) ;
        } ) ;

        setJMenuBar( buildMenuBar() ) ;

        // Table and preview on the left, transcripts on the right, status
        // bar across the bottom.
        // A strip under the transcripts shows while a rate limit holds
        // requests back, one line per limit.
        JPanel left = new JPanel( new BorderLayout() ) ;
        left.add( new JScrollPane( table ), BorderLayout.CENTER ) ;
        left.add( preview, BorderLayout.SOUTH ) ;

        JPanel right = new JPanel( new BorderLayout() ) ;
        right.add( transcriptPanel, BorderLayout.CENTER ) ;
        right.add( rateLimitStrip, BorderLayout.SOUTH ) ;

        JSplitPane split = new JSplitPane( JSplitPane.HORIZONTAL_SPLIT,
                                           left, right ) ;
        split.setResizeWeight( SPLIT_RATIO ) ;
        split.setContinuousLayout( true ) ;

        statusBar.setBorder( BorderFactory.createEmptyBorder( 4, 8, 4, 8 ) ) ;
        setLayout( new BorderLayout() ) ;
        add( split, BorderLayout.CENTER ) ;
        add( statusBar, BorderLayout.SOUTH ) ;

        // Open maximised; the divider can only be placed once there is a size.
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

        new Timer( 1000, e -> {
            model.tickRunningRows() ;
            refreshStatusBar() ;
            refreshRateLimitStrip() ;
        } ).start() ;
        refreshStatusBar() ;
    }

    /**
     * Pin the short columns to the width of their widest possible value (or
     * header, incl. sort / filter markers) and let the name and result columns
     * share whatever width is left.
     */
    private void sizeColumns() {

        FontMetrics cell = table.getFontMetrics( table.getFont() ) ;
        Font        headFont = table.getTableHeader().getFont().deriveFont( Font.BOLD ) ;
        FontMetrics head     = table.getFontMetrics( headFont ) ;
        String statusWidest = "" ;

        for( TaskStatus st : TaskStatus.values() ) {
            if( st.name().length() > statusWidest.length() ) {
                statusWidest = st.name() ;
            }
        }
        String[][] samples = {
                { "Subj",     "M" },
                { "Status",   statusWidest },
                { "Attempts", "10" },
                { "Time",     "99:59" } } ;
        int[] fixedCols = { COL_SUBJ, COL_STATUS, COL_ATTEMPTS, COL_TIME } ;
        for( int i = 0 ; i < fixedCols.length ; i++ ) {
            int w = Math.max( head.stringWidth( samples[i][0] + "  ▲  *" ),
                              cell.stringWidth( samples[i][1] ) ) + COL_PAD ;
            TableColumn col = table.getColumnModel().getColumn( fixedCols[i] ) ;

            col.setMinWidth( w ) ;
            col.setMaxWidth( w ) ;
            col.setPreferredWidth( w ) ;
        }
        int agentW = Math.max( head.stringWidth( "AI  ▲  *" ), AgentIcon.SIZE )
                     + COL_PAD ;
        TableColumn agent = table.getColumnModel().getColumn( COL_AGENT ) ;

        agent.setMinWidth( agentW ) ;
        agent.setMaxWidth( agentW ) ;
        agent.setPreferredWidth( agentW ) ;

        int assocW = TopicIcon.SIZE + 10 ;
        TableColumn assoc = table.getColumnModel().getColumn( COL_ASSOC ) ;

        assoc.setMinWidth( assocW ) ;
        assoc.setMaxWidth( assocW ) ;
        assoc.setPreferredWidth( assocW ) ;
        table.getColumnModel().getColumn( COL_NAME ).setPreferredWidth( 170 ) ;
        table.getColumnModel().getColumn( COL_RESULT ).setPreferredWidth( 300 ) ;
    }

    /**
     * File (start / stop / exit), View (expand / collapse / clear filters) and
     * Maintenance (repair) menus.
     */
    private JMenuBar buildMenuBar() {

        JMenu file = new JMenu( "File" ) ;

        // Enablement is driven entirely by processingStateChanged(); the
        // engine's
        // own compareAndSet / !stopRequested guards absorb any stray
        // double-click.
        startItem.addActionListener( a -> {
            if( controller != null ) controller.startProcessing() ;
        } ) ;
        stopItem.setEnabled( false ) ;
        stopItem.addActionListener( a -> {
            if( controller != null ) controller.stopProcessing() ;
        } ) ;
        file.add( startItem ) ;
        file.add( stopItem ) ;
        file.addSeparator() ;

        JMenuItem exit = new JMenuItem( "Exit" ) ;
        exit.addActionListener( a -> dispatchEvent(
                new WindowEvent( this, WindowEvent.WINDOW_CLOSING ) ) ) ;
        file.add( exit ) ;

        JMenu view = new JMenu( "View" ) ;
        JMenuItem expandAll = new JMenuItem( "Expand all sources" ) ;

        expandAll.addActionListener( a -> setAllSourcesExpanded( true ) ) ;
        view.add( expandAll ) ;
        JMenuItem collapseAll = new JMenuItem( "Collapse all sources" ) ;
        collapseAll.addActionListener( a -> setAllSourcesExpanded( false ) ) ;
        view.add( collapseAll ) ;
        view.addSeparator() ;
        JMenuItem clearFilters = new JMenuItem( "Clear all filters" ) ;
        clearFilters.addActionListener( a -> headerFilters.clearAll() ) ;
        view.add( clearFilters ) ;
        view.addSeparator() ;
        rateLimitItem.addActionListener( a -> refreshRateLimitStrip() ) ;
        view.add( rateLimitItem ) ;

        JMenu maintenance = new JMenu( "Maintenance" ) ;
        repairItem.addActionListener( a -> repairProcessedList() ) ;
        maintenance.add( repairItem ) ;

        JMenuBar bar = new JMenuBar() ;
        bar.add( file ) ;
        bar.add( view ) ;
        bar.add( maintenance ) ;
        return bar ;
    }

    /**
     * Run the repair off the EDT (it walks every source) behind a modal
     * progress dialog, then show its report.
     */
    private void repairProcessedList() {

        if( controller == null || repairRunning ) return ;
        repairRunning = true ;
        updateRepairEnablement() ;
        RepairProgressDialog dialog = new RepairProgressDialog( this ) ;
        new SwingWorker<ProcessedListRepair.Report,ProcessedListRepair.Progress>() {
            @Override protected ProcessedListRepair.Report doInBackground() {
                return controller.repairProcessedList( this::publish ) ;
            }
            @Override
            protected void process( List<ProcessedListRepair.Progress> chunks ) {
                dialog.update( chunks.get( chunks.size() - 1 ) ) ;
            }
            @Override protected void done() {
                repairRunning = false ;
                updateRepairEnablement() ;
                dialog.dispose() ;
                try {
                    showRepairReport( get() ) ;
                }
                catch( Exception e ) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e ;
                    log.error( "Processed list repair failed", cause ) ;
                    JOptionPane.showMessageDialog( QClassifierFrame.this,
                            "Repair failed:\n" + cause.getMessage(),
                            "Repair processed list", JOptionPane.ERROR_MESSAGE ) ;
                }
            }
        }.execute() ;
        // Modal: blocks here (pumping events) until done() disposes it.
        if( repairRunning ) dialog.setVisible( true ) ;
    }

    /**
     * Repair is only offered while processing is fully stopped and no repair is
     * running.
     */
    private void updateRepairEnablement() {
        repairItem.setEnabled( !repairRunning &&
                               procState == ProcessingState.STOPPED ) ;
    }

    /**
     * Show the repair report text in a scrollable, monospaced message dialog.
     */
    private void showRepairReport( ProcessedListRepair.Report report ) {

        JTextArea text = new JTextArea( report.toText() ) ;
        text.setEditable( false ) ;
        text.setFont( new Font( Font.MONOSPACED, Font.PLAIN, 12 ) ) ;
        text.setCaretPosition( 0 ) ;
        JScrollPane scroll = new JScrollPane( text ) ;
        scroll.setPreferredSize( new Dimension( 560, 360 ) ) ;
        JOptionPane.showMessageDialog( this, scroll, "Repair processed list",
                JOptionPane.INFORMATION_MESSAGE ) ;
    }

    // ---- ClassificationListener (marshalled onto the EDT) ---------------

    @Override
    public void taskDiscovered( QTask task, String storedResult ) {
        SwingUtilities.invokeLater( () -> {
            model.discovered( task, storedResult ) ;
            scheduleRebuild() ;
        } ) ;
    }

    @Override
    public void taskStarted( QTask task ) {
        SwingUtilities.invokeLater( () -> {
            model.started( task ) ;
            afterStatusChange() ;
        } ) ;
    }

    @Override
    public void taskAttempt( QTask task, AIAgent agent, String aiModel,
                             int attempt ) {
        SwingUtilities.invokeLater( () -> {
            model.attempting( task, agent, aiModel, attempt ) ;
            afterStatusChange() ;
        } ) ;
    }

    @Override
    public void taskRequestSent( AttemptRequest request ) {
        SwingUtilities.invokeLater( () -> transcriptPanel.requestSent( request ) ) ;
    }

    @Override
    public void taskAttemptFinished( QTask task ) {
        SwingUtilities.invokeLater( () -> transcriptPanel.attemptFinished( task ) ) ;
    }

    @Override
    public void taskFinished( QTask task, TaskStatus status, int attempts,
                              String result ) {
        SwingUtilities.invokeLater( () -> {
            model.finished( task, status, attempts, result ) ;
            afterStatusChange() ;
        } ) ;
    }

    @Override
    public void batchProgress( int sourceIndex, int sourceCount,
                               String sourceName ) {
        batchProgress = String.format( "source %d/%d - %s",
                                       sourceIndex, sourceCount, sourceName ) ;
        SwingUtilities.invokeLater( this::refreshStatusBar ) ;
    }

    @Override
    public void processingStateChanged( ProcessingState state ) {

        SwingUtilities.invokeLater( () -> {
            boolean wasActive = procState != ProcessingState.STOPPED ;
            boolean nowActive = state != ProcessingState.STOPPED ;

            if( nowActive && !wasActive ) {
                processingStartedMs = System.currentTimeMillis() ;
            }
            else if( !nowActive && wasActive ) {
                processingAccumMs += System.currentTimeMillis()
                                     - processingStartedMs ;
                processingStartedMs = 0 ;
            }
            procState = state ;
            startItem.setEnabled( state == ProcessingState.STOPPED ) ;
            stopItem.setEnabled( state == ProcessingState.RUNNING ) ;
            updateRepairEnablement() ;
            refreshStatusBar() ;
        } ) ;
    }

    // ---- tree view maintenance ----------------------------------------

    /**
     * Refresh after a row's status changed: rebuild if it may move under the
     * filter / sort.
     */
    private void afterStatusChange() {
        // A status change can move a row under an active filter / sort.
        if( model.isFilteredOrSorted() ) scheduleRebuild() ;
        refreshStatusBar() ;
    }

    /** Rebuild after a short delay, coalescing bursts of changes. */
    private void scheduleRebuild() {
        if( !rebuildTimer.isRunning() ) rebuildTimer.start() ;
    }

    /**
     * Rebuild the flattened view, keeping the selected node selected if it is
     * still visible.
     */
    private void rebuildTree() {

        rebuildTimer.stop() ;
        Node selected = selectedNode() ;
        restoringSelection = true ;

        try {
            model.rebuild() ;
            if( selected != null && selected.viewIndex >= 0 ) {
                selectViewRow( selected.viewIndex, false ) ;
            }
        }
        finally {
            restoringSelection = false ;
        }
        if( selected != null && selected.viewIndex < 0 ) showPreview( null ) ;
        refreshStatusBar() ;
    }

    /** Expand or collapse every source and rebuild the view. */
    private void setAllSourcesExpanded( boolean expanded ) {
        model.setAllSourcesExpanded( expanded ) ;
        rebuildTree() ;
    }

    private Node selectedNode() {
        int viewRow = table.getSelectedRow() ;
        return viewRow < 0 ? null : model.nodeAt( viewRow ) ;
    }

    /** Flip a node between expanded and collapsed. */
    private void toggle( Node n ) {
        model.setExpanded( n, !n.expanded ) ;
    }

    // ---- UI plumbing ---------------------------------------------------

    /**
     * Expander clicks, the association button, double-click activation and the
     * context menu.
     */
    private void installMouseHandlers() {

        table.addMouseListener( new MouseAdapter() {
            @Override public void mousePressed( MouseEvent e ) {

                if( SwingUtilities.isLeftMouseButton( e )
                    && e.getClickCount() == 1 ) {
                    Node n = nodeUnder( e ) ;
                    if( n != null && onExpander( e, n ) ) toggle( n ) ;
                }
                maybePopup( e ) ;
            }
            @Override public void mouseReleased( MouseEvent e ) { maybePopup( e ) ; }
            @Override public void mouseClicked( MouseEvent e ) {

                if( !SwingUtilities.isLeftMouseButton( e ) ) return ;
                Node n = nodeUnder( e ) ;
                // Re-shows the transcripts after Clear, when the click does
                // not change the selection.
                if( e.getClickCount() == 1 ) showTranscripts( n ) ;
                if( onAssocButton( e ) ) {
                    if( e.getClickCount() == 1 && n instanceof QuestionNode q
                        && canAssociate( q ) ) {
                        associateManually( q ) ;
                    }
                    return ;
                }
                if( e.getClickCount() != 2 ) return ;
                if( n == null || onExpander( e, n ) ) return ;
                if( n instanceof SourceNode ) toggle( n ) ;
                else                          activate( n ) ;
            }
        } ) ;
    }

    /**
     * ⌘E show in QImgExtractor, Enter activate, Right expand, Left collapse /
     * go to parent.
     */
    private void installKeyBindings() {

        InputMap  im = table.getInputMap(
                JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT ) ;
        ActionMap am = table.getActionMap() ;

        table.getInputMap( JComponent.WHEN_IN_FOCUSED_WINDOW )
             .put( SHOW_IN_EXTRACTOR_KEY, "qc-show-in-extractor" ) ;
        am.put( "qc-show-in-extractor", new AbstractAction() {
            @Override public void actionPerformed( ActionEvent e ) {
                Node n = selectedNode() ;
                if( n != null ) showInExtractor( n ) ;
            }
        } ) ;
        im.put( KeyStroke.getKeyStroke( "ENTER" ), "qc-activate" ) ;
        am.put( "qc-activate", new AbstractAction() {
            @Override public void actionPerformed( ActionEvent e ) {
                Node n = selectedNode() ;
                if( n instanceof SourceNode ) toggle( n ) ;
                else if( n != null )          activate( n ) ;
            }
        } ) ;
        im.put( KeyStroke.getKeyStroke( "RIGHT" ), "qc-expand" ) ;
        im.put( KeyStroke.getKeyStroke( "LEFT" ),  "qc-collapse" ) ;
        am.put( "qc-expand", new AbstractAction() {
            @Override public void actionPerformed( ActionEvent e ) {
                Node n = selectedNode() ;
                if( n != null ) model.setExpanded( n, true ) ;
            }
        } ) ;
        am.put( "qc-collapse", new AbstractAction() {
            @Override public void actionPerformed( ActionEvent e ) {

                Node n = selectedNode() ;
                if( n == null ) return ;
                if( n.isExpandable() && n.expanded ) {
                    model.setExpanded( n, false ) ;
                    return ;
                }
                Node parent = parentOf( n ) ;
                if( parent != null && parent.viewIndex >= 0 ) {
                    selectViewRow( parent.viewIndex, true ) ;
                }
            }
        } ) ;
    }

    /** The node's parent in the tree; null for a source. */
    private static Node parentOf( Node n ) {
        if( n instanceof QuestionNode q ) return q.parent ;
        if( n instanceof PartNode p )     return p.parent ;
        return null ;
    }

    /**
     * True if the event is on the expand / collapse triangle of an expandable
     * node.
     */
    private boolean onExpander( MouseEvent e, Node n ) {

        if( !n.isExpandable() ) return false ;
        int viewCol = table.columnAtPoint( e.getPoint() ) ;
        if( viewCol < 0 || table.convertColumnIndexToModel( viewCol ) != COL_NAME ) {
            return false ;
        }
        int x = e.getX() - table.getCellRect( n.viewIndex, viewCol, false ).x ;
        int start = LEFT_PAD + n.depth() * INDENT ;

        return x >= start - 2 && x <= start + ExpanderIcon.SIZE + ICON_GAP ;
    }

    /**
     * Show the row's context menu if {@code e} is the platform's popup trigger.
     */
    private void maybePopup( MouseEvent e ) {

        if( !e.isPopupTrigger() ) return ;
        int viewRow = table.rowAtPoint( e.getPoint() ) ;
        if( viewRow < 0 ) return ;
        table.setRowSelectionInterval( viewRow, viewRow ) ;
        Node n = model.nodeAt( viewRow ) ;

        JPopupMenu menu = new JPopupMenu() ;
        if( !( n instanceof SourceNode ) ) {
            JMenuItem open = new JMenuItem( "Open image" ) ;
            open.addActionListener( a -> openImage( n ) ) ;
            menu.add( open ) ;

            JMenuItem extractor = new JMenuItem( "Show in QImgExtractor" ) ;
            extractor.setAccelerator( SHOW_IN_EXTRACTOR_KEY ) ;
            extractor.addActionListener( a -> showInExtractor( n ) ) ;
            menu.add( extractor ) ;
        }
        if( n instanceof QuestionNode q ) {
            JMenuItem requeue = new JMenuItem( "Re-queue" ) ;
            requeue.setEnabled( controller != null && q.status.isSettled() ) ;
            requeue.addActionListener( a -> {
                model.requeued( q.task ) ;
                afterStatusChange() ;
                controller.reprocess( q.task ) ;
            } ) ;
            menu.add( requeue ) ;

            JMenuItem manual = new JMenuItem( "Manual Topic Association…" ) ;
            manual.setEnabled( canAssociate( q ) ) ;
            manual.addActionListener( a -> associateManually( q ) ) ;
            menu.add( manual ) ;
        }
        if( n.isExpandable() ) {
            if( menu.getComponentCount() > 0 ) menu.addSeparator() ;
            JMenuItem toggle = new JMenuItem( n.expanded ? "Collapse" : "Expand" ) ;
            toggle.addActionListener( a -> toggle( n ) ) ;
            menu.add( toggle ) ;
        }

        menu.show( table, e.getX(), e.getY() ) ;
    }

    /**
     * Double-click / Enter: manual association for an associable question, else
     * open the image.
     */
    private void activate( Node n ) {

        if( n instanceof QuestionNode q && canAssociate( q ) ) {
            associateManually( q ) ;
        }
        else {
            openImage( n ) ;
        }
    }

    /** The question a row belongs to (a part's parent); null for a source. */
    private static QuestionNode questionOf( Node n ) {
        if( n instanceof QuestionNode q ) return q ;
        if( n instanceof PartNode p )     return p.parent ;
        return null ;
    }

    private boolean onAssocButton( MouseEvent e ) {
        int viewCol = table.columnAtPoint( e.getPoint() ) ;
        return viewCol >= 0
               && table.convertColumnIndexToModel( viewCol ) == COL_ASSOC ;
    }

    /**
     * Manual association is offered for a question of a known subject that is
     * queued or settled (not running / requeued).
     */
    private boolean canAssociate( QuestionNode q ) {
        return controller != null &&
               q.task.getSubject().hasSyllabus() &&
               ( q.status == TaskStatus.QUEUED || q.status.isSettled() ) ;
    }

    /**
     * Open the manual association dialog on {@code q}. A QUEUED question is
     * held out of the scheduler queue while it is shown and only returns to the
     * queue if no topic gets saved for it. With "continue" ticked the dialog
     * moves on through the following QUEUED / FAILED questions, holding each
     * QUEUED one in turn.
     */
    private void associateManually( QuestionNode q ) {

        if( q.status == TaskStatus.QUEUED && !hold( q.task ) ) {
            JOptionPane.showMessageDialog( this,
                    q.task + " has already started classifying.",
                    "Manual Topic Association", JOptionPane.INFORMATION_MESSAGE ) ;
            return ;
        }
        ManualTopicDialog.open( this, q.task, new ManualTopicDialog.Flow() {
            @Override
            public QuestionTopicAssociator.Outcome save( QTask task, Topic topic )
                    throws IOException {
                return controller.manualAssociate( task, topic ) ;
            }

            @Override
            public void saved( QTask task, Topic topic,
                               QuestionTopicAssociator.Outcome outcome ) {

                if( heldTasks.remove( task ) ) {
                    controller.releaseFromManualAssociation( task, true ) ;
                }
                boolean associated =
                        outcome == QuestionTopicAssociator.Outcome.ASSOCIATED ;
                String summary =
                        WorkspaceTopicMap.summary( topic.getTopicName(), 100, true ) ;

                if( associated ) summary += QuestionTopicAssociator.MATCHES_AI_MARK ;
                model.manuallyAssociated( task, summary ) ;
                afterStatusChange() ;

                if( !associated ) {
                    Window active = KeyboardFocusManager
                            .getCurrentKeyboardFocusManager().getActiveWindow() ;
                    JOptionPane.showMessageDialog( active,
                            "Saved to ai-topic-map.json, but question-info.json " +
                            "was not updated (" + outcome + ").",
                            "Manual Topic Association", JOptionPane.WARNING_MESSAGE ) ;
                }
            }

            @Override
            public QTask nextPending( QTask task ) {
                QuestionNode next = nextPendingAfter( task ) ;
                if( next == null ) return null ;
                selectRow( next ) ;
                return next.task ;
            }

            @Override
            public void abandoned( QTask task ) {
                if( heldTasks.remove( task ) ) {
                    controller.releaseFromManualAssociation( task, false ) ;
                }
            }
        } ) ;
    }

    /**
     * Hold a QUEUED task out of scheduling and remember it, so it is released
     * when the dialog moves on.
     */
    private boolean hold( QTask task ) {
        if( !controller.holdForManualAssociation( task ) ) return false ;
        heldTasks.add( task ) ;
        return true ;
    }

    /**
     * The first associable QUEUED or FAILED question after {@code task}
     * (wrapping round), or null if none. A QUEUED one is held first and
     * skipped if it has already been dispatched.
     */
    private QuestionNode nextPendingAfter( QTask task ) {

        List<QuestionNode> qs = model.questionsInDiscoveryOrder() ;
        int from = qs.indexOf( model.questionFor( task ) ) ;

        for( int i = 1 ; i < qs.size() ; i++ ) {
            QuestionNode q = qs.get( ( from + i + qs.size() ) % qs.size() ) ;
            if( !canAssociate( q ) ) continue ;
            if( q.status == TaskStatus.FAILED ) return q ;
            if( q.status == TaskStatus.QUEUED && hold( q.task ) ) return q ;
        }
        return null ;
    }

    /** Select (and scroll to) the question's row if it is currently visible. */
    private void selectRow( QuestionNode q ) {
        if( q.viewIndex >= 0 ) selectViewRow( q.viewIndex, true ) ;
    }

    /** Select a view row, optionally scrolling it into view. */
    private void selectViewRow( int viewRow, boolean scroll ) {
        table.setRowSelectionInterval( viewRow, viewRow ) ;
        if( scroll ) table.scrollRectToVisible( table.getCellRect( viewRow, 0, true ) ) ;
    }

    /** The node of the row under the mouse; null if none. */
    private Node nodeUnder( MouseEvent e ) {
        int viewRow = table.rowAtPoint( e.getPoint() ) ;
        return viewRow < 0 ? null : model.nodeAt( viewRow ) ;
    }

    /**
     * Image file(s) a node represents: a question's image or parts, a single
     * part, or none for a source.
     */
    private static List<File> imageFilesOf( Node n ) {
        if( n instanceof QuestionNode q ) return q.task.getImgFiles() ;
        if( n instanceof PartNode p )     return List.of( p.file ) ;
        return null ;
    }

    /**
     * Show the selected row's image in the preview and its question's
     * transcripts in the transcript panel. A source row clears the preview and
     * leaves the transcripts as they are.
     */
    private void showSelection( Node node ) {
        showPreview( node ) ;
        showTranscripts( node ) ;
    }

    /**
     * Show the transcripts of the row's question (a part shows its parent's),
     * unless they are on show already.
     */
    private void showTranscripts( Node node ) {
        QuestionNode q = questionOf( node ) ;
        if( q != null ) transcriptPanel.showIfOther( q.task ) ;
    }

    // ---- preview panel -----------------------------------------------

    /**
     * Load and show the node's image (collating multi-part questions); null / a
     * source clears it.
     */
    private void showPreview( Node node ) {
        preview.show( node == null ? null : imageFilesOf( node ) ) ;
    }

    /**
     * Ask QImgExtractor to open the image's project and scroll to it, for
     * repair. A multi-part question is shown by its first part; errors are
     * reported in a dialog.
     */
    private void showInExtractor( Node n ) {

        List<File> files = imageFilesOf( n ) ;
        if( files == null ) return ;
        File img = files.get( 0 ) ;
        extractorClient.showQuestionImage( img ).thenAccept( error -> {
            if( error == null ) return ;
            String msg = "Could not show " + img.getName() +
                         " in QImgExtractor:\n" + error ;
            SwingUtilities.invokeLater( () -> JOptionPane.showMessageDialog(
                    this, msg, "QImgExtractor", JOptionPane.WARNING_MESSAGE ) ) ;
        } ) ;
    }

    /**
     * Open the node's image; a multi-part question is collated into a temp PNG
     * first.
     */
    private void openImage( Node n ) {

        List<File> files = imageFilesOf( n ) ;
        if( files == null ) return ;
        try {
            File target = files.get( 0 ) ;
            if( files.size() > 1 && n instanceof QuestionNode q ) {
                String id = q.task.getQuestionId()
                                  .replaceAll( "[^A-Za-z0-9_-]", "_" ) ;
                target = File.createTempFile( "qclassifier-" + id + "-", ".png" ) ;

                target.deleteOnExit() ;
                ImageIO.write( ImageCollator.collate( files ), "png", target ) ;
            }
            Desktop.getDesktop().open( target ) ;
        }
        catch( Exception ex ) {
            log.error( "Could not open image {}", files, ex ) ;
            JOptionPane.showMessageDialog( this,
                    "Could not open image:\n" + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE ) ;
        }
    }

    /**
     * Run state, counts per status, cumulative processing time and the current
     * source.
     */
    private void refreshStatusBar() {

        Map<TaskStatus,Integer> c = model.statusCounts() ;
        long runMs   = processingStartedMs > 0
                     ? System.currentTimeMillis() - processingStartedMs : 0 ;
        long elapsed = ( processingAccumMs + runMs ) / 1000 ;

        StringBuilder counts = new StringBuilder() ;
        for( TaskStatus s : TaskStatus.values() ) {
            if( counts.length() > 0 ) counts.append( "  " ) ;
            counts.append( capitalised( s ) ).append( ' ' ).append( c.get( s ) ) ;
        }

        String sep  = "   |   " ;
        String text = stateLabel()
                + sep + model.getSourceCount() + " sources"
                + sep + "Showing " + model.getMatchingQuestionCount() + "/"
                      + model.getQuestionCount() + " questions"
                + sep + counts
                + sep + String.format( "Processing time %02d:%02d:%02d",
                                       elapsed / 3600, ( elapsed / 60 ) % 60,
                                       elapsed % 60 ) ;

        if( !batchProgress.isEmpty() ) text += sep + batchProgress ;
        statusBar.setText( text ) ;
    }

    /**
     * Show the strip under the transcripts while any AI target or model is
     * held back by a rate limit, one line each with why and when it lifts;
     * hide it otherwise, or when View > Show rate limit bar is off.
     */
    private void refreshRateLimitStrip() {
        rateLimitStrip.update( controller != null ? controller.rateLimitNotices()
                                                  : List.of(),
                               rateLimitItem.isSelected() ) ;
    }

    /** {@code RUNNING} -> {@code Running}. */
    private static String capitalised( TaskStatus s ) {
        String name = s.name() ;
        return name.charAt( 0 ) + name.substring( 1 ).toLowerCase() ;
    }

    private String stateLabel() {
        return switch( procState ) {
            case RUNNING  -> "RUNNING" ;
            case STOPPING -> "SCHEDULED TO STOP" ;
            case STOPPED  -> "STOPPED" ;
        } ;
    }

    /** Row background: a part shares its question's shade. */
    private static Color backgroundFor( Node n ) {
        if( n instanceof SourceNode s ) return sourceBackground( s ) ;
        if( n instanceof PartNode p )   return colorFor( p.parent.status ) ;
        return colorFor( ( (QuestionNode) n ).status ) ;
    }

    /**
     * Orange while any question runs, red if any failed (once nothing is
     * pending), green once all are done / associated, else the neutral source
     * shade.
     */
    private static Color sourceBackground( SourceNode s ) {

        return switch( s.aggregateStatus() ) {
            case RUNNING          -> RUNNING_BG ;
            case FAILED           -> FAILED_BG ;
            case DONE, ASSOCIATED -> DONE_BG ;
            default               -> SOURCE_BG ;
        } ;
    }

    private static Color colorFor( TaskStatus status ) {

        return switch( status ) {
            case QUEUED     -> Color.WHITE ;
            case REQUEUED   -> REQUEUED_BG ;
            case RUNNING    -> RUNNING_BG ;
            case DONE       -> DONE_BG ;
            case FAILED     -> FAILED_BG ;
            case ASSOCIATED -> ASSOCIATED_BG ;
        } ;
    }

    /**
     * A flat, borderless topic-association button on question rows (greyed when
     * association isn't allowed); blank elsewhere. Rendered as an opaque label
     * so the row's status background shows through.
     */
    private class AssocButtonRenderer implements TableCellRenderer {
        private final JLabel button =
                new JLabel( new TopicIcon(), SwingConstants.CENTER ) ;
        private final JLabel blank = new JLabel() ;

        AssocButtonRenderer() {
            button.setOpaque( true ) ;
            button.setToolTipText( "Manual topic association" ) ;
            blank.setOpaque( true ) ;
        }

        @Override
        public Component getTableCellRendererComponent( JTable t, Object value,
                                                        boolean sel, boolean foc,
                                                        int row, int col ) {
            Node n = model.nodeAt( row ) ;
            if( !( n instanceof QuestionNode q ) ) return blank ;
            button.setEnabled( canAssociate( q ) ) ;
            return button ;
        }
    }

    // ---- tree column rendering ---------------------------------------

    /**
     * Name column: indentation by depth plus an expand / collapse triangle for
     * expandable nodes.
     */
    /**
     * AI column: the icon of the backend that handled the question's latest
     * attempt, with the backend and model as tooltip; blank otherwise.
     */
    private class AgentCellRenderer extends DefaultTableCellRenderer {

        AgentCellRenderer() {
            setHorizontalAlignment( SwingConstants.CENTER ) ;
        }

        @Override
        public Component getTableCellRendererComponent( JTable t, Object value,
                                                        boolean sel, boolean foc,
                                                        int row, int col ) {

            super.getTableCellRendererComponent( t, null, sel, foc, row, col ) ;
            Node n = model.nodeAt( row ) ;
            if( n instanceof QuestionNode q && q.agent != null ) {
                setIcon( AgentIcon.of( q.agent ) ) ;
                setToolTipText( q.agent.getLabel() + " · " + q.agentModel ) ;
            }
            else {
                setIcon( null ) ;
                setToolTipText( null ) ;
            }
            return this ;
        }
    }

    private class TreeCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent( JTable t, Object value,
                                                        boolean sel, boolean foc,
                                                        int row, int col ) {

            super.getTableCellRendererComponent( t, value, sel, foc, row, col ) ;
            Node n = model.nodeAt( row ) ;
            setText( n.label() ) ;
            setIcon( new ExpanderIcon( n.isExpandable(), n.expanded ) ) ;
            setIconTextGap( ICON_GAP ) ;
            int indent = LEFT_PAD + n.depth() * INDENT ;
            setBorder( BorderFactory.createEmptyBorder( 0, indent, 0, 0 ) ) ;
            return this ;
        }
    }
}
