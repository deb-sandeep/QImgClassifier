package com.sandy.sconsole.qimgclassifier.workspace;

import com.sandy.sconsole.qimgclassifier.model.AttemptTranscript;
import com.sandy.sconsole.qimgclassifier.model.TokenUsage;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Appends one CSV row per AI attempt with the tokens it used, for estimating
 * what a paid model would cost. The header row is written when the file is
 * created. Optional counts the backend did not report are left empty. Rows
 * are appended under a lock, so concurrent workers never interleave them.
 */
@Slf4j
public final class UsageLog {

    private static final String HEADER =
            "time,source,question,subject,agent,model,attempt,outcome," +
            "elapsed_ms,input_tokens,cached_tokens,output_tokens," +
            "reasoning_tokens,total_tokens,cost_usd,images,failure" ;

    private final File file ;

    public UsageLog( File file ) {
        this.file = file ;
    }

    /** Append the attempt's row. A failure is logged, never thrown. */
    public synchronized void append( AttemptTranscript t ) {

        try {
            if( !file.exists() ) {
                Files.writeString( file.toPath(), HEADER + "\n",
                                   StandardCharsets.UTF_8 ) ;
            }
            Files.writeString( file.toPath(), row( t ) + "\n",
                               StandardCharsets.UTF_8,
                               StandardOpenOption.APPEND ) ;
        }
        catch( IOException e ) {
            log.error( "Could not add attempt {} of {} to {}", t.getAttempt(),
                       t.getTask(), file, e ) ;
        }
    }

    private static String row( AttemptTranscript t ) {

        TokenUsage u       = t.getUsage() ;
        String     outcome = t.getOutcome().name().toLowerCase()
                              .replace( '_', '-' ) ;   // e.g. rate-limited

        return String.join( ",",
                new SimpleDateFormat( "yyyy-MM-dd HH:mm:ss" )
                        .format( new Date( t.getStartedAt() ) ),
                csv( t.getTask().getSourceName() ),
                csv( t.getTask().getQuestionId() ),
                t.getTask().getSubject().name(),
                t.getAgent().getLabel(),
                csv( t.getAiModel() ),
                String.valueOf( t.getAttempt() ),
                outcome,
                String.valueOf( t.getElapsedMs() ),
                u == null ? "" : String.valueOf( u.getInputTokens() ),
                u == null ? "" : opt( u.getCachedTokens() ),
                u == null ? "" : String.valueOf( u.getOutputTokens() ),
                u == null ? "" : opt( u.getReasoningTokens() ),
                u == null ? "" : String.valueOf( u.getTotalTokens() ),
                u == null || u.getCost() == null
                        ? "" : String.format( Locale.ROOT, "%.6f", u.getCost() ),
                csv( images( t ) ),
                csv( t.getFailure() ) ) ;
    }

    /** The question's image file names, separated by spaces. */
    private static String images( AttemptTranscript t ) {
        List<File> files = t.getTask().getImgFiles() ;
        return files.stream().map( File::getName ).collect( Collectors.joining( " " ) ) ;
    }

    private static String opt( Integer n ) {
        return n == null ? "" : n.toString() ;
    }

    /** A CSV field, quoted when it holds a comma, quote or line break. */
    private static String csv( String s ) {
        if( s == null ) return "" ;
        if( s.matches( "(?s).*[,\"\\n\\r].*" ) ) {
            return "\"" + s.replace( "\"", "\"\"" ) + "\"" ;
        }
        return s ;
    }
}
