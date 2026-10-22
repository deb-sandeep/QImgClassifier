package com.sandy.sconsole.qimgtools.core.ui;

import javax.swing.AbstractButton;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.HashSet;
import java.util.Set;

/**
 * A spinning wait indicator: an icon whose phase follows the clock, and a
 * shared timer that repaints the components showing it. {@link #busy} and
 * {@link #idle} put it on a button and take it off again. EDT only.
 */
public final class Spinner implements Icon {

    public static final Spinner ICON = new Spinner( 14 ) ;

    private static final int    SPOKES     = 8 ;
    private static final int    FRAME_MS   = 100 ;
    private static final String IDLE_ICON  = "spinner.idleIcon" ;
    private static final String IDLE_DICON = "spinner.idleDisabledIcon" ;

    private static final Set<JComponent> spinning = new HashSet<>() ;
    private static final Timer           timer    = new Timer( FRAME_MS,
            e -> spinning.forEach( JComponent::repaint ) ) ;

    private final int size ;

    private Spinner( int size ) {
        this.size = size ;
    }

    /** Keep repainting {@code c} until {@link #halt}. */
    public static void spin( JComponent c ) {
        if( spinning.add( c ) ) timer.start() ;
    }

    public static void halt( JComponent c ) {
        spinning.remove( c ) ;
        if( spinning.isEmpty() ) timer.stop() ;
    }

    /**
     * Show the spinner on {@code b}, enabled or not, until {@link #idle}.
     * Calling it on a button already busy does nothing.
     */
    public static void busy( AbstractButton b ) {

        if( b.getClientProperty( IDLE_ICON ) != null ) return ;
        b.putClientProperty( IDLE_ICON,  new Object[]{ b.getIcon() } ) ;
        b.putClientProperty( IDLE_DICON, new Object[]{ b.getDisabledIcon() } ) ;
        b.setIcon( ICON ) ;
        b.setDisabledIcon( ICON ) ;
        spin( b ) ;
    }

    /** Take the spinner off {@code b}, restoring its icons. */
    public static void idle( AbstractButton b ) {

        Object[] icon  = (Object[]) b.getClientProperty( IDLE_ICON ) ;
        Object[] dicon = (Object[]) b.getClientProperty( IDLE_DICON ) ;
        if( icon == null ) return ;

        b.putClientProperty( IDLE_ICON, null ) ;
        b.putClientProperty( IDLE_DICON, null ) ;
        b.setIcon( (Icon) icon[0] ) ;
        b.setDisabledIcon( (Icon) dicon[0] ) ;
        halt( b ) ;
    }

    @Override
    public void paintIcon( Component c, Graphics g, int x, int y ) {

        Graphics2D g2    = (Graphics2D) g.create() ;
        Color      base  = c.getForeground() != null ? c.getForeground()
                                                     : Color.DARK_GRAY ;
        int        phase = (int) ( System.currentTimeMillis() / FRAME_MS
                                   % SPOKES ) ;
        double     mid   = size / 2.0 ;

        g2.setRenderingHint( RenderingHints.KEY_ANTIALIASING,
                             RenderingHints.VALUE_ANTIALIAS_ON ) ;
        g2.setStroke( new BasicStroke( size / 7f, BasicStroke.CAP_ROUND,
                                       BasicStroke.JOIN_ROUND ) ) ;
        g2.translate( x + mid, y + mid ) ;
        for( int i = 0 ; i < SPOKES ; i++ ) {
            // The spoke at the phase is darkest, those behind it fade.
            int age   = ( phase - i + SPOKES ) % SPOKES ;
            int alpha = 255 - age * ( 255 - 40 ) / ( SPOKES - 1 ) ;

            g2.setColor( new Color( base.getRed(), base.getGreen(),
                                    base.getBlue(), alpha ) ) ;
            g2.drawLine( 0, (int) ( -mid * 0.45 ), 0, (int) ( -mid * 0.9 ) ) ;
            g2.rotate( 2 * Math.PI / SPOKES ) ;
        }
        g2.dispose() ;
    }

    @Override
    public int getIconWidth() { return size ; }

    @Override
    public int getIconHeight() { return size ; }
}
