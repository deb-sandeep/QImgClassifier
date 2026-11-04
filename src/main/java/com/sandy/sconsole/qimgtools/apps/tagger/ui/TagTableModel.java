package com.sandy.sconsole.qimgtools.apps.tagger.ui;

import com.sandy.sconsole.qimgtools.apps.tagger.model.TagDecision;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagItem;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagProposal;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TaggerStatus;
import com.sandy.sconsole.qimgtools.apps.tagger.regression.Assessment;
import com.sandy.sconsole.qimgtools.apps.tagger.regression.Decision;
import com.sandy.sconsole.qimgtools.core.ui.HeaderFilterController;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The tagger's table: one row per question loaded, with its tags (the chosen
 * set, and any undecided ones) and where it stands. The LLM's reply is an
 * opinion kept beside them: only the user moves its tags into the chosen set.
 * All row state lives here and is touched only on the EDT.
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

    /** Where a question stands with the LLM. */
    enum Llm { IDLE, QUEUED, ASKING }

    /** One question and everything that has happened to it. */
    static final class Row {

        final TagItem item ;

        /** The topic's explicit tags on it, as loaded. */
        final List<String> existing ;

        TaggerStatus status ;

        /** The OCR text and the regression's decision; null until read. */
        Assessment assessment ;

        /** The LLM's opinion; null until it has replied. */
        TagProposal proposal ;

        /** Where it stands with the LLM. */
        Llm llm = Llm.IDLE ;

        /** Why the LLM gave no usable opinion the last time; null if it did. */
        String llmFailure ;

        /** Model of the latest attempt, and the attempt's number. */
        String model ;
        int    attempts ;

        /** The undecided tags the user has turned down. */
        final Set<String> rejected = new HashSet<>() ;

        /** True while a request is in flight for it: its Tags cell is framed. */
        boolean inFlight ;

        /**
         * What is going on or went wrong, for the detail area: why the
         * regression didn't settle it, a server error, or the decision.
         */
        String message ;

        /**
         * The tags to save, staged by the user, until the server has taken
         * them (kept if it refused them); null if none.
         */
        TagDecision decision ;

        /**
         * The set {@link #decision} started from, as chosen (the regression's
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
         * The chosen set: the decision, else the regression's tags while it
         * stands on them; null if nothing is chosen.
         */
        TagDecision chosen() {

            if( decision != null ) return decision ;
            return status.isAccepted()
                   || status == TaggerStatus.PARTIALLY_UNDECIDED
                   ? proposedSet() : null ;
        }

        /**
         * The tags the regression couldn't settle, which the user is to
         * resolve: those scored between their thresholds, and the
         * {@code llm-only} ones whose pattern matched.
         */
        List<String> undecided() {

            Decision d = assessment == null ? null : assessment.decision() ;
            if( d == null ) return List.of() ;

            List<String> tags = new ArrayList<>( d.undecided() ) ;
            tags.addAll( d.triggered() ) ;
            return tags ;
        }

        /** The undecided tags neither chosen nor turned down. */
        List<String> unresolved() {

            TagDecision  d      = chosen() ;
            List<String> chosen = d == null ? List.of() : d.tags() ;

            return undecided().stream()
                              .filter( t -> !chosen.contains( t )
                                            && !rejected.contains( t ) )
                              .toList() ;
        }

        /** What the user settling on {@code d} says: the rest are turned down. */
        TagDecision settleOn( TagDecision d ) {

            for( String t : undecided() ) {
                if( !d.tags().contains( t ) ) rejected.add( t ) ;
            }
            return d.asHuman() ;
        }

        /**
         * The set key 1 chooses: while it is partly undecided the tags chosen
         * so far, else the proposed ones.
         */
        TagDecision proposedChoice() {

            return status == TaggerStatus.PARTIALLY_UNDECIDED
                   && chosen() != null ? chosen() : proposedSet() ;
        }

        /**
         * The set to save if the proposal is taken: the proposed tags and
         * those already on the question, which stay unless taken out; null
         * with no proposal.
         */
        TagDecision proposedSet() {

            TagDecision p = proposed() ;
            return p == null ? null : p.withExisting( existing ) ;
        }

        /** The set the user's edits start from with nothing chosen yet. */
        TagDecision startingSet() {
            return existing.isEmpty() ? TagDecision.empty()
                                      : TagDecision.existing( existing ) ;
        }

        /** True if the LLM says it is unsure of the tags it gave. */
        boolean isLowConfidence() {
            return proposal != null && proposal.reply().isLowConfidence() ;
        }

        /**
         * The set the regression accepted; null if it hasn't been scored.
         */
        TagDecision proposed() {
            return assessment == null || assessment.decision() == null ? null
                   : TagDecision.regression( assessment.decision().accepted() ) ;
        }

        /**
         * True if Start asks the LLM about it: the regression didn't settle
         * it, and the LLM hasn't given an opinion, or been asked.
         */
        boolean needsLlm() {
            return canAskLlm() && llm == Llm.IDLE && proposal == null
                   && decision == null && assessment.needsLlm() ;
        }

        /** True if the LLM may be asked about it now. */
        boolean canAskLlm() {
            return item.isSendable() && status.canAskLlm() && llm == Llm.IDLE
                   && assessment != null && assessment.failure() == null ;
        }

        /**
         * True if the user may decide on it: it has been read, it isn't being
         * saved, and it isn't saved.
         */
        boolean isOpen() {
            return status.isDecidable() ;
        }

        /** True if key 1 has tags to choose for it. */
        boolean canUseProposal() {
            return isOpen() && proposed() != null
                   && !proposedChoice().tags().isEmpty() ;
        }

        /** True if it has existing tags to keep. */
        boolean canKeepExisting() {
            return isOpen() && !existing.isEmpty() ;
        }

        /**
         * True if Save sends it: the regression's tags (decided as the save
         * starts), or a decision not saved yet with nothing left undecided.
         */
        boolean isSavable() {
            return status.isAccepted() || isReady() ;
        }

        /** True if it has a decision to send, with nothing left undecided. */
        boolean isReady() {
            return isUnsaved()
                   && status != TaggerStatus.PARTIALLY_UNDECIDED ;
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
     * "[R] a, b": the chosen set, marked with where it started from
     * ({@link #sourceMark}), followed by "? c, d" for the tags still undecided.
     */
    private static String tagsText( Row row ) {

        TagDecision  d    = row.chosen() ;
        List<String> open = row.unresolved() ;
        String       more = open.isEmpty() ? ""
                            : "? " + String.join( ", ", open ) ;

        if( d != null ) {
            if( d.tags().isEmpty() && !more.isEmpty() ) return more ;
            return "[" + sourceMark( d ) + "] " +
                   ( d.tags().isEmpty() ? "no tags"
                                        : String.join( ", ", d.tags() ) ) +
                   ( more.isEmpty() ? "" : "  " + more ) ;
        }
        return row.status == TaggerStatus.ESCALATED ? "(no tag fits)" : more ;
    }

    /**
     * "R" (the regression's), "E" (the existing tags) or
     * "U" (the user's own set), with a "*" once edited.
     */
    static String sourceMark( TagDecision d ) {

        String s = switch( d.source() ) {
            case REGRESSION -> "R" ;
            case EXISTING   -> "E" ;
            case USER       -> "U" ;
        } ;
        return d.edited() && d.source() != TagDecision.Source.USER ? s + "*"
                                                                    : s ;
    }

    /** True if {@code row}'s Tags cell shows tags not settled on yet. */
    static boolean showsProposal( Row row ) {
        return row.status == TaggerStatus.PARTIALLY_UNDECIDED
               || row.status == TaggerStatus.ESCALATED ;
    }
}
