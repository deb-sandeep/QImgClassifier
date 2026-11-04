package com.sandy.sconsole.qimgtools.apps.tagger.ui;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.TransferHandler;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.datatransfer.Transferable;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * A tag in a rounded box. A badge may be dragged ({@link #draggable}),
 * carrying its tag to a drop target ({@link #dropTarget}), clicked
 * ({@link #clickable}), and removed with a click on its ×
 * ({@link #removable}). Use on the EDT.
 */
@SuppressWarnings( "serial" )
final class TagBadge extends JLabel {

    private static final Color FILL     = new Color( 0xD6E9FB ) ;
    private static final Color LINE     = FILL.darker().darker() ;
    private static final float BADGE_PT = 11.5f ;
    private static final int   ARC      = 10 ;

    /** Pixels the mouse must move with its button down to start a drag. */
    private static final int DRAG_SLOP = 3 ;

    /** The × at the end of a removable badge. */
    private static final String CROSS = "  ×" ;

    /** Painted faded: e.g. a tag already chosen. */
    private boolean dimmed ;

    TagBadge( String text, String tip ) {

        super( text ) ;
        setFont( getFont().deriveFont( Font.PLAIN, BADGE_PT ) ) ;
        setForeground( LINE.darker() ) ;
        setBorder( BorderFactory.createEmptyBorder( 2, 7, 2, 7 ) ) ;
        setToolTipText( tip ) ;
    }

    /** Let the badge be dragged, carrying {@code tag}. */
    TagBadge draggable( String tag ) {

        setTransferHandler( new TransferHandler() {
            @Override public int getSourceActions( JComponent c ) {
                return COPY ;
            }

            @Override protected Transferable createTransferable( JComponent c ) {
                return new StringSelection( tag ) ;
            }
        } ) ;

        MouseAdapter drag = new MouseAdapter() {
            private MouseEvent press ;

            @Override public void mousePressed( MouseEvent e ) {
                press = e ;
            }

            @Override public void mouseReleased( MouseEvent e ) {
                press = null ;
            }

            @Override public void mouseDragged( MouseEvent e ) {

                if( press == null ) return ;
                if( Math.abs( e.getX() - press.getX() ) > DRAG_SLOP
                    || Math.abs( e.getY() - press.getY() ) > DRAG_SLOP ) {
                    getTransferHandler().exportAsDrag( TagBadge.this, press,
                                                       TransferHandler.COPY ) ;
                    press = null ;
                }
            }
        } ;
        addMouseListener( drag ) ;
        addMouseMotionListener( drag ) ;
        setCursor( Cursor.getPredefinedCursor( Cursor.HAND_CURSOR ) ) ;
        return this ;
    }

    /**
     * Run {@code onClick} on a click on the badge (a press and release
     * without a drag).
     */
    TagBadge clickable( Runnable onClick ) {

        addMouseListener( new MouseAdapter() {
            @Override public void mouseClicked( MouseEvent e ) {
                if( e.getButton() == MouseEvent.BUTTON1 ) onClick.run() ;
            }
        } ) ;
        setCursor( Cursor.getPredefinedCursor( Cursor.HAND_CURSOR ) ) ;
        return this ;
    }

    /**
     * End the badge with a ×; a click on it runs {@code onRemove}, and the
     * mouse turns into a hand over it.
     */
    TagBadge removable( Runnable onRemove ) {

        setText( getText() + CROSS ) ;
        MouseAdapter cross = new MouseAdapter() {
            @Override public void mouseClicked( MouseEvent e ) {
                if( onCross( e ) ) onRemove.run() ;
            }

            @Override public void mouseMoved( MouseEvent e ) {
                setCursor( Cursor.getPredefinedCursor(
                        onCross( e ) ? Cursor.HAND_CURSOR
                                     : Cursor.DEFAULT_CURSOR ) ) ;
            }
        } ;
        addMouseListener( cross ) ;
        addMouseMotionListener( cross ) ;
        return this ;
    }

    /** True if {@code e} is over the × at the badge's end. */
    private boolean onCross( MouseEvent e ) {
        int cross = getFontMetrics( getFont() ).stringWidth( CROSS )
                    + getInsets().right ;
        return e.getX() >= getWidth() - cross ;
    }

    /** Paint the badge faded, or not. */
    TagBadge dimmed( boolean dimmed ) {
        this.dimmed = dimmed ;
        setForeground( dimmed ? LINE : LINE.darker() ) ;
        return this ;
    }

    /** True if the badge is painted faded. */
    boolean isDimmed() {
        return dimmed ;
    }

    /**
     * A transfer handler that takes the badges dropped on its component,
     * handing the tag each carries to {@code drop}.
     */
    static TransferHandler dropTarget( Consumer<String> drop ) {
        return new TransferHandler() {
            @Override public boolean canImport( TransferSupport s ) {

                if( !s.isDataFlavorSupported( DataFlavor.stringFlavor ) ) {
                    return false ;
                }
                if( ( s.getSourceDropActions() & COPY ) != 0 ) {
                    s.setDropAction( COPY ) ;
                }
                return true ;
            }

            @Override public boolean importData( TransferSupport s ) {

                if( !canImport( s ) ) return false ;
                try {
                    drop.accept( (String) s.getTransferable().getTransferData(
                            DataFlavor.stringFlavor ) ) ;
                    return true ;
                }
                catch( UnsupportedFlavorException | IOException e ) {
                    return false ;
                }
            }
        } ;
    }

    @Override
    protected void paintComponent( Graphics g ) {

        Graphics2D g2 = (Graphics2D) g.create() ;
        int        w  = getWidth() - 1 ;
        int        h  = getHeight() - 1 ;

        g2.setRenderingHint( RenderingHints.KEY_ANTIALIASING,
                             RenderingHints.VALUE_ANTIALIAS_ON ) ;
        g2.setColor( dimmed ? blend( FILL, Color.WHITE ) : FILL ) ;
        g2.fillRoundRect( 0, 0, w, h, ARC, ARC ) ;
        g2.setColor( dimmed ? FILL : LINE ) ;
        g2.setStroke( new BasicStroke( 1f ) ) ;
        g2.drawRoundRect( 0, 0, w, h, ARC, ARC ) ;
        g2.dispose() ;
        super.paintComponent( g ) ;
    }

    /** Halfway between {@code a} and {@code b}. */
    private static Color blend( Color a, Color b ) {
        return new Color( ( a.getRed() + b.getRed() ) / 2,
                          ( a.getGreen() + b.getGreen() ) / 2,
                          ( a.getBlue() + b.getBlue() ) / 2 ) ;
    }
}
