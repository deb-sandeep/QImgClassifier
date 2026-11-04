package com.sandy.sconsole.qimgtools.apps.tagger.workspace;

import com.sandy.sconsole.qimgtools.apps.tagger.model.TagDecision;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagProposal;
import com.sandy.sconsole.qimgtools.apps.tagger.server.RefreshItem;
import com.sandy.sconsole.qimgtools.apps.tagger.server.RefreshResult;
import com.sandy.sconsole.qimgtools.core.server.ServerQuestion;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;

/**
 * The tagger's audit trail, the provenance the server doesn't keep: every
 * model's reply and every decision the server applied, one JSON object per
 * line, only ever appended to. Lines:
 * <pre>
 * { "time", "event" : "reply", "serverId", "questionId", "sourceId",
 *   "scopeTopicId", "model", "tags" : [ ... ] }
 * { "time", "event" : "decision", "serverId", "questionId", "sourceId",
 *   "scopeTopicId",
 *   "decision" : { "tags", "source", "model", "edited", "human" },
 *   "sent" : { "tagIds" }, "status", "message" }
 * </pre>
 * Thread-safe.
 */
public final class TagAuditLog {

    private final JsonLines lines ;

    public TagAuditLog( File file ) {
        this.lines = new JsonLines( file ) ;
    }

    /** Record the model's reply {@code p} on {@code q}. */
    public void reply( ServerQuestion q, int scopeTopicId, TagProposal p )
            throws IOException {

        ObjectNode line = JsonLines.entry( "reply", q ) ;
        line.put( "scopeTopicId", scopeTopicId ) ;
        line.put( "model", p.model() ) ;
        p.reply().tags().forEach( line.putArray( "tags" )::add ) ;
        lines.append( line ) ;
    }

    /** Record the decision {@code d} saved on {@code q}, and the result. */
    public void decision( ServerQuestion q, int scopeTopicId, TagDecision d,
                          RefreshItem sent, RefreshResult r )
            throws IOException {

        ObjectNode line = JsonLines.entry( "decision", q ) ;
        line.put( "scopeTopicId", scopeTopicId ) ;

        ObjectNode dn = line.putObject( "decision" ) ;
        d.tags().forEach( dn.putArray( "tags" )::add ) ;
        dn.put( "source", d.source().name() ) ;
        dn.put( "model", d.model() ) ;
        dn.put( "edited", d.edited() ) ;
        dn.put( "human", d.human() ) ;

        sent.tagIds().forEach( line.putObject( "sent" )
                                   .putArray( "tagIds" )::add ) ;
        line.put( "status", r.status() ) ;
        line.put( "message", r.message() ) ;
        lines.append( line ) ;
    }
}
