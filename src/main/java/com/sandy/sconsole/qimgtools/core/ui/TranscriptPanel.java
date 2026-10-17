package com.sandy.sconsole.qimgtools.core.ui;

import com.sandy.sconsole.qimgtools.core.model.AttemptRequest;
import com.sandy.sconsole.qimgtools.core.model.QTask;
import com.sandy.sconsole.qimgtools.core.util.ModelNames;
import com.sandy.sconsole.qimgtools.core.workspace.TranscriptStore;

import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Log window docked to the right of the main window (modelled on
 * QImgExtractor's question sync log panel): the AI attempts of one question
 * as a tree above a text area.
 * <pre>
 * C_SCA_37                                     (the question)
 *   2026-10-16                                 (a day)
 *     14:28:51  a1  openrouter  nemotron  FAILED   (an attempt)
 *       Response
 *       Request
 * </pre>
 * The text area shows the selected node: a file for a leaf, and for a parent
 * the text of all its children, one after another. The prompt inside a
 * request is hidden unless "Show prompt" is ticked.
 * <p>
 * Finished attempts are read from their transcript files, off the EDT; a
 * newer {@link #show} supersedes a slow load. A request still waiting for its
 * reply is held in memory ({@link #requestSent}) and shown live as the last
 * attempt, until {@link #attemptFinished} swaps it for its saved files. The
 * latest attempt is selected when a question is shown, and again when a new
 * attempt arrives while it (or nothing) was selected; any other selection is
 * kept.
 */
@Slf4j
@SuppressWarnings( "serial" )
public final class TranscriptPanel extends JPanel {

    private static final String PROMPT =
            "Select a question to see its AI transcripts." ;

    /** Initial height of the tree, in pixels. */
    private static final int TREE_HEIGHT = 200 ;

    private final TranscriptStore store ;
    private final ModelNames      names ;
    private final JLabel          title   = new JLabel( PROMPT ) ;
    private final JTextArea       text    = new JTextArea() ;
    private final JButton         openDir = new JButton( "Open folder" ) ;
    private final JCheckBox       prompts = new JCheckBox( "Show prompt" ) ;

    private final DefaultMutableTreeNode root  = new DefaultMutableTreeNode() ;
    private final DefaultTreeModel       model = new DefaultTreeModel( root ) ;
    private final JTree                  tree  = new JTree( model ) ;

    /** Set while the tree is rebuilt, so selection events are ignored. */
    private boolean rebuilding ;

    /** The question shown; null when the panel is empty. */
    private QTask task ;

    /** What was last read for {@link #task}; null until loaded. */
    private Transcript shown ;

    /** True until the tree of a newly shown question is first built. */
    private boolean fresh ;

    /**
     * Requests sent and not yet answered, for every question (at most one per
     * question at a time). EDT only.
     */
    private final Map<QTask,AttemptRequest> live = new HashMap<>() ;

    /**
     * Ticks the waiting time in the title and the live attempt's node while
     * {@link #task} has one.
     */
    private final Timer waitTimer = new Timer( 1000, e -> tick() ) ;

    /**
     * Monotonic token so a slow load for an old question cannot overwrite a
     * newer one.
     */
    private long loadToken ;

    /**
     * @param store where the transcripts are read from
     * @param names the names models are shown by
     */
    public TranscriptPanel( TranscriptStore store, ModelNames names ) {

        super( new BorderLayout() ) ;
        this.store = store ;
        this.names = names ;

        text.setEditable( false ) ;
        text.setLineWrap( true ) ;
        text.setWrapStyleWord( true ) ;
        text.setFont( new Font( Font.MONOSPACED, Font.PLAIN, 12 ) ) ;

        JButton clear = new JButton( "Clear" ) ;
        clear.setToolTipText( "Clear the panel (the files are kept)" ) ;
        clear.addActionListener( e -> clear() ) ;
        openDir.setToolTipText( "Open the question's transcript folder" ) ;
        openDir.addActionListener( e -> openFolder() ) ;
        openDir.setEnabled( false ) ;
        prompts.setToolTipText( "Include the prompt sent with each request" ) ;
        prompts.addActionListener( e -> showSelected( false ) ) ;

        JPanel buttons = new JPanel( new FlowLayout( FlowLayout.LEFT ) ) ;
        buttons.add( openDir ) ;
        buttons.add( clear ) ;
        buttons.add( prompts ) ;

        JPanel top = new JPanel( new BorderLayout() ) ;
        title.setBorder( BorderFactory.createEmptyBorder( 0, 6, 0, 0 ) ) ;
        top.add( buttons, BorderLayout.WEST ) ;
        top.add( title, BorderLayout.CENTER ) ;

        tree.setRootVisible( false ) ;   // until a question is shown
        tree.setShowsRootHandles( true ) ;
        tree.getSelectionModel()
            .setSelectionMode( TreeSelectionModel.SINGLE_TREE_SELECTION ) ;
        tree.addTreeSelectionListener( e -> {
            if( !rebuilding ) showSelected( true ) ;
        } ) ;

        // The tree above, the selected node's text below; the divider can be
        // dragged.
        JSplitPane split = new JSplitPane( JSplitPane.VERTICAL_SPLIT,
                                           new JScrollPane( tree ),
                                           new JScrollPane( text ) ) ;
        split.setDividerLocation( TREE_HEIGHT ) ;
        split.setResizeWeight( 0 ) ;

        add( top, BorderLayout.NORTH ) ;
        add( split, BorderLayout.CENTER ) ;
        setPreferredSize( new Dimension( 700, 200 ) ) ;
    }

    /** Show the transcripts of {@code task}. Call on the EDT. */
    public void show( QTask task ) {

        this.task  = task ;
        this.shown = null ;
        this.fresh = true ;

        openDir.setEnabled( true ) ;
        title.setText( task + " - loading…" ) ;
        load( task ) ;
    }

    /**
     * Show the transcripts of {@code task} unless it is on show already (they
     * are kept up to date as attempts finish). Call on the EDT.
     */
    public void showIfOther( QTask task ) {
        if( task != this.task ) show( task ) ;
    }

    /**
     * A request went out and waits for its reply; shown at once if its
     * question is on show. Call on the EDT.
     */
    public void requestSent( AttemptRequest request ) {

        live.put( request.getTask(), request ) ;
        if( request.getTask() == task ) redisplay() ;
    }

    /**
     * An attempt of {@code task} ended and its files are saved: drop the live
     * request and, if the question is on show, reload it from disk. Call on
     * the EDT.
     */
    public void attemptFinished( QTask task ) {

        live.remove( task ) ;
        if( task == this.task ) load( task ) ;
    }

    private void clear() {

        task  = null ;
        shown = null ;

        loadToken++ ;
        waitTimer.stop() ;
        openDir.setEnabled( false ) ;
        title.setText( PROMPT ) ;
        rebuilding = true ;
        root.removeAllChildren() ;
        root.setUserObject( null ) ;
        tree.setRootVisible( false ) ;
        model.reload() ;
        rebuilding = false ;
        text.setText( "" ) ;
    }

    /** Read and join the question's files off the EDT, then show them. */
    private void load( QTask forTask ) {

        long token = ++loadToken ;
        new SwingWorker<Transcript,Void>() {
            @Override protected Transcript doInBackground() throws Exception {
                return read( forTask ) ;
            }

            @Override protected void done() {

                if( token != loadToken ) return ;   // superseded
                try {
                    display( forTask, get() ) ;
                }
                catch( Exception e ) {
                    log.error( "Could not read transcripts of {}", forTask, e ) ;
                    title.setText( forTask + " - could not read transcripts" ) ;
                    text.setText( String.valueOf( e.getMessage() ) ) ;
                }
            }
        }.execute() ;
    }

    /** One attempt's request and response file; either may be missing. */
    private record Attempt( String stem, String request, String response ) {}

    /** A question's attempts, oldest first. */
    private record Transcript( List<Attempt> attempts ) {}

    private Transcript read( QTask forTask ) throws Exception {

        Map<String,String[]> byStem = new LinkedHashMap<>() ;
        for( File f : store.files( forTask ) ) {
            String  name = f.getName() ;
            boolean req  = name.endsWith( TranscriptStore.REQUEST ) ;
            String  stem = name.substring( 0, name.length() - ( req
                                    ? TranscriptStore.REQUEST.length()
                                    : TranscriptStore.RESPONSE.length() ) ) ;
            String  body = Files.readString( f.toPath(), StandardCharsets.UTF_8 ) ;

            byStem.computeIfAbsent( stem, k -> new String[2] )[req ? 0 : 1] = body ;
        }

        List<Attempt> attempts = new ArrayList<>() ;
        byStem.forEach( ( stem, pair ) ->
                attempts.add( new Attempt( stem, pair[0], pair[1] ) ) ) ;
        return new Transcript( attempts ) ;
    }

    private void display( QTask forTask, Transcript t ) {
        shown = t ;
        redisplay() ;
    }

    /** What a tree node stands for. */
    private enum Kind { QUESTION, DAY, ATTEMPT, RESPONSE, REQUEST }

    /**
     * A tree node's content: {@code key} identifies it across rebuilds,
     * {@code body} is a leaf's file text (null for parents).
     */
    private record Entry( Kind kind, String key, String label, String body ) {
        @Override public String toString() { return label ; }
    }

    /**
     * Rebuild the tree from {@link #shown} and the live request, keeping the
     * selection where it still exists, or moving it to the latest attempt.
     */
    private void redisplay() {

        if( shown == null ) return ;   // still loading; done() renders

        List<String> selected   = keyPath( tree.getSelectionPath() ) ;
        String       oldLatest  = latestAttemptKey() ;
        boolean      followLast = fresh || selected.isEmpty()
                                  || ( selected.size() >= 3
                                       && selected.get( 2 ).equals( oldLatest ) ) ;

        rebuilding = true ;
        try {
            buildTree() ;
        }
        finally {
            rebuilding = false ;
        }
        fresh = false ;

        // Following the latest attempt: stay on the selected node while its
        // attempt is still the latest, else move to the new latest attempt.
        boolean  sameLatest = Objects.equals( oldLatest, latestAttemptKey() ) ;
        TreePath path       = !followLast || sameLatest ? pathFor( selected ) : null ;

        if( path == null ) path = latestAttemptPath() ;
        if( path == null ) path = new TreePath( root ) ;

        tree.expandPath( path ) ;
        tree.setSelectionPath( path ) ;
        tree.scrollPathToVisible( path ) ;
        showSelected( !keyPath( path ).equals( selected ) ) ;
        updateTitle() ;
    }

    private void buildTree() {

        AttemptRequest pending  = live.get( task ) ;
        String         liveStem = pending == null ? null
                                                  : TranscriptStore.stem( pending ) ;

        root.removeAllChildren() ;
        root.setUserObject( new Entry( Kind.QUESTION, task.toString(),
                                       task.toString(), null ) ) ;
        tree.setRootVisible( true ) ;

        for( Attempt a : shown.attempts() ) {
            if( a.stem().equals( liveStem ) ) continue ;   // saved meanwhile
            addAttempt( a.stem(), describe( a ), a.response(), a.request() ) ;
        }
        if( pending != null ) {
            String waiting = "(waiting for " + pending.getAgent().getLabel() + " "
                             + names.of( pending.getAiModel() ) + ", sent at "
                             + new SimpleDateFormat( "HH:mm:ss" )
                                       .format( new Date( pending.getStartedAt() ) )
                             + ")\n" ;
            addAttempt( liveStem, liveLabel( pending ), waiting,
                        TranscriptStore.requestText( pending ) ) ;
        }
        model.reload() ;

        // Days stay open so every attempt is in view.
        for( int i = 0 ; i < root.getChildCount() ; i++ ) {
            tree.expandPath( new TreePath( child( root, i ).getPath() ) ) ;
        }
    }

    /** Add an attempt (with its response and request) under its day. */
    private void addAttempt( String stem, String label, String response,
                             String request ) {

        String day = stem.length() >= 8
                   ? stem.substring( 0, 4 ) + "-" + stem.substring( 4, 6 ) + "-"
                     + stem.substring( 6, 8 )
                   : "unknown date" ;

        DefaultMutableTreeNode dayNode = null ;
        for( int i = 0 ; i < root.getChildCount() ; i++ ) {
            DefaultMutableTreeNode n = child( root, i ) ;
            if( entry( n ).key().equals( day ) ) dayNode = n ;
        }
        if( dayNode == null ) {
            dayNode = new DefaultMutableTreeNode(
                    new Entry( Kind.DAY, day, day, null ) ) ;
            root.add( dayNode ) ;
        }

        DefaultMutableTreeNode attempt = new DefaultMutableTreeNode(
                new Entry( Kind.ATTEMPT, stem, label, null ) ) ;
        attempt.add( new DefaultMutableTreeNode( new Entry(
                Kind.RESPONSE, "response", "Response",
                response != null ? response : "(not saved)\n" ) ) ) ;
        attempt.add( new DefaultMutableTreeNode( new Entry(
                Kind.REQUEST, "request", "Request",
                request != null ? request : "(not saved)\n" ) ) ) ;
        dayNode.add( attempt ) ;
    }

    /**
     * An attempt's label: time, attempt number, agent, model and outcome, from
     * its file name and headers.
     */
    private String describe( Attempt a ) {

        String model   = names.of( headerValue( a.request(), "Model" ) ) ;
        String outcome = headerValue( a.response(), "Outcome" ) ;

        if( outcome != null ) {
            int colon = outcome.indexOf( ':' ) ;
            if( colon > 0 ) outcome = outcome.substring( 0, colon ) ;
        }
        return stemLabel( a.stem() )
               + ( model != null ? "  " + model : "" )
               + "  " + ( outcome != null ? outcome : "(no response)" ) ;
    }

    private String liveLabel( AttemptRequest r ) {
        long waited = ( System.currentTimeMillis() - r.getStartedAt() ) / 1000 ;
        return stemLabel( TranscriptStore.stem( r ) ) + "  "
               + names.of( r.getAiModel() ) + "  waiting " + waited + " s" ;
    }

    /**
     * {@code 20261016-142851-356-a1-openrouter} -> {@code 14:28:51  a1
     * openrouter}.
     */
    private static String stemLabel( String stem ) {

        String[] p = stem.split( "-", 5 ) ;
        if( p.length < 5 || p[1].length() < 6 ) return stem ;
        String time = p[1].substring( 0, 2 ) + ":" + p[1].substring( 2, 4 ) + ":"
                      + p[1].substring( 4, 6 ) ;
        return time + "  " + p[3] + "  " + p[4] ;
    }

    /** The value of a {@code Name     : value} header line; null if absent. */
    private static String headerValue( String file, String name ) {

        if( file == null ) return null ;
        for( String line : file.split( "\n" ) ) {
            if( line.equals( TranscriptStore.RULE ) ) break ;
            if( line.startsWith( name ) ) {
                int colon = line.indexOf( ':' ) ;
                if( colon > 0 ) return line.substring( colon + 1 ).trim() ;
            }
        }
        return null ;
    }

    /**
     * Show the selected node's text: a leaf's file, or its children's text
     * one after another.
     *
     * @param toTop scroll to the start (false keeps the position, e.g. when
     *        only the prompt was shown or hidden)
     */
    private void showSelected( boolean toTop ) {

        TreePath path = tree.getSelectionPath() ;
        if( path == null ) {
            text.setText( task == null ? "" : "Nothing saved yet in "
                                              + store.dirFor( task ) ) ;
            return ;
        }

        var node = (DefaultMutableTreeNode) path.getLastPathComponent() ;
        int caret = text.getCaretPosition() ;
        String s  = node.getChildCount() == 0 && entry( node ).kind() == Kind.QUESTION
                  ? "Nothing saved yet in " + store.dirFor( task )
                  : textOf( node ) ;

        text.setText( s ) ;
        text.setCaretPosition( toTop ? 0 : Math.min( caret, s.length() ) ) ;
    }

    /** A leaf's text, or a parent's children's texts each under a heading. */
    private String textOf( DefaultMutableTreeNode node ) {

        Entry e = entry( node ) ;
        if( e.kind() == Kind.REQUEST )  return request( e.body() ) ;
        if( e.kind() == Kind.RESPONSE ) return e.body() ;

        StringBuilder sb = new StringBuilder() ;
        for( int i = 0 ; i < node.getChildCount() ; i++ ) {
            DefaultMutableTreeNode child = child( node, i ) ;
            Entry c = entry( child ) ;

            String rule = switch( c.kind() ) {
                case DAY      -> "#####" ;
                case ATTEMPT  -> "=====" ;
                default       -> "-----" ;
            } ;
            if( i > 0 ) sb.append( '\n' ) ;
            sb.append( rule ).append( ' ' ).append( c.label() ).append( ' ' )
              .append( rule ).append( '\n' )
              .append( textOf( child ) ) ;
        }
        return sb.toString() ;
    }

    private static DefaultMutableTreeNode child( DefaultMutableTreeNode node,
                                                 int i ) {
        return (DefaultMutableTreeNode) node.getChildAt( i ) ;
    }

    private static Entry entry( DefaultMutableTreeNode node ) {
        return (Entry) node.getUserObject() ;
    }

    /** The keys of the entries along {@code path}; empty for none. */
    private static List<String> keyPath( TreePath path ) {

        List<String> keys = new ArrayList<>() ;
        if( path == null ) return keys ;
        for( Object o : path.getPath() ) {
            Object user = ( (DefaultMutableTreeNode) o ).getUserObject() ;
            if( !( user instanceof Entry e ) ) return new ArrayList<>() ;
            keys.add( e.key() ) ;
        }
        return keys ;
    }

    /** The node path with these keys in the current tree; null if gone. */
    private TreePath pathFor( List<String> keys ) {

        if( keys.isEmpty() || root.getUserObject() == null
            || !entry( root ).key().equals( keys.get( 0 ) ) ) {
            return null ;
        }
        DefaultMutableTreeNode node = root ;
        for( String key : keys.subList( 1, keys.size() ) ) {
            DefaultMutableTreeNode next = null ;
            for( int i = 0 ; i < node.getChildCount() && next == null ; i++ ) {
                DefaultMutableTreeNode c = child( node, i ) ;
                if( entry( c ).key().equals( key ) ) next = c ;
            }
            if( next == null ) return null ;
            node = next ;
        }
        return new TreePath( node.getPath() ) ;
    }

    private DefaultMutableTreeNode latestAttempt() {
        if( root.getChildCount() == 0 ) return null ;
        DefaultMutableTreeNode day = (DefaultMutableTreeNode) root.getLastChild() ;
        return day.getChildCount() == 0 ? null
                                        : (DefaultMutableTreeNode) day.getLastChild() ;
    }

    private String latestAttemptKey() {
        DefaultMutableTreeNode n = latestAttempt() ;
        return n == null ? null : entry( n ).key() ;
    }

    private TreePath latestAttemptPath() {
        DefaultMutableTreeNode n = latestAttempt() ;
        return n == null ? null : new TreePath( n.getPath() ) ;
    }

    /** Timer tick: refresh the title and the live attempt's waiting time. */
    private void tick() {

        updateTitle() ;
        AttemptRequest pending = task == null ? null : live.get( task ) ;
        DefaultMutableTreeNode n = latestAttempt() ;

        if( pending == null || n == null ) return ;
        Entry e = entry( n ) ;
        if( !e.key().equals( TranscriptStore.stem( pending ) ) ) return ;
        n.setUserObject( new Entry( e.kind(), e.key(), liveLabel( pending ),
                                    e.body() ) ) ;
        model.nodeChanged( n ) ;
    }

    /**
     * Title: the question, its finished attempts, and how long the live
     * request has been waiting; the timer runs only while there is one.
     */
    private void updateTitle() {

        if( task == null || shown == null ) {
            waitTimer.stop() ;
            return ;
        }

        AttemptRequest pending = live.get( task ) ;
        int            n       = shown.attempts().size() ;
        String         t       = task + " - " + ( n == 0 ? "no transcripts yet"
                                 : n + ( n == 1 ? " attempt" : " attempts" ) ) ;

        if( pending == null ) {
            waitTimer.stop() ;
            title.setText( t ) ;
            return ;
        }
        long waited = ( System.currentTimeMillis() - pending.getStartedAt() ) / 1000 ;
        title.setText( t + ", attempt " + pending.getAttempt() + " waiting "
                       + waited + " s" ) ;
        waitTimer.start() ;
    }

    /** The request file, cut to its header unless prompts are shown. */
    private String request( String file ) {

        int rule = file.indexOf( TranscriptStore.RULE ) ;
        if( prompts.isSelected() || rule < 0 ) return file ;
        return file.substring( 0, rule ) + "(prompt hidden - tick Show prompt)\n" ;
    }

    private void openFolder() {

        if( task == null ) return ;
        File dir = store.dirFor( task ) ;
        try {
            if( !dir.isDirectory() ) {
                JOptionPane.showMessageDialog( this, "No transcripts saved yet:\n" + dir,
                        "Transcripts", JOptionPane.INFORMATION_MESSAGE ) ;
                return ;
            }
            Desktop.getDesktop().open( dir ) ;
        }
        catch( Exception e ) {
            log.error( "Could not open {}", dir, e ) ;
            JOptionPane.showMessageDialog( this, "Could not open " + dir + ":\n" +
                    e.getMessage(), "Transcripts", JOptionPane.ERROR_MESSAGE ) ;
        }
    }
}
