package com.sandy.sconsole.qimgtools.apps.tagger.check;

import com.sandy.sconsole.qimgtools.apps.tagger.model.Axis;
import com.sandy.sconsole.qimgtools.apps.tagger.model.CatalogueTag;
import com.sandy.sconsole.qimgtools.apps.tagger.model.OfferedSet;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagCatalogue;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagClass;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagMode;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagReply;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagReply.ConceptPick;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagReply.GapProposal;
import com.sandy.sconsole.qimgtools.core.scheduler.TaskPrompter.BadReply;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a model's reply and checks it against what was offered. The reply
 * is a JSON object, possibly in markdown fences or among other text; extra
 * fields are ignored. In the setup pass it is {@code setup} and
 * {@code gap}; in the concept pass {@code ans}, {@code concept},
 * {@code math}, {@code xdep} and {@code gap} (a dependent: {@code ans},
 * {@code concept}, {@code gap}).
 * <p>
 * A slip is mended where the mapping is unambiguous, and noted in
 * {@link TagReply#fixes()}: a tag's case or spacing, a missing axis prefix,
 * a tag given by its full name, a tag listed under another axis, a
 * duplicate, a dependency on a topic not offered (dropped), tags of an axis
 * the pass or a dependent doesn't take, or dependencies from a dependent or
 * in the setup pass (dropped). Anything else - a missing or blank answer
 * in the concept pass, a tag that isn't offered, the wrong number of tags
 * on an axis - rejects the reply as a {@link BadReply}, so the model is
 * asked again. The one exception to the counts: a concept reply proposing a
 * concept tag as its gap may give no concept tag.
 * <p>
 * In the concept pass, {@code "ans": null} is the model declining to answer
 * (an unreadable figure, missing data, an ambiguous question): the reply is
 * {@link TagReply#abstained()}, the rest of it ignored, so it never matches
 * the key and the question goes on to tier 2. The setup pass asks for no
 * answer, and any given is ignored. Stateless.
 */
public final class ReplyValidator {

    /**
     * The most dependencies a reply keeps: the solution's focus stays on
     * the scoped topic, and the first ones listed are the most important.
     */
    public static final int MAX_DEPENDENCIES = 2 ;

    private static final ObjectMapper MAPPER = new ObjectMapper() ;

    /**
     * What a reply is checked against.
     *
     * @param catalogue  the topic's catalogue (for full names)
     * @param offered    the tags the prompt offered, for its pass
     * @param dependent  true for a dependent: 0-2 concept tags only
     * @param mode       whether a gap may be proposed
     * @param topicIds   the topics a dependency may name
     */
    public record Context( TagCatalogue catalogue, OfferedSet offered,
                           boolean dependent, TagMode mode,
                           Set<Integer> topicIds ) {

        /** The pass the reply is for. */
        public TagClass pass() {
            return offered.tagClass() ;
        }

        /** True if the reply may give tags of {@code axis}. */
        boolean takes( Axis axis ) {
            return pass().tags( axis ) && axis.max( dependent ) > 0 ;
        }
    }

    private ReplyValidator() {}

    /**
     * {@code reply} read and checked.
     *
     * @throws BadReply with a short reason if it can't be used
     */
    public static TagReply read( String reply, Context ctx ) throws BadReply {

        JsonNode root = parse( reply ) ;
        if( root == null ) {
            throw new BadReply( "the model's reply was not a JSON object" ) ;
        }

        String answer = null ;
        if( ctx.pass().solves() ) {
            JsonNode ans = root.get( "ans" ) ;
            if( ans != null && ans.isNull() ) {
                return TagReply.abstained() ;
            }
            answer = text( ans ) ;
            if( answer == null || answer.isBlank() ) {
                throw new BadReply( "the model's reply has no answer" ) ;
            }
            answer = answer.trim() ;
        }

        List<String>              fixes  = new ArrayList<>() ;
        Map<Axis,List<String>>    byAxis = new EnumMap<>( Axis.class ) ;
        Map<String,String>        whys   = new LinkedHashMap<>() ;

        for( Axis a : Axis.values() ) byAxis.put( a, new ArrayList<>() ) ;
        for( Axis a : Axis.values() ) {
            JsonNode list = root.get( a.getWord() ) ;
            if( list == null || list.isNull() ) continue ;
            if( !list.isArray() ) {
                throw new BadReply( "'" + a.getWord() + "' is not a list" ) ;
            }
            if( !ctx.takes( a ) ) {
                if( !list.isEmpty() ) {
                    fixes.add( "ignored the " + a.getWord() + " list (" +
                               taker( ctx ) + " takes none)" ) ;
                }
                continue ;
            }
            for( JsonNode entry : list ) {
                String given = entry.isObject() ? text( entry.get( "t" ) )
                                                : text( entry ) ;
                if( given == null || given.isBlank() ) continue ;

                CatalogueTag tag = resolve( given, a, ctx, fixes ) ;
                if( tag == null ) {
                    Axis named = Axis.of( given.trim() ) ;
                    if( named != null && !ctx.takes( named ) ) {
                        fixes.add( "dropped " + named.getWord() + " tag " +
                                   given + " (" + taker( ctx ) +
                                   " takes none)" ) ;
                        continue ;
                    }
                    throw new BadReply( "tag '" + given + "' is not offered" ) ;
                }
                if( tag.axis() != a ) {
                    fixes.add( tag.tagText() + " moved from " + a.getWord() +
                               " to " + tag.axis().getWord() ) ;
                }
                List<String> tags = byAxis.get( tag.axis() ) ;
                if( tags.contains( tag.tagText() ) ) {
                    fixes.add( "dropped repeated " + tag.tagText() ) ;
                    continue ;
                }
                tags.add( tag.tagText() ) ;
                if( entry.isObject() ) {
                    whys.put( tag.tagText(), text( entry.get( "why" ) ) ) ;
                }
            }
        }

        GapProposal gap = gap( root.get( "gap" ), ctx, fixes ) ;

        for( Axis a : ctx.pass().getAxes() ) {
            int n = byAxis.get( a ).size() ;
            if( n == 0 && a == Axis.CONCEPT && isConcept( gap ) ) continue ;
            if( n < a.min( ctx.dependent() ) || n > a.max( ctx.dependent() ) ) {
                throw new BadReply( n + " " + a.getWord() + " tags; " +
                                    a.countRule( ctx.dependent() ) +
                                    " allowed" ) ;
            }
        }

        List<ConceptPick> concept = byAxis.get( Axis.CONCEPT ).stream()
                .map( t -> new ConceptPick( t, whys.get( t ) ) ).toList() ;

        return new TagReply( answer, byAxis.get( Axis.SETUP ), concept,
                             byAxis.get( Axis.MATH ),
                             dependencies( root.get( "xdep" ), ctx, fixes ),
                             gap, fixes ) ;
    }

    /** "the setup pass", "a dependent": what took no tags of an axis. */
    private static String taker( Context ctx ) {
        return ctx.dependent() ? "a dependent"
                               : "the " + ctx.pass().wire() + " pass" ;
    }

    /**
     * True if {@code gap} proposes a concept tag: the dominant concept has
     * none in the lists, so the reply may give no concept tag at all.
     */
    private static boolean isConcept( GapProposal gap ) {
        return gap != null && gap.tag().startsWith( Axis.CONCEPT.getPrefix() ) ;
    }

    /**
     * The offered tag {@code given} names, found under axis {@code a};
     * null if none.
     */
    private static CatalogueTag resolve( String given, Axis a, Context ctx,
                                         List<String> fixes ) {

        OfferedSet   offered = ctx.offered() ;
        String       g       = given.trim().replaceAll( "\\s+", "" ) ;
        CatalogueTag tag     = offered.get( g ) ;

        if( tag != null ) {
            if( !tag.tagText().equals( given ) ) {
                fixes.add( "'" + given + "' read as " + tag.tagText() ) ;
            }
            return tag ;
        }

        CatalogueTag full = ctx.catalogue().byFullName( g ) ;
        if( full != null && offered.contains( full.tagText() ) ) {
            fixes.add( "full name " + given + " read as " + full.tagText() ) ;
            return offered.get( full.tagText() ) ;
        }

        if( Axis.of( g ) == null ) {
            CatalogueTag prefixed = offered.get( a.getPrefix() + g ) ;
            if( prefixed != null ) {
                fixes.add( "'" + given + "' read as " + prefixed.tagText() ) ;
                return prefixed ;
            }
        }
        return null ;
    }

    /** The dependencies a reply names, those not offered dropped. */
    private static List<Integer> dependencies( JsonNode xdep, Context ctx,
                                               List<String> fixes )
            throws BadReply {

        List<Integer> ids = new ArrayList<>() ;
        if( xdep == null || xdep.isNull() ) return ids ;
        if( !xdep.isArray() ) throw new BadReply( "'xdep' is not a list" ) ;

        if( ctx.dependent() || !ctx.pass().solves() ) {
            if( !xdep.isEmpty() ) {
                fixes.add( "ignored xdep (" + taker( ctx ) + " takes none)" ) ;
            }
            return ids ;
        }
        for( JsonNode n : xdep ) {
            Integer id = n.canConvertToInt() ? Integer.valueOf( n.asInt() )
                       : n.isTextual() && n.asText().trim().matches( "\\d+" )
                         ? Integer.valueOf( n.asText().trim() ) : null ;
            if( id == null || !ctx.topicIds().contains( id ) ) {
                fixes.add( "dropped dependency " + n + " (not offered)" ) ;
            }
            else if( !ids.contains( id ) ) {
                ids.add( id ) ;
            }
        }
        // Listed most important first, so the cut keeps the ones that count.
        if( ids.size() > MAX_DEPENDENCIES ) {
            fixes.add( "kept the first " + MAX_DEPENDENCIES + " of " +
                       ids.size() + " dependencies " + ids ) ;
            return new ArrayList<>( ids.subList( 0, MAX_DEPENDENCIES ) ) ;
        }
        return ids ;
    }

    /**
     * The gap proposed; null if none, or gaps aren't asked for. A pass may
     * propose only a tag of its first axis: setup a setup tag, concept a
     * concept tag.
     */
    private static GapProposal gap( JsonNode gap, Context ctx,
                                    List<String> fixes ) {

        if( gap == null || gap.isNull() || !gap.isObject() ) return null ;
        if( ctx.mode() != TagMode.REVIEW_GAPS ) {
            fixes.add( "ignored a gap proposal (gaps not asked for)" ) ;
            return null ;
        }
        String tag = text( gap.get( "tag" ) ) ;
        if( tag == null || tag.isBlank() ) return null ;

        // A schema-bound reply must give a gap object, and some models fill
        // it with a stand-in ("none") instead of null: only a tag of the
        // pass's axis is a proposal.
        Axis axis = ctx.pass().getAxes().get( 0 ) ;
        tag = tag.trim() ;
        if( !tag.startsWith( axis.getPrefix() ) ) {
            fixes.add( "ignored a gap proposal that names no " +
                       axis.getWord() + " tag ('" + tag + "')" ) ;
            return null ;
        }
        return new GapProposal( tag, text( gap.get( "hint" ) ),
                                text( gap.get( "why" ) ) ) ;
    }

    /**
     * The JSON object in {@code reply}: the whole reply less any markdown
     * fences, else the text from its first {@code {} to its last
     * {@code }}; null if there is none.
     */
    static JsonNode parse( String reply ) {

        if( reply == null ) return null ;
        String s     = reply.trim() ;
        int    start = s.indexOf( '{' ) ;
        int    end   = s.lastIndexOf( '}' ) ;

        if( start < 0 || end <= start ) return null ;
        try {
            JsonNode n = MAPPER.readTree( s.substring( start, end + 1 ) ) ;
            return n != null && n.isObject() ? n : null ;
        }
        catch( Exception e ) {
            return null ;
        }
    }

    /** A JSON value as text: a string's or a number's; null otherwise. */
    private static String text( JsonNode n ) {
        if( n == null || n.isNull() ) return null ;
        if( n.isTextual() || n.isNumber() ) return n.asText() ;
        if( n.isArray() ) {
            List<String> parts = new ArrayList<>() ;
            n.forEach( e -> parts.add( e.asText() ) ) ;
            return String.join( ",", parts ) ;
        }
        return null ;
    }
}
