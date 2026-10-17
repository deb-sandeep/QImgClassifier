package com.sandy.sconsole.qimgtools.apps.topicreviewer.ui;

import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewStatus;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.ui.ReviewTableModel.Row;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import java.awt.Component;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

import static com.sandy.sconsole.qimgtools.apps.topicreviewer.ui.ReviewTableModel.COL_ASSOC;
import static com.sandy.sconsole.qimgtools.apps.topicreviewer.ui.ReviewTableModel.COL_SAVED;
import static com.sandy.sconsole.qimgtools.apps.topicreviewer.ui.ReviewTableModel.COL_SOURCE;
import static com.sandy.sconsole.qimgtools.apps.topicreviewer.ui.ReviewTableModel.COL_STATUS;

/**
 * Per-column filtering driven from the review table's header: right-click a
 * header for a filter popup (left-click still sorts, through the table's
 * row sorter). The filters are ANDed with each other and with the toolbar's
 * Show choice.
 * <p>
 * Text columns filter by "contains" (case-insensitive); {@code Source},
 * {@code Associated by} and {@code Status} filter by a set of allowed values
 * (Status by its plain label, without the attempt number). A filtered
 * column's header is shown in bold with a trailing {@code *}.
 */
class HeaderFilterController {

    private final JTable           table ;
    private final ReviewTableModel model ;

    /** Invoked after a filter changed, to re-filter the table. */
    private final Runnable onChange ;

    /** model column -> "contains" text. */
    private final Map<Integer,String> textFilters = new LinkedHashMap<>() ;

    /** model column -> allowed values (absent = all allowed). */
    private final Map<Integer,Set<String>> valueFilters =
            new LinkedHashMap<>() ;

    /** Installs the header mouse handling and renderer on {@code table}. */
    HeaderFilterController( JTable table, ReviewTableModel model,
                            Runnable onChange ) {

        this.table    = table ;
        this.model    = model ;
        this.onChange = onChange ;

        JTableHeader header = table.getTableHeader() ;
        header.setReorderingAllowed( false ) ;
        header.setToolTipText( "Left-click to sort, right-click to filter" ) ;
        header.addMouseListener( new MouseAdapter() {
            @Override public void mousePressed( MouseEvent e )  { maybePopup( e ) ; }
            @Override public void mouseReleased( MouseEvent e ) { maybePopup( e ) ; }
        } ) ;

        TableCellRenderer base = header.getDefaultRenderer() ;
        header.setDefaultRenderer( ( t, value, sel, foc, row, viewCol ) -> {
            Component c = base.getTableCellRendererComponent(
                    t, value, sel, foc, row, viewCol ) ;
            if( c instanceof JLabel label ) {
                int modelCol = t.convertColumnIndexToModel( viewCol ) ;
                boolean filtered = hasFilter( modelCol ) ;
                String text = String.valueOf( value ) ;

                if( filtered ) text += "  *" ;
                label.setText( text ) ;
                label.setFont( label.getFont().deriveFont(
                        filtered ? Font.BOLD : Font.PLAIN ) ) ;
            }
            return c ;
        } ) ;
    }

    /** True if any column has a filter set. */
    boolean hasAnyFilter() {
        return !textFilters.isEmpty() || !valueFilters.isEmpty() ;
    }

    /** Remove every filter and re-filter the table. */
    void clearAll() {
        textFilters.clear() ;
        valueFilters.clear() ;
        apply() ;
    }

    /** True if {@code row} passes every column filter. */
    boolean accepts( Row row ) {

        for( Map.Entry<Integer,String> e : textFilters.entrySet() ) {
            String cell = cellText( row, e.getKey() ).toLowerCase() ;
            if( !cell.contains( e.getValue().toLowerCase() ) ) return false ;
        }
        for( Map.Entry<Integer,Set<String>> e : valueFilters.entrySet() ) {
            if( !e.getValue().contains( cellText( row, e.getKey() ) ) ) {
                return false ;
            }
        }
        return true ;
    }

    // ---- popup -------------------------------------------------------

    private void maybePopup( MouseEvent e ) {

        if( !e.isPopupTrigger() ) return ;
        JTableHeader header = table.getTableHeader() ;
        int viewCol = header.columnAtPoint( e.getPoint() ) ;

        if( viewCol < 0 ) return ;
        int modelCol = table.convertColumnIndexToModel( viewCol ) ;
        if( modelCol == COL_SAVED ) return ;   // dot column

        JPopupMenu menu = isEnumerable( modelCol )
                ? buildValueMenu( modelCol )
                : buildTextMenu( modelCol ) ;

        menu.addSeparator() ;
        JMenuItem clearAll = new JMenuItem( "Clear all filters" ) ;
        clearAll.setEnabled( hasAnyFilter() ) ;
        clearAll.addActionListener( a -> clearAll() ) ;
        menu.add( clearAll ) ;

        Rectangle r = header.getHeaderRect( viewCol ) ;
        menu.show( header, r.x, r.height ) ;
        e.consume() ;
    }

    /**
     * Filter popup for a free-text column: edit or clear its "contains" text.
     */
    private JPopupMenu buildTextMenu( int modelCol ) {

        JPopupMenu menu = new JPopupMenu() ;
        String name = model.getColumnName( modelCol ) ;
        String current = textFilters.getOrDefault( modelCol, "" ) ;

        JMenuItem edit = new JMenuItem(
                current.isEmpty() ? "Filter “" + name + "”…"
                                  : "Filter “" + name + "” (“" + current +
                                    "”)…" ) ;
        edit.addActionListener( a -> {
            Object in = JOptionPane.showInputDialog( table,
                    name + " contains:", "Filter " + name,
                    JOptionPane.PLAIN_MESSAGE, null, null, current ) ;
            if( in == null ) return ;
            String text = in.toString().trim() ;
            if( text.isEmpty() ) textFilters.remove( modelCol ) ;
            else                 textFilters.put( modelCol, text ) ;
            apply() ;
        } ) ;
        menu.add( edit ) ;

        if( !current.isEmpty() ) {
            JMenuItem clear = new JMenuItem( "Clear “" + name + "” filter" ) ;
            clear.addActionListener( a -> {
                textFilters.remove( modelCol ) ;
                apply() ;
            } ) ;
            menu.add( clear ) ;
        }
        return menu ;
    }

    /**
     * Filter popup for an enumerable column: show all, show only one value,
     * or toggle values.
     */
    private JPopupMenu buildValueMenu( int modelCol ) {

        JPopupMenu menu = new JPopupMenu() ;
        List<String> all = values( modelCol ) ;
        Set<String> allowed = allowedValues( modelCol ) ;

        JMenuItem showAll = new JMenuItem( "Show all" ) ;
        showAll.setEnabled( valueFilters.containsKey( modelCol ) ) ;
        showAll.addActionListener( a -> {
            valueFilters.remove( modelCol ) ;
            apply() ;
        } ) ;
        menu.add( showAll ) ;
        menu.addSeparator() ;

        for( String v : all ) {
            JMenuItem only = new JMenuItem( "Only " + label( v ) ) ;
            only.addActionListener( a -> {
                valueFilters.put( modelCol,
                                  new LinkedHashSet<>( List.of( v ) ) ) ;
                apply() ;
            } ) ;
            menu.add( only ) ;
        }
        menu.addSeparator() ;

        for( String v : all ) {
            JCheckBoxMenuItem cb =
                    new JCheckBoxMenuItem( label( v ), allowed.contains( v ) ) ;
            cb.addActionListener( a -> {
                Set<String> set = allowedValues( modelCol ) ;
                if( cb.isSelected() ) set.add( v ) ;
                else                  set.remove( v ) ;
                if( set.containsAll( all ) ) valueFilters.remove( modelCol ) ;
                else                         valueFilters.put( modelCol, set ) ;
                apply() ;
            } ) ;
            menu.add( cb ) ;
        }
        return menu ;
    }

    // ---- filter application ----------------------------------------

    private static boolean isEnumerable( int modelCol ) {
        return modelCol == COL_SOURCE || modelCol == COL_ASSOC
               || modelCol == COL_STATUS ;
    }

    private boolean hasFilter( int modelCol ) {
        return textFilters.containsKey( modelCol )
               || valueFilters.containsKey( modelCol ) ;
    }

    /** What {@code row} is filtered by in the column: its text, "" if none. */
    private String cellText( Row row, int modelCol ) {

        if( modelCol == COL_STATUS ) return row.status.getLabel() ;
        Object value = model.getValueAt( model.indexOf( row ), modelCol ) ;
        return value == null ? "" : value.toString() ;
    }

    /**
     * The values an enumerable column offers: every status for Status, else
     * those of the loaded rows plus any the filter allows, sorted.
     */
    private List<String> values( int modelCol ) {

        if( modelCol == COL_STATUS ) {
            return Arrays.stream( ReviewStatus.values() )
                         .map( ReviewStatus::getLabel ).toList() ;
        }
        Set<String> values = new TreeSet<>( String.CASE_INSENSITIVE_ORDER ) ;
        model.rows().forEach( r -> values.add( cellText( r, modelCol ) ) ) ;
        values.addAll( valueFilters.getOrDefault( modelCol, Set.of() ) ) ;
        return List.copyOf( values ) ;
    }

    /** A value as the menu shows it; a blank cell reads "(blank)". */
    private static String label( String value ) {
        return value.isEmpty() ? "(blank)" : value ;
    }

    /**
     * A modifiable copy of the column's allowed values (all values when
     * unfiltered).
     */
    private Set<String> allowedValues( int modelCol ) {
        Set<String> allowed = valueFilters.get( modelCol ) ;
        return new LinkedHashSet<>( allowed != null ? allowed
                                                    : values( modelCol ) ) ;
    }

    /** Re-filter the table and repaint the header. */
    private void apply() {
        table.getTableHeader().repaint() ;
        onChange.run() ;
    }
}
