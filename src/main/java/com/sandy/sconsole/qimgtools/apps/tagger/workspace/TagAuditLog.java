package com.sandy.sconsole.qimgtools.apps.tagger.workspace;

import com.sandy.sconsole.qimgtools.apps.tagger.model.TagClass;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagDecision;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagJudgement;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagOpinions;
import com.sandy.sconsole.qimgtools.apps.tagger.server.RefreshItem;
import com.sandy.sconsole.qimgtools.apps.tagger.server.RefreshResult;
import com.sandy.sconsole.qimgtools.core.server.ServerQuestion;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;

/**
 * The tagger's audit trail, the provenance the server doesn't keep: every
 * judgement of the tiers' replies and every decision the server applied,
 * one JSON object per line, only ever appended to; one file per pass, and
 * each line names its pass. Lines:
 * <pre>
 * { "time", "event" : "judgement", "serverId", "questionId", "sourceId",
 *   "tagClass", "key", "type", "scopeTopicId", "dependent",
 *   "prompt" : { ... },
 *   "tier1" : { ... }, "tier2" : { ... }, "tier2Failure",
 *   "status", "reason", "acceptedTier" }
 * { "time", "event" : "decision", "serverId", "questionId", "sourceId",
 *   "tagClass", "scopeTopicId", "dependent", "prompt" : { ... },
 *   "decision" : { "tags", "dependencies", "source", "model", "human",
 *                  "tier" },
 *   "sent" : { "tagIds", "dependencyTopicIds", "dependencyActor",
 *              "forDependencyOf" },
 *   "status", "message", "autoSetupTaggingNeeded",
 *   "autoConceptTaggingNeeded" }
 * </pre>
 * {@code prompt} is {@code { "catalogueFingerprint", "templateVersion",
 * "promptVersion" }}: what the replies were given. Thread-safe.
 */
public final class TagAuditLog {

    /** What a prompt was, for the log. */
    public record PromptStamp( String catalogueFingerprint,
                               String templateVersion,
                               String promptVersion ) {}

    private final JsonLines lines ;
    private final TagClass  tagClass ;

    /** The audit log of pass {@code tagClass}, in {@code file}. */
    public TagAuditLog( File file, TagClass tagClass ) {
        this.lines    = new JsonLines( file ) ;
        this.tagClass = tagClass ;
    }

    /** Record how the tiers' replies {@code o} on {@code q} were judged. */
    public void judgement( ServerQuestion q, int scopeTopicId,
                           boolean dependent, PromptStamp prompt,
                           TagOpinions o, TagJudgement j ) throws IOException {

        ObjectNode line = JsonLines.entry( "judgement", q ) ;
        line.put( "tagClass", tagClass.wire() ) ;
        line.put( "key", q.getAnswer() ) ;
        line.put( "type", q.getProblemType() ) ;
        line.put( "scopeTopicId", scopeTopicId ) ;
        line.put( "dependent", dependent ) ;
        stamp( line, prompt ) ;
        TagJson.putOpinions( line, o ) ;
        line.put( "status", j.status().name() ) ;
        line.put( "reason", j.reason() != null ? j.reason().name() : null ) ;
        line.put( "acceptedTier", j.accepted() != null
                                  ? j.accepted().tier() : null ) ;
        lines.append( line ) ;
    }

    /** Record the decision {@code d} saved on {@code q}, and the result. */
    public void decision( ServerQuestion q, int scopeTopicId,
                          boolean dependent, PromptStamp prompt,
                          TagDecision d, RefreshItem sent, RefreshResult r )
            throws IOException {

        ObjectNode line = JsonLines.entry( "decision", q ) ;
        line.put( "tagClass", tagClass.wire() ) ;
        line.put( "scopeTopicId", scopeTopicId ) ;
        line.put( "dependent", dependent ) ;
        stamp( line, prompt ) ;

        ObjectNode dn = line.putObject( "decision" ) ;
        TagJson.strings( dn.putArray( "tags" ), d.tags() ) ;
        d.dependencies().forEach( dn.putArray( "dependencies" )::add ) ;
        dn.put( "source", d.source().name() ) ;
        dn.put( "model", d.model() ) ;
        dn.put( "human", d.human() ) ;

        ObjectNode sn = line.putObject( "sent" ) ;
        sent.tagIds().forEach( sn.putArray( "tagIds" )::add ) ;
        if( sent.dependencyTopicIds() != null ) {
            sent.dependencyTopicIds()
                .forEach( sn.putArray( "dependencyTopicIds" )::add ) ;
            sn.put( "dependencyActor", sent.dependencyActor() ) ;
            sn.put( "dependencyModel", sent.dependencyModel() ) ;
        }
        sn.put( "forDependencyOf", sent.forDependencyOf() ) ;

        line.put( "status", r.status() ) ;
        line.put( "message", r.message() ) ;
        line.put( "autoSetupTaggingNeeded", r.autoSetupTaggingNeeded() ) ;
        line.put( "autoConceptTaggingNeeded", r.autoConceptTaggingNeeded() ) ;
        lines.append( line ) ;
    }

    private static void stamp( ObjectNode line, PromptStamp p ) {
        if( p == null ) return ;
        line.putObject( "prompt" )
            .put( "catalogueFingerprint", p.catalogueFingerprint() )
            .put( "templateVersion", p.templateVersion() )
            .put( "promptVersion", p.promptVersion() ) ;
    }
}
