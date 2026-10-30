package com.sandy.sconsole.qimgtools.apps.nxrclassifier.ui;

import com.sandy.sconsole.qimgtools.apps.nxrclassifier.model.NXRItem;
import com.sandy.sconsole.qimgtools.apps.nxrclassifier.model.NXRStatus;
import com.sandy.sconsole.qimgtools.apps.nxrclassifier.model.NXRUpdate;
import com.sandy.sconsole.qimgtools.apps.nxrclassifier.model.NXRValue;
import com.sandy.sconsole.qimgtools.core.server.ServerQuestion;
import com.sandy.sconsole.qimgtools.core.ui.HeaderFilterController;
import com.sandy.sconsole.nxr.NXRResult;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The NXR classifier's table: one row per question awaiting its label, with
 * the classifier's label, the one to save, and where the question stands.
 * All state lives here and is touched on the EDT only.
 */
@SuppressWarnings( "serial" )
final class NXRTableModel extends AbstractTableModel
        implements HeaderFilterController.Columns<NXRTableModel.Row> {

    static final int COL_ID         = 0 ;
    static final int COL_SOURCE     = 1 ;
    static final int COL_QUESTION   = 2 ;
    static final int COL_TYPE       = 3 ;
    static final int COL_CLASSIFIER = 4 ;
    static final int COL_CONFIDENCE = 5 ;
    static final int COL_LABEL      = 6 ;
    static final int COL_STATUS     = 7 ;
    static final int COL_SAVED      = 8 ;

    private static final String[] COLUMNS = {
            "Id", "Source", "Question", "Type", "Classifier", "Conf %", "Label",
            "Status", ""
    } ;

    /** One question and everything that has happened to it. */
    static final class Row {

        final NXRItem item ;

        /** The question as the server sent it; its label once saved. */
        final ServerQuestion question ;

        NXRStatus status ;

        /** The classifier's result; null until it has run. */
        NXRResult result ;

        /** The classifier's label; null if it gave none (or hasn't run). */
        NXRValue suggested ;

        /** The user's label; null if the user chose none. */
        NXRValue chosen ;

        /**
         * What is going on or went wrong, for the detail area: a failure,
         * a server error, or what was saved.
         */
        String message ;

        /**
         * The status the classifier left, to go back to when the user's
         * label is cleared.
         */
        NXRStatus classifiedStatus ;
        String    classifiedMessage ;

        Row( NXRItem item ) {
            this.item             = item ;
            this.question         = item.question() ;
            this.status           = item.hasImages() ? NXRStatus.PENDING
                                                     : NXRStatus.NO_IMAGES ;
            this.message          = item.problem() ;
            this.classifiedStatus = status ;
        }

        /** The label to save: the user's, else the classifier's; or null. */
        NXRValue value() {
            return chosen != null ? chosen : suggested ;
        }

        /**
         * True if the user may label it: its images are there, so it can be
         * seen, and it isn't being saved.
         */
        boolean isOpen() {
            return item.hasImages() && status != NXRStatus.SAVING ;
        }

        /**
         * True if Save sends it: it has a label the server doesn't hold
         * (the classifier's is taken as it is), or one the server refused.
         */
        boolean isSavable() {
            return value() != null
                   && ( status == NXRStatus.CLASSIFIED
                        || status == NXRStatus.DECIDED
                        || status == NXRStatus.ERROR ) ;
        }

        /** True if it has a label of the user's not on the server yet. */
        boolean isUnsaved() {
            return chosen != null && status != NXRStatus.SAVING
                   && status != NXRStatus.SAVED ;
        }

        /** The update Save sends for it. */
        NXRUpdate update() {
            return new NXRUpdate( item, value(), result ) ;
        }

        /**
         * "R  Recall  93%  MODEL (reason)", or "no label (reason)"; blank
         * before the classifier has run.
         */
        String classifierText() {

            if( result == null ) return "" ;
            String why = result.reason() == null ? ""
                                                 : " (" + result.reason() + ")" ;
            if( suggested == null ) {
                NXRValue lean = NXRValue.of( result.guess() ) ;
                return "no label" + ( lean == null ? ""
                                      : ", leans " + lean.describe() ) + why ;
            }
            return suggested.describe() + "  " + percent( result ) + "  " +
                   result.method() + why ;
        }

        /** Local question id, or the server's if the images weren't found. */
        String questionLabel() {
            return item.hasImages() ? item.task().getQuestionId()
                                    : question.getQuestionId() ;
        }
    }

    private final List<Row>        rows = new ArrayList<>() ;
    private final Map<Integer,Row> byId = new HashMap<>() ;

    /** Replace the rows with one for each of {@code items}. */
    void setItems( List<NXRItem> items ) {

        rows.clear() ;
        byId.clear() ;
        for( NXRItem item : items ) {
            Row row = new Row( item ) ;
            rows.add( row ) ;
            byId.put( item.id(), row ) ;
        }
        fireTableDataChanged() ;
    }

    @Override
    public List<Row> rows() {
        return rows ;
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
        return col == COL_SOURCE || col == COL_TYPE
               || col == COL_CLASSIFIER || col == COL_STATUS ;
    }

    @Override
    public List<String> values( int col ) {
        return col != COL_STATUS ? null
               : Arrays.stream( NXRStatus.values() )
                       .map( NXRStatus::getLabel ).toList() ;
    }

    Row getRow( int modelIndex ) {
        return rows.get( modelIndex ) ;
    }

    /** The row of {@code item}; null if it isn't loaded (any more). */
    Row rowOf( NXRItem item ) {
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

    /**
     * Repaint every row after their state changed. Unlike a data change,
     * this keeps the table's selection.
     */
    void changedAll() {
        if( !rows.isEmpty() ) fireTableRowsUpdated( 0, rows.size() - 1 ) ;
    }

    /** Rows with a label of the user's not on the server yet. */
    long unsavedCount() {
        return rows.stream().filter( Row::isUnsaved ).count() ;
    }

    /** Rows with {@code status}. */
    long count( NXRStatus status ) {
        return rows.stream().filter( r -> r.status == status ).count() ;
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
        // The saved flag is shown as a dot; sorted by its text.
        return switch( column ) {
            case COL_ID, COL_CONFIDENCE -> Integer.class ;
            case COL_SAVED -> Object.class ;
            default        -> String.class ;
        } ;
    }

    @Override
    public Object getValueAt( int rowIndex, int column ) {

        Row            row = rows.get( rowIndex ) ;
        ServerQuestion q   = row.question ;

        return switch( column ) {
            case COL_ID         -> q.getId() ;
            case COL_SOURCE     -> q.getSourceId() ;
            case COL_QUESTION   -> row.questionLabel() ;
            case COL_TYPE       -> q.getProblemType() ;
            case COL_CLASSIFIER -> classifierCell( row ) ;
            case COL_CONFIDENCE -> row.result == null ? null
                                   : (Integer) (int) Math.round(
                                           row.result.confidence() * 100 ) ;
            case COL_LABEL      -> labelText( row ) ;
            case COL_STATUS     -> row.status.getLabel() ;
            case COL_SAVED      -> row.status == NXRStatus.SAVED ;
            default             -> null ;
        } ;
    }

    /**
     * The classifier's label, its low-confidence lean in brackets, "-"
     * for neither; blank before it has run.
     */
    private static String classifierCell( Row row ) {

        if( row.result == null ) return "" ;
        if( row.suggested != null ) return row.suggested.name() ;
        NXRValue lean = NXRValue.of( row.result.guess() ) ;
        return lean == null ? "-" : "(" + lean.name() + ")" ;
    }

    /** The result's confidence as "93%". */
    private static String percent( NXRResult r ) {
        return Math.round( r.confidence() * 100 ) + "%" ;
    }

    /** "R  Recall", or "[U] X  Reasoning" for the user's; blank for none. */
    private static String labelText( Row row ) {

        NXRValue v = row.value() ;
        if( v == null ) return "" ;
        return ( row.chosen != null && row.chosen != row.suggested
                 ? "[U] " : "" ) + v.describe() ;
    }
}
