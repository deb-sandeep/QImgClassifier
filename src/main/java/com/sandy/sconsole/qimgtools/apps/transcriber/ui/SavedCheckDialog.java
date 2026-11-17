package com.sandy.sconsole.qimgtools.apps.transcriber.ui;

import com.sandy.sconsole.qimgtools.apps.transcriber.model.TranscriptionRoute;
import com.sandy.sconsole.qimgtools.apps.transcriber.workspace.TranscriptionAuditLog.Saved;
import com.sandy.sconsole.qimgtools.core.util.ModelNames;

import lombok.extern.slf4j.Slf4j;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * File > Spot-check saved questions… (feature-design.md §8.3): the
 * transcriptions saved in the last hours, from the audit log, in check
 * order (escalated first, then by C, largest first, then by lowest token
 * probability). The ones chosen are loaded into the main table, where the
 * usual marks and editing apply and Save stores over them. Use on the EDT.
 */
@Slf4j
@SuppressWarnings( "serial" )
final class SavedCheckDialog extends JDialog {

    /** Reads the saved transcriptions since an instant. */
    interface Lister {
        List<Saved> since( Instant since ) throws IOException ;
    }

    private static final int MAX_HOURS = 24 * 30 ;

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern( "dd MMM HH:mm" )
                             .withZone( ZoneId.systemDefault() ) ;

    private static final String[] COLUMNS = {
            "Saved at", "Question", "Source", "Route", "C", "min p"
    } ;

    /** Check order: escalated first, then by C, largest first. */
    private static final Comparator<Saved> CHECK_ORDER =
            Comparator.comparingInt( ( Saved s ) -> s.route() != null
                    && s.route().kind() == TranscriptionRoute.Kind.ESCALATED
                    ? 0 : 1 )
                      .thenComparing( ( Saved s ) -> s.c() == null ? -1
                                                                  : s.c(),
                                      Comparator.reverseOrder() )
                      .thenComparingDouble( s -> s.minP() == null ? 1
                                                                  : s.minP() ) ;

    private final Lister      lister ;
    private final ModelNames  names ;
    private final List<Saved> rows  = new ArrayList<>() ;
    private final Model       model = new Model() ;
    private final JTable      table = new JTable( model ) ;
    private final JSpinner    hours = new JSpinner(
            new SpinnerNumberModel( 24, 1, MAX_HOURS, 1 ) ) ;
    private final JLabel      found = new JLabel( " " ) ;

    private List<Saved> chosen ;

    private SavedCheckDialog( Window owner, Lister lister, ModelNames names ) {

        super( owner, "Spot-check saved questions",
               ModalityType.APPLICATION_MODAL ) ;
        this.lister = lister ;
        this.names  = names ;

        JButton list   = new JButton( "List" ) ;
        JButton check  = new JButton( "Check in the table" ) ;
        JButton cancel = new JButton( "Cancel" ) ;
        JPanel  top    = new JPanel( new FlowLayout( FlowLayout.LEFT ) ) ;
        JPanel  bottom = new JPanel( new BorderLayout() ) ;
        JPanel  keys   = new JPanel( new FlowLayout( FlowLayout.RIGHT ) ) ;

        top.add( new JLabel( "Saved in the last" ) ) ;
        top.add( hours ) ;
        top.add( new JLabel( "hours" ) ) ;
        top.add( list ) ;
        list.addActionListener( e -> refresh() ) ;

        table.setSelectionMode(
                ListSelectionModel.MULTIPLE_INTERVAL_SELECTION ) ;
        table.setAutoCreateRowSorter( false ) ;
        table.getColumnModel().getColumn( 0 ).setPreferredWidth( 100 ) ;
        table.getColumnModel().getColumn( 1 ).setPreferredWidth( 110 ) ;
        table.getColumnModel().getColumn( 2 ).setPreferredWidth( 200 ) ;
        table.getColumnModel().getColumn( 3 ).setPreferredWidth( 160 ) ;
        table.getColumnModel().getColumn( 4 ).setPreferredWidth( 30 ) ;
        table.getColumnModel().getColumn( 5 ).setPreferredWidth( 50 ) ;

        check.addActionListener( e -> {
            int[] sel = table.getSelectedRows() ;
            chosen = new ArrayList<>() ;
            if( sel.length == 0 ) chosen.addAll( rows ) ;
            for( int i : sel ) chosen.add( rows.get( i ) ) ;
            dispose() ;
        } ) ;
        cancel.addActionListener( e -> dispose() ) ;
        keys.add( check ) ;
        keys.add( cancel ) ;
        found.setBorder( BorderFactory.createEmptyBorder( 0, 8, 0, 8 ) ) ;
        bottom.add( found, BorderLayout.CENTER ) ;
        bottom.add( keys, BorderLayout.EAST ) ;

        JScrollPane scroll = new JScrollPane( table ) ;
        scroll.setPreferredSize( new Dimension( 700, 420 ) ) ;
        add( top, BorderLayout.NORTH ) ;
        add( scroll, BorderLayout.CENTER ) ;
        add( bottom, BorderLayout.SOUTH ) ;
        getRootPane().setDefaultButton( check ) ;
        refresh() ;
        pack() ;
        setLocationRelativeTo( owner ) ;
    }

    /**
     * Ask which saved transcriptions to check.
     *
     * @return those chosen (all listed if none is selected), in check
     *         order; null if cancelled or none was listed
     */
    static List<Saved> ask( Window owner, Lister lister, ModelNames names ) {

        SavedCheckDialog d = new SavedCheckDialog( owner, lister, names ) ;
        d.setVisible( true ) ;
        return d.chosen == null || d.chosen.isEmpty() ? null : d.chosen ;
    }

    /** List the transcriptions saved in the hours chosen. */
    private void refresh() {

        Instant since = Instant.now().minus(
                Duration.ofHours( (Integer) hours.getValue() ) ) ;
        rows.clear() ;
        try {
            rows.addAll( lister.since( since ) ) ;
            rows.sort( CHECK_ORDER ) ;
            found.setText( rows.size() + " saved" ) ;
        }
        catch( IOException e ) {
            log.error( "Could not read the audit log", e ) ;
            found.setText( "Could not read the audit log: " +
                           e.getMessage() ) ;
        }
        model.fireTableDataChanged() ;
    }

    private String route( TranscriptionRoute r ) {

        if( r == null ) return "" ;
        String model = r.model() == null ? "-" : names.of( r.model() ) ;
        return switch( r.kind() ) {
            case QWEN      -> model ;
            case ESCALATED -> "Qwen → " + model ;
            case STAND_IN  -> model + " (Qwen down)" ;
            case SHARED    -> model ;
            case RESENT    -> model + " (re-sent)" ;
            case CHOSEN    -> model + " (chosen)" ;
            case EDITED    -> "Edited (" + model + ")" ;
            case STORED    -> model + " (stored)" ;
        } ;
    }

    private final class Model extends AbstractTableModel {

        @Override
        public int getRowCount() { return rows.size() ; }

        @Override
        public int getColumnCount() { return COLUMNS.length ; }

        @Override
        public String getColumnName( int c ) { return COLUMNS[c] ; }

        @Override
        public Object getValueAt( int r, int c ) {

            Saved s = rows.get( r ) ;
            return switch( c ) {
                case 0  -> TIME.format( s.at() ) ;
                case 1  -> s.question().getQuestionId() ;
                case 2  -> s.question().getSourceId() ;
                case 3  -> route( s.route() ) ;
                case 4  -> s.c() == null ? "-" : s.c().toString() ;
                case 5  -> s.minP() == null ? "-"
                                            : String.format( "%.2f", s.minP() ) ;
                default -> null ;
            } ;
        }
    }
}
