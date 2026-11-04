package com.sandy.sconsole.qimgtools.apps.tagger.model;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The tags a question is to be saved with: its chosen set. A choice (the
 * regression's tags, the existing ones) sets it whole; the user then edits it
 * a tag at a time ({@link #edited}).
 *
 * @param tags   the topic's tags to put on it; its explicit tags of other
 *               topics are kept
 * @param source what the set started from
 * @param edited true if the user changed the set since it was chosen
 * @param human  true if the user decided it; false if the regression settled it
 */
public record TagDecision( List<String> tags, Source source, boolean edited,
                           boolean human ) {

    /** What a decision's set started from. */
    public enum Source {

        REGRESSION( "regression" ), EXISTING( "existing" ), USER( "user" ) ;

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

    /** The tags {@code tags} the regression settled on. */
    public static TagDecision regression( List<String> tags ) {
        return new TagDecision( tags, Source.REGRESSION, false, false ) ;
    }

    /** The tags {@code tags} already on the question. */
    public static TagDecision existing( List<String> tags ) {
        return new TagDecision( tags, Source.EXISTING, false, true ) ;
    }

    /** An empty set, for the user to fill. */
    public static TagDecision empty() {
        return new TagDecision( List.of(), Source.USER, false, true ) ;
    }

    /**
     * {@code base} with the set changed to {@code tags}: {@code base} itself
     * if they are its tags in any order, else the user's.
     */
    public static TagDecision edited( TagDecision base, List<String> tags ) {

        boolean same = tags.size() == base.tags.size()
                       && new HashSet<>( tags ).containsAll( base.tags ) ;

        return same ? base.asHuman()
                    : new TagDecision( tags, base.source, true, true ) ;
    }

    /**
     * This decision with {@code existing}, the tags already on the question,
     * in front: they stay unless the user takes them out.
     */
    public TagDecision withExisting( List<String> existing ) {

        if( new HashSet<>( tags ).containsAll( existing ) ) return this ;

        LinkedHashSet<String> all = new LinkedHashSet<>( existing ) ;
        all.addAll( tags ) ;
        return new TagDecision( List.copyOf( all ), source, edited, human ) ;
    }

    /** This decision, as the user's. */
    public TagDecision asHuman() {
        return human ? this
                     : new TagDecision( tags, source, edited, true ) ;
    }
}
