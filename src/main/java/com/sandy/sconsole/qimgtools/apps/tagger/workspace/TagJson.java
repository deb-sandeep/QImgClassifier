package com.sandy.sconsole.qimgtools.apps.tagger.workspace;

import com.sandy.sconsole.qimgtools.apps.tagger.model.TagOpinions;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagReply;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagReply.ConceptPick;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagReply.GapProposal;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TierReply;
import com.sandy.sconsole.qimgtools.core.workspace.JsonFiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * The JSON shapes {@link PendingTagging} and {@link TagAuditLog} share: a
 * tier's reply as
 * <pre>
 * { "tier", "model", "ans", "setup" : [ ... ],
 *   "concept" : [ { "t", "why" } ], "math" : [ ... ], "xdep" : [ ... ],
 *   "gap" : { "tag", "hint", "why" } | null, "fixes" : [ ... ] }
 * </pre>
 * and the opinions as {@code tier1}, {@code tier2} and
 * {@code tier2Failure} beside each other. Stateless.
 */
final class TagJson {

    private TagJson() {}

    /** Put {@code o}'s replies into {@code entry}. */
    static void putOpinions( ObjectNode entry, TagOpinions o ) {

        entry.set( "tier1", tier( o.tier1() ) ) ;
        if( o.tier2() != null ) entry.set( "tier2", tier( o.tier2() ) ) ;
        if( o.tier2Failure() != null ) {
            entry.put( "tier2Failure", o.tier2Failure() ) ;
        }
    }

    /** The opinions in {@code entry}; null if it has none readable. */
    static TagOpinions readOpinions( JsonNode entry ) {

        TierReply t1 = readTier( entry.get( "tier1" ) ) ;
        if( t1 == null ) return null ;
        return new TagOpinions( t1, readTier( entry.get( "tier2" ) ),
                                text( entry.get( "tier2Failure" ) ) ) ;
    }

    static ObjectNode tier( TierReply t ) {

        TagReply   r = t.reply() ;
        ObjectNode n = JsonFiles.MAPPER.createObjectNode() ;

        n.put( "tier", t.tier() ) ;
        n.put( "model", t.model() ) ;
        n.put( "ans", r.answer() ) ;
        strings( n.putArray( "setup" ), r.setup() ) ;

        ArrayNode concept = n.putArray( "concept" ) ;
        for( ConceptPick c : r.concept() ) {
            concept.addObject().put( "t", c.tag() ).put( "why", c.why() ) ;
        }
        strings( n.putArray( "math" ), r.math() ) ;
        r.xdep().forEach( n.putArray( "xdep" )::add ) ;
        if( r.gap() != null ) {
            n.putObject( "gap" )
             .put( "tag", r.gap().tag() )
             .put( "hint", r.gap().hint() )
             .put( "why", r.gap().why() ) ;
        }
        else {
            n.putNull( "gap" ) ;
        }
        strings( n.putArray( "fixes" ), r.fixes() ) ;
        return n ;
    }

    static TierReply readTier( JsonNode n ) {

        if( n == null || !n.isObject() || !n.hasNonNull( "ans" ) ) return null ;

        List<ConceptPick> concept = new ArrayList<>() ;
        for( JsonNode c : n.path( "concept" ) ) {
            concept.add( new ConceptPick( text( c.get( "t" ) ),
                                          text( c.get( "why" ) ) ) ) ;
        }
        List<Integer> xdep = new ArrayList<>() ;
        n.path( "xdep" ).forEach( x -> xdep.add( x.asInt() ) ) ;

        JsonNode    g   = n.get( "gap" ) ;
        GapProposal gap = g == null || !g.isObject() ? null
                        : new GapProposal( text( g.get( "tag" ) ),
                                           text( g.get( "hint" ) ),
                                           text( g.get( "why" ) ) ) ;
        TagReply reply = new TagReply(
                n.get( "ans" ).asText(), strings( n.path( "setup" ) ), concept,
                strings( n.path( "math" ) ), xdep, gap,
                strings( n.path( "fixes" ) ) ) ;
        return new TierReply( n.path( "tier" ).asInt( 1 ),
                              text( n.get( "model" ) ), reply ) ;
    }

    static void strings( ArrayNode a, List<String> values ) {
        values.forEach( a::add ) ;
    }

    static List<String> strings( JsonNode a ) {
        List<String> values = new ArrayList<>() ;
        a.forEach( v -> values.add( v.asText() ) ) ;
        return values ;
    }

    static String text( JsonNode n ) {
        return n == null || n.isNull() ? null : n.asText() ;
    }
}
