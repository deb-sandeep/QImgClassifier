package com.sandy.sconsole.qimgtools.apps.tagger.model;

import java.util.HashSet;
import java.util.List;

/**
 * The tags a question is to be saved with: its chosen set. A choice (the
 * model's tags, the existing ones) sets it whole; the user then edits it a
 * tag at a time ({@link #edited}). It stays credited to the model while it
 * is as the model gave it.
 *
 * @param tags   the topic's tags to put on it; its explicit tags of other
 *               topics are kept
 * @param source what the set started from
 * @param model  the model the tags are credited to; null for the user
 * @param edited true if the user changed the set since it was chosen
 * @param human  true if the user decided it; false if the model settled it
 */
public record TagDecision( List<String> tags, Source source, String model,
                           boolean edited, boolean human ) {

    /** What a decision's set started from. */
    public enum Source {

        MODEL( "model" ), EXISTING( "existing" ), USER( "user" ) ;

        private final String label ;

        Source( String label ) {
            this.label = label ;
        }

        @Override
        public String toString() { return label ; }
    }

    public TagDecision {
        tags = List.copyOf( tags ) ;
    }

    /** The tags of {@code proposal}. */
    public static TagDecision of( TagProposal proposal, boolean human ) {
        return new TagDecision( proposal.reply().tags(), Source.MODEL,
                                proposal.model(), false, human ) ;
    }

    /** The tags {@code tags} already on the question. */
    public static TagDecision existing( List<String> tags ) {
        return new TagDecision( tags, Source.EXISTING, null, false, true ) ;
    }

    /** An empty set, for the user to fill. */
    public static TagDecision empty() {
        return new TagDecision( List.of(), Source.USER, null, false, true ) ;
    }

    /**
     * {@code base} with the set changed to {@code tags}: {@code base} itself
     * if they are its tags in any order, else the user's.
     */
    public static TagDecision edited( TagDecision base, List<String> tags ) {

        boolean same = tags.size() == base.tags.size()
                       && new HashSet<>( tags ).containsAll( base.tags ) ;

        return same ? base.asHuman()
                    : new TagDecision( tags, base.source, null, true, true ) ;
    }

    /** This decision, as the user's. */
    public TagDecision asHuman() {
        return human ? this
                     : new TagDecision( tags, source, model, edited, true ) ;
    }
}
