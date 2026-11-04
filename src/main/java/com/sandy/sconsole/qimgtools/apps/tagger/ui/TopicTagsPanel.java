package com.sandy.sconsole.qimgtools.apps.tagger.ui;

import com.sandy.sconsole.qimgtools.apps.tagger.model.CatalogueTag;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagCatalogue;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * The scoped topic's tags: the palette the chosen set is filled from, by
 * dragging a badge to the chosen tags bar or clicking it. Each tag's hint is
 * its tooltip. The tags in the chosen set of the selection are faded, and a
 * click on a faded badge takes it out again. Use on the EDT.
 */
@SuppressWarnings( "serial" )
final class TopicTagsPanel extends JPanel {

    private static final int WIDTH = 270 ;

    private final BiConsumer<String,Boolean> toggle ;
    private final JLabel                     title = new JLabel( " " ) ;
    private final JPanel                     list  = new JPanel() ;

    /** The badges shown, by tag, to fade the chosen ones. */
    private final Map<String,TagBadge> badges = new HashMap<>() ;

    /**
     * @param toggle called with a clicked badge's tag, and true to add it to
     *               the chosen set (it isn't faded) or false to take it out
     *               (it is)
     */
    TopicTagsPanel( BiConsumer<String,Boolean> toggle ) {

        super( new BorderLayout( 0, 4 ) ) ;
        this.toggle = toggle ;

        title.setFont( title.getFont().deriveFont( Font.BOLD ) ) ;
        title.setBorder( BorderFactory.createEmptyBorder( 4, 8, 0, 8 ) ) ;
        list.setLayout( new BoxLayout( list, BoxLayout.Y_AXIS ) ) ;
        list.setBorder( BorderFactory.createEmptyBorder( 0, 8, 8, 8 ) ) ;

        JPanel top = new JPanel( new BorderLayout() ) ;
        top.add( list, BorderLayout.NORTH ) ;

        JScrollPane pane = new JScrollPane( top,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER ) ;
        pane.setBorder( null ) ;
        pane.getVerticalScrollBar().setUnitIncrement( 16 ) ;

        setBorder( BorderFactory.createMatteBorder( 0, 1, 0, 0, Color.GRAY ) ) ;
        setPreferredSize( new Dimension( WIDTH, 100 ) ) ;
        add( title, BorderLayout.NORTH ) ;
        add( pane, BorderLayout.CENTER ) ;
        show( null ) ;
    }

    /** Show {@code catalogue}'s tags; null (no scope) shows none. */
    void show( TagCatalogue catalogue ) {

        list.removeAll() ;
        badges.clear() ;
        title.setText( catalogue == null
                       ? "Topic tags"
                       : "Tags of " + catalogue.topic().getTopicName() ) ;
        title.setToolTipText( "Drag or click a tag to add it to the chosen " +
                              "tags below; click a faded one to take it out" ) ;

        if( catalogue != null ) {
            for( CatalogueTag t : catalogue.tags() ) {
                TagBadge b = badge( t.tagText(), t.hint() ) ;
                badges.put( t.tagText(), b ) ;
                item( b ) ;
            }
        }
        list.revalidate() ;
        list.repaint() ;
    }

    /** Fade the badges of {@code tags}; the rest not. */
    void fade( Set<String> tags ) {
        badges.forEach( ( t, b ) -> b.dimmed( tags.contains( t ) ) ) ;
        list.repaint() ;
    }

    /**
     * A badge that can be dragged with {@code tag}, and clicked to toggle it
     * in the chosen set.
     */
    private TagBadge badge( String tag, String tip ) {

        TagBadge b = new TagBadge( tag, tip ).draggable( tag ) ;
        return b.clickable( () -> toggle.accept( tag, !b.isDimmed() ) ) ;
    }

    /** Add {@code c} on a line of its own, left-aligned. */
    private void item( JComponent c ) {

        JPanel line = new JPanel( new FlowLayout( FlowLayout.LEFT, 0, 1 ) ) ;
        line.add( c ) ;
        line.setAlignmentX( Component.LEFT_ALIGNMENT ) ;
        line.setMaximumSize( new Dimension( Integer.MAX_VALUE,
                                            line.getPreferredSize().height ) ) ;
        list.add( line ) ;
        list.add( Box.createVerticalStrut( 1 ) ) ;
    }
}
