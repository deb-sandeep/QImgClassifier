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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The tagger's table: one row per question loaded, with its tags (the chosen
 * set, and any undecided ones left out), who gave them and where it stands. The
 * tags a question stands on come from {@link Row#automaticSet}: the regression's
 * accepted tags, with the latest LLM reply's. All row state lives here and is
 * touched only on the EDT.
 */
@SuppressWarnings( "serial" )
final class TagTableModel extends AbstractTableModel
        implements HeaderFilterController.Columns<TagTableModel.Row> {

    static final int COL_ID       = 0 ;
    static final int COL_SOURCE   = 1 ;
    static final int COL_QUESTION = 2 ;
    static final int COL_TYPE     = 3 ;
    static final int COL_FROM     = 4 ;
    static final int COL_TAGS     = 5 ;
    static final int COL_STATUS   = 6 ;
    static final int COL_SAVED    = 7 ;

    private static final String[] COLUMNS = {
            "Id", "Source", "Question", "Type", "From", "Tags", "Status", ""
    } ;

    // The From column: who gave the tags. Blank for the regression's alone.
    static final String FROM_PARTIAL = "PAR" ;
    static final String FROM_LLM     = "LLM" ;
    static final String FROM_USER    = "USR" ;

    /** Where a question stands with the LLM. */
    enum Llm { IDLE, QUEUED, ASKING }

    /** The suffix of a model's free tier on OpenRouter. */
    private static final String FREE_TIER = ":free" ;

    /** One question and everything that has happened to it. */
    static final class Row {

        final TagItem item ;

        /** The topic's explicit tags on it, as loaded. */
        final List<String> existing ;

        TaggerStatus status ;

        /** The OCR text and the regression's decision; null until read. */
        Assessment assessment ;

        /** The LLM's latest reply; null until it has replied. */
        TagProposal proposal ;

        /** Where it stands with the LLM. */
        Llm llm = Llm.IDLE ;

        /** Why the LLM gave no usable reply the last time; null if it did. */
        String llmFailure ;

        /** Model of the latest attempt, and the attempt's number. */
        String model ;
        int    attempts ;

        /** The undecided tags the user has turned down. */
        final Set<String> rejected = new HashSet<>() ;

        /** True while a request is in flight for it: its Tags cell is framed. */
        boolean inFlight ;

        /**
         * What is going on or went wrong, for the detail area: why it awaits
         * the LLM, what was dropped, a server error, or the decision.
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

        Row( TagItem item, List<String> existing ) {
            this.item     = item ;
            this.existing = List.copyOf( existing ) ;
            this.status   = item.hasImages() ? TaggerStatus.PENDING
                                             : TaggerStatus.NO_IMAGES ;
            this.message  = item.problem() ;
        }

        /**
         * The chosen set: the decision, else the tags it stands on; null if
         * it stands on none.
         */
        TagDecision chosen() {

            if( decision != null ) return decision ;
            return status.isAccepted() || status == TaggerStatus.AWAITING_LLM
                   ? automaticSet() : null ;
        }

        /**
         * The tags it stands on with nothing decided by the user. The
         * regression's accepted tags, unless they are suspect and the LLM has
         * named tags in their place; the tags of the LLM's latest reply; and
         * those already on the question, which stay unless taken out. Null if
         * it hasn't been scored.
         */
        TagDecision automaticSet() {

            Decision r = assessment == null ? null : assessment.decision() ;
            if( r == null ) return null ;
            if( !hasLlmTags() ) {
                return TagDecision.regression( r.accepted() )
                                  .withExisting( existing ) ;
            }

            Set<String> tags = new LinkedHashSet<>() ;
            if( r.suspect().isEmpty() ) tags.addAll( r.accepted() ) ;
            tags.addAll( proposal.reply().tags() ) ;
            return TagDecision.llm( List.copyOf( tags ) )
                              .withExisting( existing ) ;
        }

        /** True if the LLM's latest reply names tags. */
        boolean hasLlmTags() {
            return proposal != null && !proposal.reply().noTagFits() ;
        }

        /**
         * Put it where its assessment and the LLM's reply leave it, with
         * nothing decided by the user.
         */
        void stand() {

            if( assessment == null ) {
                status  = TaggerStatus.READING ;
                message = null ;
                return ;
            }
            if( assessment.failure() != null ) {
                status  = TaggerStatus.NEEDS_HUMAN ;
                message = assessment.failure() ;
                return ;
            }

            String reason = assessment.decision().llmReason() ;
            if( hasLlmTags() || !assessment.needsLlm() ) {
                List<String> out = unresolvedOf( automaticSet() ) ;

                status  = TaggerStatus.ACCEPTED ;
                message = out.isEmpty() ? null
                          : "Dropped: " + String.join( ", ", out ) ;
            }
            else if( proposal != null ) {
                status  = TaggerStatus.ESCALATED ;
                message = "No tag fits" +
                          ( proposal.reply().framing() != null
                            ? ": " + proposal.reply().framing() : "" ) ;
            }
            else {
                status  = TaggerStatus.AWAITING_LLM ;
                message = reason ;
            }
        }

        /**
         * The From column: USR if the user changed the tags, else LLM if the
         * LLM's reply gave them, PAR if the regression's stand with others
         * dropped, blank if they stand alone.
         */
        String from() {

            TagDecision d = chosen() ;
            if( d == null ) return "" ;
            if( d.edited() || d.source() == TagDecision.Source.USER
                || d.source() == TagDecision.Source.EXISTING ) return FROM_USER ;
            if( d.source() == TagDecision.Source.LLM ) return FROM_LLM ;
            if( assessment != null && assessment.needsLlm() ) {
                // The user took tags the regression wasn't sure of.
                return decision != null ? FROM_USER : "" ;
            }
            return undecided().isEmpty() ? "" : FROM_PARTIAL ;
        }

        /**
         * True if the user has changed the set by hand, which a new reply
         * leaves alone.
         */
        boolean isHandEdited() {
            return decision != null
                   && ( decision.edited()
                        || decision.source() == TagDecision.Source.USER
                        || decision.source() == TagDecision.Source.EXISTING ) ;
        }

        /**
         * The tags the regression left out, with their tick and cross: those
         * scored between their thresholds, and the {@code llm-only} ones
         * whose pattern matched.
         */
        List<String> undecided() {

            Decision d = assessment == null ? null : assessment.decision() ;
            return d == null ? List.of() : d.notAccepted() ;
        }

        /** The undecided tags neither chosen nor turned down. */
        List<String> unresolved() {
            return unresolvedOf( chosen() ) ;
        }

        private List<String> unresolvedOf( TagDecision d ) {

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
         * The set key 1 chooses: with undecided tags still open the tags
         * chosen so far, else the ones it stands on automatically.
         */
        TagDecision proposedChoice() {

            return !unresolved().isEmpty() && chosen() != null
                   ? chosen() : automaticSet() ;
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
         * True if it awaits a first answer from the LLM: it goes to the LLM,
         * which hasn't replied or been asked, and the user hasn't decided.
         */
        boolean needsLlm() {
            return canAskFirst() && decision == null && assessment.needsLlm() ;
        }

        /**
         * True if the LLM may be asked about it without naming a model: no
         * model has answered it. A row answered goes to a model of the user's
         * choice ({@link #answeredBy}).
         */
        boolean canAskFirst() {
            return canAskLlm() && proposal == null ;
        }

        /**
         * True if {@code modelId} gave the latest reply. A model's free and
         * paid tiers are one model.
         */
        boolean answeredBy( String modelId ) {
            return proposal != null && proposal.model() != null
                   && baseModel( proposal.model() ).equals( baseModel( modelId ) ) ;
        }

        private static String baseModel( String id ) {
            return id.endsWith( FREE_TIER )
                   ? id.substring( 0, id.length() - FREE_TIER.length() ) : id ;
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
            return isOpen() && automaticSet() != null
                   && !proposedChoice().tags().isEmpty() ;
        }

        /** True if it has existing tags to keep. */
        boolean canKeepExisting() {
            return isOpen() && !existing.isEmpty() ;
        }

        /** True if Save sends it: its tags stand, or a decision is unsaved. */
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
        return col == COL_SOURCE || col == COL_TYPE || col == COL_FROM
               || col == COL_STATUS ;
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
            case COL_FROM     -> row.from() ;
            case COL_TAGS     -> tagsText( row ) ;
            case COL_STATUS   -> row.status.getLabel() ;
            case COL_SAVED    -> row.status == TaggerStatus.SAVED ;
            default           -> null ;
        } ;
    }

    /**
     * "[E] a | b, c  ? d": the chosen set, those already on the question first
     * and marked, followed by "? d" for the undecided tags left out.
     */
    private static String tagsText( Row row ) {

        TagDecision  d    = row.chosen() ;
        List<String> open = row.unresolved() ;
        String       more = open.isEmpty() ? ""
                            : "? " + String.join( ", ", open ) ;

        if( d == null ) {
            return row.status == TaggerStatus.ESCALATED ? "(no tag fits)" : more ;
        }
        if( d.tags().isEmpty() ) return more.isEmpty() ? "no tags" : more ;

        List<String> old = d.tags().stream()
                            .filter( row.existing::contains ).toList() ;
        List<String> rest = d.tags().stream()
                             .filter( t -> !row.existing.contains( t ) ).toList() ;
        String text = String.join( ", ", rest ) ;

        if( !old.isEmpty() ) {
            text = "[E] " + String.join( ", ", old ) +
                   ( rest.isEmpty() ? "" : " | " + text ) ;
        }
        return more.isEmpty() ? text : text + "  " + more ;
    }

    /** True if {@code row}'s Tags cell shows tags not settled on yet. */
    static boolean showsProposal( Row row ) {
        return row.status == TaggerStatus.AWAITING_LLM
               || row.status == TaggerStatus.ESCALATED ;
    }
}
