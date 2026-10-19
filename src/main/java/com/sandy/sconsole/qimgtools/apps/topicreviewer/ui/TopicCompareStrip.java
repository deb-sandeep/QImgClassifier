package com.sandy.sconsole.qimgtools.apps.topicreviewer.ui;

import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewVerdict;
import com.sandy.sconsole.qimgtools.core.util.ModelNames;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.ui.ReviewTableModel.Row;

import static com.sandy.sconsole.qimgtools.apps.topicreviewer.ui.ReviewTableModel.REVIEWER;
import static com.sandy.sconsole.qimgtools.apps.topicreviewer.ui.ReviewTableModel.SERVER;
import static com.sandy.sconsole.qimgtools.apps.topicreviewer.ui.ReviewTableModel.TIE_BREAKER;

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
 * The selected question's server topic, the review model's and the
 * tie-breaker's side by side in a large font on different backgrounds, so
 * they can be compared at a glance: a topic another part shares is green, a
 * lone one pink, and the one decided on is framed. Blank unless exactly one
 * question is selected. Clicking a part decides as its key does: 1 keep the
 * server's topic, 2 use the reviewer's, 3 the tie-breaker's. Use on the EDT.
 */
@SuppressWarnings( "serial" )
final class TopicCompareStrip extends JPanel {

    private static final Color SERVER_BG = new Color( 0xDCE8F5 ) ;
    private static final Color AI_BG     = new Color( 0xFFF1D6 ) ;
    private static final Color TIE_BG    = new Color( 0xEDE3F5 ) ;
    private static final int   HEIGHT    = 72 ;
    private static final float TOPIC_PT  = 22f ;

    // Topic colours: shared with another part, or alone. Dark enough to
    // read on every background.
    private static final Color AGREES_FG  = new Color( 0x2E7D32 ) ;
    private static final Color DIFFERS_FG = new Color( 0xD81B60 ) ;

    /** The frame round the part decided on. */
    private static final Color DECIDED_LINE = new Color( 0x2E7D32 ) ;

    private final ModelNames  names ;
    private final IntConsumer decide ;
    private final Side        server = new Side( SERVER_BG ) ;
    private final Side        ai     = new Side( AI_BG ) ;
    private final Side        tie    = new Side( TIE_BG ) ;

    /** The row shown; null if none. */
    private Row row ;

    /**
     * @param decide called with the decision key a click on a part stands
     *        for: 1 the server's, 2 the reviewer's, 3 the tie-breaker's
     */
    TopicCompareStrip( ModelNames names, IntConsumer decide ) {

        super( new GridLayout( 1, 3 ) ) ;
        this.names  = names ;
        this.decide = decide ;
        setPreferredSize( new Dimension( 100, HEIGHT ) ) ;
        add( server ) ;
        add( ai ) ;
        add( tie ) ;
        onClick( server, 1 ) ;
        onClick( ai, 2 ) ;
        onClick( tie, 3 ) ;
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

    /** Show {@code row}'s three topics; null clears the strip. */
    void show( Row row ) {

        this.row = row ;
        if( row == null ) {
            server.set( SERVER, " ", null, false ) ;
            ai.set( REVIEWER, " ", null, false ) ;
            tie.set( TIE_BREAKER, " ", null, false ) ;
            server.clickable( null ) ;
            ai.clickable( null ) ;
            tie.clickable( null ) ;
            return ;
        }

        String        by      = row.question.provenance( names ) ;
        ReviewVerdict r       = row.reviewer() ;
        ReviewVerdict t       = row.tieBreaker() ;
        int           decided = ReviewTableModel.decidedColumn( row ) ;
        Integer       sId     = row.serverTopicId ;
        Integer       rId     = r == null ? null : r.top().getId() ;
        Integer       tId     = t == null ? null : t.top().getId() ;

        server.set( SERVER + ( by.isEmpty() ? "" : "   [" + by + "]" ),
                    row.serverTopicName,
                    r == null ? null : colour( sId, rId, tId ),
                    decided == ReviewTableModel.COL_SERVER ) ;
        server.clickable( "Click to keep the server's topic (1)" ) ;

        if( r == null ) {
            ai.set( REVIEWER, null, null, false ) ;
            ai.clickable( null ) ;
        }
        else {
            ai.set( REVIEWER + "   " + caption( r ), r.top().getTopicName(),
                    colour( rId, sId, tId ),
                    decided == ReviewTableModel.COL_REVIEWER ) ;
            ai.clickable( "Click to use the reviewer's topic (2)" ) ;
        }

        boolean user = decided == ReviewTableModel.COL_TIE_BREAKER
                       && ( tId == null || tId != row.assigned.getId() ) ;
        if( user ) {
            tie.set( "Chosen by the user", row.assigned.getTopicName(),
                     null, true ) ;
        }
        else if( t == null ) {
            String why = row.opinions != null
                         && row.opinions.tieBreakerFailed()
                       ? "no answer: " + row.opinions.tieBreakerFailure()
                       : null ;
            tie.set( TIE_BREAKER, why, null, false ) ;
        }
        else {
            tie.set( TIE_BREAKER + "   " + caption( t ), t.top().getTopicName(),
                     colour( tId, sId, rId ),
                     decided == ReviewTableModel.COL_TIE_BREAKER ) ;
        }
        tie.clickable( t == null ? null
                                 : "Click to use the tie-breaker's topic (3)" ) ;
    }

    /** "[Haiku, 92%]" */
    private String caption( ReviewVerdict v ) {
        return "[" + names.of( v.model() ) + ", " + v.topConfidence() + "%]" ;
    }

    /** Green if topic {@code id} is one of {@code others}, else pink. */
    private static Color colour( Integer id, Integer... others ) {

        for( Integer o : others ) {
            if( id != null && id.equals( o ) ) return AGREES_FG ;
        }
        return DIFFERS_FG ;
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
        private final Color  bg ;

        /** A click decides; set by {@link #clickable}. */
        boolean clickable ;

        Side( Color bg ) {

            super( new BorderLayout() ) ;
            this.bg = bg ;
            setBackground( bg ) ;
            frame( false ) ;
            topic.setFont( topic.getFont().deriveFont( Font.BOLD,
                                                       TOPIC_PT ) ) ;
            add( caption, BorderLayout.NORTH ) ;
            add( topic, BorderLayout.CENTER ) ;
        }

        /**
         * {@code fg} colours the topic, null for the usual colour;
         * {@code decided} frames the part as the one decided on.
         */
        void set( String captionText, String topicText, Color fg,
                  boolean decided ) {

            frame( decided ) ;
            caption.setText( captionText ) ;
            topic.setForeground( fg != null ? fg : plainFg ) ;
            // HTML so a long topic name wraps instead of being cut.
            topic.setText( topicText == null || topicText.isBlank() ? " "
                           : "<html><div style='text-align:center'>" +
                             escape( topicText ) + "</div></html>" ) ;
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

        private static String escape( String s ) {
            return s.replace( "&", "&amp;" )
                    .replace( "<", "&lt;" )
                    .replace( ">", "&gt;" ) ;
        }
    }
}
