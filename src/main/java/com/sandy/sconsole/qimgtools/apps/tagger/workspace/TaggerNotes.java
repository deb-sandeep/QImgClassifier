package com.sandy.sconsole.qimgtools.apps.tagger.workspace;

import com.sandy.sconsole.qimgtools.core.server.ServerQuestion;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;

/**
 * Notes for the tag design work, one JSON object per line, only ever
 * appended to: every deferral. Lines:
 * <pre>
 * { "time", "event" : "deferred", "serverId", "questionId", "sourceId",
 *   "scopeTopicId", "note" }
 * </pre>
 * Thread-safe.
 */
public final class TaggerNotes {

    private final JsonLines lines ;

    public TaggerNotes( File file ) {
        this.lines = new JsonLines( file ) ;
    }

    /** Note that {@code q} was deferred, and why. */
    public void deferred( ServerQuestion q, int scopeTopicId, String note )
            throws IOException {

        ObjectNode line = JsonLines.entry( "deferred", q ) ;
        line.put( "scopeTopicId", scopeTopicId ) ;
        line.put( "note", note ) ;
        lines.append( line ) ;
    }
}
