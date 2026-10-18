package com.sandy.sconsole.qimgtools.apps.topicreviewer.ui;

import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ServerQuestion;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewVerdict;
import com.sandy.sconsole.qimgtools.core.util.ModelNames;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.ui.ReviewTableModel.Row;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.IntConsumer;

/**
 * The selected question's server topic (left) and the model's best topic
 * (right) side by side in a large font on different backgrounds, so the
 * two can be compared at a glance. Blank unless exactly one question is
 * selected. Clicking a side decides as its key does: the server's side as
 * 2 (keep the server's topic), the model's as 1 (use the model's). Use on
 * the EDT.
 */
@SuppressWarnings( "serial" )
final class TopicCompareStrip extends JPanel {

    private static final Color SERVER_BG = new Color( 0xDCE8F5 ) ;
    private static final Color AI_BG     = new Color( 0xFFF1D6 ) ;
    private static final int   HEIGHT    = 72 ;
    private static final float TOPIC_PT  = 22f ;

    // Topic colours: the model agrees with the server, or differs. Dark
    // enough to read on both backgrounds.
    private static final Color AGREES_FG  = new Color( 0x2E7D32 ) ;
    private static final Color DIFFERS_FG = new Color( 0xD81B60 ) ;

    private final ModelNames  names ;
    private final IntConsumer decide ;
    private final Side        server = new Side( SERVER_BG ) ;
    private final Side        ai     = new Side( AI_BG ) ;

    /** The row shown; null if none. */
    private Row row ;

    /**
     * @param decide called with the decision key a click on a side stands
     *        for: 2 for the server's side, 1 for the model's
     */
    TopicCompareStrip( ModelNames names, IntConsumer decide ) {

        super( new GridLayout( 1, 2 ) ) ;
        this.names  = names ;
        this.decide = decide ;
        setPreferredSize( new Dimension( 100, HEIGHT ) ) ;
        add( server ) ;
        add( ai ) ;
        onClick( server, 2 ) ;
        onClick( ai, 1 ) ;
        show( null ) ;
    }

    /** A click on {@code side} decides as key {@code key} while it may. */
    private void onClick( Side side, int key ) {
        side.addMouseListener( new MouseAdapter() {
            @Override public void mouseClicked( MouseEvent e ) {
                if( side.clickable ) decide.accept( key ) ;
            }
        } ) ;
    }

    /** Show {@code row}'s two topics; null clears the strip. */
    void show( Row row ) {

        this.row = row ;
        if( row == null ) {
            server.set( "Server", " ", null ) ;
            ai.set( "AI", " ", null ) ;
            server.clickable( null ) ;
            ai.clickable( null ) ;
            return ;
        }

        ServerQuestion q      = row.question ;
        String         by     = q.provenance( names ) ;
        ReviewVerdict  v      = row.verdict ;
        boolean        agrees = v != null && v.agreesWith( q.getTopicId() ) ;
        Color          fg     = v == null ? null
                              : agrees    ? AGREES_FG : DIFFERS_FG ;

        server.set( "Server" + ( by.isEmpty() ? "" : "   [" + by + "]" ),
                    q.getTopicName(), fg ) ;
        server.clickable( "Click to keep the server's topic (2)" ) ;
        if( v == null ) {
            ai.set( "AI", null, null ) ;
            ai.clickable( null ) ;
        }
        else {
            ai.set( "AI   [" + names.of( v.model() ) + ", " +
                    v.topConfidence() + "%]" +
                    ( agrees ? "   agrees" : "   differs" ),
                    v.top().getTopicName(), fg ) ;
            ai.clickable( "Click to use the model's topic (1)" ) ;
        }
    }

    /** Re-show the row shown if it is {@code changed}. */
    void refresh( Row changed ) {
        if( changed == row ) show( row ) ;
    }

    /** One half: a small caption above the topic in a large font. */
    private static final class Side extends JPanel {

        private final JLabel caption = new JLabel( " " ) ;
        private final JLabel topic   =
                new JLabel( " ", SwingConstants.CENTER ) ;
        private final Color  plainFg = topic.getForeground() ;

        /** A click decides; set by {@link #clickable}. */
        boolean clickable ;

        Side( Color bg ) {

            super( new BorderLayout() ) ;
            setBackground( bg ) ;
            setBorder( BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder( bg.darker() ),
                    BorderFactory.createEmptyBorder( 2, 10, 4, 10 ) ) ) ;
            topic.setFont( topic.getFont().deriveFont( Font.BOLD,
                                                       TOPIC_PT ) ) ;
            add( caption, BorderLayout.NORTH ) ;
            add( topic, BorderLayout.CENTER ) ;
        }

        /** {@code fg} colours the topic; null for the usual colour. */
        void set( String captionText, String topicText, Color fg ) {

            caption.setText( captionText ) ;
            topic.setForeground( fg != null ? fg : plainFg ) ;
            // HTML so a long topic name wraps instead of being cut.
            topic.setText( topicText == null || topicText.isBlank() ? " "
                           : "<html><div style='text-align:center'>" +
                             escape( topicText ) + "</div></html>" ) ;
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

        private static String escape( String s ) {
            return s.replace( "&", "&amp;" )
                    .replace( "<", "&lt;" )
                    .replace( ">", "&gt;" ) ;
        }
    }
}
