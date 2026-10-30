package com.sandy.sconsole.qimgtools.apps.nxrclassifier.ui;

import com.sandy.sconsole.qimgtools.apps.nxrclassifier.model.NXRItem;
import com.sandy.sconsole.qimgtools.apps.nxrclassifier.model.NXRSaveResult;
import com.sandy.sconsole.qimgtools.apps.nxrclassifier.model.NXRStatus;
import com.sandy.sconsole.qimgtools.apps.nxrclassifier.model.NXRUpdate;
import com.sandy.sconsole.qimgtools.apps.nxrclassifier.model.NXRValue;
import com.sandy.sconsole.qimgtools.apps.nxrclassifier.server.NXRServerClient;
import com.sandy.sconsole.qimgtools.apps.nxrclassifier.ui.NXRTableModel.Row;
import com.sandy.sconsole.qimgtools.core.server.SConsoleServer.ServerException;
import com.sandy.sconsole.qimgtools.core.ui.HeaderFilterController;
import com.sandy.sconsole.qimgtools.core.ui.ImagePreview;
import com.sandy.sconsole.qimgtools.core.ui.Spinner;
import com.sandy.sconsole.qimgtools.core.ui.ToolIcon;
import com.sandy.sconsole.nxr.NXRResult;

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
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SpinnerNumberModel;
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
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ItemEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The NXR classifier's window: the questions awaiting their NXR label in a
 * table, the classifier's label and the one to save, the selected
 * question's image, and buttons (and keys) to label it.
 * <p>
 * Load fetches the first unlabelled questions from the server in place of
 * those shown and runs the classifier on each; its label is taken as it is.
 * The user labels the questions it left (or overrides it), and Save sends
 * every label to the server. Run unattended loops: load, classify, save,
 * pause. Every command is an {@link Action} shared by the menus and the
 * toolbar. Row state lives in {@link NXRTableModel}, on the EDT.
 */
@Slf4j
@SuppressWarnings( "serial" )
public class NXRFrame extends JFrame implements NXRDecisionPanel.Actions {

    /** What the window asks of the app. */
    public interface Controller {

        /**
         * The first {@code batchSize} questions with no label, each with its
         * local images found. Blocks; called off the EDT.
         */
        Loaded load( int batchSize ) throws ServerException ;

        /**
         * The classifier's result for {@code item} (which has images): its
         * label, or null with the reason. Blocks; called off the EDT.
         *
         * @throws IOException if the question's images can't be read
         */
        NXRResult classify( NXRItem item ) throws IOException ;

        /**
         * Save {@code updates} on the server; a request that fails refuses
         * all of its updates, with the reason. Blocks; called off the EDT.
         *
         * @return the server's result for each update, in the same order
         */
        List<NXRSaveResult> save( List<NXRUpdate> updates ) ;
    }

    /**
     * A load: its questions, and how many await a label on the server, the
     * returned included.
     */
    public record Loaded( List<NXRItem> items, int pending ) {}

    private static final double SPLIT_RATIO          = 0.5 ;
    private static final int    PREVIEW_PANEL_HEIGHT = 500 ;
    private static final int    PREVIEW_IMAGE_WIDTH  = 800 ;
    private static final int    ROW_HEIGHT           = 20 ;
    private static final int    DETAIL_ROWS          = 2 ;

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
     * columns of {@link NXRTableModel}; edit to taste.
     */
    private static final int[] COLUMN_WIDTHS = {
             50,    // Id
            160,    // Source
             90,    // Question
             50,    // Type
             80,    // Classifier
             60,    // Conf %
            130,    // Label
             90,    // Status
            SQUARE  // saved dot (untitled)
    } ;

    /** Row background per status; a status not listed keeps the table's. */
    private static final Map<NXRStatus,Color> STATUS_BG =
            new EnumMap<>( Map.ofEntries(
                    Map.entry( NXRStatus.CLASSIFIED, new Color( 0xE2F1E3 ) ),
                    Map.entry( NXRStatus.UNDECIDED,  new Color( 0xFFE6CC ) ),
                    Map.entry( NXRStatus.NO_IMAGES,  new Color( 0xEEEEEE ) ),
                    Map.entry( NXRStatus.FAILED,     new Color( 0xF9E6E8 ) ),
                    Map.entry( NXRStatus.DECIDED,    new Color( 0xF6E0EA ) ),
                    Map.entry( NXRStatus.SAVING,     new Color( 0xE8E3F2 ) ),
                    Map.entry( NXRStatus.SAVED,      new Color( 0xDCEFED ) ),
                    Map.entry( NXRStatus.ERROR,      new Color( 0xF4D2D2 ) ) ) ) ;

    /** The Status cell of an Undecided row. */
    private static final Color UNDECIDED_CELL_BG = new Color( 0xD32F2F ) ;

    /** Which rows the table shows. */
    private enum Show {

        ALL      ( "All questions",   r -> true ),
        UNDECIDED( "Undecided",       r -> r.status == NXRStatus.UNDECIDED ),
        UNSAVED  ( "Unsaved",         Row::isSavable ),
        PROBLEMS ( "Failed / errors", r -> r.status == NXRStatus.FAILED
                                           || r.status == NXRStatus.ERROR
                                           || r.status == NXRStatus.NO_IMAGES ) ;

        final String         label ;
        final Predicate<Row> test ;

        Show( String label, Predicate<Row> test ) {
            this.label = label ;
            this.test  = test ;
        }

        @Override
        public String toString() { return label ; }
    }

    /** The classifier's answer for one row, published by its worker. */
    private record Outcome( Row row, NXRResult result, String failure ) {}

    private Controller controller ;

    private final NXRTableModel                    model ;
    private final JTable                           table ;
    private final TableRowSorter<NXRTableModel>    sorter ;
    private final HeaderFilterController<Row>      headerFilters ;
    private final NXRDecisionPanel                 decisionPanel ;
    private final ImagePreview                     preview   =
            new ImagePreview( PREVIEW_PANEL_HEIGHT, PREVIEW_IMAGE_WIDTH ) ;
    private final JTextArea                        detail    =
            new JTextArea( DETAIL_ROWS, 20 ) ;
    private final JLabel                           statusBar =
            new JLabel( " " ) ;

    // The menus and the toolbar share these actions, and so their state.

    private final Action loadAction = action( "Load", ToolIcon.LOAD,
            shortcut( KeyEvent.VK_L ), "Fetch the first questions with no " +
            "label and classify them (replaces the table)", this::load ) ;
    private final Action reclassifyAction = action(
            "Classify selected again", ToolIcon.REQUEUE, null,
            "Run the classifier again on the selected questions not saved; " +
            "a label of the user's stays", this::reclassifySelected ) ;
    private final Action saveAction = action( "Save labels",
            ToolIcon.SAVE, shortcut( KeyEvent.VK_S ), "Send every label " +
            "not on the server yet, the classifier's and the user's (red " +
            "while any wait)", this::saveAll ) ;
    private final Action clearAction = action( "Clear user labels…",
            ToolIcon.CLEAR, null, "Forget the labels chosen by the user " +
            "and not saved; the classifier's stand", this::clearAll ) ;
    private final Action selectAllAction = action( "Select all shown rows",
            null, shortcut( KeyEvent.VK_A ), null, this::selectAllRows ) ;

    /** Named "Stop unattended processing" while the loop runs. */
    private final Action unattendedAction = action( RUN_UNATTENDED, null,
            shortcut( KeyEvent.VK_U ), "Load, classify, save, and repeat " +
            "up to a given number of loads", this::toggleUnattended ) ;

    /** The decision keys 1-5 as menu items; the keys are the table's. */
    private final Action[] decideActions = {
            action( "1  N  Numeric", null, null, null, () -> pressKey( 1 ) ),
            action( "2  X  Reasoning", null, null, null, () -> pressKey( 2 ) ),
            action( "3  R  Recall", null, null, null, () -> pressKey( 3 ) ),
            action( "4  Clear the user's label", null, null, null,
                    () -> pressKey( 4 ) ),
            action( "5  Skip to the next undecided", null, null, null,
                    () -> pressKey( 5 ) ) } ;

    /** The toolbar's Load and Save buttons spin while their call runs. */
    private final JButton loadButton = toolButton( loadAction ) ;
    private final JButton saveButton = toolButton( saveAction ) ;

    private final JCheckBox loadAfterSaveBox =
            new JCheckBox( "then load again", true ) ;
    private final JComboBox<Show> showBox = new JComboBox<>( Show.values() ) ;
    private final JSpinner batchSpinner ;

    /** True while a Load's call runs, and while the classifier runs. */
    private boolean loading ;
    private boolean classifying ;

    /**
     * Questions awaiting a label on the server, as of the last load less
     * those saved since; -1 before the first Load.
     */
    private int pending = -1 ;

    /** True while Run unattended loops: load, classify, save. */
    private boolean unattended ;

    /** The loads the unattended run has made. */
    private int loads ;

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

    /** Counts down the pause before the next unattended batch. */
    private Timer pauseTimer ;

    /** @param batchSize the questions a Load fetches, to start with */
    public NXRFrame( int batchSize ) {

        super( "NXRClassifier" ) ;
        this.model         = new NXRTableModel() ;
        this.table         = new JTable( model ) ;
        this.sorter        = new TableRowSorter<>( model ) ;
        this.headerFilters = new HeaderFilterController<>( table, model,
                                                           this::applyFilter ) ;
        this.decisionPanel = new NXRDecisionPanel( this ) ;
        this.batchSpinner  = new JSpinner( new SpinnerNumberModel(
                batchSize, 1, NXRServerClient.MAX_BATCH, 1 ) ) ;

        buildTable() ;

        loadAfterSaveBox.setToolTipText( "After Save labels, load the " +
                                         "first questions with no label " +
                                         "again" ) ;
        loadAfterSaveBox.setFocusable( false ) ;
        showBox.addActionListener( e -> {
            applyFilter() ;
            table.requestFocusInWindow() ;
        } ) ;
        batchSpinner.setToolTipText( "Questions a Load fetches (1-" +
                                     NXRServerClient.MAX_BATCH + ")" ) ;
        ( (JSpinner.DefaultEditor) batchSpinner.getEditor() ).getTextField()
                                                             .setColumns( 3 ) ;
        batchSpinner.setMaximumSize( batchSpinner.getPreferredSize() ) ;
        setJMenuBar( buildMenuBar() ) ;

        JToolBar toolbar = new JToolBar() ;
        toolbar.setFloatable( false ) ;
        toolbar.add( new JLabel( "Batch: " ) ) ;
        toolbar.add( batchSpinner ) ;
        toolbar.add( loadButton ) ;
        toolbar.addSeparator() ;
        toolbar.add( saveButton ) ;
        toolbar.add( loadAfterSaveBox ) ;
        toolbar.add( toolButton( clearAction ) ) ;
        toolbar.addSeparator() ;
        toolbar.add( new JLabel( "Show: " ) ) ;
        // As wide as its longest label; the toolbar would stretch it.
        showBox.setMaximumSize( showBox.getPreferredSize() ) ;
        toolbar.add( showBox ) ;

        // The table and the selected row's detail on the left; the label
        // buttons above the image on the right.
        detail.setEditable( false ) ;
        detail.setLineWrap( true ) ;
        detail.setWrapStyleWord( true ) ;
        detail.setFocusable( false ) ;
        detail.setBorder( BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder( 1, 0, 0, 0, Color.GRAY ),
                BorderFactory.createEmptyBorder( 4, 8, 4, 8 ) ) ) ;

        JPanel left = new JPanel( new BorderLayout() ) ;
        left.add( new JScrollPane( table ), BorderLayout.CENTER ) ;
        left.add( detail, BorderLayout.SOUTH ) ;
        detail.setBackground( left.getBackground() ) ;

        JPanel right = new JPanel( new BorderLayout() ) ;
        right.add( decisionPanel, BorderLayout.NORTH ) ;
        right.add( preview, BorderLayout.CENTER ) ;

        JSplitPane split = new JSplitPane( JSplitPane.HORIZONTAL_SPLIT,
                                           left, right ) ;
        split.setResizeWeight( SPLIT_RATIO ) ;
        split.setContinuousLayout( true ) ;

        statusBar.setBorder( BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder( 1, 0, 0, 0, Color.GRAY ),
                BorderFactory.createEmptyBorder( 4, 8, 4, 8 ) ) ) ;

        setLayout( new BorderLayout() ) ;
        add( toolbar, BorderLayout.NORTH ) ;
        add( split, BorderLayout.CENTER ) ;
        add( statusBar, BorderLayout.SOUTH ) ;

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
                dispose() ;
                System.exit( 0 ) ;
            }
        } ) ;
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
     * File (load, unattended, exit), Labels (the decision keys, classifying
     * again, saving, clearing) and View (status filter, header filters,
     * select all). The toolbar shares their actions.
     */
    private JMenuBar buildMenuBar() {

        JMenu     file = new JMenu( "File" ) ;
        JMenuItem exit = new JMenuItem( "Exit" ) ;

        exit.addActionListener( e -> dispatchEvent(
                new WindowEvent( this, WindowEvent.WINDOW_CLOSING ) ) ) ;
        file.add( loadAction ) ;
        file.add( unattendedAction ) ;
        file.addSeparator() ;
        file.add( exit ) ;

        JMenu             labels    = new JMenu( "Labels" ) ;
        JCheckBoxMenuItem loadAfter = new JCheckBoxMenuItem(
                "Load again after saving" ) ;

        // One model, so the menu item and the toolbar's box stay in step.
        loadAfter.setModel( loadAfterSaveBox.getModel() ) ;
        for( Action a : decideActions ) labels.add( a ) ;
        labels.addSeparator() ;
        labels.add( reclassifyAction ) ;
        labels.addSeparator() ;
        labels.add( saveAction ) ;
        labels.add( loadAfter ) ;
        labels.add( clearAction ) ;
        refreshOnOpen( labels ) ;

        JMenu     view         = new JMenu( "View" ) ;
        JMenuItem clearFilters = new JMenuItem( "Clear all column filters" ) ;

        clearFilters.addActionListener( e -> headerFilters.clearAll() ) ;
        view.add( buildShowMenu() ) ;
        view.add( clearFilters ) ;
        view.addSeparator() ;
        view.add( selectAllAction ) ;

        JMenuBar bar = new JMenuBar() ;
        bar.add( file ) ;
        bar.add( labels ) ;
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

    /**
     * Enable the actions on the selected rows as the selection allows; the
     * labels as the decision panel's buttons are.
     */
    private void updateSelectionActions() {

        for( int n = 1 ; n <= 4 ; n++ ) {
            decideActions[n - 1].setEnabled( !unattended
                                             && decisionPanel.canPress( n ) ) ;
        }
        decideActions[4].setEnabled( table.getRowCount() > 0 ) ;
        reclassifyAction.setEnabled( !unattended && !busy()
                                     && !reclassifiable().isEmpty() ) ;
    }

    /**
     * Decision key {@code n}: 1-4 press the decision panel's buttons, 5
     * skips to the next undecided question.
     */
    private void pressKey( int n ) {
        if( n == 5 ) select( nextUndecided() ) ;
        else if( !unattended ) decisionPanel.press( n ) ;
    }

    private void buildTable() {

        // Re-filter as statuses change, so a labelled row leaves
        // "Undecided".
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

    /**
     * Right-click on the table, for the selected rows, or the clicked row if
     * it isn't one of them: the labels, classifying again, and saving or
     * clearing their labels.
     */
    private void addRowMenu() {

        JPopupMenu menu  = new JPopupMenu() ;
        JMenuItem  save  = new JMenuItem() ;
        JMenuItem  clear = new JMenuItem() ;

        save.addActionListener( e -> save( selectedRows(), null, () -> {} ) ) ;
        clear.addActionListener( e -> clearLabel( selectedRows() ) ) ;
        for( int i = 0 ; i < 4 ; i++ ) menu.add( decideActions[i] ) ;
        menu.addSeparator() ;
        menu.add( reclassifyAction ) ;
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
                int       savable = count( sel, Row::isSavable ) ;
                int       unsaved = count( sel, Row::isUnsaved ) ;

                updateSelectionActions() ;
                save.setText( "Save " + plural( savable, "label" ) ) ;
                save.setEnabled( savable > 0 && !unattended && !busy()
                                 && controller != null ) ;
                clear.setText( "Clear " + plural( unsaved, "user label" ) ) ;
                clear.setEnabled( unsaved > 0 && !unattended ) ;
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

    /**
     * Select every row the filter shows: View's Cmd+A (Ctrl+A off the Mac),
     * unless the focused component uses the key itself.
     */
    private void selectAllRows() {
        table.selectAll() ;
        table.requestFocusInWindow() ;
    }

    /**
     * Keys on the table (main row or keypad), for every selected row: 1 or
     * N numeric, 2 or X reasoning, 3 or R recall, Space the classifier's
     * low-confidence guess, 4 clear the user's label; 5 skips to the next
     * undecided question. The Labels menu's items show
     * them; they aren't its accelerators, which would take the keys from
     * the whole window.
     */
    private void bindDecisionKeys() {

        InputMap  keys    = table.getInputMap( JComponent.WHEN_FOCUSED ) ;
        ActionMap actions = table.getActionMap() ;
        int[]     letters = { KeyEvent.VK_N, KeyEvent.VK_X, KeyEvent.VK_R } ;

        for( int n = 1 ; n <= 5 ; n++ ) {
            String name = "decision-" + n ;
            int    key  = n ;

            keys.put( KeyStroke.getKeyStroke( KeyEvent.VK_0 + n, 0 ), name ) ;
            keys.put( KeyStroke.getKeyStroke( KeyEvent.VK_NUMPAD0 + n, 0 ), name ) ;
            if( n <= letters.length ) {
                keys.put( KeyStroke.getKeyStroke( letters[n - 1], 0 ), name ) ;
            }
            // Not the menu's action: its enabled state is only brought up
            // to date as a menu opens.
            actions.put( name, new AbstractAction() {
                @Override public void actionPerformed( ActionEvent e ) {
                    pressKey( key ) ;
                }
            } ) ;
        }

        // Space takes the classifier's low-confidence guess (its button is
        // framed); it replaces the table's own Space, add-to-selection.
        keys.put( KeyStroke.getKeyStroke( KeyEvent.VK_SPACE, 0 ), "guess" ) ;
        actions.put( "guess", new AbstractAction() {
            @Override public void actionPerformed( ActionEvent e ) {
                if( !unattended ) decisionPanel.pressGuess() ;
            }
        } ) ;
    }

    // ---- loading and classifying ---------------------------------------

    /**
     * Fetch the first questions with no label off the EDT, show them in
     * place of the rows loaded so far, and classify them.
     */
    private void load() {

        if( controller == null || busy() ) return ;
        if( !discardUnsaved( "Load" ) ) return ;
        fetch( loaded -> {
            showItems( loaded ) ;
            classify( model.rows(), () -> {} ) ;
        } ) ;
    }

    /**
     * Fetch a batch, without asking about unsaved labels, and hand it to
     * {@code onLoaded}; a failure is reported instead, and ends an
     * unattended run.
     */
    private void fetch( Consumer<Loaded> onLoaded ) {

        int limit = (Integer) batchSpinner.getValue() ;

        loading = true ;
        statusBar.setText( "Loading the questions with no label…" ) ;
        Spinner.busy( loadButton ) ;
        updateControls() ;

        new SwingWorker<Loaded,Void>() {
            @Override protected Loaded doInBackground() throws Exception {
                return controller.load( limit ) ;
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
                    JOptionPane.showMessageDialog( NXRFrame.this,
                            "Could not load the questions:\n" +
                            cause.getMessage(), "Load",
                            JOptionPane.ERROR_MESSAGE ) ;
                }
                updateControls() ;
                if( loaded != null ) onLoaded.accept( loaded ) ;
            }
        }.execute() ;
    }

    /** Fill the table with {@code loaded}; the rows shown before are dropped. */
    private void showItems( Loaded loaded ) {

        pending = loaded.pending() ;
        model.setItems( loaded.items() ) ;
        decisionPanel.show( List.of() ) ;
        showDetail( null ) ;
        preview.show( null ) ;
        updateControls() ;
    }

    /**
     * Run the classifier off the EDT on those of {@code rows} with images,
     * showing each label as it comes, then run {@code after}. A label the
     * user chose stays.
     */
    private void classify( List<Row> rows, Runnable after ) {

        List<Row> todo = rows.stream().filter( r -> r.item.hasImages() )
                             .toList() ;

        classifying = true ;
        updateControls() ;

        new SwingWorker<Void,Outcome>() {
            @Override protected Void doInBackground() {
                for( Row row : todo ) {
                    try {
                        publish( new Outcome(
                                row, controller.classify( row.item ), null ) ) ;
                    }
                    catch( Exception e ) {
                        log.error( "{}: could not classify", row.item, e ) ;
                        publish( new Outcome( row, null,
                                              String.valueOf( e.getMessage() ) ) ) ;
                    }
                }
                return null ;
            }

            @Override protected void process( List<Outcome> outcomes ) {
                outcomes.forEach( NXRFrame.this::setOutcome ) ;
            }

            @Override protected void done() {

                classifying = false ;
                try {
                    get() ;
                }
                catch( Exception e ) {
                    log.error( "Classifying failed", e ) ;
                }
                updateControls() ;
                if( table.getSelectedRowCount() == 0 ) {
                    select( firstUndecided() ) ;
                }
                after.run() ;
            }
        }.execute() ;
    }

    /** Record the classifier's answer on its row. */
    private void setOutcome( Outcome o ) {

        Row row = o.row() ;

        row.result            = o.result() ;
        row.suggested         = o.result() == null
                              ? null : NXRValue.of( o.result().value() ) ;
        row.classifiedStatus  = o.failure() != null  ? NXRStatus.FAILED
                              : row.suggested != null ? NXRStatus.CLASSIFIED
                              : NXRStatus.UNDECIDED ;
        row.classifiedMessage = o.failure() != null
                              ? "The classifier failed: " + o.failure()
                              : "Classifier: " + row.classifierText() ;
        // A label of the user's stands.
        if( row.chosen == null ) {
            row.status  = row.classifiedStatus ;
            row.message = row.classifiedMessage ;
        }
        rowChanged( row ) ;
    }

    /** The selected rows the classifier may be run on again. */
    private List<Row> reclassifiable() {
        return selectedRows().stream()
                             .filter( r -> r.item.hasImages()
                                           && r.status != NXRStatus.SAVING
                                           && r.status != NXRStatus.SAVED )
                             .toList() ;
    }

    private void reclassifySelected() {
        if( controller == null || busy() || unattended ) return ;
        classify( reclassifiable(), () -> {} ) ;
    }

    // ---- labels --------------------------------------------------------

    /**
     * Give every open row of {@code rows} the user's label {@code value},
     * then move on to the next undecided question.
     */
    @Override
    public void label( List<Row> rows, NXRValue value ) {

        if( unattended ) return ;
        for( Row row : rows ) {
            if( !row.isOpen() ) continue ;
            row.chosen  = value ;
            row.status  = NXRStatus.DECIDED ;
            row.message = null ;
            rowChanged( row ) ;
        }
        Row next = nextUndecided() ;
        if( next != null && rows.size() == 1 ) select( next ) ;
    }

    /** Drop the user's labels of {@code rows}: the classifier's stand. */
    @Override
    public void clearLabel( List<Row> rows ) {

        for( Row row : rows ) {
            if( !row.isOpen() || row.chosen == null ) continue ;
            row.chosen  = null ;
            row.status  = row.classifiedStatus ;
            row.message = row.classifiedMessage ;
            rowChanged( row ) ;
        }
        updateControls() ;
    }

    /**
     * The next row shown, after the last one selected and wrapping round,
     * that is undecided; failing that, the next one failed or refused by
     * the server; null if there is none.
     */
    private Row nextUndecided() {

        int count    = table.getRowCount() ;
        int from     = table.getSelectionModel().getMaxSelectionIndex() ;
        Row fallback = null ;

        for( int k = 1 ; k <= count ; k++ ) {
            Row row = rowAt( Math.floorMod( from + k, count ) ) ;
            if( row.status == NXRStatus.UNDECIDED ) return row ;
            if( fallback == null && ( row.status == NXRStatus.FAILED
                                      || row.status == NXRStatus.ERROR ) ) {
                fallback = row ;
            }
        }
        return fallback ;
    }

    /** The first row shown that is undecided; null if none is. */
    private Row firstUndecided() {

        for( int view = 0 ; view < table.getRowCount() ; view++ ) {
            Row row = rowAt( view ) ;
            if( row.status == NXRStatus.UNDECIDED ) return row ;
        }
        return null ;
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

    /** Send every label not on the server, then load again if asked to. */
    private void saveAll() {

        boolean reload = loadAfterSaveBox.isSelected() ;
        save( model.rows(), saveButton, () -> {
            if( reload && pending > 0 && !unattended ) load() ;
        } ) ;
    }

    /**
     * Send the labels of {@code rows} the server doesn't hold, then run
     * {@code after} (at once if there are none); {@code button}, if not
     * null, spins meanwhile, and the rows being saved spin in their Status
     * cell.
     */
    private void save( List<Row> rows, JButton button, Runnable after ) {

        if( controller == null ) return ;
        List<Row> savable = rows.stream().filter( Row::isSavable ).toList() ;
        if( savable.isEmpty() ) {
            after.run() ;
            return ;
        }

        List<NXRUpdate> updates = savable.stream().map( Row::update )
                                         .toList() ;

        for( Row row : savable ) {
            row.status  = NXRStatus.SAVING ;
            row.message = "Saving " + row.value().describe() ;
            rowChanged( row ) ;
        }
        if( button != null ) Spinner.busy( button ) ;

        new SwingWorker<List<NXRSaveResult>,Void>() {
            @Override protected List<NXRSaveResult> doInBackground() {
                return controller.save( updates ) ;
            }

            @Override protected void done() {

                List<NXRSaveResult> results ;

                if( button != null ) Spinner.idle( button ) ;
                try {
                    results = get() ;
                }
                catch( Exception e ) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e ;
                    log.error( "Could not save {} labels", updates.size(),
                               cause ) ;
                    results = updates.stream()
                                     .map( u -> NXRSaveResult.failed(
                                             u.item().id(),
                                             String.valueOf( cause ) ) )
                                     .toList() ;
                }
                for( int i = 0 ; i < updates.size() ; i++ ) {
                    Row row = model.rowOf( updates.get( i ).item() ) ;
                    if( row == null ) continue ;
                    showResult( row, updates.get( i ), results.get( i ) ) ;
                    rowChanged( row ) ;
                }
                after.run() ;
            }
        }.execute() ;
    }

    /**
     * Show on {@code row} the server's answer {@code r} to update {@code u}.
     * The server doesn't send the question back, so a label it took is set
     * on the row's copy here.
     */
    private void showResult( Row row, NXRUpdate u, NXRSaveResult r ) {

        // A refused label stays, to save again or clear.
        if( !r.accepted() ) {
            row.status  = NXRStatus.ERROR ;
            row.message = "Not saved: " + ( r.message() != null
                                            ? r.message()
                                            : "refused by the server" ) ;
            return ;
        }
        if( row.question.getNxrValue() == null && pending > 0 ) pending-- ;
        row.question.setNxrValue( u.value().name() ) ;
        row.status  = NXRStatus.SAVED ;
        row.message = "Saved: " + u.describe() +
                      ( r.status() == NXRSaveResult.Status.UNCHANGED
                        ? ", as it already was" : "" ) ;
    }

    private void clearAll() {

        long n = model.unsavedCount() ;
        if( n == 0 ) return ;

        int answer = JOptionPane.showConfirmDialog( this,
                "Clear the " + plural( (int) n, "user label" ) +
                "?\nThe classifier's labels stay.", "Clear user labels",
                JOptionPane.OK_CANCEL_OPTION ) ;
        if( answer == JOptionPane.OK_OPTION ) clearLabel( model.rows() ) ;
    }

    /**
     * True if there are no unsaved labels of the user's, or the user agrees
     * to lose them; {@code title} names what would lose them. The
     * classifier's labels are not asked about: they come back with a Load.
     */
    private boolean discardUnsaved( String title ) {

        long n = model.unsavedCount() ;
        if( n == 0 ) return true ;
        return JOptionPane.showConfirmDialog( this,
                plural( (int) n, "user label" ) + " not saved yet will be " +
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
     * Loop until stopped, logging each step: load a batch, classify it,
     * save every label the classifier gave, and after
     * {@link #UNATTENDED_PAUSE_SECONDS} load the next. The loop ends by
     * itself after as many loads as the user gives as it starts, when
     * nothing awaits a label, or when a batch saves none (the next load
     * would bring the same questions back).
     */
    private void startUnattended() {

        if( controller == null || busy() ) return ;
        if( !askMaxLoads() ) return ;
        if( !discardUnsaved( RUN_UNATTENDED ) ) return ;

        unattended = true ;
        loads      = 0 ;

        unattendedAction.putValue( Action.NAME, STOP_UNATTENDED ) ;
        log.info( "[unattended] Started, at most {} loads of {}", maxLoads,
                  batchSpinner.getValue() ) ;
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
     * End the unattended run, if one is going, saying {@code why}; a step
     * under way finishes, and the table is left as it is for the user to
     * take over.
     */
    private void endUnattended( String why ) {

        if( !unattended ) return ;
        unattended     = false ;
        unattendedStep = "ended, " + why ;

        if( pauseTimer != null ) pauseTimer.stop() ;
        pauseTimer = null ;
        unattendedAction.putValue( Action.NAME, RUN_UNATTENDED ) ;
        log.info( "[unattended] Ended in batch {}: {}", loads, why ) ;
        updateControls() ;
    }

    private void nextBatch() {

        if( !unattended ) return ;
        loads++ ;
        log.info( "[unattended] Batch {}: loading", loads ) ;
        step( "loading" ) ;
        fetch( loaded -> {
            showItems( loaded ) ;
            batchLoaded() ;
        } ) ;
    }

    /** The batch is shown: classify it. */
    private void batchLoaded() {

        if( !unattended ) return ;
        if( model.getRowCount() == 0 ) {
            endUnattended( "no questions await a label" ) ;
            return ;
        }
        log.info( "[unattended] Batch {}: {} loaded, {} await a label on " +
                  "the server", loads, model.getRowCount(), pending ) ;
        step( "classifying " + plural( model.getRowCount(), "question" ) ) ;
        classify( model.rows(), this::batchClassified ) ;
    }

    /** The batch is classified: save the labels the classifier gave. */
    private void batchClassified() {

        if( !unattended ) return ;
        for( Row row : model.rows() ) {
            log.info( "[unattended]   {}: {}{}", row.questionLabel(),
                      row.value() != null ? row.value().describe()
                                          : row.status.getLabel(),
                      row.message != null ? " - " + row.message : "" ) ;
        }
        step( "saving" ) ;
        save( model.rows(), saveButton, this::batchSaved ) ;
    }

    /**
     * The batch is saved: log the outcome, and pause before the next one,
     * unless nothing is left or the batch saved no label.
     */
    private void batchSaved() {

        if( !unattended ) return ;
        long saved = model.count( NXRStatus.SAVED ) ;

        for( Row row : model.rows() ) {
            if( row.status != NXRStatus.ERROR ) continue ;
            log.warn( "[unattended]   {}: not saved - {}",
                      row.questionLabel(), row.message ) ;
        }
        log.info( "[unattended] Batch {} saved: {} saved, {} undecided, " +
                  "{} failed, {} errors, {} without images; {} await a " +
                  "label", loads, saved,
                  model.count( NXRStatus.UNDECIDED ),
                  model.count( NXRStatus.FAILED ),
                  model.count( NXRStatus.ERROR ),
                  model.count( NXRStatus.NO_IMAGES ), pending ) ;

        if( pending == 0 ) endUnattended( "no questions await a label" ) ;
        else if( saved == 0 ) {
            endUnattended( "the batch saved no label, so the next load " +
                           "would bring the same questions back" ) ;
        }
        else if( loads >= maxLoads ) {
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

    /** Show {@code what} the unattended run is doing in the status bar. */
    private void step( String what ) {
        unattendedStep = what ;
        updateControls() ;
    }

    // ---- display -------------------------------------------------------

    private void rowChanged( Row row ) {
        model.changed( row ) ;
        decisionPanel.refresh( row ) ;
        if( table.getSelectedRowCount() == 1 && selectedRow() == row ) {
            showDetail( row ) ;
        }
        updateControls() ;
    }

    private void showSelection() {

        Row row = selectedRow() ;

        decisionPanel.show( selectedRows() ) ;
        showDetail( table.getSelectedRowCount() == 1 ? row : null ) ;
        preview.show( row != null && row.item.hasImages()
                      ? row.item.task().getImgFiles() : null ) ;
    }

    /**
     * Show what is going on with {@code row} under the table: its status
     * and what failed or was saved; blank for null.
     */
    private void showDetail( Row row ) {

        if( row == null ) {
            detail.setText( "" ) ;
            return ;
        }
        String text = row.questionLabel() + ": " + row.status.getLabel() ;
        if( row.message != null ) text += "\n" + row.message ;
        detail.setText( text ) ;
        detail.setCaretPosition( 0 ) ;
    }

    private Row selectedRow() {
        int view = table.getSelectedRow() ;
        return view < 0 ? null
                        : model.getRow( table.convertRowIndexToModel( view ) ) ;
    }

    private void applyFilter() {

        Show show = (Show) showBox.getSelectedItem() ;
        sorter.setRowFilter( new RowFilter<NXRTableModel,Integer>() {
            @Override
            public boolean include(
                    Entry<? extends NXRTableModel,? extends Integer> e ) {
                Row row = model.getRow( e.getIdentifier() ) ;
                return show.test.test( row ) && headerFilters.accepts( row ) ;
            }
        } ) ;
    }

    /** True while a load, the classifier or a save runs. */
    private boolean busy() {
        return loading || classifying
               || model.count( NXRStatus.SAVING ) > 0 ;
    }

    private void updateControls() {

        boolean saving  = model.count( NXRStatus.SAVING ) > 0 ;
        boolean idle    = controller != null && !busy() ;
        boolean manual  = !unattended ;
        boolean savable = model.rows().stream().anyMatch( Row::isSavable ) ;

        // An unattended run does the loading, classifying and saving itself.
        loadAction.setEnabled( manual && idle ) ;
        unattendedAction.setEnabled( unattended || idle ) ;
        batchSpinner.setEnabled( manual ) ;
        // Its icon is red while enabled, i.e. while labels wait.
        saveAction.setEnabled( manual && idle && savable ) ;
        clearAction.setEnabled( manual && model.unsavedCount() > 0 ) ;
        updateSelectionActions() ;
        if( saving ) Spinner.spin( table ) ;
        else Spinner.halt( table ) ;
        if( !loading ) refreshStatusBar() ;
    }

    private void refreshStatusBar() {

        String text = String.format(
                "%d loaded · %d classified · %d undecided · %d decided · " +
                "%d saved · %d failed · %d errors · %d without images",
                model.getRowCount(),
                model.count( NXRStatus.CLASSIFIED ),
                model.count( NXRStatus.UNDECIDED ),
                model.count( NXRStatus.DECIDED ),
                model.count( NXRStatus.SAVED ),
                model.count( NXRStatus.FAILED ),
                model.count( NXRStatus.ERROR ),
                model.count( NXRStatus.NO_IMAGES ) ) ;
        if( pending >= 0 ) {
            text += " · " + pending + " awaiting a label on the server" ;
        }
        if( classifying ) text += "   |   classifying" ;
        // Kept after the run ends, to say why it did.
        if( unattendedStep != null ) {
            text = "Unattended, batch " + loads + " of " + maxLoads + ": " +
                   unattendedStep + "   |   " + text ;
        }
        statusBar.setText( text ) ;
    }

    /**
     * Tints rows by status, an Undecided status red, spins in the Status
     * cell of a row being saved, and shows the last column as a dot: green
     * once the label is saved on the server, red until then.
     */
    private final class RowRenderer extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent( JTable t, Object value,
                boolean selected, boolean focus, int viewRow, int column ) {

            int       col  = t.convertColumnIndexToModel( column ) ;
            boolean   dot  = col == NXRTableModel.COL_SAVED ;

            // setForeground sticks to the renderer, which paints every
            // cell: clear it so each cell starts with the table's colour.
            setForeground( null ) ;
            Component c    = super.getTableCellRendererComponent(
                    t, dot ? null : value, selected, focus, viewRow, column ) ;
            Row       row  = model.getRow( t.convertRowIndexToModel( viewRow ) ) ;
            boolean   stat = col == NXRTableModel.COL_STATUS ;

            setHorizontalAlignment( dot ? CENTER
                                    : col == NXRTableModel.COL_CONFIDENCE
                                      ? RIGHT : LEADING ) ;
            if( dot ) setIcon( (Boolean) value ? Dot.SAVED : Dot.UNSAVED ) ;
            else {
                setIcon( stat && row.status == NXRStatus.SAVING
                         ? Spinner.ICON : null ) ;
            }
            // An Undecided status is red, selected or not, to stand out.
            if( stat && row.status == NXRStatus.UNDECIDED ) {
                c.setBackground( UNDECIDED_CELL_BG ) ;
                c.setForeground( Color.WHITE ) ;
                return c ;
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
