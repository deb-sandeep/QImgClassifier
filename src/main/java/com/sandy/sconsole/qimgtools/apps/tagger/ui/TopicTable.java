package com.sandy.sconsole.qimgtools.apps.tagger.ui;

import com.sandy.sconsole.qimgtools.apps.tagger.prompt.PromptStore;
import com.sandy.sconsole.qimgtools.core.model.Topic;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableRowSorter;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A syllabus' topics as a table for the scope and prompt dialogs: id, name,
 * the prompt's state (a {@link PromptStateIcon} badge, its label in the
 * state's colour, the row tinted), its version and when it was approved
 * and generated, and optionally why a topic can't be chosen (its row then
 * greyed). Columns sort on a header click. A double-click or Enter on a
 * row picks it: in a {@link JOptionPane}, as OK. Only on the EDT.
 */
final class TopicTable {

    /** Light grey lines between columns, as in the Models tab. */
    private static final Color GRID    = new Color( 0xD4D4D4 ) ;
    private static final Color BLOCKED = new Color( 0x9E9E9E ) ;

    /** Room on both sides of a cell's text, so columns don't run together. */
    private static final Border PAD = BorderFactory.createEmptyBorder( 0, 6, 0, 6 ) ;

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern( "dd MMM yyyy HH:mm" )
                             .withZone( ZoneId.systemDefault() ) ;

    private static final int COL_ID        = 0 ;
    private static final int COL_TOPIC     = 1 ;
    private static final int COL_PROMPT    = 2 ;
    private static final int COL_VERSION   = 3 ;
    private static final int COL_APPROVED  = 4 ;
    private static final int COL_GENERATED = 5 ;
    private static final int COL_BLOCKED   = 6 ;

    private static final String[] NAMES  = {
            "Id", "Topic", "Prompt", "Version", "Approved", "Generated" } ;
    private static final int[]    WIDTHS = { 45, 230, 105, 60, 135, 135 } ;

    /** One topic as shown. */
    private record Row( Topic topic, PromptStore.Summary summary,
                        String blocked ) {}

    private final Function<Topic,PromptStore.Summary>   summaryOf ;
    private final BiFunction<Topic,PromptStore.State,String> blockedOf ;
    private final String                                blockedName ;

    /** Summaries read so far, by topic id: each topic's files once. */
    private final Map<Integer,PromptStore.Summary> summaries = new HashMap<>() ;

    private final Model                  model  = new Model() ;
    private final JTable                 table  = new JTable( model ) ;
    private final TableRowSorter<Model>  sorter = new TableRowSorter<>( model ) ;
    private final JScrollPane            scroll = new JScrollPane( table ) ;

    /**
     * @param summaryOf   a topic's prompt at a glance
     * @param blockedOf   why a topic in a state can't be chosen, null if it
     *                    can; null for no such column
     * @param blockedName the header of that column
     */
    TopicTable( Function<Topic,PromptStore.Summary> summaryOf,
                BiFunction<Topic,PromptStore.State,String> blockedOf,
                String blockedName ) {

        this.summaryOf   = summaryOf ;
        this.blockedOf   = blockedOf ;
        this.blockedName = blockedName ;

        // The table was made before blockedOf was known: count again.
        table.createDefaultColumnsFromModel() ;

        JTableHeader header = table.getTableHeader() ;
        header.setReorderingAllowed( false ) ;
        header.setFont( header.getFont().deriveFont( Font.BOLD ) ) ;

        table.setRowSorter( sorter ) ;
        table.setSelectionMode( ListSelectionModel.SINGLE_SELECTION ) ;
        table.setFillsViewportHeight( true ) ;
        table.setShowHorizontalLines( false ) ;
        table.setShowVerticalLines( true ) ;
        table.setGridColor( GRID ) ;
        table.setIntercellSpacing( new Dimension( 1, 0 ) ) ;
        table.setRowHeight( Math.max( table.getRowHeight(),
                                      PromptStateIcon.SIZE + 6 ) ) ;

        Cells cells = new Cells() ;
        int   width = 0 ;
        for( int i = 0 ; i < model.getColumnCount() ; i++ ) {
            int w = i < WIDTHS.length ? WIDTHS[i] : 170 ;
            table.getColumnModel().getColumn( i ).setPreferredWidth( w ) ;
            table.getColumnModel().getColumn( i ).setCellRenderer( cells ) ;
            width += w ;
        }
        scroll.setPreferredSize( new Dimension(
                width + 20, table.getRowHeight() * 20 + 28 ) ) ;

        table.addMouseListener( new MouseAdapter() {
            @Override
            public void mouseClicked( MouseEvent e ) {
                if( e.getClickCount() == 2 && selected() != null ) pick() ;
            }
        } ) ;
        table.getInputMap( JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT )
             .put( KeyStroke.getKeyStroke( KeyEvent.VK_ENTER, 0 ), "pick" ) ;
        table.getActionMap().put( "pick", new AbstractAction() {
            @Override
            public void actionPerformed( ActionEvent e ) {
                if( selected() != null ) pick() ;
            }
        } ) ;
    }

    /** The table in its scroll pane, to lay out. */
    JComponent component() {
        return scroll ;
    }

    /** Show {@code topics}, in id order, selecting {@code select} (null: none). */
    void show( List<Topic> topics, Integer select ) {

        List<Row> rows = new ArrayList<>() ;
        for( Topic t : topics ) {
            PromptStore.Summary s = summaries.computeIfAbsent(
                    t.getId(), id -> summaryOf.apply( t ) ) ;
            rows.add( new Row( t, s, blockedOf == null ? null
                                     : blockedOf.apply( t, s.state() ) ) ) ;
        }
        model.set( rows ) ;
        sorter.setSortKeys( null ) ;

        for( int i = 0 ; i < rows.size() ; i++ ) {
            if( select != null && rows.get( i ).topic().getId() == select ) {
                int v = table.convertRowIndexToView( i ) ;
                table.getSelectionModel().setSelectionInterval( v, v ) ;
                table.scrollRectToVisible( table.getCellRect( v, 0, true ) ) ;
            }
        }
    }

    /** The topic selected; null if none. */
    Topic selected() {
        int v = table.getSelectedRow() ;
        return v < 0 ? null
                     : model.rows.get( table.convertRowIndexToModel( v ) )
                                 .topic() ;
    }

    /** Why the selected topic can't be chosen; null if it can or none is. */
    String selectedBlocked() {
        int v = table.getSelectedRow() ;
        return v < 0 ? null
                     : model.rows.get( table.convertRowIndexToModel( v ) )
                                 .blocked() ;
    }

    /** Close the enclosing option pane as OK, if there is one. */
    private void pick() {

        JOptionPane pane = (JOptionPane) SwingUtilities.getAncestorOfClass(
                JOptionPane.class, table ) ;
        if( pane != null ) pane.setValue( JOptionPane.OK_OPTION ) ;
    }

    private static String when( Instant t ) {
        return t == null ? "" : WHEN.format( t ) ;
    }

    private final class Model extends AbstractTableModel {

        private List<Row> rows = List.of() ;

        void set( List<Row> rows ) {
            this.rows = rows ;
            fireTableDataChanged() ;
        }

        @Override
        public int getRowCount() {
            return rows.size() ;
        }

        @Override
        public int getColumnCount() {
            return blockedOf == null ? NAMES.length : NAMES.length + 1 ;
        }

        @Override
        public String getColumnName( int col ) {
            return col == COL_BLOCKED ? blockedName : NAMES[col] ;
        }

        @Override
        public Class<?> getColumnClass( int col ) {
            return switch( col ) {
                case COL_ID, COL_VERSION -> Integer.class ;
                case COL_PROMPT          -> PromptStore.State.class ;
                case COL_APPROVED,
                     COL_GENERATED       -> Instant.class ;
                default                  -> String.class ;
            } ;
        }

        @Override
        public Object getValueAt( int row, int col ) {

            Row r = rows.get( row ) ;
            return switch( col ) {
                case COL_ID        -> r.topic().getId() ;
                case COL_TOPIC     -> r.topic().getTopicName() ;
                case COL_PROMPT    -> r.summary().state() ;
                case COL_VERSION   -> r.summary().version() ;
                case COL_APPROVED  -> r.summary().approvedAt() ;
                case COL_GENERATED -> r.summary().generatedAt() ;
                case COL_BLOCKED   -> r.blocked() ;
                default            -> null ;
            } ;
        }
    }

    /**
     * Cells: numbers right-aligned, dates formatted, the state as its
     * badge and coloured label; a row tinted by its state, or greyed if it
     * can't be chosen.
     */
    private final class Cells extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent( JTable t,
                Object value, boolean selected, boolean focus, int row,
                int col ) {

            Row             r     = model.rows.get(
                                        t.convertRowIndexToModel( row ) ) ;
            PromptStateIcon badge = PromptStateIcon.of( r.summary().state() ) ;
            Object          shown = switch( col ) {
                case COL_VERSION   -> (Integer) value == 0 ? "" : value ;
                case COL_APPROVED,
                     COL_GENERATED -> when( (Instant) value ) ;
                default            -> value ;
            } ;

            JLabel c = (JLabel) super.getTableCellRendererComponent(
                    t, shown, selected, focus, row, col ) ;
            c.setHorizontalAlignment( col == COL_ID || col == COL_VERSION
                                      ? RIGHT : LEFT ) ;
            c.setIcon( col == COL_PROMPT ? badge : null ) ;
            c.setIconTextGap( 6 ) ;
            c.setBorder( PAD ) ;
            c.setFont( t.getFont() ) ;

            if( !selected ) {
                Color tint = r.blocked() == null ? badge.rowTint : null ;
                c.setBackground( tint != null ? tint : t.getBackground() ) ;
                c.setForeground( r.blocked() != null && col != COL_PROMPT
                                 ? BLOCKED
                                 : col == COL_PROMPT ? badge.color
                                 : t.getForeground() ) ;
            }
            if( col == COL_PROMPT ) {
                c.setFont( t.getFont().deriveFont( Font.BOLD ) ) ;
            }
            return c ;
        }
    }
}
