package com.sandy.sconsole.qimgclassifier.ui;

import com.sandy.sconsole.qimgclassifier.model.AIAgent;

import lombok.RequiredArgsConstructor;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.EnumMap;
import java.util.Map;

/**
 * Marks which AI backend handled a question: a monitor for the local Gemma
 * server, a cloud for OpenRouter. Shapes differ as well as colours, so the
 * two stay distinguishable at a glance.
 */
@RequiredArgsConstructor
final class AgentIcon implements Icon {

    /** Width and height of the icon in pixels. */
    static final int SIZE = 14 ;

    private static final Color GEMMA_COLOR      = new Color( 0x2E7D32 ) ;
    private static final Color OPENROUTER_COLOR = new Color( 0x6A1B9A ) ;

    private static final Map<AIAgent,AgentIcon> ICONS = new EnumMap<>( AIAgent.class ) ;

    static {
        for( AIAgent agent : AIAgent.values() ) {
            ICONS.put( agent, new AgentIcon( agent ) ) ;
        }
    }

    private final AIAgent agent ;

    /** The shared icon for {@code agent}. */
    static AgentIcon of( AIAgent agent ) {
        return ICONS.get( agent ) ;
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
            switch( agent ) {
                case GEMMA      -> paintMonitor( g2 ) ;
                case OPENROUTER -> paintCloud( g2 ) ;
            }
        }
        finally {
            g2.dispose() ;
        }
    }

    /** A desktop monitor on a stand - the locally hosted model. */
    private static void paintMonitor( Graphics2D g2 ) {

        g2.setColor( GEMMA_COLOR ) ;
        g2.fill( new RoundRectangle2D.Float( 1, 2, 12, 8, 3, 3 ) ) ;
        g2.setColor( Color.WHITE ) ;
        g2.fill( new Rectangle2D.Float( 2.5f, 3.5f, 9, 5 ) ) ;
        g2.setColor( GEMMA_COLOR ) ;
        g2.fill( new Rectangle2D.Float( 6, 10, 2, 2 ) ) ;
        g2.fill( new Rectangle2D.Float( 4, 12, 6, 1.5f ) ) ;
    }

    /** A cloud - the hosted model reached over the internet. */
    private static void paintCloud( Graphics2D g2 ) {

        Area cloud = new Area( new Ellipse2D.Float( 0.5f, 5.5f, 6, 6 ) ) ;
        cloud.add( new Area( new Ellipse2D.Float( 3.5f, 2, 7.5f, 7.5f ) ) ) ;
        cloud.add( new Area( new Ellipse2D.Float( 7.5f, 5, 6, 6 ) ) ) ;
        cloud.add( new Area( new Rectangle2D.Float( 3.5f, 7.5f, 7, 4 ) ) ) ;

        g2.setColor( OPENROUTER_COLOR ) ;
        g2.fill( cloud ) ;
    }
}
