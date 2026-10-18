package com.sandy.sconsole.qimgtools.core.ui;

import com.sandy.sconsole.ai.HubStatus;
import com.sandy.sconsole.ai.HubStatus.GatewayStatus;
import com.sandy.sconsole.ai.HubStatus.ModelStatus;
import com.sandy.sconsole.ai.HubStatus.Request;
import com.sandy.sconsole.ai.HubStatus.WaitingJob;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.border.Border;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumnModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The AI hub at a glance, from a {@link HubStatus} snapshot: the job counts,
 * the gateways and models with their state, load and limits, the requests in
 * flight and the jobs waiting for a model. Shown as the Models tab beside
 * the transcripts in both apps; call {@link #update} on the EDT, e.g. each
 * second while the tab is showing. The models table's first row holds a
 * check box per model to pass its questions through to its
 * {@code passthrough} fallback; such a model's column is pink.
 */
@SuppressWarnings( "serial" )
public class ModelsPanel extends JPanel {

    private static final DateTimeFormatter HH_MM_SS =
            DateTimeFormatter.ofPattern( "HH:mm:ss" )
                             .withZone( ZoneId.systemDefault() ) ;

    // ---- table layout: tune the column widths here ----------------------
    //
    // Widths are preferred widths in pixels; a table fills its width, so
    // they act as proportions.

    /** A table column: its header and preferred width. */
    private record Col( String name, int width ) {}

    private static final Col[] GATEWAY_COLUMNS = {
        new Col( "Gateway",   120 ),
        new Col( "State",     300 ),
        new Col( "In flight",  70 ),
        new Col( "Today",      70 ),
    } ;

    private static final Col[] REQUEST_COLUMNS = {
        new Col( "Question",    220 ),
        new Col( "Model",       200 ),
        new Col( "Attempt",      60 ),
        new Col( "Running for",  90 ),
    } ;

    private static final Col[] QUEUE_COLUMNS = {
        new Col( "#",             55 ),
        new Col( "Question",     220 ),
        new Col( "Waiting",      120 ),
        new Col( "Att#",          60 ),
        new Col( "Can run on",   250 ),
        new Col( "Last failure", 250 ),
    } ;

    /**
     * The models table is transposed: its first column holds the row
     * headers ({@link #MODEL_ROWS}), then there is one column per model.
     */
    private static final int MODEL_LABEL_WIDTH = 150 ;
    private static final int MODEL_WIDTH       = 240 ;

    /** The rows of the models table. */
    private static final List<String> MODEL_ROWS = List.of(
            "Passthrough", "Gateway", "State", "In flight", "Waiting",
            "Last min", "Today", "Avg reply", "OK", "Failed" ) ;

    // ---- table look -----------------------------------------------------

    /** The lines between columns. */
    private static final Color GRID   = new Color( 0xD4D4D4 ) ;

    /** The background of every other row of the models table. */
    private static final Color STRIPE = new Color( 0xEEF2F7 ) ;

    /** The background of a model's column while it passes through. */
    private static final Color PASSTHROUGH = new Color( 0xFFE4EA ) ;

    private final JLabel    summary  = new JLabel( " " ) ;
    private final RowsModel gateways = new RowsModel( 0, GATEWAY_COLUMNS ) ;
    private final RowsModel models   = new ModelRows() ;
    private final RowsModel requests = new RowsModel( 0, REQUEST_COLUMNS ) ;
    private final RowsModel queue    = new RowsModel( 1, QUEUE_COLUMNS ) ;

    /** Called with a model's id and whether it is to pass questions through. */
    private final BiConsumer<String,Boolean> passthrough ;

    /** The models shown, one per column after the row headers. */
    private List<ModelStatus> shownModels = List.of() ;

    /**
     * @param passthrough called on the EDT with a model's id and true to
     *        pass its questions through, false to take them again
     */
    public ModelsPanel( BiConsumer<String,Boolean> passthrough ) {

        super( new BorderLayout() ) ;
        this.passthrough = passthrough ;

        JPanel hub = new JPanel( new BorderLayout() ) ;
        hub.add( section( "Gateways", gateways, 2, false ),
                 BorderLayout.NORTH ) ;
        hub.add( section( "Models", models, MODEL_ROWS.size(), true ),
                 BorderLayout.CENTER ) ;

        JSplitPane work = new JSplitPane( JSplitPane.VERTICAL_SPLIT,
                section( "Requests in flight", requests, 4, false ),
                section( "Waiting for a model", queue, 8, false ) ) ;
        work.setResizeWeight( 0.3 ) ;
        work.setContinuousLayout( true ) ;

        JSplitPane split = new JSplitPane( JSplitPane.VERTICAL_SPLIT,
                                           hub, work ) ;
        split.setResizeWeight( 0.35 ) ;
        split.setContinuousLayout( true ) ;

        summary.setBorder( BorderFactory.createEmptyBorder( 6, 8, 6, 8 ) ) ;
        add( summary, BorderLayout.NORTH ) ;
        add( split, BorderLayout.CENTER ) ;

        JCheckBox box = new JCheckBox() ;
        box.setHorizontalAlignment( JCheckBox.CENTER ) ;
        models.table.setDefaultRenderer( Object.class,
                new Cells( true, this::isPassthrough ) ) ;
        models.table.setDefaultEditor( Object.class,
                                       new DefaultCellEditor( box ) ) ;
    }

    /** True if models table column {@code c} is a model passing through. */
    private boolean isPassthrough( int c ) {
        return c >= 1 && c <= shownModels.size()
               && shownModels.get( c - 1 ).passthrough() ;
    }

    /** Show {@code s}; null clears the panel. Call on the EDT. */
    public void update( HubStatus s ) {

        if( s == null ) {
            shownModels = List.of() ;
            summary.setText( " " ) ;
            List.of( gateways, models, requests, queue )
                .forEach( m -> m.set( List.of() ) ) ;
            return ;
        }

        long                now   = s.takenAt() ;
        Map<String,String>  names = new HashMap<>() ;
        Map<String,Boolean> gwOn  = new HashMap<>() ;

        s.models().forEach( m -> names.put( m.id(), m.displayName() ) ) ;
        s.gateways().forEach( g -> gwOn.put( g.name(), g.enabled() ) ) ;
        summary.setText( summary( s, now ) ) ;

        List<Object[]> rows = new ArrayList<>() ;
        for( GatewayStatus g : s.gateways() ) {
            rows.add( new Object[] {
                    g.name(), state( g, now ),
                    of( g.inFlight(), g.maxParallel() ),
                    g.requestsToday() > 0 ? g.requestsToday() : "" } ) ;
        }
        gateways.set( rows ) ;

        showModels( s.models(), gwOn, now ) ;

        rows = new ArrayList<>() ;
        for( Request r : s.requests() ) {
            rows.add( new Object[] {
                    r.job(), names.getOrDefault( r.model(), r.model() ),
                    r.attempt(), duration( now - r.startedAt() ) } ) ;
        }
        requests.set( rows ) ;

        rows = new ArrayList<>() ;
        int n = 0 ;
        for( WaitingJob j : s.queue() ) {
            String on = j.eligible().isEmpty()
                      ? "none now"
                      : j.eligible().stream()
                         .map( id -> names.getOrDefault( id, id ) )
                         .collect( Collectors.joining( ", " ) ) ;
            rows.add( new Object[] {
                    ++n, j.job(), duration( now - j.queuedAt() ), j.attempts(),
                    on, j.lastFailure() != null ? j.lastFailure() : "" } ) ;
        }
        queue.set( rows ) ;
    }

    /** The models table: one column per model, one row per attribute. */
    private void showModels( List<ModelStatus> ms,
                             Map<String,Boolean> gatewayEnabled, long now ) {

        List<Col>      columns = new ArrayList<>() ;
        List<Object[]> rows    = new ArrayList<>() ;

        shownModels = ms ;
        columns.add( new Col( "", MODEL_LABEL_WIDTH ) ) ;
        ms.forEach( m -> columns.add( new Col( m.displayName(), MODEL_WIDTH ) ) ) ;
        for( String label : MODEL_ROWS ) {
            Object[] row = new Object[ ms.size() + 1 ] ;
            row[0] = label ;
            for( int i = 0 ; i < ms.size() ; i++ ) {
                ModelStatus m  = ms.get( i ) ;
                boolean     on = gatewayEnabled.getOrDefault( m.gateway(), true ) ;
                row[i + 1] = attribute( m, on, label, now ) ;
            }
            rows.add( row ) ;
        }
        models.set( columns, rows ) ;
    }

    /**
     * Row {@code label} of model {@code m}'s column.
     *
     * @param gatewayOn its gateway is switched on
     */
    private static Object attribute( ModelStatus m, boolean gatewayOn,
                                     String label, long now ) {
        return switch( label ) {
            case "Passthrough" -> m.usable() ? m.passthrough() : "" ;
            case "Gateway"     -> m.gateway() ;
            case "State"       -> state( m, gatewayOn, now ) ;
            case "In flight"   -> of( m.inFlight(), m.maxParallel() ) ;
            case "Waiting"     -> m.waiting() ;
            case "Last min"    -> of( m.lastMinute(), m.rpm() ) ;
            case "Today"       -> of( m.today(), m.rpd() ) ;
            case "Avg reply"   -> m.avgLatencyMs() > 0
                                  ? seconds( m.avgLatencyMs() ) : "" ;
            case "OK"          -> m.succeeded() ;
            case "Failed"      -> m.failed() ;
            default            -> "" ;
        } ;
    }

    private static String summary( HubStatus s, long now ) {

        HubStatus.Jobs j    = s.jobs() ;
        String         next = "" ;

        if( s.nextSendAt() != Long.MAX_VALUE ) {
            long wait = s.nextSendAt() - now ;
            next = wait <= 0 ? "   next request: now"
                             : "   next request in " + duration( wait ) ;
        }
        return "Jobs: " + j.running() + " running, " + j.waiting() +
               " waiting, " + j.held() + " held, " + j.done() + " done" + next ;
    }

    private static String state( GatewayStatus g, long now ) {

        if( !g.enabled() ) return "off (g)" ;
        if( !g.reachable() ) return "not reachable" ;
        if( g.heldReason() != null ) {
            return held( g.heldUntil(), g.heldForTheDay(), now ) ;
        }
        return "ok" ;
    }

    /**
     * Short state: off (l) / off (g) when switched off in the app / global
     * config (or its gateway is), off for another reason (no API key, no
     * root reaches it), passthrough (with its requests still finishing),
     * held, busy, working or ready.
     */
    private static String state( ModelStatus m, boolean gatewayOn, long now ) {

        if( !m.usable() ) {
            if( !m.enabledInApp() ) return "off (l)" ;
            if( !m.enabledGlobally() || !gatewayOn ) return "off (g)" ;
            return "off" ;
        }
        if( m.passthrough() ) {
            return m.inFlight() > 0 ? "passthrough, " + m.inFlight() +
                                      " finishing"
                                    : "passthrough" ;
        }
        if( m.heldReason() != null ) {
            return held( m.heldUntil(), m.heldForTheDay(), now ) ;
        }
        if( m.maxParallel() > 0 && m.inFlight() >= m.maxParallel() ) {
            return "busy" ;
        }
        return m.inFlight() > 0 ? "working" : "ready" ;
    }

    private static String held( long until, boolean daily, long now ) {
        if( daily ) return "held for the day" ;
        return until > now ? "held until " + HH_MM_SS.format(
                                     Instant.ofEpochMilli( until ) )
                           : "held" ;
    }

    /** {@code n / limit}, or just {@code n} without a limit. */
    private static String of( int n, int limit ) {
        return limit > 0 ? n + " / " + limit : String.valueOf( n ) ;
    }

    private static String seconds( long ms ) {
        return String.format( "%.1f s", ms / 1000.0 ) ;
    }

    /** {@code ms} as e.g. "45 s", "3 m 05 s", "2 h 10 m". */
    private static String duration( long ms ) {

        long s = Math.max( 0, ms / 1000 ) ;
        if( s < 60 )   return s + " s" ;
        if( s < 3600 ) return String.format( "%d m %02d s", s / 60, s % 60 ) ;
        return String.format( "%d h %02d m", s / 3600, ( s % 3600 ) / 60 ) ;
    }

    /**
     * A titled table of {@code model} about {@code rows} rows high, with
     * bold column headers and light lines between the columns.
     *
     * @param transposed the first column holds row headers (bold) and the
     *        rows are striped
     */
    private static JPanel section( String title, RowsModel model, int rows,
                                   boolean transposed ) {

        JTable       table  = new JTable( model ) ;
        JTableHeader header = table.getTableHeader() ;

        table.setFillsViewportHeight( true ) ;
        table.setShowHorizontalLines( false ) ;
        table.setShowVerticalLines( true ) ;
        table.setGridColor( GRID ) ;
        table.setIntercellSpacing( new Dimension( 1, 0 ) ) ;
        table.setDefaultRenderer( Object.class,
                                  new Cells( transposed, null ) ) ;
        header.setReorderingAllowed( false ) ;
        header.setFont( header.getFont().deriveFont( Font.BOLD ) ) ;
        model.attach( table ) ;

        JScrollPane scroll = new JScrollPane( table ) ;
        scroll.setPreferredSize( new Dimension(
                400, table.getRowHeight() * ( rows + 1 ) + 8 ) ) ;

        JPanel panel = new JPanel( new BorderLayout() ) ;
        panel.setBorder( BorderFactory.createTitledBorder( title ) ) ;
        panel.add( scroll, BorderLayout.CENTER ) ;
        return panel ;
    }

    /**
     * Cells with numbers ("12", "3 / 20", "8.3 s", "1 m 05 s") right
     * aligned and the rest left aligned; in a transposed table the first
     * column (the row headers) is bold and every other row striped.
     */
    private static class Cells extends DefaultTableCellRenderer {

        private static final Pattern NUMERIC = Pattern.compile(
                "\\d+(\\.\\d+)?( / \\d+)?( s| m \\d\\d s| h \\d\\d m)?" ) ;

        /** Keeps right-aligned numbers off the next column's text. */
        private static final Border PADDING =
                BorderFactory.createEmptyBorder( 0, 6, 0, 8 ) ;

        /** Shows a check box for a {@code Boolean} value. */
        private final JCheckBox box = new JCheckBox() ;

        private final boolean      transposed ;
        private final IntPredicate highlighted ;

        /** @param highlighted the columns shown pink; null for none */
        Cells( boolean transposed, IntPredicate highlighted ) {
            this.transposed  = transposed ;
            this.highlighted = highlighted ;
            box.setHorizontalAlignment( CENTER ) ;
            box.setOpaque( true ) ;
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean selected, boolean focus,
                int row, int column ) {

            super.getTableCellRendererComponent( table, value, selected,
                                                 focus, row, column ) ;
            boolean number = value instanceof Number || value != null
                             && NUMERIC.matcher( value.toString() ).matches() ;
            boolean rowHeader = transposed && column == 0 ;

            setHorizontalAlignment( number ? RIGHT : LEFT ) ;
            setBorder( PADDING ) ;
            setFont( rowHeader ? table.getFont().deriveFont( Font.BOLD )
                               : table.getFont() ) ;
            if( !selected ) {
                boolean pink = highlighted != null
                               && highlighted.test( column ) ;
                setBackground( pink ? PASSTHROUGH
                             : transposed && row % 2 == 1 ? STRIPE
                             : table.getBackground() ) ;
            }
            if( value instanceof Boolean b ) {
                box.setSelected( b ) ;
                box.setBackground( getBackground() ) ;
                return box ;
            }
            return this ;
        }
    }

    /**
     * The models table's rows: the Passthrough cell of a usable model can be
     * ticked, which sets it to pass its questions through.
     */
    private final class ModelRows extends RowsModel {

        ModelRows() {
            super( 0, new Col( "", MODEL_LABEL_WIDTH ) ) ;
        }

        @Override
        public boolean isCellEditable( int r, int c ) {
            return getValueAt( r, c ) instanceof Boolean ;
        }

        @Override
        public void setValueAt( Object value, int r, int c ) {
            if( value instanceof Boolean on && c >= 1
                && c <= shownModels.size() ) {
                passthrough.accept( shownModels.get( c - 1 ).id(), on ) ;
            }
        }
    }

    /**
     * A read-only table of rows, replaced whole on each update. The rows
     * selected in its table stay selected across updates: they are matched
     * by the value in the key column.
     */
    private static class RowsModel extends AbstractTableModel {

        private final int keyColumn ;

        private List<Col>      columns ;
        private List<Object[]> rows = List.of() ;
        private JTable         table ;

        RowsModel( int keyColumn, Col... columns ) {
            this.keyColumn = keyColumn ;
            this.columns   = List.of( columns ) ;
        }

        /** Show this model in {@code table}, sizing its columns. */
        void attach( JTable table ) {
            this.table = table ;
            sizeColumns() ;
        }

        void set( List<Object[]> rows ) {
            set( columns, rows ) ;
        }

        /** New rows under {@code columns}, which may differ from the last. */
        void set( List<Col> columns, List<Object[]> rows ) {

            List<Object> selected = selectedKeys() ;

            this.rows = rows ;
            if( columns.equals( this.columns ) ) {
                fireTableDataChanged() ;
            }
            else {
                this.columns = List.copyOf( columns ) ;
                fireTableStructureChanged() ;
                sizeColumns() ;
            }
            select( selected ) ;
        }

        /** The key column's values of the rows selected in the table. */
        private List<Object> selectedKeys() {

            List<Object> keys = new ArrayList<>() ;
            if( table == null ) return keys ;
            for( int view : table.getSelectedRows() ) {
                int r = table.convertRowIndexToModel( view ) ;
                if( r < rows.size() ) keys.add( rows.get( r )[keyColumn] ) ;
            }
            return keys ;
        }

        /** Select the rows whose key is among {@code keys}. */
        private void select( List<Object> keys ) {

            if( table == null || keys.isEmpty() ) return ;
            ListSelectionModel sel = table.getSelectionModel() ;

            sel.setValueIsAdjusting( true ) ;
            sel.clearSelection() ;
            for( int r = 0 ; r < rows.size() ; r++ ) {
                if( keys.contains( rows.get( r )[keyColumn] ) ) {
                    int view = table.convertRowIndexToView( r ) ;
                    sel.addSelectionInterval( view, view ) ;
                }
            }
            sel.setValueIsAdjusting( false ) ;
        }

        private void sizeColumns() {

            if( table == null ) return ;
            TableColumnModel cols = table.getColumnModel() ;
            for( int c = 0 ; c < cols.getColumnCount() ; c++ ) {
                cols.getColumn( c ).setPreferredWidth( columns.get( c ).width() ) ;
            }
        }

        @Override public int getRowCount() { return rows.size() ; }

        @Override public int getColumnCount() { return columns.size() ; }

        @Override public String getColumnName( int c ) {
            return columns.get( c ).name() ;
        }

        @Override public Object getValueAt( int r, int c ) {
            return rows.get( r )[c] ;
        }
    }
}
