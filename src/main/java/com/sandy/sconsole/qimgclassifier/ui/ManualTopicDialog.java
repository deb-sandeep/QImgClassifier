package com.sandy.sconsole.qimgclassifier.ui;

import com.sandy.sconsole.qimgclassifier.model.QTask;
import com.sandy.sconsole.qimgclassifier.model.Topic;
import com.sandy.sconsole.qimgclassifier.model.TopicRepo;
import com.sandy.sconsole.qimgclassifier.util.ImageCollator;
import com.sandy.sconsole.qimgclassifier.workspace.QuestionTopicAssociator;
import com.sandy.sconsole.qimgclassifier.workspace.WorkspaceTopicMap;

import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Modal picker for a hand-chosen topic: every topic of the question's syllabus,
 * in a two-column (topic, section) table, narrowed by a type-to-filter field,
 * with the question's (collated) image below. The question's current top stored
 * topic is preselected. Enter or a double-click saves the topic; Esc / Cancel
 * always closes.
 * <p>
 * With "Continue with next queued / failed question" ticked, a successful save
 * moves on to the next such question (as supplied by the {@link Flow}) instead
 * of closing.
 */
@Slf4j
@SuppressWarnings( "serial" )
final class ManualTopicDialog extends JDialog {

    /**
     * Supplied by the caller; every method is called on the EDT unless noted.
     */
    interface Flow {

        /**
         * Persist the topic. Blocking; called off the EDT. Throws on failure.
         */
        QuestionTopicAssociator.Outcome save( QTask task, Topic topic )
                throws Exception ;

        /** The topic for {@code task} was saved. */
        void saved( QTask task, Topic topic,
                    QuestionTopicAssociator.Outcome outcome ) ;

        /**
         * The next QUEUED or FAILED question after {@code task} (a QUEUED one
         * already held), or null.
         */
        QTask nextPending( QTask task ) ;

        /**
         * The dialog is closing without having saved a topic for {@code task}.
         */
        void abandoned( QTask task ) ;
    }

    private static final float FONT_DELTA     = 2f ;
    private static final int   IMAGE_HEIGHT   = 260 ;
    private static final int   DIALOG_WIDTH   = 760 ;
    private static ManualTopicDialog instance ;

    private Flow                    flow ;
    private final TopicTableModel   tableModel = new TopicTableModel() ;
    private final JTable            table      = new JTable( tableModel ) ;
    private final JTextField        filter     = new JTextField() ;
    private final JLabel            imageLabel =
            new JLabel( "", SwingConstants.CENTER ) ;
    private final JCheckBox         continueBox =
            new JCheckBox( "Continue with next queued / failed question" ) ;
    private final JButton           ok         = new JButton( "OK" ) ;

    private QTask         task ;
    private List<Topic>   topics = List.of() ;
    private BufferedImage image ;
    private boolean       saving ;

    /**
     * Shows the dialog for {@code task}; returns once it has been hidden. One
     * dialog per owner window is kept and re-populated, so its size, position
     * and checkbox state carry over between uses.
     */
    static void open( Component parent, QTask task, Flow flow ) {
        Window owner = SwingUtilities.getWindowAncestor( parent ) ;
        boolean created = instance == null || instance.getOwner() != owner ;
        if( created ) {
            if( instance != null ) instance.dispose() ;
            instance = new ManualTopicDialog( owner ) ;
        }
        instance.flow = flow ;
        instance.load( task ) ;
        if( created ) {
            instance.fitInitialSize() ;
            instance.setLocationRelativeTo( owner ) ;
        }
        instance.setVisible( true ) ;
    }

    private ManualTopicDialog( Window owner ) {
        super( owner, "Manual Topic Association", ModalityType.APPLICATION_MODAL ) ;

        table.setSelectionMode( ListSelectionModel.SINGLE_SELECTION ) ;
        table.setFillsViewportHeight( true ) ;
        table.getTableHeader().setReorderingAllowed( false ) ;
        enlargeFont( table ) ;
        enlargeFont( table.getTableHeader() ) ;
        enlargeFont( filter ) ;
        int fontHeight = table.getFontMetrics( table.getFont() ).getHeight() ;
        table.setRowHeight( fontHeight + 6 ) ;
        table.getColumnModel().getColumn( 0 ).setPreferredWidth( 480 ) ;
        table.getColumnModel().getColumn( 1 ).setPreferredWidth( 240 ) ;
        // Enter confirms instead of moving to the next row.
        table.getInputMap( JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT )
             .put( KeyStroke.getKeyStroke( KeyEvent.VK_ENTER, 0 ), "confirm" ) ;
        table.getActionMap().put( "confirm", new AbstractAction() {
            @Override public void actionPerformed( ActionEvent e ) {
                confirm() ;
            }
        } ) ;
        table.addMouseListener( new MouseAdapter() {
            @Override public void mouseClicked( MouseEvent e ) {
                if( e.getClickCount() == 2 ) confirm() ;
            }
        } ) ;

        filter.getDocument().addDocumentListener( new DocumentListener() {
            @Override
            public void insertUpdate( DocumentEvent e )  { applyFilter() ; }
            @Override
            public void removeUpdate( DocumentEvent e )  { applyFilter() ; }
            @Override
            public void changedUpdate( DocumentEvent e ) { applyFilter() ; }
        } ) ;
        // Up / Down in the filter field move the table selection.
        bindListMove( KeyEvent.VK_UP,   -1 ) ;
        bindListMove( KeyEvent.VK_DOWN, +1 ) ;

        JButton cancel = new JButton( "Cancel" ) ;
        ok.addActionListener( e -> confirm() ) ;
        cancel.addActionListener( e -> cancel() ) ;
        table.getSelectionModel().addListSelectionListener( e -> updateOk() ) ;
        getRootPane().setDefaultButton( ok ) ;
        getRootPane().registerKeyboardAction( e -> cancel(),
                KeyStroke.getKeyStroke( KeyEvent.VK_ESCAPE, 0 ),
                JComponent.WHEN_IN_FOCUSED_WINDOW ) ;
        setDefaultCloseOperation( DO_NOTHING_ON_CLOSE ) ;
        addWindowListener( new WindowAdapter() {
            @Override public void windowClosing( WindowEvent e ) { cancel() ; }
        } ) ;

        JPanel top = new JPanel( new BorderLayout( 6, 0 ) ) ;
        JLabel filterLabel = new JLabel( "Filter:" ) ;
        enlargeFont( filterLabel ) ;
        top.add( filterLabel, BorderLayout.WEST ) ;
        top.add( filter, BorderLayout.CENTER ) ;

        imageLabel.setOpaque( true ) ;
        imageLabel.setBackground( Color.WHITE ) ;
        imageLabel.setBorder( BorderFactory.createLineBorder( Color.LIGHT_GRAY ) ) ;
        imageLabel.setPreferredSize( new Dimension( DIALOG_WIDTH, IMAGE_HEIGHT ) ) ;
        imageLabel.addComponentListener( new ComponentAdapter() {
            @Override public void componentResized( ComponentEvent e ) {
                rescaleImage() ;
            }
        } ) ;

        JPanel buttons = new JPanel( new FlowLayout( FlowLayout.RIGHT ) ) ;
        buttons.add( cancel ) ;
        buttons.add( ok ) ;

        JPanel controls = new JPanel( new BorderLayout() ) ;
        controls.add( continueBox, BorderLayout.WEST ) ;
        controls.add( buttons, BorderLayout.EAST ) ;

        JPanel south = new JPanel( new BorderLayout( 0, 6 ) ) ;
        south.add( imageLabel, BorderLayout.CENTER ) ;
        south.add( controls, BorderLayout.SOUTH ) ;

        JPanel content = new JPanel( new BorderLayout( 0, 6 ) ) ;
        content.setBorder( BorderFactory.createEmptyBorder( 8, 8, 8, 8 ) ) ;
        content.add( top, BorderLayout.NORTH ) ;
        content.add( new JScrollPane( table ), BorderLayout.CENTER ) ;
        content.add( south, BorderLayout.SOUTH ) ;
        setContentPane( content ) ;
    }

    /**
     * Show {@code task}: its syllabus' topics (current topic preselected) and
     * its image.
     */
    private void load( QTask task ) {
        this.task = task ;
        setTitle( "Manual Topic Association - " + task ) ;

        topics = TopicRepo.instance().getTopics( task.getSubject() ) ;

        table.clearSelection() ;
        filter.setText( "" ) ;
        applyFilter() ;
        List<WorkspaceTopicMap.StoredMapping> stored =
                WorkspaceTopicMap.topicMappings( task.getSrcDir(),
                                                 task.getQuestionId() ) ;
        if( !stored.isEmpty() ) {
            int currentId = stored.get( 0 ).topicId() ;
            for( int i = 0 ; i < tableModel.rows.size() ; i++ ) {
                if( tableModel.rows.get( i ).getId() == currentId ) {
                    select( i ) ;
                    break ;
                }
            }
        }
        if( table.getSelectedRow() < 0 ) {
            table.scrollRectToVisible( table.getCellRect( 0, 0, true ) ) ;
        }

        loadImage( task ) ;
        updateOk() ;
        filter.requestFocusInWindow() ;
    }

    /**
     * Initial size: tall enough to show every topic of the first question's
     * syllabus without scrolling, within the screen. Later shows keep whatever
     * size the dialog then has.
     */
    private void fitInitialSize() {
        int viewportWidth = table.getPreferredScrollableViewportSize().width ;
        table.setPreferredScrollableViewportSize( new Dimension(
                viewportWidth, topics.size() * table.getRowHeight() ) ) ;
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                                              .getMaximumWindowBounds() ;
        setSize( DIALOG_WIDTH,
                 Math.min( getPreferredSize().height, screen.height ) ) ;
    }

    /**
     * Load (collate) the task's image in the background; ignored if the dialog
     * has moved on meanwhile.
     */
    private void loadImage( QTask forTask ) {
        image = null ;
        imageLabel.setIcon( null ) ;
        imageLabel.setText( "Loading image…" ) ;
        new SwingWorker<BufferedImage,Void>() {
            @Override protected BufferedImage doInBackground() throws Exception {
                return ImageCollator.collate( forTask.getImgFiles() ) ;
            }
            @Override protected void done() {
                // The dialog may have moved on to another question meanwhile.
                if( task != forTask ) return ;
                try {
                    image = get() ;
                    imageLabel.setText( null ) ;
                    rescaleImage() ;
                }
                catch( Exception e ) {
                    log.error( "Could not load image for {}", forTask, e ) ;
                    imageLabel.setText( "Could not load image" ) ;
                }
            }
        }.execute() ;
    }

    /**
     * Fit the image inside the image area, preserving aspect ratio; never
     * upscaled.
     */
    private void rescaleImage() {
        if( image == null ) return ;
        Insets in = imageLabel.getInsets() ;
        int availW = imageLabel.getWidth()  - in.left - in.right - 8 ;
        int availH = imageLabel.getHeight() - in.top - in.bottom - 8 ;
        if( availW <= 0 || availH <= 0 ) return ;
        double fit   = Math.min( (double) availW / image.getWidth(),
                                 (double) availH / image.getHeight() ) ;
        double scale = Math.min( 1.0, fit ) ;
        int w = Math.max( 1, (int) Math.round( image.getWidth()  * scale ) ) ;
        int h = Math.max( 1, (int) Math.round( image.getHeight() * scale ) ) ;
        imageLabel.setIcon( new ImageIcon(
                image.getScaledInstance( w, h, Image.SCALE_SMOOTH ) ) ) ;
    }

    /**
     * Narrow the rows to topics whose name or section contains the filter text,
     * keeping the selection if it survives, else selecting the first match.
     */
    private void applyFilter() {
        Topic selected = selectedTopic() ;
        String text = filter.getText().trim().toLowerCase() ;
        List<Topic> rows = new ArrayList<>() ;
        for( Topic t : topics ) {
            String section = t.getSection() == null ? "" : t.getSection() ;
            if( text.isEmpty()
                || t.getTopicName().toLowerCase().contains( text )
                || section.toLowerCase().contains( text ) ) {
                rows.add( t ) ;
            }
        }
        tableModel.setRows( rows ) ;
        int idx = selected != null ? rows.indexOf( selected ) : -1 ;
        if( idx < 0 && !text.isEmpty() && !rows.isEmpty() ) idx = 0 ;
        if( idx >= 0 ) select( idx ) ;
    }

    private Topic selectedTopic() {
        int row = table.getSelectedRow() ;
        return row >= 0 ? tableModel.rows.get( row ) : null ;
    }

    private void select( int row ) {
        table.setRowSelectionInterval( row, row ) ;
        table.scrollRectToVisible( table.getCellRect( row, 0, true ) ) ;
    }

    private void updateOk() {
        ok.setEnabled( !saving && selectedTopic() != null ) ;
    }

    private static void enlargeFont( JComponent c ) {
        Font f = c.getFont() ;
        c.setFont( f.deriveFont( f.getSize2D() + FONT_DELTA ) ) ;
    }

    /**
     * Bind {@code keyCode} in the filter field to move the table selection by
     * {@code delta} rows.
     */
    private void bindListMove( int keyCode, int delta ) {
        String key = "move" + delta ;
        filter.getInputMap().put( KeyStroke.getKeyStroke( keyCode, 0 ), key ) ;
        filter.getActionMap().put( key, new AbstractAction() {
            @Override public void actionPerformed( ActionEvent e ) {
                int size = tableModel.rows.size() ;
                if( size == 0 ) return ;
                int target = table.getSelectedRow() + delta ;
                select( Math.max( 0, Math.min( size - 1, target ) ) ) ;
            }
        } ) ;
    }

    /**
     * Save the selected topic, then move to the next queued / failed question
     * or close.
     */
    private void confirm() {
        Topic topic = selectedTopic() ;
        if( topic == null || saving ) return ;
        QTask current = task ;
        setSaving( true ) ;

        new SwingWorker<QuestionTopicAssociator.Outcome,Void>() {
            @Override
            protected QuestionTopicAssociator.Outcome doInBackground()
                    throws Exception {
                return flow.save( current, topic ) ;
            }
            @Override protected void done() {
                setSaving( false ) ;
                QuestionTopicAssociator.Outcome outcome ;
                try {
                    outcome = get() ;
                }
                catch( Exception ex ) {
                    log.error( "Manual topic association failed for {}",
                               current, ex ) ;
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex ;
                    JOptionPane.showMessageDialog( ManualTopicDialog.this,
                            "Could not save the topic:\n" + cause.getMessage(),
                            "Manual Topic Association", JOptionPane.ERROR_MESSAGE ) ;
                    return ;     // stay on this question; Cancel abandons it
                }
                flow.saved( current, topic, outcome ) ;

                QTask next = continueBox.isSelected()
                           ? flow.nextPending( current ) : null ;
                if( next != null ) load( next ) ;
                else               setVisible( false ) ;
            }
        }.execute() ;
    }

    /** Lock the inputs while a save runs. */
    private void setSaving( boolean s ) {
        saving = s ;
        table.setEnabled( !s ) ;
        filter.setEnabled( !s ) ;
        updateOk() ;
    }

    /** Close without saving (ignored while a save runs). */
    private void cancel() {
        if( saving ) return ;     // the save decides this question's fate
        flow.abandoned( task ) ;
        setVisible( false ) ;
    }

    /** Filtered topics as (topic name, section) rows. */
    private static final class TopicTableModel extends AbstractTableModel {

        private static final String[] COLUMNS = { "Topic", "Section" } ;

        private List<Topic> rows = new ArrayList<>() ;

        void setRows( List<Topic> rows ) {
            this.rows = rows ;
            fireTableDataChanged() ;
        }

        @Override public int    getRowCount()             { return rows.size() ; }
        @Override public int    getColumnCount()          { return COLUMNS.length ; }
        @Override public String getColumnName( int col )  { return COLUMNS[col] ; }

        @Override
        public Object getValueAt( int row, int col ) {
            Topic t = rows.get( row ) ;
            return col == 0 ? t.getTopicName() : t.getSection() ;
        }
    }
}
