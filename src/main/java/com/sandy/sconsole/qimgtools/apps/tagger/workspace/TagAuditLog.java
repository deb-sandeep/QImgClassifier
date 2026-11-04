package com.sandy.sconsole.qimgtools.apps.tagger.workspace;

import com.sandy.sconsole.qimgtools.apps.tagger.model.TagDecision;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagProposal;
import com.sandy.sconsole.qimgtools.apps.tagger.regression.Assessment;
import com.sandy.sconsole.qimgtools.apps.tagger.regression.Decision;
import com.sandy.sconsole.qimgtools.apps.tagger.regression.TopicFiles;
import com.sandy.sconsole.qimgtools.apps.tagger.server.RefreshItem;
import com.sandy.sconsole.qimgtools.apps.tagger.server.RefreshResult;
import com.sandy.sconsole.qimgtools.core.server.ServerQuestion;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The tagger's audit trail, the provenance the server doesn't keep: every
 * model's reply and every decision the server applied, one JSON object per
 * line, only ever appended to. The format is documented in
 * {@code docs/feature-enhancements/2026-11-04/tagger-workflow/tag-audit-format.md}.
 * Thread-safe.
 */
public final class TagAuditLog {

    private final JsonLines lines ;

    public TagAuditLog( File file ) {
        this.lines = new JsonLines( file ) ;
    }

    /** Record the model's reply {@code p} on {@code q}. */
    public void reply( ServerQuestion q, int scopeTopicId, String cycle,
                       TagProposal p ) throws IOException {

        ObjectNode line = JsonLines.entry( "reply", q ) ;
        line.put( "scopeTopicId", scopeTopicId ) ;
        line.put( "cycle", cycle ) ;
        line.put( "model", p.model() ) ;
        p.reply().tags().forEach( line.putArray( "tags" )::add ) ;
        line.put( "confidence", String.valueOf( p.reply().confidence() ) ) ;
        line.put( "noTagFits", p.reply().noTagFits() ) ;
        line.put( "framing", p.reply().framing() ) ;
        line.put( "reason", p.reply().reason() ) ;
        lines.append( line ) ;
    }

    /**
     * Record the decision {@code d} saved on {@code q}, what led to it, what
     * was sent and how the server answered.
     *
     * @param a what the regression made of it; null if it was never read
     * @param p what the LLM replied; null if it wasn't asked
     */
    public void decision( ServerQuestion q, int scopeTopicId, TopicFiles files,
                          TagDecision d, Assessment a, TagProposal p,
                          RefreshItem sent, RefreshResult r )
            throws IOException {

        ObjectNode line = JsonLines.entry( "decision", q ) ;
        line.put( "scopeTopicId", scopeTopicId ) ;
        line.put( "cycle", files.cycle() ) ;
        line.put( "modelHash", files.modelHash() ) ;
        line.put( "promptHash", files.promptHash() ) ;

        regression( line, a ) ;
        llm( line, p ) ;

        ObjectNode dn = line.putObject( "decision" ) ;

        d.tags().forEach( dn.putArray( "tags" )::add ) ;
        dn.put( "source", d.source().name() ) ;
        dn.put( "edited", d.edited() ) ;
        dn.put( "human", d.human() ) ;

        sent.tagIds().forEach( line.putObject( "sent" )
                                   .putArray( "tagIds" )::add ) ;
        line.put( "status", r.status() ) ;
        line.put( "message", r.message() ) ;
        lines.append( line ) ;
    }

    /** What the regression made of it; null values if it was never read. */
    private static void regression( ObjectNode line, Assessment a ) {

        Decision d = a == null ? null : a.decision() ;

        line.put( "ocrHash", a == null || a.ocrText() == null ? null
                : TopicFiles.sha256( a.ocrText()
                                      .getBytes( StandardCharsets.UTF_8 ) ) ) ;
        if( d == null ) {
            line.putNull( "regression" ) ;
            return ;
        }

        ObjectNode n = line.putObject( "regression" ) ;
        n.put( "route", d.isSettled() ? "apply" : "llm" ) ;
        scored( n.putObject( "accepted" ), d.accepted(), d ) ;
        scored( n.putObject( "undecided" ), d.undecided(), d ) ;
        d.triggered().forEach( n.putArray( "triggered" )::add ) ;
    }

    private static void scored( ObjectNode n, List<String> tags, Decision d ) {
        for( String t : tags ) n.put( t, d.scores().get( t ) ) ;
    }

    /** What the LLM replied; null if it wasn't asked. */
    private static void llm( ObjectNode line, TagProposal p ) {

        if( p == null ) {
            line.putNull( "llm" ) ;
            return ;
        }

        ObjectNode n = line.putObject( "llm" ) ;
        n.put( "model", p.model() ) ;
        p.reply().tags().forEach( n.putArray( "tags" )::add ) ;
        n.put( "confidence", String.valueOf( p.reply().confidence() ) ) ;
        n.put( "noTagFits", p.reply().noTagFits() ) ;
        n.put( "framing", p.reply().framing() ) ;
        n.put( "reason", p.reply().reason() ) ;
    }
}
