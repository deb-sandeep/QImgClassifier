package com.sandy.sconsole.qimgtools.apps.transcriber.ui;

import com.sandy.sconsole.qimgtools.apps.transcriber.model.Transcription;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.TranscriptionItem;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.TranscriptionRoute;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.TranscriptionStatus;
import com.sandy.sconsole.qimgtools.apps.transcriber.workspace.StagedTranscriptions.Staged;
import com.sandy.sconsole.qimgtools.core.server.ServerQuestion;
import com.sandy.sconsole.qimgtools.core.ui.HeaderFilterController;
import com.sandy.sconsole.qimgtools.core.util.ModelNames;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The transcriber's table: one row per question awaiting its
 * transcription, with how its transcription came about, Qwen's C, where it
 * stands, and whether the user checked it. All state lives here and is
 * touched on the EDT only.
 */
@SuppressWarnings( "serial" )
final class TranscriberTableModel extends AbstractTableModel
        implements HeaderFilterController.Columns<TranscriberTableModel.Row> {

    static final int COL_ID       = 0 ;
    static final int COL_SOURCE   = 1 ;
    static final int COL_QUESTION = 2 ;
    static final int COL_TYPE     = 3 ;
    static final int COL_ROUTE    = 4 ;
    static final int COL_C        = 5 ;
    static final int COL_STATUS   = 6 ;
    static final int COL_CHECKED  = 7 ;
    static final int COL_SAVED    = 8 ;

    private static final String[] COLUMNS = {
            "Id", "Source", "Question", "Type", "Route", "C", "Status",
            "Checked", ""
    } ;

    /**
     * The order rows are checked in (feature-design.md §8.2): escalated
     * first, then the unconfirmed, then Qwen's by C, largest first, ties
     * by the lowest token probability.
     */
    static final Comparator<Row> CHECK_ORDER =
            Comparator.comparingInt( Row::checkRank )
                      .thenComparing( Comparator.comparingInt(
                              ( Row r ) -> r.c() == null ? -1 : r.c() )
                                                .reversed() )
                      .thenComparingDouble( Row::minP ) ;

    /** One question and everything that has happened to it. */
    static final class Row {

        final TranscriptionItem item ;
        final ServerQuestion    question ;

        TranscriptionStatus status ;

        /**
         * What is going on or went wrong, for the detail line: the model
         * asked, a failure, the server's answer.
         */
        String message ;

        /**
         * The accepted transcription (an LCT question's part only), its
         * route, and the user's check; null until there is one. Kept while
         * the question is re-sent, until the new one arrives.
         */
        Staged staged ;

        /** True once the server stores it. */
        boolean saved ;

        /**
         * True if it can't be sent: the prompt has no section for its type,
         * or its subject is unknown.
         */
        boolean unsupported ;

        Row( TranscriptionItem item ) {
            this.item     = item ;
            this.question = item.question() ;
            this.status   = item.hasImages() ? TranscriptionStatus.PENDING
                                             : TranscriptionStatus.NO_IMAGES ;
            this.message  = item.problem() ;
        }

        /** The transcription, an LCT question's part only; or null. */
        Transcription transcription() {
            return staged == null ? null : staged.transcription() ;
        }

        TranscriptionRoute route() {
            return staged == null ? null : staged.route() ;
        }

        Integer c() {
            Transcription t = transcription() ;
            return t == null ? null : t.c() ;
        }

        double minP() {
            Transcription t = transcription() ;
            return t == null || t.minP() == null ? 1 : t.minP() ;
        }

        boolean checked() {
            return staged != null && staged.checked() ;
        }

        /** The model whose text it is; null without a transcription. */
        String model() {
            Transcription t = transcription() ;
            return t == null ? null : t.model() ;
        }

        /**
         * The status its transcription gives it: Unconfirmed while
         * provisional, else Transcribed; Pending without one.
         */
        TranscriptionStatus restingStatus() {
            if( staged == null ) {
                return item.hasImages() ? TranscriptionStatus.PENDING
                                        : TranscriptionStatus.NO_IMAGES ;
            }
            return staged.provisional() ? TranscriptionStatus.UNCONFIRMED
                                        : TranscriptionStatus.TRANSCRIBED ;
        }

        /** True if Start sends it: pending, with images. */
        boolean isSendable() {
            return item.hasImages() && !unsupported
                   && status == TranscriptionStatus.PENDING ;
        }

        /**
         * True if Save sends it: transcribed (an unconfirmed one only once
         * checked), or refused by the server before.
         */
        boolean isSavable() {
            return staged != null && !staged.provisional() && !saved
                   && ( status == TranscriptionStatus.TRANSCRIBED
                        || status == TranscriptionStatus.ERROR ) ;
        }

        /**
         * True if the user may mark it: it has a transcription and isn't
         * being transcribed again or saved.
         */
        boolean isMarkable() {
            return staged != null
                   && status != TranscriptionStatus.TRANSCRIBING
                   && status != TranscriptionStatus.SAVING ;
        }

        /** True if it is waiting for a model or with one. */
        boolean isBusy() {
            return status == TranscriptionStatus.TRANSCRIBING
                   || status == TranscriptionStatus.WAITING_PASSAGE ;
        }

        /** Where it comes in the check order; 9 if it isn't to check. */
        int checkRank() {

            if( staged == null || checked()
                || status == TranscriptionStatus.FAULTY ) {
                return 9 ;
            }
            TranscriptionRoute.Kind k = staged.route().kind() ;
            if( k == TranscriptionRoute.Kind.ESCALATED ) return 0 ;
            if( staged.provisional() ) return 1 ;
            return 2 ;
        }

        /** Local question id, or the server's if the images weren't found. */
        String questionLabel() {
            return item.label() ;
        }
    }

    private final ModelNames       names ;
    private final List<Row>        rows = new ArrayList<>() ;
    private final Map<Integer,Row> byId = new HashMap<>() ;

    TranscriberTableModel( ModelNames names ) {
        this.names = names ;
    }

    /** Replace the rows with one for each of {@code items}. */
    List<Row> setItems( List<TranscriptionItem> items ) {
        rows.clear() ;
        byId.clear() ;
        fireTableDataChanged() ;
        return addItems( items ) ;
    }

    /**
     * Add a row for each of {@code items} after those shown.
     *
     * @return the rows added
     */
    List<Row> addItems( List<TranscriptionItem> items ) {

        int       first = rows.size() ;
        List<Row> added = new ArrayList<>() ;

        for( TranscriptionItem item : items ) {
            Row row = new Row( item ) ;
            rows.add( row ) ;
            byId.put( item.id(), row ) ;
            added.add( row ) ;
        }
        if( !added.isEmpty() ) {
            fireTableRowsInserted( first, rows.size() - 1 ) ;
        }
        return added ;
    }

    /** True if the question with server id {@code id} has a row. */
    boolean has( int id ) {
        return byId.containsKey( id ) ;
    }

    /**
     * Drop the rows {@code test} accepts.
     *
     * @return how many were dropped
     */
    int removeIf( Predicate<Row> test ) {

        List<Row> gone = rows.stream().filter( test ).toList() ;
        if( gone.isEmpty() ) return 0 ;

        rows.removeAll( gone ) ;
        gone.forEach( r -> byId.remove( r.item.id() ) ) ;
        fireTableDataChanged() ;
        return gone.size() ;
    }

    @Override
    public List<Row> rows() {
        return rows ;
    }

    /** The rows of LCT group {@code lctCtxId}. */
    List<Row> group( String lctCtxId ) {
        return rows.stream()
                   .filter( r -> lctCtxId != null
                                 && lctCtxId.equals( r.item.lctCtxId() ) )
                   .toList() ;
    }

    @Override
    public String cellText( Row row, int col ) {
        Object value = getValueAt( indexOf( row ), col ) ;
        return value == null ? "" : value.toString() ;
    }

    @Override
    public boolean filterable( int col ) {
        return col != COL_SAVED ;
    }

    @Override
    public boolean enumerable( int col ) {
        return col == COL_SOURCE || col == COL_TYPE || col == COL_ROUTE
               || col == COL_STATUS || col == COL_CHECKED ;
    }

    @Override
    public List<String> values( int col ) {
        return col != COL_STATUS ? null
               : Arrays.stream( TranscriptionStatus.values() )
                       .map( TranscriptionStatus::getLabel ).toList() ;
    }

    Row getRow( int modelIndex ) {
        return rows.get( modelIndex ) ;
    }

    /** The row of the question with server id {@code id}; null if none. */
    Row rowOf( int id ) {
        return byId.get( id ) ;
    }

    /** The row of {@code item}; null if it isn't loaded (any more). */
    Row rowOf( TranscriptionItem item ) {
        Row row = item == null ? null : byId.get( item.id() ) ;
        return row != null && row.item == item ? row : null ;
    }

    int indexOf( Row row ) {
        return rows.indexOf( row ) ;
    }

    /** Repaint {@code row} after its state changed. */
    void changed( Row row ) {
        int i = rows.indexOf( row ) ;
        if( i >= 0 ) fireTableRowsUpdated( i, i ) ;
    }

    /** Rows with {@code status}. */
    long count( TranscriptionStatus status ) {
        return rows.stream().filter( r -> r.status == status ).count() ;
    }

    /** Rows with a transcription not saved yet. */
    long unsavedCount() {
        return rows.stream()
                   .filter( r -> r.staged != null && !r.saved )
                   .count() ;
    }

    /**
     * How a row's transcription came about, short: "Qwen",
     * "Qwen → Nemotron", "Nemotron (Qwen down)", "GLM (re-sent)",
     * "Edited (Qwen)"; blank without one.
     */
    String routeText( Row row ) {

        TranscriptionRoute r = row.route() ;
        if( r == null ) return "" ;

        String model = shortName( r.model() ) ;
        return switch( r.kind() ) {
            case QWEN      -> model ;
            case ESCALATED -> "Qwen → " + model ;
            case STAND_IN  -> model + " (Qwen down)" ;
            case RESENT    -> model + " (re-sent)" ;
            case EDITED    -> r.model() == null ? "Typed"
                                                : "Edited (" + model + ")" ;
            case STORED    -> model + " (stored)" ;
        } ;
    }

    /** A model's display name up to its first space, e.g. "Nemotron". */
    String shortName( String model ) {

        if( model == null ) return "-" ;
        String name = names.of( model ) ;
        int    sp   = name.indexOf( ' ' ) ;
        return sp > 0 ? name.substring( 0, sp ) : name ;
    }

    @Override
    public int getRowCount() {
        return rows.size() ;
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length ;
    }

    @Override
    public String getColumnName( int column ) {
        return COLUMNS[column] ;
    }

    @Override
    public Class<?> getColumnClass( int column ) {
        return switch( column ) {
            case COL_ID, COL_C -> Integer.class ;
            case COL_SAVED     -> Object.class ;
            default            -> String.class ;
        } ;
    }

    @Override
    public Object getValueAt( int rowIndex, int column ) {

        Row            row = rows.get( rowIndex ) ;
        ServerQuestion q   = row.question ;

        return switch( column ) {
            case COL_ID       -> q.getId() ;
            case COL_SOURCE   -> q.getSourceId() ;
            case COL_QUESTION -> row.questionLabel() ;
            case COL_TYPE     -> q.getProblemType() ;
            case COL_ROUTE    -> routeText( row ) ;
            case COL_C        -> row.c() ;
            case COL_STATUS   -> row.status.getLabel() ;
            case COL_CHECKED  -> row.checked() ? "✓" : "" ;
            case COL_SAVED    -> row.saved ;
            default           -> null ;
        } ;
    }
}
