package com.sandy.sconsole.qimgtools.apps.tagger.ui;

import com.sandy.sconsole.qimgtools.apps.tagger.check.AnswerChecker;
import com.sandy.sconsole.qimgtools.apps.tagger.model.HandOffReason;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagDecision;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagItem;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagJudgement;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagOpinions;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TaggerStatus;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TierReply;
import com.sandy.sconsole.qimgtools.core.ui.HeaderFilterController;
import com.sandy.sconsole.qimgtools.core.util.ModelNames;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The tagger's table: one row per question loaded, with its key, the hand
 * tags already on it, each tier's answer and tags, and where it stands. All
 * row state lives here and is touched only on the EDT.
 */
@SuppressWarnings( "serial" )
final class TagTableModel extends AbstractTableModel
        implements HeaderFilterController.Columns<TagTableModel.Row> {

    static final int COL_ID       = 0 ;
    static final int COL_SOURCE   = 1 ;
    static final int COL_QUESTION = 2 ;
    static final int COL_TYPE     = 3 ;
    static final int COL_KEY      = 4 ;
    static final int COL_EXISTING = 5 ;
    static final int COL_TIER1    = 6 ;
    static final int COL_TIER2    = 7 ;
    static final int COL_STATUS   = 8 ;
    static final int COL_SAVED    = 9 ;

    /** The tiers' names: their columns' headers, also the panel's. */
    static final String TIER1 = "Tier 1" ;
    static final String TIER2 = "Tier 2" ;

    private static final String[] COLUMNS = {
            "Id", "Source", "Question", "Type", "Key", "Existing", TIER1,
            TIER2, "Status", ""
    } ;

    /** One question and everything that has happened to it. */
    static final class Row {

        final TagItem item ;

        /** The hand tags on it the run could replace, as loaded. */
        final List<String> existing ;

        TaggerStatus status ;

        /** The tiers' replies; null until tier 1 has given one. */
        TagOpinions opinions ;

        /** How the replies were judged; null until they were. */
        TagJudgement judgement ;

        /** Why it was handed to the user; null unless it was. */
        HandOffReason reason ;

        /** Model of the latest attempt, and the attempt's number. */
        String model ;
        int    attempts ;

        /**
         * The column of the tier a request is in flight to
         * ({@link #COL_TIER1} or {@link #COL_TIER2}); -1 while none is. Its
         * cell is framed in red.
         */
        int inFlightColumn = -1 ;

        /**
         * What is going on or went wrong, for the detail area: the attempt
         * under way, a failure, a server error, or the decision.
         */
        String message ;

        /**
         * The tags to save, staged by the models (an accepted tier) or the
         * user, until the server has taken them (kept if it refused them);
         * null if none.
         */
        TagDecision decision ;

        /** The note it was deferred with; null unless deferred. */
        String deferNote ;

        /** Status and message to go back to if the decision is cleared. */
        TaggerStatus undecidedStatus ;
        String       undecidedMessage ;

        Row( TagItem item, List<String> existing ) {
            this.item     = item ;
            this.existing = List.copyOf( existing ) ;
            this.status   = !item.hasImages()         ? TaggerStatus.NO_IMAGES
                          : item.unsupported() != null ? TaggerStatus.UNSUPPORTED
                          :                              TaggerStatus.PENDING ;
            this.message  = item.problem() != null ? item.problem()
                                                   : item.unsupported() ;
        }

        /** Tier {@code tier}'s reply; null if it has given none. */
        TierReply tier( int tier ) {
            return opinions == null ? null : opinions.tier( tier ) ;
        }

        /** True if tier {@code tier}'s answer matches the key. */
        boolean correct( int tier ) {
            TierReply t = tier( tier ) ;
            return t != null && AnswerChecker.matches( item.type(),
                                                       item.key(),
                                                       t.answer() ) ;
        }

        /**
         * True if the user may decide on it: the models are done with it,
         * it isn't being saved, and it isn't saved.
         */
        boolean isOpen() {
            return status.isDecidable() ;
        }

        /**
         * True if it may take tier {@code tier}'s tags: the tier answered
         * (one that declined has none).
         */
        boolean canUse( int tier ) {
            TierReply t = tier( tier ) ;
            return isOpen() && t != null && !t.reply().isAbstained() ;
        }

        /** True if it has hand tags to keep. */
        boolean canKeepExisting() {
            return isOpen() && !existing.isEmpty() ;
        }

        /**
         * True if Save sends it: an accepted tier's tags (decided as the
         * save starts), or a decision not saved yet.
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

    private final ModelNames       names ;
    private final List<Row>        rows = new ArrayList<>() ;
    private final Map<Integer,Row> byId = new HashMap<>() ;

    /** The column names, the tiers' with their models. */
    private final String[] columns = COLUMNS.clone() ;

    /**
     * @param tier1 tier 1's first model, named in its column's title; null
     *              for none
     * @param tier2 tier 2's, likewise
     */
    TagTableModel( ModelNames names, String tier1, String tier2 ) {

        this.names = names ;
        columns[COL_TIER1] = title( COL_TIER1, tier1, names ) ;
        columns[COL_TIER2] = title( COL_TIER2, tier2, names ) ;
    }

    /**
     * "Tier 1 (Nemotron)": column {@code col}'s title with the first word
     * of {@code model}'s display name; the plain title without a model.
     */
    private static String title( int col, String model, ModelNames names ) {

        if( model == null ) return COLUMNS[col] ;
        String name = names.of( model ).strip() ;
        int    sp   = name.indexOf( ' ' ) ;

        return COLUMNS[col] + " (" + ( sp < 0 ? name : name.substring( 0, sp ) )
               + ")" ;
    }

    /** Replace the rows with {@code rows}. */
    void setRows( List<Row> rows ) {

        this.rows.clear() ;
        byId.clear() ;
        rows.forEach( this::index ) ;
        fireTableDataChanged() ;
    }

    /** Add {@code rows} after those there; the selection stays. */
    void addRows( List<Row> rows ) {

        int first = this.rows.size() ;

        rows.forEach( this::index ) ;
        if( this.rows.size() > first ) {
            fireTableRowsInserted( first, this.rows.size() - 1 ) ;
        }
    }

    private void index( Row row ) {
        rows.add( row ) ;
        byId.put( row.item.id(), row ) ;
    }

    /**
     * Drop the rows {@code test} accepts; the selection of the others
     * stays.
     *
     * @return how many were dropped
     */
    int removeIf( Predicate<Row> test ) {

        int removed = 0 ;

        for( int i = rows.size() - 1 ; i >= 0 ; i-- ) {
            Row row = rows.get( i ) ;
            if( !test.test( row ) ) continue ;
            rows.remove( i ) ;
            byId.remove( row.item.id() ) ;
            fireTableRowsDeleted( i, i ) ;
            removed++ ;
        }
        return removed ;
    }

    /** True if question {@code id} has a row. */
    boolean has( int id ) {
        return byId.containsKey( id ) ;
    }

    /** Every row, in load order. */
    @Override
    public List<Row> rows() {
        return Collections.unmodifiableList( rows ) ;
    }

    @Override
    public String cellText( Row row, int col ) {

        // The status filter offers the plain labels.
        if( col == COL_STATUS ) return row.status.getLabel() ;
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

    /** Rows with tags the user staged that aren't saved. */
    long unsavedCount() {
        return rows.stream().filter( Row::isUnsaved ).count() ;
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
        return columns.length ;
    }

    @Override
    public String getColumnName( int col ) {
        return columns[col] ;
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
            case COL_QUESTION -> row.questionLabel() +
                                 ( item.dependent() ? "  (dep)" : "" ) ;
            case COL_TYPE     -> item.type() ;
            case COL_KEY      -> item.key() ;
            case COL_EXISTING -> String.join( ", ", row.existing ) ;
            case COL_TIER1    -> tierText( row, 1 ) ;
            case COL_TIER2    -> tierText( row, 2 ) ;
            case COL_STATUS   -> statusText( row ) ;
            case COL_SAVED    -> row.status == TaggerStatus.SAVED ;
            default           -> null ;
        } ;
    }

    /**
     * "✓ C   s.x, c.y": tier {@code tier}'s answer marked against the key,
     * and its tags; tier 2's failure if asked and it gave none.
     */
    private static String tierText( Row row, int tier ) {

        TierReply t = row.tier( tier ) ;
        if( t == null ) {
            return tier == 2 && row.opinions != null
                   && row.opinions.tier2Failure() != null ? "-" : "" ;
        }
        return ( row.correct( tier ) ? "✓ " : "✗ " ) +
               t.reply().answerText() + "   " +
               String.join( ", ", t.reply().tags() ) ;
    }

    /** The status, with the hand-off reason. */
    static String statusText( Row row ) {
        return row.status == TaggerStatus.NEEDS_HUMAN && row.reason != null
               ? row.status.getLabel() + " (" + row.reason + ")"
               : row.status.getLabel() ;
    }

    /** The names models are shown by. */
    ModelNames names() {
        return names ;
    }
}
