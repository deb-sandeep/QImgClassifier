package com.sandy.sconsole.qimgtools.apps.tagger.ui;

import com.sandy.sconsole.qimgtools.apps.tagger.check.AnswerChecker;
import com.sandy.sconsole.qimgtools.apps.tagger.model.Axis;
import com.sandy.sconsole.qimgtools.apps.tagger.model.HandOffReason;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagClass;
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
 * The tagger's table: one row per question loaded, with its key, its tags
 * (in the concept pass after each tier's answer marked against the key: the
 * chosen set, else the tier's proposal) and where it stands. All row state
 * lives here and is touched only on the EDT.
 */
@SuppressWarnings( "serial" )
final class TagTableModel extends AbstractTableModel
        implements HeaderFilterController.Columns<TagTableModel.Row> {

    static final int COL_ID       = 0 ;
    static final int COL_SOURCE   = 1 ;
    static final int COL_QUESTION = 2 ;
    static final int COL_TYPE     = 3 ;
    static final int COL_KEY      = 4 ;
    static final int COL_TAGS     = 5 ;
    static final int COL_STATUS   = 6 ;
    static final int COL_SAVED    = 7 ;

    /** The tiers' names, as the answer strip shows them. */
    static final String TIER1 = "Tier 1" ;
    static final String TIER2 = "Tier 2" ;

    private static final String[] COLUMNS = {
            "Id", "Source", "Question", "Type", "Key", "Tags", "Status", ""
    } ;

    /** One question and everything that has happened to it. */
    static final class Row {

        final TagItem item ;

        /** The hand tags on it the run could replace, as loaded. */
        final List<String> existing ;

        /**
         * True if the tiers answer and are checked against the key (the
         * concept pass); false if they only tag (setup).
         */
        final boolean solved ;

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

        /** True while a request is in flight for it: its Tags cell is framed. */
        boolean inFlight ;

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

        /**
         * The set {@link #decision} started from, as chosen (a tier's tags,
         * the hand tags, or none), before the user's edits; null with no
         * decision.
         */
        TagDecision base ;

        /** The note it was deferred with; null unless deferred. */
        String deferNote ;

        /** Status and message to go back to if the decision is cleared. */
        TaggerStatus undecidedStatus ;
        String       undecidedMessage ;

        Row( TagItem item, List<String> existing, boolean solved ) {
            this.item     = item ;
            this.existing = List.copyOf( existing ) ;
            this.solved   = solved ;
            this.status   = !item.hasImages()         ? TaggerStatus.NO_IMAGES
                          : item.unsupported() != null ? TaggerStatus.UNSUPPORTED
                          :                              TaggerStatus.PENDING ;
            this.message  = item.problem() != null ? item.problem()
                                                   : item.unsupported() ;
        }

        /**
         * The chosen set: the decision, else an accepted tier's tags; null
         * if nothing is chosen.
         */
        TagDecision chosen() {

            if( decision != null ) return decision ;
            return status.isAccepted() && judgement != null
                   && judgement.accepted() != null
                   ? TagDecision.of( judgement.accepted(), false ) : null ;
        }

        /**
         * What breaks the count rules of {@code pass}'s axes in the chosen
         * set, e.g. "concept 1-2, not 0"; null if nothing does, or nothing
         * is chosen.
         */
        String countProblem( TagClass pass ) {

            TagDecision d = chosen() ;
            if( d == null ) return null ;

            List<String> problems = new ArrayList<>() ;
            boolean      dep      = item.dependent() ;
            for( Axis axis : pass.getAxes() ) {
                long n = d.tags().stream()
                          .filter( t -> Axis.of( t ) == axis ).count() ;
                if( n < axis.min( dep ) || n > axis.max( dep ) ) {
                    problems.add( axis.getWord() + " " +
                                  axis.countRule( dep ) + ", not " + n ) ;
                }
            }
            return problems.isEmpty() ? null : String.join( "; ", problems ) ;
        }

        /** Tier {@code tier}'s reply; null if it has given none. */
        TierReply tier( int tier ) {
            return opinions == null ? null : opinions.tier( tier ) ;
        }

        /** True if tier {@code tier}'s answer matches the key. */
        boolean correct( int tier ) {
            TierReply t = tier( tier ) ;
            return solved && t != null && AnswerChecker.matches( item.type(),
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
         * True if it may take tier {@code tier}'s tags: the tier replied,
         * and, where it solves, answered (one that declined has none).
         */
        boolean canUse( int tier ) {
            TierReply t = tier( tier ) ;
            return isOpen() && t != null
                   && !( solved && t.reply().isAbstained() ) ;
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

    TagTableModel( ModelNames names ) {
        this.names = names ;
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
            case COL_QUESTION -> row.questionLabel() +
                                 ( item.dependent() ? "  (dep)" : "" ) ;
            case COL_TYPE     -> item.type() ;
            case COL_KEY      -> item.key() ;
            case COL_TAGS     -> tagsText( row ) ;
            case COL_STATUS   -> statusText( row ) ;
            case COL_SAVED    -> row.status == TaggerStatus.SAVED ;
            default           -> null ;
        } ;
    }

    /**
     * "✗✓  [T2] c.x, m.y": in the concept pass each tier's answer marked
     * against the key (✓ right, ✗ wrong, - no answer); then the chosen set,
     * marked with where it started from ({@link #sourceMark}), else, in
     * parentheses, the proposal of the last tier that replied.
     */
    private static String tagsText( Row row ) {

        StringBuilder sb = new StringBuilder() ;
        if( row.solved && row.opinions != null ) {
            sb.append( mark( row, 1 ) ) ;
            if( row.tier( 2 ) != null || row.opinions.tier2Failure() != null ) {
                sb.append( mark( row, 2 ) ) ;
            }
            sb.append( "  " ) ;
        }

        TagDecision d = row.chosen() ;
        TierReply   p = row.tier( 2 ) != null ? row.tier( 2 ) : row.tier( 1 ) ;
        if( d != null ) {
            sb.append( '[' ).append( sourceMark( d ) ).append( "] " )
              .append( d.tags().isEmpty() ? "no tags"
                                          : String.join( ", ", d.tags() ) ) ;
        }
        else if( p != null && !( row.solved && p.reply().isAbstained() ) ) {
            sb.append( "(T" ).append( p.tier() ).append( ") " )
              .append( String.join( ", ", p.reply().tags() ) ) ;
        }
        return sb.toString().strip() ;
    }

    /** ✓, ✗, or - for no answer: tier {@code tier}'s answer. */
    private static String mark( Row row, int tier ) {

        TierReply t = row.tier( tier ) ;
        if( t == null || t.reply().isAbstained() ) return "-" ;
        return row.correct( tier ) ? "✓" : "✗" ;
    }

    /**
     * "T1", "T2", "E" (the hand tags) or "U" (the user's own set), with a
     * "*" once edited.
     */
    static String sourceMark( TagDecision d ) {

        String s = switch( d.source() ) {
            case TIER1    -> "T1" ;
            case TIER2    -> "T2" ;
            case EXISTING -> "E" ;
            default       -> "U" ;
        } ;
        return d.edited() && d.source() != TagDecision.Source.USER ? s + "*"
                                                                    : s ;
    }

    /** True if {@code row}'s Tags cell shows a proposal, not a chosen set. */
    static boolean showsProposal( Row row ) {
        return row.chosen() == null && row.opinions != null ;
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
