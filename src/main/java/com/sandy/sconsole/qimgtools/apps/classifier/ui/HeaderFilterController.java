package com.sandy.sconsole.qimgtools.apps.classifier.ui;

import com.sandy.sconsole.qimgtools.core.model.Subject;
import com.sandy.sconsole.qimgtools.apps.classifier.model.TaskStatus;

import javax.swing.*;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import java.awt.Component;
import java.awt.Cursor;
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
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.sandy.sconsole.qimgtools.apps.classifier.ui.QTaskTreeTableModel.COL_ASSOC;
import static com.sandy.sconsole.qimgtools.apps.classifier.ui.QTaskTreeTableModel.COL_STATUS;
import static com.sandy.sconsole.qimgtools.apps.classifier.ui.QTaskTreeTableModel.COL_SUBJ;

/**
 * Sorting and per-column filtering driven from the table header of the
 * {@link QTaskTreeTableModel} tree table. Left-click a header to cycle its sort
 * (ascending, descending, discovery order); right-click for a filter popup.
 * Sorting and filtering apply to questions within each source; a source is
 * hidden while none of its questions pass the filters.
 * <p>
 * Text columns filter by "contains" (case-insensitive; the name column matches
 * the source name or question id); {@code Subj} and {@code Status} filter by a
 * set of allowed values. A filtered column's header is shown in bold with a
 * trailing {@code *}; the sorted column carries ▲ / ▼.
 */
class HeaderFilterController {

    private final JTable              table ;
    private final QTaskTreeTableModel model ;

    /** Invoked after the filter or sort changed, to rebuild the view. */
    private final Runnable onChange ;

    /** model column -> "contains" text. */
    private final Map<Integer,String> textFilters = new LinkedHashMap<>() ;

    /** model column -> allowed values (absent = all allowed). */
    private final Map<Integer,Set<String>> valueFilters = new LinkedHashMap<>() ;

    /**
     * model column -> the full set of possible values, for enumerable columns.
     */
    private final Map<Integer,List<String>> enumerable = new LinkedHashMap<>() ;

    /** Installs the header mouse handling and renderer on {@code table}. */
    HeaderFilterController( JTable table, QTaskTreeTableModel model,
                            Runnable onChange ) {

        this.table    = table ;
        this.model    = model ;
        this.onChange = onChange ;

        enumerable.put( COL_SUBJ, Arrays.stream( Subject.values() )
                .map( Subject::getCode ).collect( Collectors.toList() ) ) ;
        enumerable.put( COL_STATUS, Arrays.stream( TaskStatus.values() )
                .map( Enum::name ).collect( Collectors.toList() ) ) ;

        JTableHeader header = table.getTableHeader() ;
        header.setReorderingAllowed( false ) ;
        header.setToolTipText( "Left-click to sort, right-click to filter" ) ;
        header.addMouseListener( new MouseAdapter() {
            @Override public void mousePressed( MouseEvent e )  { maybePopup( e ) ; }
            @Override public void mouseReleased( MouseEvent e ) { maybePopup( e ) ; }
            @Override public void mouseClicked( MouseEvent e ) {
                if( SwingUtilities.isLeftMouseButton( e ) && !e.isPopupTrigger() ) {
                    cycleSort( e ) ;
                }
            }
        } ) ;

        TableCellRenderer base = header.getDefaultRenderer() ;
        header.setDefaultRenderer( ( t, value, sel, foc, row, viewCol ) -> {
            Component c = base.getTableCellRendererComponent(
                    t, value, sel, foc, row, viewCol ) ;
            if( c instanceof JLabel ) {
                JLabel label = (JLabel) c ;
                int modelCol = t.convertColumnIndexToModel( viewCol ) ;
                boolean filtered = hasFilter( modelCol ) ;
                String text = String.valueOf( value ) ;

                if( model.getSortColumn() == modelCol ) {
                    text += model.isSortAscending() ? "  ▲" : "  ▼" ;
                }
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

    /** Remove every filter and rebuild the view. */
    void clearAll() {
        textFilters.clear() ;
        valueFilters.clear() ;
        apply() ;
    }

    // ---- sorting -----------------------------------------------------

    /**
     * Left-click on a header: ascending, then descending, then back to
     * discovery order.
     */
    private void cycleSort( MouseEvent e ) {

        JTableHeader header = table.getTableHeader() ;
        // A non-default cursor means the user is resizing a column.
        if( header.getCursor().getType() != Cursor.DEFAULT_CURSOR ) return ;
        int viewCol = header.columnAtPoint( e.getPoint() ) ;
        if( viewCol < 0 ) return ;
        int modelCol = table.convertColumnIndexToModel( viewCol ) ;
        if( modelCol == COL_ASSOC ) return ;   // button column

        if( model.getSortColumn() != modelCol ) model.setSort( modelCol, true ) ;
        else if( model.isSortAscending() )      model.setSort( modelCol, false ) ;
        else                                    model.setSort( -1, true ) ;

        header.repaint() ;
        onChange.run() ;
    }

    // ---- popup -------------------------------------------------------

    private void maybePopup( MouseEvent e ) {

        if( !e.isPopupTrigger() ) return ;
        JTableHeader header = table.getTableHeader() ;
        int viewCol = header.columnAtPoint( e.getPoint() ) ;

        if( viewCol < 0 ) return ;
        int modelCol = table.convertColumnIndexToModel( viewCol ) ;
        if( modelCol == COL_ASSOC ) return ;   // button column

        JPopupMenu menu = enumerable.containsKey( modelCol )
                ? buildValueMenu( modelCol )
                : buildTextMenu( modelCol ) ;

        menu.addSeparator() ;
        JMenuItem clearAll = new JMenuItem( "Clear all filters" ) ;
        clearAll.setEnabled( hasAnyFilter() ) ;
        clearAll.addActionListener( a -> clearAll() ) ;
        menu.add( clearAll ) ;

        Rectangle r = header.getHeaderRect( viewCol ) ;
        menu.show( header, r.x, r.height ) ;
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
            Object in = JOptionPane.showInputDialog( table, name + " contains:",
                    "Filter " + name,
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
     * Filter popup for an enumerable column: show all, show only one value, or
     * toggle values.
     */
    private JPopupMenu buildValueMenu( int modelCol ) {

        JPopupMenu menu = new JPopupMenu() ;
        List<String> all = enumerable.get( modelCol ) ;
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
            JMenuItem only = new JMenuItem( "Only " + v ) ;
            only.addActionListener( a -> {
                valueFilters.put( modelCol, new LinkedHashSet<>( List.of( v ) ) ) ;
                apply() ;
            } ) ;
            menu.add( only ) ;
        }
        menu.addSeparator() ;

        for( String v : all ) {
            JCheckBoxMenuItem cb =
                    new JCheckBoxMenuItem( v, allowed.contains( v ) ) ;
            cb.addActionListener( a -> {
                Set<String> set = allowedValues( modelCol ) ;
                if( cb.isSelected() ) set.add( v ) ;
                else                  set.remove( v ) ;
                if( set.size() == all.size() ) valueFilters.remove( modelCol ) ;
                else                           valueFilters.put( modelCol, set ) ;
                apply() ;
            } ) ;
            menu.add( cb ) ;
        }
        return menu ;
    }

    // ---- filter application ----------------------------------------

    private boolean hasFilter( int modelCol ) {
        return textFilters.containsKey( modelCol )
               || valueFilters.containsKey( modelCol ) ;
    }

    /**
     * A modifiable copy of the column's allowed values (all values when
     * unfiltered).
     */
    private Set<String> allowedValues( int modelCol ) {
        Set<String> allowed = valueFilters.get( modelCol ) ;
        return new LinkedHashSet<>( allowed != null ? allowed
                                                    : enumerable.get( modelCol ) ) ;
    }

    /**
     * Push a predicate combining all filters (AND) into the model and rebuild
     * the view.
     */
    private void apply() {

        Predicate<QTaskTreeTableModel.QuestionNode> filter = null ;
        if( hasAnyFilter() ) {
            Map<Integer,String> texts = new LinkedHashMap<>() ;
            textFilters.forEach(
                    ( col, text ) -> texts.put( col, text.toLowerCase() ) ) ;
            Map<Integer,Set<String>> values = new LinkedHashMap<>( valueFilters ) ;

            filter = q -> {
                for( Map.Entry<Integer,String> e : texts.entrySet() ) {
                    String cell = model.filterText( q, e.getKey() ).toLowerCase() ;
                    if( !cell.contains( e.getValue() ) ) return false ;
                }
                for( Map.Entry<Integer,Set<String>> e : values.entrySet() ) {
                    Object cell = model.valueAt( q, e.getKey() ) ;
                    if( !e.getValue().contains( String.valueOf( cell ) ) ) return false ;
                }
                return true ;
            } ;
        }
        model.setFilter( filter ) ;
        table.getTableHeader().repaint() ;
        onChange.run() ;
    }
}
