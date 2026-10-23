package com.sandy.sconsole.qimgtools.apps.tagger.ui;

import com.sandy.sconsole.qimgtools.apps.tagger.prompt.PromptStore;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;

/**
 * A badge per prompt state, drawn rather than loaded, with the colour of
 * the state and the light tint its row takes in a topic table: approved a
 * green tick, draft an amber pencil mark, stale a red-orange "!", base only
 * a half-filled blue-grey circle, none a hollow grey one.
 */
enum PromptStateIcon implements Icon {

    NONE     ( new Color( 0x9E9E9E ), null ),
    BASE_ONLY( new Color( 0x607D8B ), null ),
    DRAFT    ( new Color( 0xF9A825 ), new Color( 0xFFF8E1 ) ),
    APPROVED ( new Color( 0x2E7D32 ), new Color( 0xE8F5E9 ) ),
    STALE    ( new Color( 0xE64A19 ), new Color( 0xFBE9E7 ) ) ;

    /** Width and height of the badge in pixels. */
    static final int SIZE = 14 ;

    /** The state's colour: the badge's, and its label's text. */
    final Color color ;

    /** The tint of a row in this state; null to leave it plain. */
    final Color rowTint ;

    PromptStateIcon( Color color, Color rowTint ) {
        this.color   = color ;
        this.rowTint = rowTint ;
    }

    /** The badge of {@code state}. */
    static PromptStateIcon of( PromptStore.State state ) {
        return switch( state ) {
            case NONE      -> NONE ;
            case BASE_ONLY -> BASE_ONLY ;
            case DRAFT     -> DRAFT ;
            case APPROVED  -> APPROVED ;
            case STALE     -> STALE ;
        } ;
    }

    @Override public int getIconWidth()  { return SIZE ; }
    @Override public int getIconHeight() { return SIZE ; }

    @Override
    public void paintIcon( Component c, Graphics g, int x, int y ) {

        Graphics2D g2 = (Graphics2D) g.create() ;
        try {
            g2.setRenderingHint( RenderingHints.KEY_ANTIALIASING,
                                 RenderingHints.VALUE_ANTIALIAS_ON ) ;
            g2.translate( x, y ) ;

            Ellipse2D disc = new Ellipse2D.Double( 1, 1, SIZE - 2, SIZE - 2 ) ;
            g2.setColor( color ) ;
            switch( this ) {
                case NONE -> {
                    g2.setStroke( new BasicStroke( 1.5f ) ) ;
                    g2.draw( new Ellipse2D.Double( 1.5, 1.5, SIZE - 3,
                                                   SIZE - 3 ) ) ;
                }
                case BASE_ONLY -> {
                    g2.setStroke( new BasicStroke( 1.5f ) ) ;
                    g2.draw( new Ellipse2D.Double( 1.5, 1.5, SIZE - 3,
                                                   SIZE - 3 ) ) ;
                    g2.fill( new Arc2D.Double( 1, 1, SIZE - 2, SIZE - 2,
                                               90, 180, Arc2D.PIE ) ) ;
                }
                default -> {
                    g2.fill( disc ) ;
                    g2.setColor( Color.WHITE ) ;
                    g2.setStroke( new BasicStroke( 1.8f, BasicStroke.CAP_ROUND,
                                                   BasicStroke.JOIN_ROUND ) ) ;
                    paintGlyph( g2 ) ;
                }
            }
        }
        finally {
            g2.dispose() ;
        }
    }

    /** The white mark on a filled badge. */
    private void paintGlyph( Graphics2D g2 ) {

        switch( this ) {
            case APPROVED -> {
                Path2D tick = new Path2D.Double() ;
                tick.moveTo( 4, 7.2 ) ;
                tick.lineTo( 6.2, 9.5 ) ;
                tick.lineTo( 10, 4.8 ) ;
                g2.draw( tick ) ;
            }
            case STALE -> {
                g2.draw( new Line2D.Double( 7, 3.8, 7, 7.8 ) ) ;
                g2.fill( new Ellipse2D.Double( 6, 9.3, 2, 2 ) ) ;
            }
            case DRAFT -> {
                // a pencil stroke, tip at the lower left
                g2.draw( new Line2D.Double( 4.5, 9.5, 9.5, 4.5 ) ) ;
                g2.fill( new Ellipse2D.Double( 3.4, 9, 1.8, 1.8 ) ) ;
            }
            default -> { }
        }
    }
}
