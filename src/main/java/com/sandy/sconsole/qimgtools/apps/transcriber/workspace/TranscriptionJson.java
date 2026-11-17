package com.sandy.sconsole.qimgtools.apps.transcriber.workspace;

import com.sandy.sconsole.qimgtools.apps.transcriber.gate.ConfidenceScore;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.Transcription;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.TranscriptionRoute;
import com.sandy.sconsole.qimgtools.core.workspace.JsonFiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * The JSON shapes the transcriber's workspace files share: a
 * transcription with its score, and its route.
 * <pre>
 * { "content", "model", "c", "geoMeanP", "minP",
 *   "contests" : [ { "offset", "line", "chosen", "pChosen", "rival",
 *                    "pRival", "continuation" } ],
 *   "route" : { "kind", "model", "reason" } }
 * </pre>
 */
final class TranscriptionJson {

    private TranscriptionJson() {}

    /** Put {@code t} and {@code route} into {@code node}. */
    static ObjectNode put( ObjectNode node, Transcription t,
                           TranscriptionRoute route ) {

        node.put( "content", t.content() ) ;
        node.put( "model", t.model() ) ;
        node.put( "c", t.c() ) ;
        node.put( "geoMeanP", t.geoMeanP() ) ;
        node.put( "minP", t.minP() ) ;

        ArrayNode contests = node.putArray( "contests" ) ;
        for( ConfidenceScore.Contest x : t.contests() ) {
            contests.addObject()
                    .put( "offset", x.offset() )
                    .put( "line", x.line() )
                    .put( "chosen", x.chosen() )
                    .put( "pChosen", x.pChosen() )
                    .put( "rival", x.rival() )
                    .put( "pRival", x.pRival() )
                    .put( "continuation", x.continuation() ) ;
        }
        node.set( "route", route( route ) ) ;
        return node ;
    }

    /** {@code route} as a JSON object. */
    static ObjectNode route( TranscriptionRoute route ) {
        return JsonFiles.MAPPER.createObjectNode()
                               .put( "kind", route.kind().name() )
                               .put( "model", route.model() )
                               .put( "reason", route.reason() ) ;
    }

    /** The transcription in {@code node}; null if it has no content. */
    static Transcription transcription( JsonNode node ) {

        if( !node.path( "content" ).isTextual() ) return null ;

        List<ConfidenceScore.Contest> contests = new ArrayList<>() ;
        for( JsonNode x : node.path( "contests" ) ) {
            contests.add( new ConfidenceScore.Contest(
                    x.path( "offset" ).asInt(), x.path( "line" ).asInt(),
                    x.path( "chosen" ).asText(),
                    x.path( "pChosen" ).asDouble(),
                    x.path( "rival" ).asText(),
                    x.path( "pRival" ).asDouble(),
                    x.path( "continuation" ).asText() ) ) ;
        }
        return new Transcription( node.path( "content" ).asText(),
                                  text( node, "model" ),
                                  node.path( "c" ).isNumber()
                                  ? node.path( "c" ).asInt() : null,
                                  number( node, "geoMeanP" ),
                                  number( node, "minP" ), contests ) ;
    }

    /** The route in {@code node}'s {@code route}; null if it has none. */
    static TranscriptionRoute route( JsonNode node ) {

        JsonNode r = node.path( "route" ) ;
        try {
            return new TranscriptionRoute(
                    TranscriptionRoute.Kind.valueOf( r.path( "kind" ).asText() ),
                    text( r, "model" ), text( r, "reason" ) ) ;
        }
        catch( IllegalArgumentException e ) {
            return null ;
        }
    }

    static String text( JsonNode node, String field ) {
        JsonNode v = node.path( field ) ;
        return v.isTextual() ? v.asText() : null ;
    }

    private static Double number( JsonNode node, String field ) {
        JsonNode v = node.path( field ) ;
        return v.isNumber() ? v.asDouble() : null ;
    }
}
