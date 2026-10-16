package com.sandy.sconsole.qimgclassifier.ui;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.TextLayout;
import java.awt.geom.Rectangle2D;

/**
 * A small circled "T" marking the manual topic association button; grey when
 * its component is disabled.
 */
final class TopicIcon implements Icon {

    /** Width and height of the icon in pixels. */
    static final int SIZE = 11 ;

    private static final Color ENABLED  = new Color( 0x1565C0 ) ;
    private static final Color DISABLED = new Color( 0xB0B0B0 ) ;

    @Override public int getIconWidth()  { return SIZE ; }
    @Override public int getIconHeight() { return SIZE ; }

    @Override
    public void paintIcon( Component c, Graphics g, int x, int y ) {

        Graphics2D g2 = (Graphics2D) g.create() ;
        try {
            g2.setRenderingHint( RenderingHints.KEY_ANTIALIASING,
                                 RenderingHints.VALUE_ANTIALIAS_ON ) ;
            g2.setRenderingHint( RenderingHints.KEY_TEXT_ANTIALIASING,
                                 RenderingHints.VALUE_TEXT_ANTIALIAS_ON ) ;
            g2.setColor( c.isEnabled() ? ENABLED : DISABLED ) ;
            g2.setStroke( new BasicStroke( 1.2f ) ) ;
            g2.drawOval( x, y, SIZE - 1, SIZE - 1 ) ;
            g2.setFont( c.getFont().deriveFont( Font.BOLD, SIZE * 0.6f ) ) ;
            // Centre on the glyph's actual bounds so the T sits in the middle
            // of the circle.
            Rectangle2D b = new TextLayout( "T", g2.getFont(),
                                            g2.getFontRenderContext() ).getBounds() ;
            float tx = x + ( SIZE - (float) b.getWidth() ) / 2f - (float) b.getX() ;
            float ty = y + ( SIZE - (float) b.getHeight() ) / 2f - (float) b.getY() ;

            g2.drawString( "T", tx, ty ) ;
        }
        finally {
            g2.dispose() ;
        }
    }
}
