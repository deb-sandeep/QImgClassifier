package com.sandy.sconsole.qimgclassifier.ui;

import com.sandy.sconsole.qimgclassifier.model.AIAgent;
import com.sandy.sconsole.qimgclassifier.model.QTask;
import com.sandy.sconsole.qimgclassifier.model.TaskStatus;
import com.sandy.sconsole.qimgclassifier.util.FileHelper;

import lombok.AccessLevel;
import lombok.Getter;

import javax.swing.table.AbstractTableModel;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Tree-shaped table model backing {@code QClassifierFrame}: sources at the top
 * level, their questions beneath, and - for multi-part questions - the part
 * images beneath the question (collapsed by default). Sources start collapsed;
 * a source expands when one of its questions starts running and collapses again
 * once none of its questions is QUEUED or RUNNING. The tree is flattened
 * into the list of currently visible rows; filtering and sorting apply to
 * questions within each source.
 * <p>
 * Every method must be called on the Swing event dispatch thread. Structural
 * changes ({@link #discovered}, {@link #setFilter}, {@link #setSort},
 * {@link #setAllSourcesExpanded}) only take effect on the next
 * {@link #rebuild()}.
 */
@SuppressWarnings( "serial" )
public class QTaskTreeTableModel extends AbstractTableModel {

    static final int COL_NAME     = 0 ;
    static final int COL_SUBJ     = 1 ;
    static final int COL_STATUS   = 2 ;
    static final int COL_AGENT    = 3 ;
    static final int COL_ATTEMPTS = 4 ;
    static final int COL_TIME     = 5 ;
    static final int COL_RESULT   = 6 ;

    /** Holds the manual topic association button; no value of its own. */
    static final int COL_ASSOC = 7 ;

    private static final String[] COLS =
            { "Source / Question", "Subj", "Status", "AI", "Attempts", "Time",
              "Result", "" } ;

    // ---- nodes -------------------------------------------------------

    /**
     * A row of the tree. The frame reads (and the model writes) the fields
     * directly; EDT only.
     */
    abstract static class Node {

        /** Row index in the flattened view, -1 when not visible. */
        int     viewIndex = -1 ;
        boolean expanded ;

        /** Tree level: 0 source, 1 question, 2 part. */
        abstract int    depth() ;

        /** Text shown in the name column. */
        abstract String label() ;
        boolean isExpandable() { return false ; }
    }

    /** A source folder; its children are its questions in discovery order. */
    static final class SourceNode extends Node {
        final String name ;
        final List<QuestionNode> children = new ArrayList<>() ;

        SourceNode( String name ) {
            this.name = name ;
            this.expanded = false ;   // expanded only while it is being processed
        }

        @Override int     depth()        { return 0 ; }
        @Override String  label()        { return name ; }
        @Override boolean isExpandable() { return true ; }

        /**
         * True once no question is waiting or running (DONE / FAILED /
         * ASSOCIATED only).
         */
        boolean isFullyProcessed() {
            return children.stream().allMatch( q -> q.status.isSettled() ) ;
        }

        /** Number of child questions per status (every status present). */
        Map<TaskStatus,Integer> counts() {
            return countByStatus( children ) ;
        }

        /**
         * Most "active" status among the children: RUNNING > REQUEUED > QUEUED
         * > FAILED > DONE > ASSOCIATED.
         */
        TaskStatus aggregateStatus() {

            Map<TaskStatus,Integer> c = counts() ;
            TaskStatus[] byActivity = { TaskStatus.RUNNING, TaskStatus.REQUEUED,
                                        TaskStatus.QUEUED, TaskStatus.FAILED,
                                        TaskStatus.DONE } ;

            for( TaskStatus s : byActivity ) {
                if( c.get( s ) > 0 ) return s ;
            }
            return TaskStatus.ASSOCIATED ;
        }

        /**
         * Result-column text: question count and the non-zero per-status
         * counts.
         */
        String summary() {

            Map<TaskStatus,Integer> c = counts() ;
            StringBuilder sb = new StringBuilder()
                    .append( children.size() ).append( " questions" ) ;

            c.forEach( ( s, n ) -> {
                if( n > 0 ) sb.append( "  ·  " ).append( s ).append( ' ' ).append( n ) ;
            } ) ;
            return sb.toString() ;
        }
    }

    /** A question; a multi-part question has its part images as children. */
    static final class QuestionNode extends Node {
        final SourceNode     parent ;
        final QTask          task ;
        final List<PartNode> parts = new ArrayList<>() ;
        TaskStatus status ;
        int        attempts ;
        long       startedAtMs ;
        long       finishedAtMs ;
        String     result = "" ;

        /** Backend of the latest attempt in this session; null if none. */
        AIAgent agent ;

        /** Model of the latest attempt; null if none. */
        String agentModel ;

        QuestionNode( SourceNode parent, QTask task, TaskStatus status ) {

            this.parent = parent ;
            this.task   = task ;
            this.status = status ;

            if( task.isMultiPart() ) {
                for( File f : task.getImgFiles() ) {
                    parts.add( new PartNode( this, f ) ) ;
                }
            }
        }

        @Override int     depth()        { return 1 ; }
        @Override boolean isExpandable() { return !parts.isEmpty() ; }
        @Override String  label() {
            return parts.isEmpty() ? task.getQuestionId()
                                   : task.getQuestionId() +
                                     "   [" + parts.size() + " parts]" ;
        }

        /**
         * Seconds spent running (still counting while RUNNING); -1 if never
         * started.
         */
        double elapsedSeconds() {
            if( startedAtMs == 0 ) return -1 ;
            long end = finishedAtMs > 0 ? finishedAtMs : System.currentTimeMillis() ;
            return ( end - startedAtMs ) / 1000.0 ;
        }
    }

    /** One part image of a multi-part question. */
    static final class PartNode extends Node {
        final QuestionNode parent ;
        final File         file ;

        PartNode( QuestionNode parent, File file ) {
            this.parent = parent ;
            this.file   = file ;
        }

        @Override int    depth() { return 2 ; }
        @Override String label() { return FileHelper.extractQuestionId( file ) ; }
    }

    // ---- state -------------------------------------------------------

    private final List<SourceNode>        sources     = new ArrayList<>() ;
    private final Map<String,SourceNode>  sourceByDir = new HashMap<>() ;
    private final Map<QTask,QuestionNode> byTask      = new HashMap<>() ;
    private final List<Node>              visible     = new ArrayList<>() ;

    /** Questions passing the filter at the last {@link #rebuild()}. */
    @Getter( AccessLevel.PACKAGE )
    private int matchingQuestionCount ;

    private Predicate<QuestionNode> filter ; // null = everything passes

    /** Model column the questions are sorted by; -1 for discovery order. */
    @Getter( AccessLevel.PACKAGE )
    private int     sortColumn = -1 ;

    @Getter( AccessLevel.PACKAGE )
    private boolean sortAscending = true ;

    // ---- task lifecycle --------------------------------------------

    /**
     * Add a newly discovered question (ignored if already known). Takes effect
     * on the next {@link #rebuild()}.
     *
     * @param storedResult summary of its stored classification (row starts
     *         ASSOCIATED), or null (row starts QUEUED)
     */
    public void discovered( QTask task, String storedResult ) {

        if( byTask.containsKey( task ) ) return ;
        String     dirKey = task.getSrcDir().getAbsolutePath() ;
        SourceNode src    = sourceByDir.computeIfAbsent( dirKey, k -> {
            SourceNode s = new SourceNode( task.getSourceName() ) ;
            sources.add( s ) ;
            return s ;
        } ) ;
        QuestionNode q = new QuestionNode( src, task,
                storedResult != null ? TaskStatus.ASSOCIATED : TaskStatus.QUEUED ) ;
        if( storedResult != null ) q.result = storedResult ;
        src.children.add( q ) ;
        byTask.put( task, q ) ;
    }

    /**
     * The task was submitted for reprocessing; it waits as REQUEUED until a
     * worker picks it up.
     */
    public void requeued( QTask task ) {

        QuestionNode q = byTask.get( task ) ;
        if( q == null ) return ;
        q.status       = TaskStatus.REQUEUED ;
        q.attempts     = 0 ;
        q.startedAtMs  = 0 ;
        q.finishedAtMs = 0 ;
        q.result       = "" ;

        clearAgent( q ) ;
        fireQuestionUpdated( q ) ;
    }

    /**
     * A worker picked the task up: RUNNING, timer started, its source expanded.
     */
    public void started( QTask task ) {

        QuestionNode q = byTask.get( task ) ;
        if( q == null ) return ;
        q.status       = TaskStatus.RUNNING ;
        q.startedAtMs  = System.currentTimeMillis() ;
        q.finishedAtMs = 0 ;
        q.result       = "" ;

        clearAgent( q ) ;
        setExpanded( q.parent, true ) ;   // show the source being processed
        fireQuestionUpdated( q ) ;
    }

    /**
     * An attempt of the running task went to {@code agent}; the AI and
     * Attempts columns follow it, including a retry on the other backend.
     */
    public void attempting( QTask task, AIAgent agent, String model,
                            int attempt ) {

        QuestionNode q = byTask.get( task ) ;
        if( q == null ) return ;
        q.agent      = agent ;
        q.agentModel = model ;
        q.attempts   = attempt ;

        fireQuestionUpdated( q ) ;
    }

    /** The task finished; its source collapses once nothing in it is active. */
    public void finished( QTask task, TaskStatus status, int attempts,
                          String result ) {

        QuestionNode q = byTask.get( task ) ;
        if( q == null ) return ;
        q.status       = status ;
        q.attempts     = attempts ;
        q.finishedAtMs = System.currentTimeMillis() ;
        q.result       = result != null ? result : "" ;

        if( q.parent.isFullyProcessed() ) setExpanded( q.parent, false ) ;
        fireQuestionUpdated( q ) ;
    }

    /**
     * A topic was hand-picked for the task; it is shown as ASSOCIATED with
     * {@code result}.
     */
    public void manuallyAssociated( QTask task, String result ) {

        QuestionNode q = byTask.get( task ) ;
        if( q == null ) return ;
        q.status       = TaskStatus.ASSOCIATED ;
        q.attempts     = 0 ;
        q.startedAtMs  = 0 ;
        q.finishedAtMs = 0 ;
        q.result       = result ;

        clearAgent( q ) ;
        if( q.parent.isFullyProcessed() ) setExpanded( q.parent, false ) ;
        fireQuestionUpdated( q ) ;
    }

    private static void clearAgent( QuestionNode q ) {
        q.agent      = null ;
        q.agentModel = null ;
    }

    /** Repaint the elapsed-time cell of visible questions still running. */
    public void tickRunningRows() {
        for( QuestionNode q : byTask.values() ) {
            if( q.status == TaskStatus.RUNNING && q.viewIndex >= 0 ) {
                fireTableCellUpdated( q.viewIndex, COL_TIME ) ;
            }
        }
    }

    /**
     * Repaint the question's row, its source's row (aggregate status) and its
     * part rows.
     */
    private void fireQuestionUpdated( QuestionNode q ) {
        fireNodeUpdated( q ) ;
        fireNodeUpdated( q.parent ) ;
        q.parts.forEach( this::fireNodeUpdated ) ;
    }

    private void fireNodeUpdated( Node n ) {
        if( n.viewIndex >= 0 ) fireTableRowsUpdated( n.viewIndex, n.viewIndex ) ;
    }

    // ---- filter / sort / expansion ---------------------------------

    /**
     * Questions failing {@code filter} are hidden (null shows all). Takes
     * effect on {@link #rebuild()}.
     */
    void setFilter( Predicate<QuestionNode> filter ) { this.filter = filter ; }

    /**
     * Sort by model column {@code col}, or -1 for discovery order. Takes effect
     * on {@link #rebuild()}.
     */
    void setSort( int col, boolean ascending ) {
        this.sortColumn = col ;
        this.sortAscending = ascending ;
    }


    /**
     * True if a status change can move a row (so the view needs a
     * {@link #rebuild()}).
     */
    boolean isFilteredOrSorted() { return filter != null || sortColumn >= 0 ; }

    /** Expand / collapse every source. Takes effect on {@link #rebuild()}. */
    void setAllSourcesExpanded( boolean expanded ) {
        for( SourceNode s : sources ) s.expanded = expanded ;
    }

    /**
     * Expand / collapse one visible node in place, firing row insert / delete
     * events so the table keeps its selection.
     */
    void setExpanded( Node n, boolean expanded ) {

        if( !n.isExpandable() || n.expanded == expanded ) return ;
        n.expanded = expanded ;
        int idx = n.viewIndex ;

        if( idx < 0 ) return ;

        if( expanded ) {
            List<Node> sub = new ArrayList<>() ;
            appendChildren( n, sub ) ;
            visible.addAll( idx + 1, sub ) ;
            reindexFrom( idx + 1 ) ;
            if( !sub.isEmpty() ) fireTableRowsInserted( idx + 1, idx + sub.size() ) ;
        }
        else {
            int end = idx + 1 ;
            while( end < visible.size() && visible.get( end ).depth() > n.depth() ) {
                end++ ;
            }
            if( end > idx + 1 ) {
                List<Node> removed = visible.subList( idx + 1, end ) ;
                removed.forEach( r -> r.viewIndex = -1 ) ;
                removed.clear() ;
                reindexFrom( idx + 1 ) ;
                fireTableRowsDeleted( idx + 1, end - 1 ) ;
            }
        }
        fireTableRowsUpdated( idx, idx ) ;   // expander glyph
    }

    /**
     * Recompute the flattened view from the tree, applying filter, sort and
     * expansion.
     */
    void rebuild() {

        visible.forEach( n -> n.viewIndex = -1 ) ;
        visible.clear() ;
        matchingQuestionCount = 0 ;

        List<SourceNode> srcs = new ArrayList<>( sources ) ;
        if( sortColumn == COL_NAME || sortColumn == COL_STATUS ) {
            srcs.sort( comparator() ) ;
        }

        for( SourceNode s : srcs ) {
            List<QuestionNode> qs = matchingChildren( s ) ;
            if( filter != null && qs.isEmpty() ) continue ;
            matchingQuestionCount += qs.size() ;
            visible.add( s ) ;
            if( s.expanded ) appendQuestions( qs, visible ) ;
        }
        reindexFrom( 0 ) ;
        fireTableDataChanged() ;
    }

    /** Append the visible descendants of an expanded node. */
    private void appendChildren( Node n, List<Node> out ) {

        if( n instanceof SourceNode s ) {
            appendQuestions( matchingChildren( s ), out ) ;
        }
        else if( n instanceof QuestionNode q ) {
            out.addAll( q.parts ) ;
        }
    }

    private void appendQuestions( List<QuestionNode> qs, List<Node> out ) {
        for( QuestionNode q : qs ) {
            out.add( q ) ;
            if( q.expanded ) out.addAll( q.parts ) ;
        }
    }

    /** The source's questions passing the filter, in the current sort order. */
    private List<QuestionNode> matchingChildren( SourceNode s ) {

        List<QuestionNode> qs = new ArrayList<>() ;
        for( QuestionNode q : s.children ) {
            if( filter == null || filter.test( q ) ) qs.add( q ) ;
        }
        if( sortColumn >= 0 ) qs.sort( comparator() ) ;
        return qs ;
    }

    private void reindexFrom( int from ) {
        for( int i = from ; i < visible.size() ; i++ ) {
            visible.get( i ).viewIndex = i ;
        }
    }

    /**
     * Comparator for the current sort column and direction (sources only sort
     * by name or status).
     */
    private <T extends Node> Comparator<T> comparator() {

        Comparator<T> c = switch( sortColumn ) {
            case COL_NAME     -> QTaskTreeTableModel::compareNames ;
            case COL_STATUS   -> Comparator.comparing(
                                         n -> (TaskStatus) valueAt( n, COL_STATUS ) ) ;
            case COL_ATTEMPTS -> Comparator.comparingInt(
                                         n -> ( (QuestionNode) n ).attempts ) ;
            case COL_TIME     -> Comparator.comparingDouble(
                                         n -> ( (QuestionNode) n ).elapsedSeconds() ) ;
            default           -> Comparator.comparing(
                                         n -> String.valueOf( valueAt( n, sortColumn ) ),
                                         String.CASE_INSENSITIVE_ORDER ) ;
        } ;
        return sortAscending ? c : c.reversed() ;
    }

    /**
     * Sources by natural name order; questions by subject, type, then sequence.
     */
    private static int compareNames( Node a, Node b ) {
        return a instanceof QuestionNode
               ? FileHelper.QUESTION_ORDER.compare( sortName( a ), sortName( b ) )
               : FileHelper.naturalCompare( sortName( a ), sortName( b ) ) ;
    }

    private static String sortName( Node n ) {
        return n instanceof QuestionNode q ? q.task.getQuestionId() : n.label() ;
    }

    // ---- queries ---------------------------------------------------

    /** The node shown at a view row. */
    Node nodeAt( int viewRow ) { return visible.get( viewRow ) ; }

    int getQuestionCount()         { return byTask.size() ; }
    int getSourceCount()           { return sources.size() ; }

    QuestionNode questionFor( QTask task ) { return byTask.get( task ) ; }

    /**
     * Every question, sources and questions in discovery order, ignoring filter
     * / sort / expansion.
     */
    List<QuestionNode> questionsInDiscoveryOrder() {
        List<QuestionNode> qs = new ArrayList<>() ;
        for( SourceNode s : sources ) qs.addAll( s.children ) ;
        return qs ;
    }

    /**
     * Number of questions per status across all sources (every status present).
     */
    public Map<TaskStatus,Integer> statusCounts() {
        return countByStatus( byTask.values() ) ;
    }

    private static Map<TaskStatus,Integer> countByStatus(
                                        Collection<QuestionNode> questions ) {
        Map<TaskStatus,Integer> counts = new EnumMap<>( TaskStatus.class ) ;
        for( TaskStatus s : TaskStatus.values() ) counts.put( s, 0 ) ;
        for( QuestionNode q : questions ) counts.merge( q.status, 1, Integer::sum ) ;
        return counts ;
    }

    /**
     * Text a "contains" filter matches against; the name column matches source
     * or question id.
     */
    String filterText( QuestionNode q, int col ) {
        if( col == COL_NAME ) return q.parent.name + "/" + q.task.getQuestionId() ;
        return String.valueOf( valueAt( q, col ) ) ;
    }

    /** Cell value of a node; also the basis for sorting and value filters. */
    Object valueAt( Node n, int c ) {

        if( n instanceof SourceNode s ) {
            return switch( c ) {
                case COL_NAME   -> s.name ;
                case COL_STATUS -> s.aggregateStatus() ;
                case COL_RESULT -> s.summary() ;
                default         -> "" ;
            } ;
        }
        if( n instanceof PartNode ) {
            return c == COL_NAME ? n.label() : "" ;
        }
        QuestionNode q = (QuestionNode) n ;
        return switch( c ) {
            case COL_NAME     -> q.task.getQuestionId() ;
            case COL_SUBJ     -> q.task.getSubject().getCode() ;
            case COL_STATUS   -> q.status ;
            case COL_AGENT    -> q.agent != null ? q.agent.getLabel() : "" ;
            case COL_ATTEMPTS -> q.attempts > 0 ? String.valueOf( q.attempts ) : "" ;
            case COL_TIME     -> formatElapsed( q.elapsedSeconds() ) ;
            case COL_RESULT   -> q.result ;
            default           -> "" ;
        } ;
    }

    /** {@code mm:ss}; "" for a negative (never started) value. */
    private static String formatElapsed( double seconds ) {
        if( seconds < 0 ) return "" ;
        long secs = (long) seconds ;
        return String.format( "%02d:%02d", secs / 60, secs % 60 ) ;
    }

    // ---- AbstractTableModel ----------------------------------------

    @Override public int     getRowCount()          { return visible.size() ; }
    @Override public int     getColumnCount()       { return COLS.length ; }
    @Override public String  getColumnName( int c ) { return COLS[c] ; }

    @Override
    public boolean isCellEditable( int r, int c ) { return false ; }

    @Override
    public Object getValueAt( int r, int c ) {
        return valueAt( visible.get( r ), c ) ;
    }
}
