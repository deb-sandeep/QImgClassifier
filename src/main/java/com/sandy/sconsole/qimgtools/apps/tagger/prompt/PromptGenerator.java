package com.sandy.sconsole.qimgtools.apps.tagger.prompt;

import com.sandy.sconsole.qimgtools.apps.tagger.model.Axis;
import com.sandy.sconsole.qimgtools.apps.tagger.model.CatalogueTag;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagCatalogue;

/**
 * Writes a topic's base prompt from its catalogue, mechanically: one
 * section per axis ({@code ## Setup tags}, {@code ## Concept tags},
 * {@code ## Math tags}) stating the axis' rule once, then a
 * {@code - <tagText>: <hint>} line per tag with the hint less that rule.
 * Every Foundation Mathematics tag goes in the math section; the user
 * prunes per topic, and may add a section of topic notes. No rewording.
 * Stateless.
 */
public final class PromptGenerator {

    private PromptGenerator() {}

    /** The base prompt's text for a topic with {@code catalogue}. */
    public static String generate( TagCatalogue catalogue ) {

        StringBuilder sb = new StringBuilder() ;
        for( Axis axis : Axis.values() ) {
            sb.append( "## " ).append( axis.getHeading() ).append( " tags\n\n" ) ;

            String rule = catalogue.axisRule( axis ) ;
            if( !rule.isEmpty() ) sb.append( rule ).append( "\n\n" ) ;

            for( CatalogueTag t : catalogue.tags( axis ) ) {
                sb.append( "- " ).append( t.tagText() ).append( ": " )
                  .append( catalogue.ownHint( t ) ).append( '\n' ) ;
            }
            sb.append( '\n' ) ;
        }
        return sb.toString().strip() + "\n" ;
    }
}
