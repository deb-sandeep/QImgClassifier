package com.sandy.sconsole.qimgtools.core.util;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * A question's image made smaller before it is sent: scaled down to a width
 * (aspect kept) and/or turned to greyscale, as PNG. With neither, the image
 * is sent as {@link ImageCollator} makes it. Stateless.
 *
 * @param width     the width to scale a wider image down to; 0 for none
 * @param greyscale turn the image to greyscale
 */
public record ImagePrep( int width, boolean greyscale ) {

    /** True if the image is sent as it is. */
    public boolean isOff() {
        return width <= 0 && !greyscale ;
    }

    /** The bytes to send for {@code imgFiles}. */
    public byte[] bytes( List<File> imgFiles ) throws IOException {

        if( isOff() ) return ImageCollator.collatedBytes( imgFiles ) ;

        BufferedImage img = ImageCollator.collate( imgFiles ) ;
        int           w   = img.getWidth() ;
        int           h   = img.getHeight() ;

        if( width > 0 && w > width ) {
            h = Math.max( 1, (int)Math.round( h * (double)width / w ) ) ;
            w = width ;
        }
        BufferedImage out = new BufferedImage(
                w, h, greyscale ? BufferedImage.TYPE_BYTE_GRAY
                                : BufferedImage.TYPE_INT_RGB ) ;
        Graphics2D g = out.createGraphics() ;
        try {
            g.setRenderingHint( RenderingHints.KEY_INTERPOLATION,
                                RenderingHints.VALUE_INTERPOLATION_BICUBIC ) ;
            g.drawImage( img, 0, 0, w, h, Color.WHITE, null ) ;
        }
        finally {
            g.dispose() ;
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream() ;
        ImageIO.write( out, "png", bos ) ;
        return bos.toByteArray() ;
    }

    /** The media type of {@link #bytes}. */
    public String mimeType( List<File> imgFiles ) {
        return isOff() ? ImageCollator.collatedMimeType( imgFiles )
                       : "image/png" ;
    }
}
