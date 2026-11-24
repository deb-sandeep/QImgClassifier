package com.sandy.sconsole.qimgtools.apps.transcriber.workspace;

import com.sandy.sconsole.qimgtools.apps.transcriber.gate.ReplyMend;
import com.sandy.sconsole.qimgtools.core.model.QTask;
import com.sandy.sconsole.qimgtools.core.workspace.TranscriptStore;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * What a question's transcripts on disk say of its earlier attempts, in
 * this session or before: the last reply the gate refused (a refused
 * reply's response file carries {@code Well formed : no} in its
 * {@code Confidence:} section), and whether every model asked failed.
 */
@Slf4j
public final class TranscriptHistory {

    private static final String REFUSED    = "Well formed : no" ;
    private static final String CONFIDENCE = "\n\n" + TranscriptStore.RULE +
                                             "\nConfidence:" ;
    private static final String OUTCOME    = "Outcome  : " ;
    private static final String OK         = "OK" ;
    private static final String FAILED     = "FAILED: " ;

    private TranscriptHistory() {}

    /**
     * How a question's attempts all failed.
     *
     * @param attempts the failed attempts in its transcripts
     * @param reason   the last one's reason
     */
    public record Failure( int attempts, String reason ) {}

    /**
     * The latest reply the gate refused for {@code task}, mended as the
     * gate mends a reply; null if there is none.
     */
    public static String lastRefused( TranscriptStore store, QTask task ) {

        List<File> files = responses( store, task ) ;
        for( int i = files.size() - 1 ; i >= 0 ; i-- ) {
            String reply = refusedReply( files.get( i ) ) ;
            if( reply != null ) return reply ;
        }
        return null ;
    }

    /**
     * How {@code task}'s attempts failed, if they all did: at least one
     * failed and none gave a transcription. Attempts held back by a rate
     * limit or an unreachable gateway count neither way. Null if it has
     * no failed attempt, or one succeeded.
     */
    public static Failure failure( TranscriptStore store, QTask task ) {

        int    failed = 0 ;
        String reason = null ;

        for( File f : responses( store, task ) ) {
            String outcome = outcome( f ) ;
            if( outcome == null ) continue ;
            if( outcome.equals( OK ) ) return null ;
            if( outcome.startsWith( FAILED ) ) {
                failed++ ;
                reason = outcome.substring( FAILED.length() ) ;
            }
        }
        return failed == 0 ? null : new Failure( failed, reason ) ;
    }

    /** {@code task}'s response files, oldest first. */
    private static List<File> responses( TranscriptStore store, QTask task ) {
        return store.files( task ).stream()
                    .filter( f -> f.getName()
                                   .endsWith( TranscriptStore.RESPONSE ) )
                    .toList() ;
    }

    /** The {@code Outcome} header of response file {@code f}; null if none. */
    private static String outcome( File f ) {

        String text = read( f ) ;
        if( text == null ) return null ;

        for( String line : text.split( "\n" ) ) {
            if( line.equals( TranscriptStore.RULE ) ) break ;
            if( line.startsWith( OUTCOME ) ) {
                return line.substring( OUTCOME.length() ).strip() ;
            }
        }
        return null ;
    }

    /** The reply in response file {@code f}, if the gate refused it. */
    private static String refusedReply( File f ) {

        String text = read( f ) ;
        if( text == null ) return null ;

        int start = text.indexOf( TranscriptStore.RULE + "\n" ) ;
        if( start < 0 ) return null ;
        start += TranscriptStore.RULE.length() + 1 ;

        int end = text.indexOf( CONFIDENCE, start ) ;
        if( end < 0 || text.indexOf( REFUSED, end ) < 0 ) return null ;

        String reply = ReplyMend.mend( text.substring( start, end ) ).text()
                                .stripTrailing() ;
        return reply.isBlank() ? null : reply ;
    }

    private static String read( File f ) {

        try {
            return Files.readString( f.toPath(), StandardCharsets.UTF_8 ) ;
        }
        catch( IOException e ) {
            log.warn( "Could not read the transcript {}: {}", f,
                      e.getMessage() ) ;
            return null ;
        }
    }
}
