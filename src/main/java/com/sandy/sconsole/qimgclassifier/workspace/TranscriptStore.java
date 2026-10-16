package com.sandy.sconsole.qimgclassifier.workspace;

import com.sandy.sconsole.qimgclassifier.model.AttemptTranscript;
import com.sandy.sconsole.qimgclassifier.model.QTask;
import com.sandy.sconsole.qimgclassifier.util.JsonText;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Saves the request and response of every AI attempt as text files, laid out
 * as {@code <workspace>/<source>/<questionId>/}. Each attempt writes a pair:
 * <pre>
 * 20261016-142301-123-a2-openrouter-request.txt
 * 20261016-142301-123-a2-openrouter-response.txt
 * </pre>
 * The names sort chronologically, so a question's history reads in order.
 * JSON in the prompt and the reply is stored indented (whitespace only).
 * Distinct attempts never share a name, so concurrent writers need no lock.
 */
@Slf4j
@RequiredArgsConstructor
public final class TranscriptStore {

    /**
     * Suffix of a request file; the matching response file ends in
     * {@link #RESPONSE}.
     */
    public static final String REQUEST  = "-request.txt" ;
    public static final String RESPONSE = "-response.txt" ;

    /** Line separating a file's header from its prompt or reply. */
    public static final String RULE =
            "------------------------------------------------------------------" ;

    /** Root folder holding one folder per source. */
    @Getter
    private final File workspaceDir ;

    /** Folder holding the transcripts of {@code task}'s question. */
    public File dirFor( QTask task ) {
        File srcDir = new File( workspaceDir, task.getSourceName() ) ;
        return new File( srcDir, task.getQuestionId() ) ;
    }

    /**
     * Write the request and response files of one attempt.
     *
     * @throws IOException if the folder or either file cannot be written
     */
    public void save( AttemptTranscript t ) throws IOException {

        File dir = dirFor( t.getTask() ) ;
        Files.createDirectories( dir.toPath() ) ;

        String stem = new SimpleDateFormat( "yyyyMMdd-HHmmss-SSS" )
                              .format( new Date( t.getStartedAt() ) )
                      + "-a" + t.getAttempt() + "-" + t.getAgent().getLabel() ;

        Files.writeString( new File( dir, stem + REQUEST ).toPath(),
                           requestText( t ), StandardCharsets.UTF_8 ) ;
        Files.writeString( new File( dir, stem + RESPONSE ).toPath(),
                           responseText( t ), StandardCharsets.UTF_8 ) ;
    }

    /**
     * The question's transcript files, oldest first; empty if there are none.
     */
    public List<File> files( QTask task ) {

        File[] files = dirFor( task ).listFiles(
                f -> f.isFile() && ( f.getName().endsWith( REQUEST )
                                     || f.getName().endsWith( RESPONSE ) ) ) ;
        if( files == null ) return List.of() ;

        // Same stem: the request sorts before its response.
        return Arrays.stream( files )
                     .sorted( ( a, b ) -> sortKey( a ).compareTo( sortKey( b ) ) )
                     .collect( Collectors.toList() ) ;
    }

    private static String sortKey( File f ) {
        String name = f.getName() ;
        return name.endsWith( REQUEST )
               ? name.substring( 0, name.length() - REQUEST.length() ) + "-0"
               : name.substring( 0, name.length() - RESPONSE.length() ) + "-1" ;
    }

    private static String requestText( AttemptTranscript t ) {

        String images = t.getTask().getImgFiles().stream()
                         .map( File::getName )
                         .collect( Collectors.joining( ", " ) ) ;

        return header( "Time",     timestamp( t.getStartedAt() ) )
             + header( "Question", t.getTask().toString() )
             + header( "Agent",    t.getAgent().getLabel() )
             + header( "Model",    t.getAiModel() )
             + header( "Attempt",  String.valueOf( t.getAttempt() ) )
             + header( "Images",   images + " (" + t.getMimeType() + ")" )
             + RULE + "\n"
             + JsonText.format( t.getPrompt() ) + "\n" ;
    }

    private static String responseText( AttemptTranscript t ) {

        String outcome = t.isSuccess()     ? "OK"
                       : t.isRateLimited() ? "RATE LIMITED: " + t.getFailure()
                       : "FAILED: " + t.getFailure() ;
        String elapsed = String.format( "%.1f s", t.getElapsedMs() / 1000.0 ) ;

        return header( "Time",    timestamp( t.getStartedAt() + t.getElapsedMs() ) )
             + header( "Elapsed", elapsed )
             + header( "Outcome", outcome )
             + RULE + "\n"
             + ( t.getResponse() != null ? JsonText.format( t.getResponse() )
                                         : "(no response body)" )
             + "\n" ;
    }

    private static String header( String name, String value ) {
        return String.format( "%-9s: %s\n", name, value ) ;
    }

    private static String timestamp( long epochMs ) {
        return new SimpleDateFormat( "yyyy-MM-dd HH:mm:ss.SSS" )
                       .format( new Date( epochMs ) ) ;
    }
}
