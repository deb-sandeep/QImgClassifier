package com.sandy.sconsole.qimgtools.apps.tagger.ui;

import com.sandy.sconsole.qimgtools.apps.tagger.model.CatalogueTag;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagCatalogue;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.ToolTipManager;
import javax.swing.TransferHandler;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;

/**
 * The scoped topic's tags: the palette the chosen set is filled from, by
 * dragging a badge to the chosen tags bar or clicking it. They form a tree by
 * the dots in their names ({@code lens.combination.block}), sorted
 * alphabetically at each level and open at first, each node showing its own
 * part of the name, with a vertical line beside each level. A node that isn't a tag itself ({@code prism} of
 * {@code prism.deviation}) is greyed out, and can't be clicked or dragged. A
 * tag's full name and hint are its tooltip. The tags in the chosen set of the
 * selection are faded, and a click on a faded badge takes it out again. The
 * tree never takes the focus, so the decision keys stay with the table. Use on
 * the EDT.
 */
@SuppressWarnings( "serial" )
final class TopicTagsPanel extends JPanel {

    private static final int WIDTH      = 270 ;
    private static final int ROW_HEIGHT = 22 ;
    private static final int DRAG_SLOP  = 3 ;

    /** The vertical line beside a level, and how far left of its nodes. */
    private static final Color LEVEL_LINE = new Color( 0xBDBDBD ) ;
    private static final int   LINE_GAP   = 5 ;

    /** The text of a node that isn't a tag. */
    private static final Color NOT_A_TAG_FG = new Color( 0x9E9E9E ) ;

    /** What a node of the tree stands for. */
    private record Node( String name, CatalogueTag tag ) {}

    private final BiConsumer<String,Boolean> toggle ;
    private final JLabel                     title = new JLabel( " " ) ;
    private final JTree                      tree  = new LinedTree() ;

    /** The tags to show faded. */
    private Set<String> faded = Set.of() ;

    /** The tag being dragged. */
    private String dragged ;

    /**
     * @param toggle called with a clicked tag, and true to add it to the chosen
     *               set (it isn't faded) or false to take it out (it is)
     */
    TopicTagsPanel( BiConsumer<String,Boolean> toggle ) {

        super( new BorderLayout( 0, 4 ) ) ;
        this.toggle = toggle ;

        title.setFont( title.getFont().deriveFont( Font.BOLD ) ) ;
        title.setBorder( BorderFactory.createEmptyBorder( 4, 8, 0, 8 ) ) ;
        buildTree() ;

        JScrollPane pane = new JScrollPane( tree ) ;
        pane.setBorder( null ) ;
        pane.getVerticalScrollBar().setUnitIncrement( 16 ) ;

        setBorder( BorderFactory.createMatteBorder( 0, 1, 0, 0, Color.GRAY ) ) ;
        setPreferredSize( new Dimension( WIDTH, 100 ) ) ;
        add( title, BorderLayout.NORTH ) ;
        add( pane, BorderLayout.CENTER ) ;
        show( null ) ;
    }

    private void buildTree() {

        tree.setRootVisible( false ) ;
        tree.setShowsRootHandles( true ) ;
        tree.setRowHeight( ROW_HEIGHT ) ;
        tree.setFocusable( false ) ;
        // Nothing to select, and quick clicks on a tag must not fold it.
        tree.setSelectionModel( null ) ;
        tree.setToggleClickCount( 0 ) ;
        tree.setBorder( BorderFactory.createEmptyBorder( 0, 4, 8, 4 ) ) ;
        tree.setCellRenderer( ( t, value, sel, open, leaf, row, focus ) ->
                cell( (Node) ( (DefaultMutableTreeNode) value ).getUserObject() ) ) ;
        tree.setTransferHandler( new TransferHandler() {
            @Override public int getSourceActions( JComponent c ) {
                return COPY ;
            }

            @Override protected Transferable createTransferable( JComponent c ) {
                return dragged == null ? null : new StringSelection( dragged ) ;
            }
        } ) ;
        ToolTipManager.sharedInstance().registerComponent( tree ) ;

        MouseAdapter mouse = new MouseAdapter() {
            private MouseEvent press ;

            @Override public void mousePressed( MouseEvent e ) {
                press = tagAt( e ) != null ? e : null ;
            }

            @Override public void mouseReleased( MouseEvent e ) {
                press = null ;
            }

            @Override public void mouseClicked( MouseEvent e ) {

                String tag = tagAt( e ) ;
                if( tag != null && e.getButton() == MouseEvent.BUTTON1 ) {
                    toggle.accept( tag, !faded.contains( tag ) ) ;
                }
            }

            @Override public void mouseMoved( MouseEvent e ) {
                tree.setCursor( Cursor.getPredefinedCursor(
                        tagAt( e ) != null ? Cursor.HAND_CURSOR
                                           : Cursor.DEFAULT_CURSOR ) ) ;
            }

            @Override public void mouseDragged( MouseEvent e ) {

                if( press == null ) return ;
                if( Math.abs( e.getX() - press.getX() ) > DRAG_SLOP
                    || Math.abs( e.getY() - press.getY() ) > DRAG_SLOP ) {
                    dragged = tagAt( press ) ;
                    tree.getTransferHandler().exportAsDrag(
                            tree, press, TransferHandler.COPY ) ;
                    press = null ;
                }
            }
        } ;
        tree.addMouseListener( mouse ) ;
        tree.addMouseMotionListener( mouse ) ;
    }

    /** Show {@code catalogue}'s tags, all open; null (no scope) shows none. */
    void show( TagCatalogue catalogue ) {

        faded = Set.of() ;
        title.setText( catalogue == null
                       ? "Topic tags"
                       : "Tags of " + catalogue.topic().getTopicName() ) ;
        title.setToolTipText( "Drag or click a tag to add it to the chosen " +
                              "tags below; click a faded one to take it out. " +
                              "Grey names group tags, and aren't tags" ) ;

        tree.setModel( new DefaultTreeModel( catalogue == null
                ? new DefaultMutableTreeNode( new Node( "", null ) )
                : treeOf( catalogue.tags() ) ) ) ;
        // Expanding adds rows, so the count is read afresh each time.
        for( int row = 0 ; row < tree.getRowCount() ; row++ ) {
            tree.expandRow( row ) ;
        }
    }

    /** Fade the badges of {@code tags}; the rest not. */
    void fade( Set<String> tags ) {
        faded = tags ;
        tree.repaint() ;
    }

    /** The tree of {@code tags}, by the dots in their names. */
    private static DefaultMutableTreeNode treeOf( List<CatalogueTag> tags ) {

        Branch top = new Branch() ;
        for( CatalogueTag t : tags ) {
            Branch b = top ;
            for( String part : t.tagText().split( "\\." ) ) {
                b = b.children.computeIfAbsent( part, p -> new Branch() ) ;
            }
            b.tag = t ;
        }

        DefaultMutableTreeNode root =
                new DefaultMutableTreeNode( new Node( "", null ) ) ;
        addChildren( root, top ) ;
        return root ;
    }

    private static void addChildren( DefaultMutableTreeNode node, Branch from ) {

        for( Map.Entry<String,Branch> c : from.children.entrySet() ) {
            DefaultMutableTreeNode child = new DefaultMutableTreeNode(
                    new Node( c.getKey(), c.getValue().tag ) ) ;
            node.add( child ) ;
            addChildren( child, c.getValue() ) ;
        }
    }

    /**
     * A tree with a vertical line beside each set of siblings showing, drawn
     * here as the look and feel may draw none. It runs in the gap between the
     * siblings' fold arrows and their badges, so it crosses neither.
     */
    private static final class LinedTree extends JTree {

        @Override
        protected void paintComponent( Graphics g ) {

            super.paintComponent( g ) ;
            if( !( getModel().getRoot() instanceof DefaultMutableTreeNode r ) ) {
                return ;
            }
            g.setColor( LEVEL_LINE ) ;
            drawLines( g, r, new TreePath( r ) ) ;
        }

        /** The lines of {@code path}'s children, and of those below them. */
        private void drawLines( Graphics g, DefaultMutableTreeNode node,
                                TreePath path ) {

            if( node.getChildCount() == 0 || !isExpanded( path ) ) return ;

            Rectangle first = getPathBounds( path.pathByAddingChild(
                    node.getFirstChild() ) ) ;
            Rectangle last  = getPathBounds( path.pathByAddingChild(
                    node.getLastChild() ) ) ;

            if( first != null && last != null ) {
                int x = first.x - LINE_GAP ;
                g.drawLine( x, first.y, x, last.y + last.height - 1 ) ;
            }
            for( int i = 0 ; i < node.getChildCount() ; i++ ) {
                DefaultMutableTreeNode c =
                        (DefaultMutableTreeNode) node.getChildAt( i ) ;
                drawLines( g, c, path.pathByAddingChild( c ) ) ;
            }
        }
    }

    /** One level of the tree being built, its children in alphabetical order. */
    private static final class Branch {

        final Map<String,Branch> children = new TreeMap<>(
                Comparator.comparing( String::toLowerCase ) ) ;

        /** The tag ending here; null if the name only groups others. */
        CatalogueTag tag ;
    }

    /** How {@code n} is drawn: a badge for a tag, else grey text. */
    private JComponent cell( Node n ) {

        if( n.tag() == null ) {
            JLabel l = new JLabel( n.name() ) ;
            l.setForeground( NOT_A_TAG_FG ) ;
            return l ;
        }

        String text = n.tag().tagText() ;
        String hint = n.tag().hint() ;
        return new TagBadge( n.name(),
                             hint == null ? text : text + ": " + hint )
                       .dimmed( faded.contains( text ) ) ;
    }

    /** The tag under the mouse of {@code e}; null if there is none. */
    private String tagAt( MouseEvent e ) {

        TreePath path = tree.getPathForLocation( e.getX(), e.getY() ) ;
        if( path == null ) return null ;

        Node n = (Node) ( (DefaultMutableTreeNode) path.getLastPathComponent() )
                        .getUserObject() ;
        return n.tag() == null ? null : n.tag().tagText() ;
    }
}
