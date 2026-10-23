package com.sandy.sconsole.qimgtools.apps.tagger.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The tags a topic prompt offers the model: its {@code - <tagText>:} lines,
 * by the axis section ({@code ## Setup}, {@code ## Concept},
 * {@code ## Math}) they sit in. Replies are validated against it. What is
 * wrong with the file (a tag the catalogue lacks, a tag in another axis'
 * section, a tag listed twice) is collected in {@link #problems()}; such
 * lines are left out. Immutable.
 */
public final class OfferedSet {

    /**
     * The heading of a topic prompt's notes on dependencies (the routine
     * uses of other topics): not an axis, so its lines are never tags.
     */
    public static final String DEPENDENCIES = "Dependencies" ;

    /** A tag line: {@code - s.connector.pulley: ...} */
    private static final Pattern TAG_LINE =
            Pattern.compile( "^\\s*-\\s+([A-Za-z0-9.\\-]+)\\s*:" ) ;

    /** A section heading: {@code ## Setup ...} */
    private static final Pattern HEADING = Pattern.compile( "^##\\s+(.*)$" ) ;

    private final Map<Axis,List<CatalogueTag>> byAxis =
            new EnumMap<>( Axis.class ) ;
    private final Map<String,CatalogueTag>     byText = new LinkedHashMap<>() ;
    private final List<String>                 problems ;

    private OfferedSet( List<String> problems ) {
        this.problems = Collections.unmodifiableList( problems ) ;
        for( Axis a : Axis.values() ) byAxis.put( a, new ArrayList<>() ) ;
    }

    /** The tags {@code text} (a topic prompt's body) offers. */
    public static OfferedSet parse( String text, TagCatalogue catalogue ) {

        List<String> problems = new ArrayList<>() ;
        OfferedSet   set      = new OfferedSet( problems ) ;
        Axis         section  = null ;
        boolean      notes    = false ;
        int          lineNo   = 0 ;

        for( String line : text.split( "\\R" ) ) {
            lineNo++ ;
            Matcher h = HEADING.matcher( line ) ;
            if( h.matches() ) {
                section = axisOf( h.group( 1 ) ) ;
                notes   = isDependencies( h.group( 1 ) ) ;
                continue ;
            }
            if( notes ) continue ;

            Matcher m = TAG_LINE.matcher( line ) ;
            if( !m.find() ) continue ;

            String       tagText = m.group( 1 ) ;
            CatalogueTag tag     = catalogue.get( tagText ) ;
            String       at      = "line " + lineNo + ": " + tagText ;

            if( tag == null ) {
                problems.add( at + " is not a hinted tag of the catalogue" ) ;
            }
            else if( tag.axis() != section ) {
                problems.add( at + " is not in its " + tag.axis().getHeading() +
                              " section" ) ;
            }
            else if( set.contains( tagText ) ) {
                problems.add( at + " is listed twice" ) ;
            }
            else {
                set.byAxis.get( tag.axis() ).add( tag ) ;
                set.byText.put( key( tag.tagText() ), tag ) ;
            }
        }
        if( set.byAxis.get( Axis.CONCEPT ).isEmpty() ) {
            problems.add( "no concept tag is offered" ) ;
        }
        return set ;
    }

    /** True if a section heading is the {@value #DEPENDENCIES} notes'. */
    public static boolean isDependencies( String heading ) {
        return heading.trim().toLowerCase( Locale.ROOT ).startsWith(
                DEPENDENCIES.toLowerCase( Locale.ROOT ) ) ;
    }

    /** The axis a section heading names; null for any other section. */
    public static Axis axisOf( String heading ) {

        String h = heading.trim().toLowerCase( Locale.ROOT ) ;
        for( Axis a : Axis.values() ) {
            if( h.startsWith( a.getHeading().toLowerCase( Locale.ROOT ) ) ) {
                return a ;
            }
        }
        return null ;
    }

    private static String key( String text ) {
        return text.trim().toLowerCase( Locale.ROOT ) ;
    }

    /** True if {@code tagText} is offered, ignoring case. */
    public boolean contains( String tagText ) {
        return tagText != null && byText.containsKey( key( tagText ) ) ;
    }

    /** The offered tag {@code tagText}, ignoring case; null if not offered. */
    public CatalogueTag get( String tagText ) {
        return tagText == null ? null : byText.get( key( tagText ) ) ;
    }

    /** The offered tags of {@code axis}, in prompt order. */
    public List<CatalogueTag> tags( Axis axis ) {
        return Collections.unmodifiableList( byAxis.get( axis ) ) ;
    }

    /** Every offered tag, setup, concept, then math. */
    public List<CatalogueTag> all() {
        List<CatalogueTag> all = new ArrayList<>() ;
        for( Axis a : Axis.values() ) all.addAll( byAxis.get( a ) ) ;
        return all ;
    }

    /**
     * The tags a run may change on a question: every offered tag, or for a
     * dependent its setup and concept tags (its math was done in its own
     * topic's run).
     */
    public List<CatalogueTag> replaceable( boolean dependent ) {
        return all().stream()
                    .filter( t -> !dependent || t.axis() != Axis.MATH )
                    .toList() ;
    }

    /** The catalogue's tags the prompt leaves out (allowed, warned about). */
    public List<CatalogueTag> leftOut( TagCatalogue catalogue ) {
        return catalogue.tags().stream()
                        .filter( t -> !contains( t.tagText() ) ).toList() ;
    }

    /** What is wrong with the prompt's tag lines; empty if nothing. */
    public List<String> problems() {
        return problems ;
    }
}
