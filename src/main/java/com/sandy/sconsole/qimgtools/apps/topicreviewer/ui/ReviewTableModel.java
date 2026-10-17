package com.sandy.sconsole.qimgtools.apps.topicreviewer.ui;

import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewDecision;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewItem;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewStatus;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewVerdict;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ServerQuestion;
import com.sandy.sconsole.qimgtools.core.model.Topic;
import com.sandy.sconsole.qimgtools.core.util.ModelNames;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The topic reviewer's table: one row per question of the review queue, with
 * the server's topic, the model's verdict, and where the question stands.
 * All state lives here and is touched on the EDT only.
 */
@SuppressWarnings( "serial" )
final class ReviewTableModel extends AbstractTableModel {

    static final int COL_ID       = 0 ;
    static final int COL_SOURCE   = 1 ;
    static final int COL_QUESTION = 2 ;
    static final int COL_CURRENT  = 3 ;
    static final int COL_ASSOC    = 4 ;
    static final int COL_AI       = 5 ;
    static final int COL_STATUS   = 6 ;
    static final int COL_RESULT   = 7 ;
    static final int COL_SAVED    = 8 ;

    private static final String[] COLUMNS = {
            "Id", "Source", "Question", "Server topic", "Associated by",
            "Model's topic", "Status", "Result", ""
    } ;

    /** One question and everything that has happened to it. */
    static final class Row {

        final ReviewItem item ;

        /** The question as the server last returned it. */
        ServerQuestion question ;
        ReviewStatus   status ;

        /** The model's opinion; null until it has given one. */
        ReviewVerdict verdict ;

        /** Model of the latest attempt, and the attempt's number. */
        String model ;
        int    attempts ;

        /** Failure reason, server error, or what the decision does or did. */
        String result ;

        /**
         * The user's decision until the server has taken it (kept if the
         * server refused it, to save again); null if none.
         */
        ReviewDecision decision ;

        /** Status and result to go back to if the decision is cleared. */
        ReviewStatus undecidedStatus ;
        String       undecidedResult ;

        Row( ReviewItem item ) {
            this.item     = item ;
            this.question = item.question() ;
            this.status   = item.hasImages() ? ReviewStatus.PENDING
                                             : ReviewStatus.NO_IMAGES ;
            this.result   = item.problem() ;
        }

        /**
         * True if the user may decide on it: neither the model nor the
         * server is working on it, and no decision of it is saved.
         */
        boolean isOpen() {
            return status != ReviewStatus.QUEUED
                   && status != ReviewStatus.RUNNING
                   && status != ReviewStatus.SAVING
                   && !status.isDecided() ;
        }

        /** True if it may be given the model's topic. */
        boolean canUseModel() {
            return isOpen() && verdict != null ;
        }

        /** True if its server topic may be kept. */
        boolean canKeep() {
            return isOpen() && question.getTopicId() != null ;
        }

        /** True if it has a decision not sent to the server yet. */
        boolean isUnsaved() {
            return decision != null && status != ReviewStatus.SAVING ;
        }

        /** Local question id, or the server's if the images weren't found. */
        String questionLabel() {
            return item.hasImages() ? item.task().getQuestionId()
                                    : question.getQuestionId() ;
        }
    }

    private final List<Row>        rows = new ArrayList<>() ;
    private final Map<Integer,Row> byId = new HashMap<>() ;

    /** The names models are shown by. */
    private final ModelNames names ;

    ReviewTableModel( ModelNames names ) {
        this.names = names ;
    }

    /** Replace the rows with one for each of {@code items}. */
    void setItems( List<ReviewItem> items ) {

        rows.clear() ;
        byId.clear() ;
        for( ReviewItem item : items ) {
            Row row = new Row( item ) ;
            rows.add( row ) ;
            byId.put( item.id(), row ) ;
        }
        fireTableDataChanged() ;
    }

    List<Row> rows() {
        return rows ;
    }

    Row getRow( int modelIndex ) {
        return rows.get( modelIndex ) ;
    }

    /** The row of {@code item}; null if it isn't loaded (any more). */
    Row rowOf( ReviewItem item ) {
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

    /** Rows with a decision not sent to the server yet. */
    long unsavedCount() {
        return rows.stream().filter( Row::isUnsaved ).count() ;
    }

    /** Rows with {@code status}. */
    long count( ReviewStatus status ) {
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
            case COL_ID    -> Integer.class ;
            case COL_SAVED -> Object.class ;
            default        -> String.class ;
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
            case COL_CURRENT  -> q.getTopicName() ;
            case COL_ASSOC    -> provenance( row ) ;
            case COL_AI       -> aiTopic( row ) ;
            case COL_STATUS   -> statusText( row ) ;
            case COL_RESULT   -> row.result ;
            case COL_SAVED    -> saved( row ) ;
            default           -> null ;
        } ;
    }

    /**
     * True once the user's decision is saved on the server; false until
     * then, so for every question as loaded (the server sends only those
     * awaiting review).
     */
    private static boolean saved( Row row ) {
        return row.status.isDecided() ;
    }

    /**
     * Who the association is by: as decided, while the decision isn't saved
     * yet, else as on the server (which a saved decision has updated).
     */
    private String provenance( Row row ) {

        ReviewDecision d = row.decision ;
        if( d == null ) return row.question.provenance( names ) ;
        return ServerQuestion.provenance( d.actor(), d.model(),
                                          d.confidence(), names ) ;
    }

    /** "Kinematics (92%)", or blank before the model has answered. */
    private static String aiTopic( Row row ) {

        if( row.verdict == null ) return "" ;
        Topic top = row.verdict.top() ;
        return top.getTopicName() + " (" + row.verdict.topConfidence() + "%)" ;
    }

    /** The status, with the attempt number while running or on a retry. */
    private static String statusText( Row row ) {

        String s = row.status.getLabel() ;
        if( row.status == ReviewStatus.RUNNING && row.attempts > 1 ) {
            s += " (attempt " + row.attempts + ")" ;
        }
        return s ;
    }
}
