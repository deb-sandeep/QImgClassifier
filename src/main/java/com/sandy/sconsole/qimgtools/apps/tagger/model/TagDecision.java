package com.sandy.sconsole.qimgtools.apps.tagger.model;

import java.util.List;

/**
 * The tags a question is to be saved with, and its dependencies.
 *
 * @param tags         the offered tags to put on it (the tags the run may
 *                     replace; the rest of its explicit tags are kept)
 * @param dependencies for a question of the scoped topic, the topics of its
 *                     syllabus it depends on; ignored for a dependent
 * @param source       where the tags come from
 * @param model        the model whose reply it is; null for the user's
 *                     own set
 * @param human        true if the user decided it (a key, the editor, the
 *                     gap dialog); false if the models settled it
 * @param note         the user's note on a deferral; null otherwise
 */
public record TagDecision( List<String> tags, List<Integer> dependencies,
                           Source source, String model, boolean human,
                           String note ) {

    public static final String AI   = "AI" ;
    public static final String USER = "User" ;

    /** Where a decision's tags come from. */
    public enum Source {

        TIER1( "tier 1" ), TIER2( "tier 2" ), EDITED( "edited" ),
        EXISTING( "existing" ), DEFERRED( "deferred" ) ;

        private final String label ;

        Source( String label ) {
            this.label = label ;
        }

        @Override
        public String toString() { return label ; }
    }

    public TagDecision {
        tags         = List.copyOf( tags ) ;
        dependencies = List.copyOf( dependencies ) ;
    }

    /** {@code reply}'s tags and dependencies, as tier {@code reply.tier()}'s. */
    public static TagDecision of( TierReply reply, boolean human ) {
        return new TagDecision( reply.reply().tags(), reply.reply().xdep(),
                                reply.tier() == 1 ? Source.TIER1 : Source.TIER2,
                                reply.model(), human, null ) ;
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
        return model != null ? AI : USER ;
    }
}
