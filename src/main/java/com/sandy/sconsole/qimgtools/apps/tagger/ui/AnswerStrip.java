package com.sandy.sconsole.qimgtools.apps.tagger.ui;

import com.sandy.sconsole.qimgtools.apps.tagger.model.TagDecision;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TierReply;
import com.sandy.sconsole.qimgtools.apps.tagger.ui.TagTableModel.Row;
import com.sandy.sconsole.qimgtools.core.util.ModelNames;

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
 * The selected question's key and the two tiers' answers side by side in a
 * large font, so they can be compared at a glance: an answer that matches
 * the key is green, one that misses it pink, and the tier whose tags are
 * decided on is framed. Blank unless exactly one question is selected.
 * Clicking a tier's part decides as its key does: 1 tier 1's tags, 2 tier
 * 2's. Use on the EDT.
 */
@SuppressWarnings( "serial" )
final class AnswerStrip extends JPanel {

    private static final Color KEY_BG   = new Color( 0xDCE8F5 ) ;
    private static final Color TIER1_BG = new Color( 0xFFF1D6 ) ;
    private static final Color TIER2_BG = new Color( 0xEDE3F5 ) ;
    private static final int   HEIGHT   = 72 ;
    private static final float TEXT_PT  = 22f ;

    // Answer colours: matches the key, or misses it.
    private static final Color RIGHT_FG = new Color( 0x2E7D32 ) ;
    private static final Color WRONG_FG = new Color( 0xD81B60 ) ;

    /** The frame round the part decided on. */
    private static final Color DECIDED_LINE = new Color( 0x2E7D32 ) ;

    private final ModelNames  names ;
    private final IntConsumer decide ;
    private final Part        key   = new Part( KEY_BG ) ;
    private final Part        tier1 = new Part( TIER1_BG ) ;
    private final Part        tier2 = new Part( TIER2_BG ) ;

    /** The row shown; null if none. */
    private Row row ;

    /**
     * @param decide called with the decision key a click on a tier stands
     *        for: 1 tier 1's tags, 2 tier 2's
     */
    AnswerStrip( ModelNames names, IntConsumer decide ) {

        super( new GridLayout( 1, 3 ) ) ;
        this.names  = names ;
        this.decide = decide ;
        setPreferredSize( new Dimension( 100, HEIGHT ) ) ;
        add( key ) ;
        add( tier1 ) ;
        add( tier2 ) ;
        onClick( tier1, 1 ) ;
        onClick( tier2, 2 ) ;
        show( null ) ;
    }

    /** A click on {@code part} decides as key {@code n} while it may. */
    private void onClick( Part part, int n ) {
        part.addMouseListener( new MouseAdapter() {
            @Override public void mouseClicked( MouseEvent e ) {
                if( part.clickable ) decide.accept( n ) ;
            }
        } ) ;
    }

    /** Show {@code row}'s key and answers; null clears the strip. */
    void show( Row row ) {

        this.row = row ;
        if( row == null ) {
            key.set( "Key", " ", null, false ) ;
            tier1.set( TagTableModel.TIER1, " ", null, false ) ;
            tier2.set( TagTableModel.TIER2, " ", null, false ) ;
            tier1.clickable( null ) ;
            tier2.clickable( null ) ;
            return ;
        }

        TagDecision d = row.decision ;
        key.set( "Key   [" + row.item.type() + "]", row.item.key(), null,
                 false ) ;
        showTier( tier1, 1, d != null && d.source() == TagDecision.Source.TIER1 ) ;
        showTier( tier2, 2, d != null && d.source() == TagDecision.Source.TIER2 ) ;
    }

    private void showTier( Part part, int n, boolean decided ) {

        TierReply t     = row.tier( n ) ;
        String    title = n == 1 ? TagTableModel.TIER1 : TagTableModel.TIER2 ;

        if( t == null ) {
            String why = n == 2 && row.opinions != null
                         && row.opinions.tier2Failure() != null
                       ? "no answer: " + row.opinions.tier2Failure() : null ;
            part.set( title, why, null, false ) ;
            part.clickable( null ) ;
            return ;
        }
        part.set( title + "   [" + names.of( t.model() ) + "]", t.answer(),
                  row.correct( n ) ? RIGHT_FG : WRONG_FG, decided ) ;
        part.clickable( "Click to use " + title.toLowerCase() + "'s tags (" +
                        n + ")" ) ;
    }

    /** Re-show the row shown if it is {@code changed}. */
    void refresh( Row changed ) {
        if( changed == row ) show( row ) ;
    }

    /** One part: a small caption above the answer in a large font. */
    private static final class Part extends JPanel {

        private final JLabel caption = new JLabel( " " ) ;
        private final JLabel text    =
                new JLabel( " ", SwingConstants.CENTER ) ;
        private final Color  plainFg = text.getForeground() ;
        private final Color  bg ;

        /** A click decides; set by {@link #clickable}. */
        boolean clickable ;

        Part( Color bg ) {

            super( new BorderLayout() ) ;
            this.bg = bg ;
            setBackground( bg ) ;
            frame( false ) ;
            text.setFont( text.getFont().deriveFont( Font.BOLD, TEXT_PT ) ) ;
            add( caption, BorderLayout.NORTH ) ;
            add( text, BorderLayout.CENTER ) ;
        }

        /**
         * {@code fg} colours the text, null for the usual colour;
         * {@code decided} frames the part as the one decided on.
         */
        void set( String captionText, String value, Color fg,
                  boolean decided ) {

            frame( decided ) ;
            caption.setText( captionText ) ;
            text.setForeground( fg != null ? fg : plainFg ) ;
            // HTML so a long text wraps instead of being cut.
            text.setText( value == null || value.isBlank() ? " "
                          : "<html><div style='text-align:center'>" +
                            escape( value ) + "</div></html>" ) ;
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
