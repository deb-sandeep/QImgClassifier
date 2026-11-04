package com.sandy.sconsole.qimgtools.apps.tagger.ui;

import com.sandy.sconsole.qimgtools.apps.tagger.model.TagDecision;
import com.sandy.sconsole.qimgtools.apps.tagger.ui.TagTableModel.Row;
import com.sandy.sconsole.qimgtools.core.util.ModelNames;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * The selected question's tags side by side, so they can be compared at a
 * glance: the topic's tags already on it, and those the regression or the model
 * proposes, as badges. The part the chosen set started from is framed. Blank unless
 * exactly one question is selected. Clicking the proposed part chooses its
 * tags, as key 1 does. Each tag can be dragged to the chosen set
 * ({@link TagBadge}). Use on the EDT.
 */
@SuppressWarnings( "serial" )
final class TagStrip extends JPanel {

    private static final String EXISTING = "Existing" ;
    private static final String PROPOSED = "Proposed" ;

    private static final Color EXISTING_BG = new Color( 0xDCE8F5 ) ;
    private static final Color PROPOSED_BG = new Color( 0xFFF1D6 ) ;
    private static final int   HEIGHT      = 92 ;
    private static final float TITLE_PT    = 13f ;

    /** Text that stands in for tags. */
    private static final Color NOTE_FG = new Color( 0x757575 ) ;

    /** The frame round the part decided on. */
    private static final Color DECIDED_LINE = new Color( 0x2E7D32 ) ;

    private final ModelNames  names ;
    private final IntConsumer decide ;
    private final Part        existing = new Part( EXISTING_BG ) ;
    private final Part        proposed = new Part( PROPOSED_BG ) ;

    /** The row shown; null if none. */
    private Row row ;

    /** @param decide called with key 1, for a click on the model's part */
    TagStrip( ModelNames names, IntConsumer decide ) {

        super( new GridLayout( 1, 0 ) ) ;
        this.names  = names ;
        this.decide = decide ;
        setPreferredSize( new Dimension( 100, HEIGHT ) ) ;
        add( existing ) ;
        add( proposed ) ;
        proposed.addMouseListener( new MouseAdapter() {
            @Override public void mouseClicked( MouseEvent e ) {
                if( proposed.clickable ) decide.accept( 1 ) ;
            }
        } ) ;
        show( null ) ;
    }

    /** Show {@code row}'s tags; null clears the strip. */
    void show( Row row ) {

        this.row = row ;
        if( row == null ) {
            existing.set( EXISTING, false ) ;
            proposed.set( PROPOSED, false ) ;
            proposed.clickable( null ) ;
            return ;
        }

        TagDecision d = row.chosen() ;
        TagDecision p = row.proposed() ;

        existing.set( EXISTING + "  ·  " + row.item.type(),
                      d != null && d.source() == TagDecision.Source.EXISTING ) ;
        existing.tags( row.existing, "no tags" ) ;

        if( p == null ) {
            proposed.set( PROPOSED + ( row.needsLlm() ? "  ·  needs the LLM" : "" ),
                          false ) ;
            proposed.clickable( null ) ;
            return ;
        }
        proposed.set( PROPOSED + "  ·  " + ( p.model() != null
                                             ? names.of( p.model() )
                                             : "Regression" ),
                      d != null && d.source() == p.source() ) ;
        proposed.tags( p.tags(), "no tags" ) ;
        proposed.clickable( "Click to use the proposed tags (1)" ) ;
    }

    /** Re-show the row shown if it is {@code changed}. */
    void refresh( Row changed ) {
        if( changed == row ) show( row ) ;
    }

    /** One part: a title above the tags as badges, which wrap. */
    private final class Part extends JPanel {

        private final JLabel title = new JLabel( " " ) ;
        private final JPanel body  =
                new JPanel( new FlowLayout( FlowLayout.LEFT, 4, 3 ) ) ;
        private final Color  bg ;

        /** A click decides; set by {@link #clickable}. */
        boolean clickable ;

        Part( Color bg ) {

            super( new BorderLayout( 0, 2 ) ) ;
            this.bg = bg ;
            setBackground( bg ) ;
            frame( false ) ;
            title.setFont( title.getFont().deriveFont( Font.BOLD, TITLE_PT ) ) ;
            body.setOpaque( false ) ;
            add( title, BorderLayout.NORTH ) ;
            add( body, BorderLayout.CENTER ) ;
        }

        /**
         * Show {@code titleText}; {@code decided} frames the part as the one
         * decided on. The body is cleared.
         */
        void set( String titleText, boolean decided ) {

            frame( decided ) ;
            title.setText( titleText ) ;
            body.removeAll() ;
            body.revalidate() ;
            body.repaint() ;
        }

        /** Show {@code tags} as badges to drag; {@code none} if empty. */
        void tags( List<String> tags, String none ) {

            body.removeAll() ;
            for( String t : tags ) {
                TagBadge b = new TagBadge( t, null ).draggable( t ) ;
                // The badge takes the clicks, so it passes them on.
                b.addMouseListener( new MouseAdapter() {
                    @Override public void mouseClicked( MouseEvent e ) {
                        if( clickable ) decide.accept( 1 ) ;
                    }
                } ) ;
                body.add( b ) ;
            }
            if( tags.isEmpty() ) {
                JLabel l = new JLabel( none ) ;
                l.setFont( l.getFont().deriveFont( Font.ITALIC ) ) ;
                l.setForeground( NOTE_FG ) ;
                body.add( l ) ;
            }
            body.revalidate() ;
            body.repaint() ;
        }

        /** A thick green frame when decided on, else a thin one. */
        private void frame( boolean decided ) {

            int   w    = decided ? 3 : 1 ;
            Color line = decided ? DECIDED_LINE : bg.darker() ;

            setBorder( BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder( line, w ),
                    BorderFactory.createEmptyBorder( 4 - w, 10, 5 - w, 10 ) ) ) ;
        }

        /**
         * Make a click decide, with {@code tip} as the tooltip and a hand
         * cursor; null makes it inert.
         */
        void clickable( String tip ) {
            clickable = tip != null ;
            setToolTipText( tip ) ;
            setCursor( Cursor.getPredefinedCursor(
                    clickable ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR ) ) ;
        }
    }
}
