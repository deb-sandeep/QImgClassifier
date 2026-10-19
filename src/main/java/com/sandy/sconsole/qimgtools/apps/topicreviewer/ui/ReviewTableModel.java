package com.sandy.sconsole.qimgtools.apps.topicreviewer.ui;

import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewDecision;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewItem;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewOpinions;
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
 * the server's topic, the review model's and the tie-breaker's, and where
 * the question stands.
 * All state lives here and is touched on the EDT only.
 */
@SuppressWarnings( "serial" )
final class ReviewTableModel extends AbstractTableModel {

    static final int COL_ID          = 0 ;
    static final int COL_SOURCE      = 1 ;
    static final int COL_QUESTION    = 2 ;
    static final int COL_SERVER      = 3 ;
    static final int COL_REVIEWER    = 4 ;
    static final int COL_TIE_BREAKER = 5 ;
    static final int COL_STATUS      = 6 ;
    static final int COL_SAVED       = 7 ;

    private static final String[] COLUMNS = {
            "Id", "Source", "Question", "Server", "Reviewer", "Tie breaker",
            "Status", ""
    } ;

    /** One question and everything that has happened to it. */
    static final class Row {

        final ReviewItem item ;

        /** The question as the server last returned it. */
        ServerQuestion question ;
        ReviewStatus   status ;

        /** The server's topic as loaded, which a saved decision replaces. */
        final Integer serverTopicId ;
        final String  serverTopicName ;

        /** The AI's opinions; null until the review model has given one. */
        ReviewOpinions opinions ;

        /** Model of the latest attempt, and the attempt's number. */
        String model ;
        int    attempts ;

        /**
         * What is going on or went wrong, for the detail panel: the attempt
         * under way, a failure reason, a server error, or the decision.
         */
        String message ;

        /**
         * The user's decision until the server has taken it (kept if the
         * server refused it, to save again); null if none.
         */
        ReviewDecision decision ;

        /**
         * The topic decided on, also once saved; null while undecided. Its
         * cell is shown green.
         */
        Topic assigned ;

        /** Status and message to go back to if the decision is cleared. */
        ReviewStatus undecidedStatus ;
        String       undecidedMessage ;

        Row( ReviewItem item ) {
            this.item            = item ;
            this.question        = item.question() ;
            this.serverTopicId   = question.getTopicId() ;
            this.serverTopicName = question.getTopicName() ;
            this.status          = item.hasImages() ? ReviewStatus.PENDING
                                                    : ReviewStatus.NO_IMAGES ;
            this.message         = item.problem() ;
        }

        /** The review model's verdict; null until it has given one. */
        ReviewVerdict reviewer() {
            return opinions == null ? null : opinions.reviewer() ;
        }

        /** The tie-breaker's verdict; null unless it has given one. */
        ReviewVerdict tieBreaker() {
            return opinions == null ? null : opinions.tieBreaker() ;
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

        /** True if it may be given the review model's topic. */
        boolean canUseReviewer() {
            return isOpen() && reviewer() != null ;
        }

        /** True if it may be given the tie-breaker's topic. */
        boolean canUseTieBreaker() {
            return isOpen() && tieBreaker() != null ;
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

    /** The column names, the model columns' with their models. */
    private final String[] columns = COLUMNS.clone() ;

    /**
     * @param reviewer   the review model's id, named in its column's title;
     *                   null for none
     * @param tieBreaker the tie-breaker's id, likewise
     */
    ReviewTableModel( ModelNames names, String reviewer, String tieBreaker ) {

        columns[COL_REVIEWER]    = title( COL_REVIEWER, reviewer, names ) ;
        columns[COL_TIE_BREAKER] = title( COL_TIE_BREAKER, tieBreaker,
                                          names ) ;
    }

    /**
     * "Reviewer (Nemotron)": column {@code col}'s title with the first word
     * of {@code model}'s display name; the plain title without a model.
     */
    private static String title( int col, String model, ModelNames names ) {

        if( model == null ) return COLUMNS[col] ;
        String name = names.of( model ).strip() ;
        int    sp   = name.indexOf( ' ' ) ;

        return COLUMNS[col] + " (" + ( sp < 0 ? name : name.substring( 0, sp ) )
               + ")" ;
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
        return columns[column] ;
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
            case COL_ID          -> q.getId() ;
            case COL_SOURCE      -> q.getSourceId() ;
            case COL_QUESTION    -> row.questionLabel() ;
            case COL_SERVER      -> row.serverTopicName ;
            case COL_REVIEWER    -> topic( row.reviewer() ) ;
            case COL_TIE_BREAKER -> tieBreakerText( row ) ;
            case COL_STATUS      -> statusText( row ) ;
            case COL_SAVED       -> saved( row ) ;
            default              -> null ;
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

    /** "Kinematics (92%)", or blank without a verdict. */
    private static String topic( ReviewVerdict v ) {
        return v == null ? ""
                         : v.top().getTopicName() + " (" + v.topConfidence() +
                           "%)" ;
    }

    /**
     * The tie-breaker's topic, "-" if it gave none, or "[U] Work" when the
     * topic decided on is none of the three opinions' (chosen by the user).
     */
    private static String tieBreakerText( Row row ) {

        if( decidedColumn( row ) == COL_TIE_BREAKER && !isTieBreakers( row ) ) {
            return "[U] " + row.assigned.getTopicName() ;
        }
        if( row.opinions != null && row.opinions.tieBreakerFailed() ) {
            return "-" ;
        }
        return topic( row.tieBreaker() ) ;
    }

    /**
     * The topic column of the topic decided on for {@code row}: the server's
     * if it is the server's topic as loaded, else the reviewer's or the
     * tie-breaker's if it is theirs, else the tie-breaker's (where a topic
     * the user chose is shown); -1 while undecided.
     */
    static int decidedColumn( Row row ) {

        Topic t = row.assigned ;
        if( t == null ) return -1 ;
        if( row.serverTopicId != null && row.serverTopicId == t.getId() ) {
            return COL_SERVER ;
        }
        if( row.reviewer() != null && row.reviewer().agreesWith( t.getId() ) ) {
            return COL_REVIEWER ;
        }
        return COL_TIE_BREAKER ;
    }

    /**
     * True if column {@code col} of {@code row} holds the topic decided on:
     * its {@link #decidedColumn}, and the tie-breaker's too when the topic
     * is the tie-breaker's (it broke the tie).
     */
    static boolean isDecidedCell( Row row, int col ) {
        return col == decidedColumn( row )
               || col == COL_TIE_BREAKER && row.assigned != null
                  && isTieBreakers( row ) ;
    }

    /** True if the topic decided on is the tie-breaker's. */
    private static boolean isTieBreakers( Row row ) {
        return row.tieBreaker() != null
               && row.tieBreaker().agreesWith( row.assigned.getId() ) ;
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
