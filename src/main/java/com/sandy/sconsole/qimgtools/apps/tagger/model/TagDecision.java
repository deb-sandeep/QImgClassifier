package com.sandy.sconsole.qimgtools.apps.tagger.model;

import java.util.HashSet;
import java.util.List;

/**
 * The tags a question is to be saved with, and its dependencies: its chosen
 * set. A choice (a tier's tags, the hand tags) sets it whole; the user then
 * edits it a tag or a topic at a time ({@link #edited}). The tags and the
 * dependencies are credited apart: each to the model whose reply it is, as
 * long as it is left as that reply gave it, else to the user.
 *
 * @param tags            the offered tags to put on it (the tags the run
 *                        may replace; the rest of its explicit tags are
 *                        kept)
 * @param dependencies    for a question of the scoped topic, the topics of
 *                        its syllabus it depends on; null to leave them as
 *                        they are on the server; ignored for a dependent
 * @param source          what the set started from
 * @param model           the model the tags are credited to; null for the
 *                        user
 * @param dependencyModel the model the dependencies are credited to; null
 *                        for the user
 * @param edited          true if the user changed the set since it was
 *                        chosen
 * @param human           true if the user decided it (a key, an edit, the
 *                        gap dialog); false if the models settled it
 * @param note            the user's note on a deferral; null otherwise
 */
public record TagDecision( List<String> tags, List<Integer> dependencies,
                           Source source, String model,
                           String dependencyModel, boolean edited,
                           boolean human, String note ) {

    public static final String AI   = "AI" ;
    public static final String USER = "User" ;

    /** What a decision's set started from. */
    public enum Source {

        TIER1( "tier 1" ), TIER2( "tier 2" ), EXISTING( "existing" ),
        USER( "user" ), DEFERRED( "deferred" ) ;

        private final String label ;

        Source( String label ) {
            this.label = label ;
        }

        @Override
        public String toString() { return label ; }
    }

    public TagDecision {
        tags = List.copyOf( tags ) ;
        if( dependencies != null ) dependencies = List.copyOf( dependencies ) ;
    }

    /** {@code reply}'s tags and dependencies, as tier {@code reply.tier()}'s. */
    public static TagDecision of( TierReply reply, boolean human ) {
        return new TagDecision( reply.reply().tags(), reply.reply().xdep(),
                                reply.tier() == 1 ? Source.TIER1 : Source.TIER2,
                                reply.model(), reply.model(), false, human,
                                null ) ;
    }

    /** The hand tags {@code tags}, the dependencies left as they are. */
    public static TagDecision existing( List<String> tags ) {
        return new TagDecision( tags, null, Source.EXISTING, null, null,
                                false, true, null ) ;
    }

    /** An empty set, for the user to fill. */
    public static TagDecision empty() {
        return new TagDecision( List.of(), null, Source.USER, null, null,
                                false, true, null ) ;
    }

    /**
     * {@code base} with the set changed to {@code tags} and
     * {@code dependencies} (null: as {@code base} has them). Each part keeps
     * {@code base}'s credit if it is as {@code base} has it (in any order),
     * else is the user's.
     */
    public static TagDecision edited( TagDecision base, List<String> tags,
                                      List<Integer> dependencies ) {

        boolean sameTags = sameSet( tags, base.tags ) ;
        boolean sameDeps = dependencies == null
                           || base.dependencies != null
                              && sameSet( dependencies, base.dependencies ) ;

        if( sameTags && sameDeps ) return base.asHuman() ;
        return new TagDecision( tags, sameDeps ? base.dependencies
                                               : dependencies,
                                base.source,
                                sameTags ? base.model : null,
                                sameDeps ? base.dependencyModel : null,
                                true, true, null ) ;
    }

    private static <T> boolean sameSet( List<T> a, List<T> b ) {
        return a.size() == b.size() && new HashSet<>( a ).containsAll( b ) ;
    }

    /** This decision, as the user's. */
    public TagDecision asHuman() {
        return human ? this
                     : new TagDecision( tags, dependencies, source, model,
                                        dependencyModel, edited, true, note ) ;
    }

    /** True for a deferral: nothing is saved, the question stays pending. */
    public boolean isDeferral() {
        return source == Source.DEFERRED ;
    }

    /**
     * Who the dependencies are credited to on the server: the model when
     * they are its reply's, else the user.
     */
    public String dependencyActor() {
        return dependencyModel != null ? AI : USER ;
    }
}
