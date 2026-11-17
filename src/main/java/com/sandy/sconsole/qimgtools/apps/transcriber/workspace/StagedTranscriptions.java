package com.sandy.sconsole.qimgtools.apps.transcriber.workspace;

import com.sandy.sconsole.qimgtools.apps.transcriber.model.Transcription;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.TranscriptionRoute;
import com.sandy.sconsole.qimgtools.core.workspace.JsonFiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Transcriptions accepted but not saved on the server, by server id, so a
 * later Load brings them back without asking a model. A JSON object:
 * <pre>
 * { "6156" : { "questionId", "lctCtxId", ...the transcription and its
 *              route ({@link TranscriptionJson})..., "checked",
 *              "promptSha256", "stagedAt" } }
 * </pre>
 * For an LCT question the content is its question part; the passage is
 * the group's ({@link HeldPassages} or the server's). An entry is valid
 * while the prompt is the one it was made with; an edit, typed by the
 * user, always is. Dropped once the server stores the question.
 * Thread-safe.
 */
public final class StagedTranscriptions extends JsonStore {

    /**
     * A staged transcription.
     *
     * @param transcription the text, an LCT question's part only
     * @param route         how it came about
     * @param checked       true if the user marked it correct
     * @param provisional   true if it is Qwen's, kept without the second
     *                      opinion asked for, awaiting the user
     */
    public record Staged( Transcription transcription,
                          TranscriptionRoute route, boolean checked,
                          boolean provisional ) {}

    public StagedTranscriptions( File file ) {
        super( file, "staged transcriptions" ) ;
    }

    /**
     * The staged transcription of the question with server id {@code id},
     * if it was made with the prompt {@code promptSha256} (or edited);
     * null otherwise.
     */
    public Staged get( int id, String promptSha256 ) {

        JsonNode n = entry( String.valueOf( id ) ) ;
        if( n == null ) return null ;

        Transcription      t     = TranscriptionJson.transcription( n ) ;
        TranscriptionRoute route = TranscriptionJson.route( n ) ;
        if( t == null || route == null ) return null ;

        boolean edited = route.kind() == TranscriptionRoute.Kind.EDITED ;
        if( !edited && !promptSha256.equals(
                TranscriptionJson.text( n, "promptSha256" ) ) ) {
            return null ;
        }
        return new Staged( t, route, n.path( "checked" ).asBoolean(),
                           n.path( "provisional" ).asBoolean() ) ;
    }

    /** Stage {@code staged} for question {@code id}. */
    public void put( int id, String questionId, String lctCtxId,
                     Staged staged, String promptSha256 ) throws IOException {

        ObjectNode n = JsonFiles.MAPPER.createObjectNode() ;

        n.put( "questionId", questionId ) ;
        n.put( "lctCtxId", lctCtxId ) ;
        TranscriptionJson.put( n, staged.transcription(), staged.route() ) ;
        n.put( "checked", staged.checked() ) ;
        n.put( "provisional", staged.provisional() ) ;
        n.put( "promptSha256", promptSha256 ) ;
        n.put( "stagedAt", Instant.now().toString() ) ;
        put( String.valueOf( id ), n ) ;
    }

    /** Drop the staged transcriptions of the questions {@code ids}. */
    public void remove( Collection<Integer> ids ) throws IOException {
        List<String> keys = ids.stream().map( String::valueOf ).toList() ;
        remove( keys ) ;
    }
}
