package com.sandy.sconsole.qimgtools.apps.tagger.ocr;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Reads a question's text the way its regression model was trained
 * ({@code scripts/start_cycle.py} in the tag workspace, whose settings must not
 * drift from these): {@code tesseract <image> stdout --psm 6} with no language
 * option and {@code OMP_THREAD_LIMIT=1}, on each image file as it is, in the
 * order given, each result followed by a newline. Thread-safe: each image gets
 * its own process.
 */
public final class QuestionOcr {

    /** The text read, or why there is none. */
    public record Result( String text, String failure ) {

        static Result failed( String why ) {
            return new Result( null, why ) ;
        }
    }

    private final File     bin ;
    private final Duration timeout ;

    /** @param timeout how long tesseract may take over one image */
    public QuestionOcr( File bin, Duration timeout ) {
        this.bin     = bin ;
        this.timeout = timeout ;
    }

    /** True if the tesseract given can be run. */
    public boolean isUsable() {
        return bin.canExecute() ;
    }

    /** The text of {@code images}; a failure on any of them fails the whole. */
    public Result read( List<File> images ) {

        StringBuilder text = new StringBuilder() ;
        for( File image : images ) {
            Path out = null ;
            try {
                out = Files.createTempFile( "qtagger-ocr-", ".txt" ) ;
                String failure = run( image, out ) ;
                if( failure != null ) {
                    return Result.failed( image.getName() + ": " + failure ) ;
                }
                text.append( Files.readString( out, StandardCharsets.UTF_8 ) )
                    .append( '\n' ) ;
            }
            catch( IOException e ) {
                return Result.failed( image.getName() + ": " + e.getMessage() ) ;
            }
            catch( InterruptedException e ) {
                Thread.currentThread().interrupt() ;
                return Result.failed( "interrupted" ) ;
            }
            finally {
                delete( out ) ;
            }
        }
        if( text.toString().isBlank() ) return Result.failed( "OCR read no text" ) ;
        return new Result( text.toString(), null ) ;
    }

    /** Run tesseract on {@code image}; why it failed, or null. */
    private String run( File image, Path out )
            throws IOException, InterruptedException {

        ProcessBuilder pb = new ProcessBuilder( bin.getPath(), image.getPath(),
                                                "stdout", "--psm", "6" ) ;
        pb.environment().put( "OMP_THREAD_LIMIT", "1" ) ;
        pb.redirectOutput( out.toFile() ) ;
        pb.redirectError( ProcessBuilder.Redirect.DISCARD ) ;

        Process p = pb.start() ;
        if( !p.waitFor( timeout.toSeconds(), TimeUnit.SECONDS ) ) {
            p.destroyForcibly() ;
            return "tesseract took too long" ;
        }
        return p.exitValue() == 0 ? null
                                  : "tesseract exited with " + p.exitValue() ;
    }

    private static void delete( Path file ) {
        if( file == null ) return ;
        try {
            Files.deleteIfExists( file ) ;
        }
        catch( IOException ignored ) {
            // A temporary file left behind harms nothing.
        }
    }
}
