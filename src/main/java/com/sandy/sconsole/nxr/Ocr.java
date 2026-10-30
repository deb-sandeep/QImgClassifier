package com.sandy.sconsole.nxr;

import com.fasterxml.jackson.databind.JsonNode;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Tesseract run on a question image (spec §4.2): the image is written to a
 * temporary PNG, read by {@code <bin> <png> stdout --psm <psm> -l <lang>},
 * and deleted. The settings come from the model file's {@code ocr}, so they
 * are the trainer's. Thread-safe: each call runs its own process.
 */
final class Ocr {

    /** The text read, or why there is none. */
    record Text( String text, String failure ) {}

    private final Path   bin ;
    private final int    psm ;
    private final String lang ;
    private final int    timeoutSeconds ;
    private final int    minChars ;

    Ocr( JsonNode model, Path bin ) throws NXRException {

        JsonNode ocr = ModelFile.node( model, "ocr" ) ;

        this.bin            = bin ;
        this.psm            = (int) ModelFile.number( ocr, "psm" ) ;
        this.lang           = ModelFile.text( ocr, "lang" ) ;
        this.timeoutSeconds = (int) ModelFile.number( ocr, "timeoutSeconds" ) ;
        this.minChars       = (int) ModelFile.number( ocr, "minChars" ) ;
    }

    /** Fewer characters than this after cleaning: the OCR read nothing. */
    int minChars() {
        return minChars ;
    }

    /**
     * Check that tesseract runs: it must read some text off a drawn test
     * image.
     */
    void check() throws NXRException {

        if( !Files.isExecutable( bin ) ) {
            throw new NXRException( "tesseract not found at " + bin ) ;
        }

        BufferedImage img = new BufferedImage( 600, 100,
                                               BufferedImage.TYPE_INT_RGB ) ;
        Graphics2D    g   = img.createGraphics() ;

        g.setColor( Color.WHITE ) ;
        g.fillRect( 0, 0, 600, 100 ) ;
        g.setColor( Color.BLACK ) ;
        g.setFont( new Font( Font.SANS_SERIF, Font.PLAIN, 40 ) ) ;
        g.drawString( "Find the value of x", 20, 65 ) ;
        g.dispose() ;

        Text t = read( img ) ;
        if( t.failure() != null ) {
            throw new NXRException( "tesseract failed on its test image: " +
                                    t.failure() ) ;
        }
        if( t.text().isBlank() ) {
            throw new NXRException( "tesseract read nothing off its test " +
                                    "image" ) ;
        }
    }

    /** The raw text tesseract reads off {@code image}. */
    Text read( BufferedImage image ) {

        Path tmp = null ;
        try {
            tmp = Files.createTempFile( "nxr-", ".png" ) ;
            if( !ImageIO.write( rgb( image ), "png", tmp.toFile() ) ) {
                return new Text( null, "no PNG writer" ) ;
            }
            return run( tmp ) ;
        }
        catch( IOException e ) {
            return new Text( null, e.getMessage() ) ;
        }
        catch( InterruptedException e ) {
            Thread.currentThread().interrupt() ;
            return new Text( null, "interrupted" ) ;
        }
        finally {
            if( tmp != null ) {
                try { Files.deleteIfExists( tmp ) ; }
                catch( IOException ignored ) {}
            }
        }
    }

    /** Run tesseract on {@code png}, its output to a temporary file. */
    private Text run( Path png ) throws IOException, InterruptedException {

        Path txt = Files.createTempFile( "nxr-", ".txt" ) ;
        try {
            Process p = new ProcessBuilder( List.of(
                    bin.toString(), png.toString(), "stdout",
                    "--psm", String.valueOf( psm ), "-l", lang ) )
                    .redirectOutput( txt.toFile() )
                    .redirectError( ProcessBuilder.Redirect.DISCARD )
                    .start() ;

            if( !p.waitFor( timeoutSeconds, TimeUnit.SECONDS ) ) {
                p.destroyForcibly() ;
                return new Text( null, "timed out after " + timeoutSeconds +
                                       " s" ) ;
            }
            if( p.exitValue() != 0 ) {
                return new Text( null, "tesseract exit code " +
                                       p.exitValue() ) ;
            }
            // Undecodable bytes become U+FFFD rather than failing the question.
            return new Text( new String( Files.readAllBytes( txt ),
                                         StandardCharsets.UTF_8 ), null ) ;
        }
        finally {
            Files.deleteIfExists( txt ) ;
        }
    }

    /**
     * {@code image} without alpha, its colour channels kept as they are,
     * as the trainer's {@code Image.convert( "RGB" )} does.
     */
    private static BufferedImage rgb( BufferedImage image ) {

        if( !image.getColorModel().hasAlpha() ) return image ;

        int           w   = image.getWidth() ;
        int           h   = image.getHeight() ;
        BufferedImage out = new BufferedImage( w, h,
                                               BufferedImage.TYPE_INT_RGB ) ;

        for( int y = 0 ; y < h ; y++ ) {
            for( int x = 0 ; x < w ; x++ ) {
                out.setRGB( x, y, image.getRGB( x, y ) & 0xFFFFFF ) ;
            }
        }
        return out ;
    }
}
