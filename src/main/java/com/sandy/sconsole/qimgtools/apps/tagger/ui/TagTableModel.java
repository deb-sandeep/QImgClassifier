package com.sandy.sconsole.qimgtools.apps.tagger.ui;

import com.sandy.sconsole.qimgtools.apps.tagger.model.TagDecision;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagItem;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagProposal;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TaggerStatus;
import com.sandy.sconsole.qimgtools.apps.tagger.regression.Assessment;
import com.sandy.sconsole.qimgtools.core.ui.HeaderFilterController;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The tagger's table: one row per question loaded, with its tags (the chosen
 * set, else the model's proposal) and where it stands. All row state lives
 * here and is touched only on the EDT.
 */
@SuppressWarnings( "serial" )
final class TagTableModel extends AbstractTableModel
        implements HeaderFilterController.Columns<TagTableModel.Row> {

    static final int COL_ID       = 0 ;
    static final int COL_SOURCE   = 1 ;
    static final int COL_QUESTION = 2 ;
    static final int COL_TYPE     = 3 ;
    static final int COL_TAGS     = 4 ;
    static final int COL_STATUS   = 5 ;
    static final int COL_SAVED    = 6 ;

    private static final String[] COLUMNS = {
            "Id", "Source", "Question", "Type", "Tags", "Status", ""
    } ;

    /** One question and everything that has happened to it. */
    static final class Row {

        final TagItem item ;

        /** The topic's explicit tags on it, as loaded. */
        final List<String> existing ;

        TaggerStatus status ;

        /** The OCR text and the regression's decision; null until read. */
        Assessment assessment ;

        /** What the model proposed; null until it has replied. */
        TagProposal proposal ;

        /** Model of the latest attempt, and the attempt's number. */
        String model ;
        int    attempts ;

        /** True while a request is in flight for it: its Tags cell is framed. */
        boolean inFlight ;

        /**
         * What is going on or went wrong, for the detail area: the attempt
         * under way, a failure, a server error, or the decision.
         */
        String message ;

        /**
         * The tags to save, staged by the model or the user, until the
         * server has taken them (kept if it refused them); null if none.
         */
        TagDecision decision ;

        /**
         * The set {@link #decision} started from, as chosen (the model's
         * tags, the existing ones, or none), before the user's edits; null
         * with no decision.
         */
        TagDecision base ;

        /** The note it was deferred with; null unless deferred. */
        String deferNote ;

        /** Status and message to go back to if the decision is cleared. */
        TaggerStatus undecidedStatus ;
        String       undecidedMessage ;

        Row( TagItem item, List<String> existing ) {
            this.item     = item ;
            this.existing = List.copyOf( existing ) ;
            this.status   = item.hasImages() ? TaggerStatus.PENDING
                                             : TaggerStatus.NO_IMAGES ;
            this.message  = item.problem() ;
        }

        /**
         * The chosen set: the decision, else the accepted proposal; null if
         * nothing is chosen.
         */
        TagDecision chosen() {

            if( decision != null ) return decision ;
            return status.isAccepted() ? proposed() : null ;
        }

        /**
         * The set proposed for it: the model's, else the one the regression
         * settled on; null if neither.
         */
        TagDecision proposed() {

            if( proposal != null ) return TagDecision.of( proposal, false ) ;
            return assessment != null && assessment.isSettled()
                   ? TagDecision.regression( assessment.decision().accepted() )
                   : null ;
        }

        /** True if it waits for the LLM: the regression didn't settle it. */
        boolean needsLlm() {
            return status == TaggerStatus.PENDING && assessment != null
                   && assessment.needsLlm() ;
        }

        /**
         * True if the user may decide on it: the models are done with it,
         * it isn't being saved, and it isn't saved.
         */
        boolean isOpen() {
            return status.isDecidable() ;
        }

        /** True if it may take the model's tags. */
        boolean canUseProposal() {
            return isOpen() && proposed() != null ;
        }

        /** True if it has existing tags to keep. */
        boolean canKeepExisting() {
            return isOpen() && !existing.isEmpty() ;
        }

        /**
         * True if Save sends it: an accepted proposal (decided as the save
         * starts), or a decision not saved yet.
         */
        boolean isSavable() {
            return status.isAccepted() || isUnsaved() ;
        }

        /** True if it has a decision not sent to the server yet. */
        boolean isUnsaved() {
            return decision != null && status != TaggerStatus.SAVING
                   && status != TaggerStatus.SAVED ;
        }

        /** Local question id, or the server's if the images weren't found. */
        String questionLabel() {
            return item.hasImages() ? item.task().getQuestionId()
                                    : item.question().getQuestionId() ;
        }
    }

    private final List<Row>        rows = new ArrayList<>() ;
    private final Map<Integer,Row> byId = new HashMap<>() ;

    /** Replace the rows with {@code rows}. */
    void setRows( List<Row> rows ) {

        this.rows.clear() ;
        byId.clear() ;
        for( Row row : rows ) {
            this.rows.add( row ) ;
            byId.put( row.item.id(), row ) ;
        }
        fireTableDataChanged() ;
    }

    /** Every row, in load order. */
    @Override
    public List<Row> rows() {
        return Collections.unmodifiableList( rows ) ;
    }

    @Override
    public String cellText( Row row, int col ) {
        Object v = valueOf( row, col ) ;
        return v == null ? "" : v.toString() ;
    }

    @Override
    public boolean filterable( int col ) {
        return col != COL_SAVED ;
    }

    @Override
    public boolean enumerable( int col ) {
        return col == COL_SOURCE || col == COL_TYPE || col == COL_STATUS ;
    }

    @Override
    public List<String> values( int col ) {
        return col != COL_STATUS ? null
               : Arrays.stream( TaggerStatus.values() )
                       .map( TaggerStatus::getLabel ).toList() ;
    }

    Row getRow( int modelIndex ) {
        return rows.get( modelIndex ) ;
    }

    /** The row of {@code item}; null if it isn't loaded (any more). */
    Row rowOf( TagItem item ) {
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

    /** Rows with {@code status}. */
    long count( TaggerStatus status ) {
        return rows.stream().filter( r -> r.status == status ).count() ;
    }

    /** Rows {@code test} accepts. */
    long count( Predicate<Row> test ) {
        return rows.stream().filter( test ).count() ;
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
    public String getColumnName( int col ) {
        return COLUMNS[col] ;
    }

    @Override
    public Class<?> getColumnClass( int col ) {
        return col == COL_ID    ? Integer.class
             : col == COL_SAVED ? Boolean.class
             :                    String.class ;
    }

    @Override
    public Object getValueAt( int row, int col ) {
        return valueOf( rows.get( row ), col ) ;
    }

    private Object valueOf( Row row, int col ) {

        TagItem item = row.item ;
        return switch( col ) {
            case COL_ID       -> item.id() ;
            case COL_SOURCE   -> item.question().getSourceId() ;
            case COL_QUESTION -> row.questionLabel() ;
            case COL_TYPE     -> item.type() ;
            case COL_TAGS     -> tagsText( row ) ;
            case COL_STATUS   -> row.status.getLabel() ;
            case COL_SAVED    -> row.status == TaggerStatus.SAVED ;
            default           -> null ;
        } ;
    }

    /**
     * "[M] a, b": the chosen set, marked with where it started from
     * ({@link #sourceMark}), else the model's proposal, in parentheses, or
     * "[LLM] (a, b)" for a question waiting for the LLM, with the tags the
     * regression accepted so far.
     */
    private static String tagsText( Row row ) {

        TagDecision d = row.chosen() ;
        if( d != null ) {
            return "[" + sourceMark( d ) + "] " +
                   ( d.tags().isEmpty() ? "no tags"
                                        : String.join( ", ", d.tags() ) ) ;
        }
        if( row.needsLlm() ) {
            List<String> sofar = row.assessment.decision().accepted() ;
            return "[LLM]" + ( sofar.isEmpty() ? ""
                               : " (" + String.join( ", ", sofar ) + ")" ) ;
        }
        return row.proposal == null ? ""
               : "(" + String.join( ", ", row.proposal.reply().tags() ) + ")" ;
    }

    /**
     * "R" (the regression's), "M" (the model's), "E" (the existing tags) or
     * "U" (the user's own set), with a "*" once edited.
     */
    static String sourceMark( TagDecision d ) {

        String s = switch( d.source() ) {
            case REGRESSION -> "R" ;
            case MODEL      -> "M" ;
            case EXISTING   -> "E" ;
            case USER       -> "U" ;
        } ;
        return d.edited() && d.source() != TagDecision.Source.USER ? s + "*"
                                                                    : s ;
    }

    /** True if {@code row}'s Tags cell shows a proposal, not a chosen set. */
    static boolean showsProposal( Row row ) {
        return row.chosen() == null && ( row.proposal != null || row.needsLlm() ) ;
    }
}
