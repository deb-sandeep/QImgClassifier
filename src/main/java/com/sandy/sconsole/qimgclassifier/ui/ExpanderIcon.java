package com.sandy.sconsole.qimgclassifier.ui;

import lombok.RequiredArgsConstructor;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;

/**
 * Fixed-width tree triangle (▸ collapsed, ▾ expanded); blank for leaves so
 * labels stay aligned.
 */
@RequiredArgsConstructor
final class ExpanderIcon implements Icon {

    /** Width and height of the icon in pixels. */
    static final int SIZE = 12 ;

    private final boolean expandable ;
    private final boolean expanded ;

    @Override public int getIconWidth()  { return SIZE ; }
    @Override public int getIconHeight() { return SIZE ; }

    @Override
    public void paintIcon( Component c, Graphics g, int x, int y ) {

        if( !expandable ) return ;
        Graphics2D g2 = (Graphics2D) g.create() ;
        try {
            g2.setRenderingHint( RenderingHints.KEY_ANTIALIASING,
                                 RenderingHints.VALUE_ANTIALIAS_ON ) ;
            g2.setColor( Color.DARK_GRAY ) ;
            int s = SIZE ;
            Polygon p = expanded
                    ? new Polygon( new int[]{ x + 2, x + s - 2, x + s / 2 },
                                   new int[]{ y + 3, y + 3, y + s - 3 }, 3 )
                    : new Polygon( new int[]{ x + 3, x + s - 3, x + 3 },
                                   new int[]{ y + 2, y + s / 2, y + s - 2 }, 3 ) ;
            g2.fillPolygon( p ) ;
        }
        finally {
            g2.dispose() ;
        }
    }
}
