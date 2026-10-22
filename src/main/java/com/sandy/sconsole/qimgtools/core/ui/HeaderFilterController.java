package com.sandy.sconsole.qimgtools.core.ui;


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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;


/**
 * Per-column filtering driven from a table's header, for the topic
 * reviewer's and the tagger's tables: right-click a header for a filter
 * popup (left-click still sorts, through the table's row sorter). The
 * filters are ANDed with each other and with the app's own (e.g. a status
 * filter in the toolbar).
 * <p>
 * Text columns filter by "contains" (case-insensitive); the columns the
 * {@link Columns} call enumerable filter by a set of allowed values. A
 * filtered column's header is shown in bold with a trailing {@code *}.
 *
 * @param <R> the table's row type
 */
public class HeaderFilterController<R> {

    /** What the controller needs to know of the table's columns and rows. */
    public interface Columns<R> {

        /** The rows loaded, filtered out or not. */
        List<R> rows() ;

        /** What {@code row} is filtered by in column {@code col}. */
        String cellText( R row, int col ) ;

        /** False for a column that can't be filtered (e.g. an icon). */
        default boolean filterable( int col ) { return true ; }

        /** True for a column filtered by a set of allowed values. */
        default boolean enumerable( int col ) { return false ; }

        /**
         * Every value an enumerable column may hold (e.g. every status);
         * null to offer those of the loaded rows.
         */
        default List<String> values( int col ) { return null ; }
    }

    private final JTable     table ;
    private final Columns<R> columns ;

    /** Invoked after a filter changed, to re-filter the table. */
    private final Runnable onChange ;

    /** model column -> "contains" text. */
    private final Map<Integer,String> textFilters = new LinkedHashMap<>() ;

    /** model column -> allowed values (absent = all allowed). */
    private final Map<Integer,Set<String>> valueFilters =
            new LinkedHashMap<>() ;

    /** Installs the header mouse handling and renderer on {@code table}. */
    public HeaderFilterController( JTable table, Columns<R> columns,
                                   Runnable onChange ) {

        this.table    = table ;
        this.columns  = columns ;
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
    public boolean hasAnyFilter() {
        return !textFilters.isEmpty() || !valueFilters.isEmpty() ;
    }

    /** Remove every filter and re-filter the table. */
    public void clearAll() {
        textFilters.clear() ;
        valueFilters.clear() ;
        apply() ;
    }

    /** True if {@code row} passes every column filter. */
    public boolean accepts( R row ) {

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
        if( !columns.filterable( modelCol ) ) return ;

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
        String name = table.getModel().getColumnName( modelCol ) ;
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

    private boolean isEnumerable( int modelCol ) {
        return columns.enumerable( modelCol ) ;
    }

    private boolean hasFilter( int modelCol ) {
        return textFilters.containsKey( modelCol )
               || valueFilters.containsKey( modelCol ) ;
    }

    /** What {@code row} is filtered by in the column: its text, "" if none. */
    private String cellText( R row, int modelCol ) {
        String text = columns.cellText( row, modelCol ) ;
        return text == null ? "" : text ;
    }

    /**
     * The values an enumerable column offers: those the columns name, else
     * those of the loaded rows plus any the filter allows, sorted.
     */
    private List<String> values( int modelCol ) {

        List<String> fixed = columns.values( modelCol ) ;
        if( fixed != null ) return fixed ;

        Set<String> values = new TreeSet<>( String.CASE_INSENSITIVE_ORDER ) ;
        columns.rows().forEach( r -> values.add( cellText( r, modelCol ) ) ) ;
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
