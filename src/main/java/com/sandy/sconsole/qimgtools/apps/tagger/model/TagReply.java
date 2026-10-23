package com.sandy.sconsole.qimgtools.apps.tagger.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A model's reply, as read and checked: in the concept pass its answer to
 * the blind solve, the tags it chose by axis, the other topics the solution
 * needs; in the setup pass only setup tags; and any new tag it proposes
 * (only in {@link TagMode#REVIEW_GAPS}).
 *
 * @param answer  the answer, in the labels the image prints (e.g. "C",
 *                "1,3", "2.5"); null in the setup pass, which asks for
 *                none, and in the concept pass if the model declined to
 *                answer, and then it has no tags
 * @param setup   setup tags, in reply order
 * @param concept concept tags, each with why it is dominant
 * @param math    math tags
 * @param xdep    topic ids of the syllabus the solution depends on; empty
 *                for a dependent
 * @param gap     a proposed new tag; null if none
 * @param fixes   what was mended locally to read the reply (e.g. a tag's
 *                case); empty if nothing
 */
public record TagReply( String answer, List<String> setup,
                        List<ConceptPick> concept, List<String> math,
                        List<Integer> xdep, GapProposal gap,
                        List<String> fixes ) {

    /** A concept tag and why the model found it dominant. */
    public record ConceptPick( String tag, String why ) {}

    /**
     * A tag the model would add to the topic.
     *
     * @param tag  its proposed tag text
     * @param hint when to apply it, as the model words it
     * @param why  what in this question needs it
     */
    public record GapProposal( String tag, String hint, String why ) {}

    public TagReply {
        setup   = List.copyOf( setup ) ;
        concept = List.copyOf( concept ) ;
        math    = List.copyOf( math ) ;
        xdep    = List.copyOf( xdep ) ;
        fixes   = List.copyOf( fixes ) ;
    }

    /** The reply of a model that declined to answer: no answer, no tags. */
    public static TagReply abstained() {
        return new TagReply( null, List.of(), List.of(), List.of(), List.of(),
                             null, List.of() ) ;
    }

    /**
     * True if the model declined to answer: meaningful only in the concept
     * pass (a setup reply has no answer).
     */
    public boolean isAbstained() {
        return answer == null ;
    }

    /** The answer as shown: "no answer" if the model declined. */
    public String answerText() {
        return answer == null ? "no answer" : answer ;
    }

    /** Every tag chosen: setup, concept, then math. */
    public List<String> tags() {

        List<String> tags = new ArrayList<>( setup ) ;
        concept.forEach( c -> tags.add( c.tag() ) ) ;
        tags.addAll( math ) ;
        return tags ;
    }

    /** This reply without its gap proposal (once it is settled). */
    public TagReply withoutGap() {
        return new TagReply( answer, setup, concept, math, xdep, null, fixes ) ;
    }

    /** This reply with {@code tag} added to its axis' tags. */
    public TagReply withTag( String tag ) {

        if( tags().contains( tag ) ) return this ;

        Axis axis = Axis.of( tag ) ;
        if( axis == null ) {
            throw new IllegalArgumentException( tag + " has no axis prefix" ) ;
        }

        List<String>      s = new ArrayList<>( setup ) ;
        List<ConceptPick> c = new ArrayList<>( concept ) ;
        List<String>      m = new ArrayList<>( math ) ;

        switch( axis ) {
            case SETUP   -> s.add( tag ) ;
            case CONCEPT -> c.add( new ConceptPick( tag, gap != null
                                                         ? gap.why() : null ) ) ;
            case MATH    -> m.add( tag ) ;
        }
        return new TagReply( answer, s, c, m, xdep, gap, fixes ) ;
    }
}
