package com.sandy.sconsole.qimgclassifier.util;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the single image that represents a question: the file itself for a
 * plain question, or its part images stacked top-to-bottom (in the given order,
 * left-aligned on a white background) for a multi-part question.
 */
@NoArgsConstructor( access = AccessLevel.PRIVATE )
public final class ImageCollator {

    /**
     * The question as one image: the decoded file itself, or the parts stacked
     * top-to-bottom.
     *
     * @throws IOException if any file cannot be read or decoded
     */
    public static BufferedImage collate( List<File> imgFiles ) throws IOException {

        List<BufferedImage> parts = new ArrayList<>() ;
        int width = 0, height = 0 ;

        for( File f : imgFiles ) {
            BufferedImage img = ImageIO.read( f ) ;
            if( img == null ) throw new IOException( "Unreadable image " + f ) ;
            parts.add( img ) ;
            width   = Math.max( width, img.getWidth() ) ;
            height += img.getHeight() ;
        }
        if( parts.size() == 1 ) return parts.get( 0 ) ;

        BufferedImage out = new BufferedImage( width, height,
                                               BufferedImage.TYPE_INT_RGB ) ;
        Graphics2D g = out.createGraphics() ;

        try {
            g.setColor( Color.WHITE ) ;
            g.fillRect( 0, 0, width, height ) ;
            int y = 0 ;
            for( BufferedImage img : parts ) {
                g.drawImage( img, 0, y, null ) ;
                y += img.getHeight() ;
            }
        }
        finally {
            g.dispose() ;
        }
        return out ;
    }

    /**
     * Image bytes to send to the model: the original file as-is, or the
     * collated parts as PNG.
     */
    public static byte[] collatedBytes( List<File> imgFiles ) throws IOException {

        if( imgFiles.size() == 1 ) {
            return Files.readAllBytes( imgFiles.get( 0 ).toPath() ) ;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream() ;
        ImageIO.write( collate( imgFiles ), "png", bos ) ;
        return bos.toByteArray() ;
    }

    /**
     * Media type of {@link #collatedBytes}: PNG when collated, otherwise from
     * the file extension.
     */
    public static String collatedMimeType( List<File> imgFiles ) {

        if( imgFiles.size() != 1 ) return "image/png" ;
        String name = imgFiles.get( 0 ).getName().toLowerCase() ;
        if( name.endsWith( ".jpg" ) || name.endsWith( ".jpeg" ) ) {
            return "image/jpeg" ;
        }
        if( name.endsWith( ".gif" ) )  return "image/gif" ;
        if( name.endsWith( ".bmp" ) )  return "image/bmp" ;
        return "image/png" ;
    }
}
