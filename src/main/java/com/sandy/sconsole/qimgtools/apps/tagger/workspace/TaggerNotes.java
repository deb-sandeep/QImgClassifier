package com.sandy.sconsole.qimgtools.apps.tagger.workspace;

import com.sandy.sconsole.qimgtools.apps.tagger.model.TagReply.GapProposal;
import com.sandy.sconsole.qimgtools.core.server.ServerQuestion;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;

/**
 * Notes for the tag design work, one JSON object per line, only ever
 * appended to: every gap proposal and its fate, and every deferral (e.g. a
 * key to check). Lines:
 * <pre>
 * { "time", "event" : "gap", "serverId", "questionId", "sourceId",
 *   "scopeTopicId", "model", "proposal" : { "tag", "hint", "why" },
 *   "fate" : "accepted" | "mapped" | "rejected", "tag" }
 * { "time", "event" : "deferred", "serverId", "questionId", "sourceId",
 *   "scopeTopicId", "key", "reason", "note" }
 * </pre>
 * {@code tag} is the tag created (accepted) or taken instead (mapped).
 * Thread-safe.
 */
public final class TaggerNotes {

    private final JsonLines lines ;

    public TaggerNotes( File file ) {
        this.lines = new JsonLines( file ) ;
    }

    /** Note the fate of gap proposal {@code gap} on {@code q}. */
    public void gap( ServerQuestion q, int scopeTopicId, String model,
                     GapProposal gap, String fate, String tag )
            throws IOException {

        ObjectNode line = JsonLines.entry( "gap", q ) ;
        line.put( "scopeTopicId", scopeTopicId ) ;
        line.put( "model", model ) ;
        line.putObject( "proposal" )
            .put( "tag", gap.tag() )
            .put( "hint", gap.hint() )
            .put( "why", gap.why() ) ;
        line.put( "fate", fate ) ;
        line.put( "tag", tag ) ;
        lines.append( line ) ;
    }

    /** Note that {@code q} was deferred, why it was handed off, and the note. */
    public void deferred( ServerQuestion q, int scopeTopicId, String reason,
                          String note ) throws IOException {

        ObjectNode line = JsonLines.entry( "deferred", q ) ;
        line.put( "scopeTopicId", scopeTopicId ) ;
        line.put( "key", q.getAnswer() ) ;
        line.put( "reason", reason ) ;
        line.put( "note", note ) ;
        lines.append( line ) ;
    }
}
