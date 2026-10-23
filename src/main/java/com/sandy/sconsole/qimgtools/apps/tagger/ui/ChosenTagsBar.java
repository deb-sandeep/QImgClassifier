package com.sandy.sconsole.qimgtools.apps.tagger.ui;

import com.sandy.sconsole.qimgtools.apps.tagger.ui.TagBadge.Payload;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.TransferHandler;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/**
 * The chosen set of the selected questions, the tags they are to be saved
 * with: the tags as badges, then (concept, own questions) the topics they
 * depend on, each with a × that takes it out. Tags and topics dragged from
 * the answer strip or the topic tags panel and dropped here are added.
 * With several questions selected it shows what their sets share, and an
 * edit applies to each of them. At its right, where the set came from and
 * who it is credited to, and whether it keeps the count rules. Use on the
 * EDT.
 */
@SuppressWarnings( "serial" )
final class ChosenTagsBar extends JPanel {

    /**
     * What the bar shows.
     *
     * @param tags       the chosen tags (shared by every row selected)
     * @param topics     the topics depended on; null to show none
     * @param provenance where the set came from, who it is credited to
     * @param problem    what breaks the count rules; null if nothing
     */
    record View( List<String> tags, List<Integer> topics, String provenance,
                 String problem ) {}

    private static final Color BG         = new Color( 0xF5F9F5 ) ;
    private static final Color OK_FG      = new Color( 0x2E7D32 ) ;
    private static final Color PROBLEM_FG = new Color( 0xC62828 ) ;
    private static final Color NOTE_FG    = new Color( 0x757575 ) ;
    private static final int   HEIGHT     = 64 ;

    private final IntFunction<String> topicName ;
    private final Consumer<Payload>   remove ;
    private final JPanel              body =
            new JPanel( new FlowLayout( FlowLayout.LEFT, 4, 3 ) ) ;
    private final JLabel              from   = new JLabel( " " ) ;
    private final JLabel              counts = new JLabel( " " ) ;

    /**
     * @param topicName a topic's name by id
     * @param add       called with what is dropped on the bar
     * @param remove    called with what a × takes out
     */
    ChosenTagsBar( IntFunction<String> topicName, Consumer<Payload> add,
                   Consumer<Payload> remove ) {

        super( new BorderLayout( 8, 0 ) ) ;
        this.topicName = topicName ;
        this.remove    = remove ;

        JLabel heading = new JLabel( "Chosen" ) ;
        heading.setFont( heading.getFont().deriveFont( Font.BOLD, 13f ) ) ;
        heading.setVerticalAlignment( JLabel.TOP ) ;
        heading.setBorder( BorderFactory.createEmptyBorder( 5, 0, 0, 0 ) ) ;

        JPanel right = new JPanel( new GridLayout( 0, 1 ) ) ;
        right.setOpaque( false ) ;
        right.add( from ) ;
        right.add( counts ) ;
        counts.setFont( counts.getFont().deriveFont( Font.BOLD ) ) ;

        TransferHandler drop = TagBadge.dropTarget( add ) ;
        setTransferHandler( drop ) ;
        body.setTransferHandler( drop ) ;
        body.setOpaque( false ) ;

        setBackground( BG ) ;
        setBorder( BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder( 1, 0, 0, 0, Color.GRAY ),
                BorderFactory.createEmptyBorder( 4, 8, 4, 8 ) ) ) ;
        setPreferredSize( new Dimension( 100, HEIGHT ) ) ;
        add( heading, BorderLayout.WEST ) ;
        add( body, BorderLayout.CENTER ) ;
        add( right, BorderLayout.EAST ) ;
        show( null ) ;
    }

    /** Show {@code view}; null when no question it can edit is selected. */
    void show( View view ) {

        body.removeAll() ;
        if( view == null ) {
            note( "Select a question to choose its tags" ) ;
            from.setText( " " ) ;
            counts.setText( " " ) ;
        }
        else {
            for( String t : view.tags() ) {
                body.add( new TagBadge( t, "Click × to take it out", false )
                                  .removable( () -> remove.accept(
                                          Payload.tag( t ) ) ) ) ;
            }
            if( view.topics() != null ) {
                for( int id : view.topics() ) {
                    body.add( new TagBadge( "→ " + topicName.apply( id ),
                                            "Click × to take it out", false )
                                      .removable( () -> remove.accept(
                                              Payload.topic( id ) ) ) ) ;
                }
            }
            if( body.getComponentCount() == 0 ) {
                note( "Nothing chosen: drag tags here" ) ;
            }
            from.setText( view.provenance() ) ;
            counts.setText( view.problem() == null ? "✓ counts kept"
                                                   : "✗ " + view.problem() ) ;
            counts.setForeground( view.problem() == null ? OK_FG
                                                         : PROBLEM_FG ) ;
        }
        body.revalidate() ;
        body.repaint() ;
    }

    private void note( String text ) {

        JLabel l = new JLabel( text ) ;
        l.setFont( l.getFont().deriveFont( Font.ITALIC ) ) ;
        l.setForeground( NOTE_FG ) ;
        body.add( l ) ;
    }
}
