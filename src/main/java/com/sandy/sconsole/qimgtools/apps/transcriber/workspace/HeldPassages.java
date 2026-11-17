package com.sandy.sconsole.qimgtools.apps.transcriber.workspace;

import com.sandy.sconsole.qimgtools.apps.transcriber.model.Passage;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.Transcription;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.TranscriptionRoute;
import com.sandy.sconsole.qimgtools.core.workspace.JsonFiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * LCT passages transcribed in the app but not on the server yet, by
 * {@code lctCtxId}. A JSON object:
 * <pre>
 * { "LK-A-P-11://P/LCT/1" : { ...the transcription and its route
 *                             ({@link TranscriptionJson})...,
 *                             "promptSha256", "heldAt" } }
 * </pre>
 * Valid while the prompt is the one it was made with (an edit always is).
 * Dropped once the server stores the passage. Thread-safe.
 */
public final class HeldPassages extends JsonStore {

    public HeldPassages( File file ) {
        super( file, "held passages" ) ;
    }

    /**
     * The held passage of group {@code lctCtxId}, if made with the prompt
     * {@code promptSha256} (or edited); null otherwise.
     */
    public Passage get( String lctCtxId, String promptSha256 ) {

        JsonNode n = entry( lctCtxId ) ;
        if( n == null ) return null ;

        Transcription      t     = TranscriptionJson.transcription( n ) ;
        TranscriptionRoute route = TranscriptionJson.route( n ) ;
        if( t == null || route == null ) return null ;

        boolean edited = route.kind() == TranscriptionRoute.Kind.EDITED ;
        if( !edited && !promptSha256.equals(
                TranscriptionJson.text( n, "promptSha256" ) ) ) {
            return null ;
        }
        return new Passage( lctCtxId, t, route, false ) ;
    }

    /** Hold {@code passage}, replacing the group's earlier one. */
    public void put( Passage passage, String promptSha256 )
            throws IOException {

        ObjectNode n = JsonFiles.MAPPER.createObjectNode() ;

        TranscriptionJson.put( n, passage.transcription(), passage.route() ) ;
        n.put( "promptSha256", promptSha256 ) ;
        n.put( "heldAt", Instant.now().toString() ) ;
        put( passage.lctCtxId(), n ) ;
    }

    /** Drop group {@code lctCtxId}'s held passage. */
    public void remove( String lctCtxId ) throws IOException {
        remove( List.of( lctCtxId ) ) ;
    }
}
