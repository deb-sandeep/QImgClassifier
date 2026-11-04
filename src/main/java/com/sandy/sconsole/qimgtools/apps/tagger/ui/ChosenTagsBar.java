package com.sandy.sconsole.qimgtools.apps.tagger.ui;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.TransferHandler;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.List;
import java.util.function.Consumer;

/**
 * The chosen set of the selected questions, the tags they are to be saved
 * with: the tags as badges, each with a × that takes it out. Tags dragged
 * from the tag strip or the topic tags panel and dropped here are added.
 * With several questions selected it shows what their sets share, and an
 * edit applies to each of them. At its right, where the set came from and who
 * it is credited to. Use on the EDT.
 */
@SuppressWarnings( "serial" )
final class ChosenTagsBar extends JPanel {

    /**
     * What the bar shows.
     *
     * @param tags       the chosen tags (shared by every row selected)
     * @param provenance where the set came from, who it is credited to
     */
    record View( List<String> tags, String provenance ) {}

    private static final Color BG      = new Color( 0xF5F9F5 ) ;
    private static final Color NOTE_FG = new Color( 0x757575 ) ;
    private static final int   HEIGHT  = 64 ;

    private final Consumer<String> remove ;
    private final JPanel           body =
            new JPanel( new FlowLayout( FlowLayout.LEFT, 4, 3 ) ) ;
    private final JLabel           from = new JLabel( " " ) ;

    /**
     * @param add    called with the tag dropped on the bar
     * @param remove called with the tag a × takes out
     */
    ChosenTagsBar( Consumer<String> add, Consumer<String> remove ) {

        super( new BorderLayout( 8, 0 ) ) ;
        this.remove = remove ;

        JLabel heading = new JLabel( "Chosen" ) ;
        heading.setFont( heading.getFont().deriveFont( Font.BOLD, 13f ) ) ;
        heading.setVerticalAlignment( JLabel.TOP ) ;
        heading.setBorder( BorderFactory.createEmptyBorder( 5, 0, 0, 0 ) ) ;

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
        add( from, BorderLayout.EAST ) ;
        show( null ) ;
    }

    /** Show {@code view}; null when no question it can edit is selected. */
    void show( View view ) {

        body.removeAll() ;
        if( view == null ) {
            note( "Select a question to choose its tags" ) ;
            from.setText( " " ) ;
        }
        else {
            for( String t : view.tags() ) {
                body.add( new TagBadge( t, "Click × to take it out" )
                                  .removable( () -> remove.accept( t ) ) ) ;
            }
            if( body.getComponentCount() == 0 ) {
                note( "Nothing chosen: drag tags here" ) ;
            }
            from.setText( view.provenance() ) ;
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
