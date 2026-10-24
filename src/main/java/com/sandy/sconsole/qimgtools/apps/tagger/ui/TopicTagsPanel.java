package com.sandy.sconsole.qimgtools.apps.tagger.ui;

import com.sandy.sconsole.qimgtools.apps.tagger.model.Axis;
import com.sandy.sconsole.qimgtools.apps.tagger.model.CatalogueTag;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagClass;
import com.sandy.sconsole.qimgtools.apps.tagger.prompt.TagPrompter.Assembly;
import com.sandy.sconsole.qimgtools.apps.tagger.ui.TagBadge.Payload;
import com.sandy.sconsole.qimgtools.core.model.Topic;

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
 * The scoped topic's tags the pass offers, one section per axis of the pass
 * (with its count rule), and in the concept pass the syllabus' other topics
 * a question may depend on: the palette the chosen set is filled from, by
 * dragging a badge to the chosen tags bar or clicking it. Each tag's hint
 * is its tooltip. The tags and topics in the chosen set of the selection
 * are faded, and a click on a faded badge takes it out again. Use on the
 * EDT.
 */
@SuppressWarnings( "serial" )
final class TopicTagsPanel extends JPanel {

    private static final int WIDTH = 270 ;

    private final TagClass                    pass ;
    private final BiConsumer<Payload,Boolean> toggle ;
    private final JLabel                      title = new JLabel( " " ) ;
    private final JPanel                      list  = new JPanel() ;

    // The badges shown, by what they carry, to fade the chosen ones.
    private final Map<String,TagBadge>  tagBadges   = new HashMap<>() ;
    private final Map<Integer,TagBadge> topicBadges = new HashMap<>() ;

    /**
     * @param toggle called with a clicked badge's payload, and true to add
     *               it to the chosen set (it isn't faded) or false to take
     *               it out (it is)
     */
    TopicTagsPanel( TagClass pass, BiConsumer<Payload,Boolean> toggle ) {

        super( new BorderLayout( 0, 4 ) ) ;
        this.pass   = pass ;
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

    /** Show the tags {@code a} offers; null (no scope) shows none. */
    void show( Assembly a ) {

        list.removeAll() ;
        tagBadges.clear() ;
        topicBadges.clear() ;
        title.setText( a == null ? "Topic tags"
                                 : "Tags of " + a.topic().getTopicName() ) ;
        title.setToolTipText( "Drag or click a tag to add it to the chosen " +
                             "tags below; click a faded one to take it out" ) ;

        if( a != null ) {
            for( Axis axis : pass.getAxes() ) {
                section( axis.getHeading() + "  (" +
                         axis.countRule( false ) + ")" ) ;
                for( CatalogueTag t : a.offered().tags( axis ) ) {
                    TagBadge b = badge( t.tagText(), t.hint(),
                                        Payload.tag( t.tagText() ) ) ;
                    tagBadges.put( t.tagText(), b ) ;
                    item( b ) ;
                }
            }
            if( pass.hasDependents() ) {
                section( "Depends on  (own questions)" ) ;
                for( Topic t : a.otherTopics() ) {
                    TagBadge b = badge( "→ " + t.getTopicName(),
                                        "Topic " + t.getId(),
                                        Payload.topic( t.getId() ) ) ;
                    topicBadges.put( t.getId(), b ) ;
                    item( b ) ;
                }
            }
        }
        list.revalidate() ;
        list.repaint() ;
    }

    /** Fade the badges of {@code tags} and {@code topics}; the rest not. */
    void fade( Set<String> tags, Set<Integer> topics ) {
        tagBadges.forEach( ( t, b ) -> b.dimmed( tags.contains( t ) ) ) ;
        topicBadges.forEach( ( id, b ) -> b.dimmed( topics.contains( id ) ) ) ;
        list.repaint() ;
    }

    /**
     * A badge that can be dragged with {@code p}, and clicked to toggle it
     * in the chosen set.
     */
    private TagBadge badge( String text, String tip, Payload p ) {

        TagBadge b = new TagBadge( text, tip, false ).draggable( p ) ;
        return b.clickable( () -> toggle.accept( p, !b.isDimmed() ) ) ;
    }

    private void section( String heading ) {

        JLabel l = new JLabel( heading ) ;
        l.setFont( l.getFont().deriveFont( Font.BOLD ) ) ;
        l.setBorder( BorderFactory.createEmptyBorder( 8, 0, 3, 0 ) ) ;
        item( l ) ;
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
