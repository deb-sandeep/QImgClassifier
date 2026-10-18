package com.sandy.sconsole.qimgtools.apps.topicreviewer.ui;

import javax.swing.Icon;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

/**
 * The reviewer's toolbar and menu icons, drawn rather than loaded. Each is
 * painted grey on a disabled component, since Swing only greys out image
 * icons by itself.
 */
enum ToolIcon implements Icon {

    CRITERIA( new Color( 0x455A64 ) ),
    LOAD    ( new Color( 0x1565C0 ) ),
    START   ( new Color( 0x2E7D32 ) ),
    STOP    ( new Color( 0xC62828 ) ),
    REQUEUE ( new Color( 0xEF6C00 ) ),
    USE_AI  ( new Color( 0x6A1B9A ) ),

    /** Red: it is enabled only while decisions wait to be saved. */
    SAVE    ( new Color( 0xC62828 ) ),
    CLEAR   ( new Color( 0x455A64 ) ) ;

    /** Width and height of the icons in pixels. */
    static final int SIZE = 16 ;

    private static final Color DISABLED = new Color( 0xB0B0B0 ) ;

    private final Color color ;

    ToolIcon( Color color ) {
        this.color = color ;
    }

    @Override public int getIconWidth()  { return SIZE ; }
    @Override public int getIconHeight() { return SIZE ; }

    @Override
    public void paintIcon( Component c, Graphics g, int x, int y ) {

        Graphics2D g2      = (Graphics2D) g.create() ;
        Color      greyed  = UIManager.getColor( "Label.disabledForeground" ) ;
        boolean    enabled = c == null || c.isEnabled() ;

        try {
            g2.setRenderingHint( RenderingHints.KEY_ANTIALIASING,
                                 RenderingHints.VALUE_ANTIALIAS_ON ) ;
            g2.translate( x, y ) ;
            g2.setColor( enabled ? color
                                 : greyed != null ? greyed : DISABLED ) ;
            g2.setStroke( new BasicStroke( 1.8f, BasicStroke.CAP_ROUND,
                                           BasicStroke.JOIN_ROUND ) ) ;
            switch( this ) {
                case CRITERIA -> paintFunnel( g2 ) ;
                case LOAD     -> paintDownload( g2 ) ;
                case START    -> g2.fill( polygon( 4, 2.5, 13.5, 8, 4, 13.5 ) ) ;
                case STOP     -> g2.fill( new RoundRectangle2D.Double(
                                                  3, 3, 10, 10, 3, 3 ) ) ;
                case REQUEUE  -> paintRetry( g2 ) ;
                case USE_AI   -> paintSparkle( g2 ) ;
                case SAVE     -> paintDisk( g2 ) ;
                case CLEAR    -> paintUndo( g2 ) ;
            }
        }
        finally {
            g2.dispose() ;
        }
    }

    /** A funnel - the criteria Load filters the review queue by. */
    private static void paintFunnel( Graphics2D g2 ) {
        g2.fill( polygon( 1.5, 2.5, 14.5, 2.5, 9.5, 8.5,
                          9.5, 14, 6.5, 12.5, 6.5, 8.5 ) ) ;
    }

    /** An arrow into a tray - fetching from the server. */
    private static void paintDownload( Graphics2D g2 ) {

        g2.fill( new Rectangle2D.Double( 7, 1.5, 2, 6.5 ) ) ;
        g2.fill( polygon( 3.5, 7.5, 12.5, 7.5, 8, 12 ) ) ;

        Path2D tray = new Path2D.Double() ;
        tray.moveTo( 2, 10.5 ) ;
        tray.lineTo( 2, 14 ) ;
        tray.lineTo( 14, 14 ) ;
        tray.lineTo( 14, 10.5 ) ;
        g2.draw( tray ) ;
    }

    /** A circling arrow - sending the failed questions again. */
    private static void paintRetry( Graphics2D g2 ) {
        g2.draw( new Arc2D.Double( 2.5, 2.5, 11, 11, 90, -270,
                                   Arc2D.OPEN ) ) ;
        g2.fill( polygon( 0, 9, 5, 9, 2.5, 5 ) ) ;
    }

    /** A four-pointed star - the model's recommendation. */
    private static void paintSparkle( Graphics2D g2 ) {
        g2.fill( polygon( 8, 1, 9.6, 6.4, 15, 8, 9.6, 9.6,
                          8, 15, 6.4, 9.6, 1, 8, 6.4, 6.4 ) ) ;
    }

    /** A floppy disk - saving the decisions. */
    private static void paintDisk( Graphics2D g2 ) {

        Area disk = new Area( new RoundRectangle2D.Double(
                1.5, 1.5, 13, 13, 3, 3 ) ) ;

        disk.subtract( new Area( new Rectangle2D.Double( 5, 1.5, 6, 4 ) ) ) ;
        disk.subtract( new Area( new Rectangle2D.Double( 4, 8.5, 8, 4.5 ) ) ) ;
        g2.fill( disk ) ;
    }

    /** A hooked arrow pointing back - undoing the decisions. */
    private static void paintUndo( Graphics2D g2 ) {

        g2.draw( new Arc2D.Double( 4.5, 4.5, 9, 9, 90, -180, Arc2D.OPEN ) ) ;
        g2.draw( new Line2D.Double( 9, 4.5, 5.5, 4.5 ) ) ;
        g2.draw( new Line2D.Double( 9, 13.5, 4, 13.5 ) ) ;
        g2.fill( polygon( 2, 4.5, 6, 1.5, 6, 7.5 ) ) ;
    }

    /** A closed polygon through the points {@code x0, y0, x1, y1, ...}. */
    private static Path2D polygon( double... xy ) {

        Path2D p = new Path2D.Double() ;

        p.moveTo( xy[0], xy[1] ) ;
        for( int i = 2 ; i < xy.length ; i += 2 ) p.lineTo( xy[i], xy[i + 1] ) ;
        p.closePath() ;
        return p ;
    }
}
