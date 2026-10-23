package com.sandy.sconsole.qimgtools.apps.tagger.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Locale;

/**
 * The pass a tagger process runs, chosen at launch and fixed until it exits:
 * the server's tag class, which picks the queue a pending fetch reads and
 * the flag a refresh clears. The two passes are independent; each tags only
 * its own axes and leaves the other's tags as they are.
 * <ul>
 * <li>{@link #SETUP}: the question is read, not solved, and given its own
 *     topic's setup tags. Every question awaiting them, one tier, no key
 *     check.</li>
 * <li>{@link #CONCEPT}: the question is solved blind and checked against the
 *     key, and given the concept and math tags and its dependencies. Only
 *     questions in an exam, then the topic's dependents.</li>
 * </ul>
 */
@Getter
@RequiredArgsConstructor
public enum TagClass {

    SETUP  ( "Setup",   List.of( Axis.SETUP ) ),
    CONCEPT( "Concept", List.of( Axis.CONCEPT, Axis.MATH ) ) ;

    /** "Setup", "Concept": for people. */
    private final String label ;

    /** The axes the pass tags, in prompt order. */
    private final List<Axis> axes ;

    /**
     * The value the server takes and the prefix of the pass's prompt files:
     * {@code setup}, {@code concept}.
     */
    public String wire() {
        return name().toLowerCase( Locale.ROOT ) ;
    }

    /** True if the pass tags {@code axis}. */
    public boolean tags( Axis axis ) {
        return axes.contains( axis ) ;
    }

    /**
     * True if the model solves the question and its answer is checked
     * against the key (the concept pass).
     */
    public boolean solves() {
        return this == CONCEPT ;
    }

    /** True if the pass also runs the topic's dependents (concept). */
    public boolean hasDependents() {
        return this == CONCEPT ;
    }

    /** The pass named {@code value} ({@code setup}, {@code concept}). */
    public static TagClass of( String value ) {

        for( TagClass c : values() ) {
            if( c.wire().equalsIgnoreCase( value.strip() ) ) return c ;
        }
        throw new IllegalArgumentException( "no tag class '" + value +
                                            "': setup or concept" ) ;
    }

    @Override
    public String toString() { return label ; }
}
