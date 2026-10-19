package com.sandy.sconsole.qimgtools.core.ui;

import com.sandy.sconsole.qimgtools.core.util.ImageCollator;

import lombok.extern.slf4j.Slf4j;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Point;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.Objects;

/**
 * A titled panel showing a question's image, its parts stacked into one
 * ({@link ImageCollator}), either scaled to fit or scaled to a fixed width
 * in a scroll pane. Images load off the EDT; a newer {@link #show}
 * supersedes a slow load. Use on the EDT.
 */
@Slf4j
@SuppressWarnings( "serial" )
public final class ImagePreview extends JPanel {

    private final JLabel label =
            new JLabel( "Select a row to preview", SwingConstants.CENTER ) ;

    /** Width the image is scaled to, scrolling; 0 to fit the panel. */
    private final int imageWidth ;

    /** The label's scroll pane; null when the image is scaled to fit. */
    private JScrollPane scroll ;

    /** Full-resolution image shown, and the file(s) it was collated from. */
    private BufferedImage image ;
    private List<File>    files ;

    /**
     * Bumped on every {@link #show}; a load whose token is stale is ignored.
     */
    private long token ;

    /** A preview {@code height} pixels high that scales images to fit. */
    public ImagePreview( int height ) {
        this( height, 0 ) ;
    }

    /**
     * A preview {@code height} pixels high that scales images to
     * {@code imageWidth} pixels wide, keeping their aspect ratio, in a
     * scroll pane; 0 scales them to fit instead.
     */
    public ImagePreview( int height, int imageWidth ) {

        super( new BorderLayout() ) ;
        this.imageWidth = imageWidth ;
        setBorder( BorderFactory.createTitledBorder( "Preview" ) ) ;
        setPreferredSize( new Dimension( 100, height ) ) ;
        if( imageWidth > 0 ) {
            scroll = new JScrollPane( label ) ;
            label.setVerticalAlignment( SwingConstants.TOP ) ;
            scroll.setBorder( BorderFactory.createEmptyBorder() ) ;
            scroll.getVerticalScrollBar().setUnitIncrement( 16 ) ;
            scroll.getHorizontalScrollBar().setUnitIncrement( 16 ) ;
            add( scroll, BorderLayout.CENTER ) ;
            return ;
        }
        add( label, BorderLayout.CENTER ) ;
        addComponentListener( new ComponentAdapter() {
            @Override public void componentResized( ComponentEvent e ) {
                rescale() ;
            }
        } ) ;
    }

    /**
     * Load and show {@code files} (collating multi-part questions); null
     * clears the preview.
     */
    public void show( List<File> files ) {

        if( Objects.equals( files, this.files ) ) return ;

        this.files = files ;
        this.image = null ;
        long t = ++token ;

        label.setIcon( null ) ;
        if( files == null ) {
            label.setText( "Select a question to preview" ) ;
            return ;
        }
        if( !files.stream().allMatch( File::isFile ) ) {
            label.setText( "Image not found" ) ;
            return ;
        }
        label.setText( "Loading…" ) ;

        new Thread( () -> {
            BufferedImage img = null ;
            try {
                img = ImageCollator.collate( files ) ;
            }
            catch( Exception ex ) {
                log.warn( "Could not read preview image {}", files, ex ) ;
            }
            BufferedImage loaded = img ;
            SwingUtilities.invokeLater( () -> {
                if( t != token ) return ;   // a newer selection won
                image = loaded ;
                if( loaded == null ) {
                    label.setIcon( null ) ;
                    label.setText( "Could not load image" ) ;
                }
                else {
                    label.setText( null ) ;
                    rescale() ;
                }
            } ) ;
        }, "preview-loader" ).start() ;
    }

    /**
     * Scale {@link #image} to {@link #imageWidth}, else to fit inside the
     * panel without overflow, preserving its aspect ratio.
     */
    private void rescale() {

        if( image == null ) return ;
        if( imageWidth > 0 ) {
            int h = Math.max( 1, (int) Math.round(
                    (double) image.getHeight() * imageWidth /
                    image.getWidth() ) ) ;

            label.setIcon( new ImageIcon( image.getScaledInstance(
                    imageWidth, h, Image.SCALE_SMOOTH ) ) ) ;
            label.setText( null ) ;
            scroll.getViewport().setViewPosition( new Point( 0, 0 ) ) ;
            return ;
        }

        Insets in = getInsets() ;
        int availW = getWidth()  - in.left - in.right  - 8 ;
        int availH = getHeight() - in.top  - in.bottom - 8 ;

        if( availW <= 0 || availH <= 0 ) return ;

        int iw = image.getWidth() ;
        int ih = image.getHeight() ;
        double scale = Math.min( (double) availW / iw, (double) availH / ih ) ;
        scale = Math.min( scale, 3.0 ) ;   // allow modest upscaling of narrow strips

        int w = Math.max( 1, (int) Math.round( iw * scale ) ) ;
        int h = Math.max( 1, (int) Math.round( ih * scale ) ) ;

        label.setIcon( new ImageIcon(
                image.getScaledInstance( w, h, Image.SCALE_SMOOTH ) ) ) ;
        label.setText( null ) ;
    }
}
