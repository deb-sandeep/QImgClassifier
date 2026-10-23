package com.sandy.sconsole.qimgtools.apps.tagger.prompt;

import com.sandy.sconsole.qimgtools.apps.tagger.model.Axis;
import com.sandy.sconsole.qimgtools.apps.tagger.model.CatalogueTag;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagCatalogue;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagClass;

/**
 * Writes a topic's base prompt of a pass from its catalogue, mechanically:
 * one section per axis of the pass ({@code ## Setup tags} for setup;
 * {@code ## Concept tags} and {@code ## Math tags} for concept) stating the
 * axis' rule once, then a {@code - <tagText>: <hint>} line per tag with the
 * hint less that rule. Every Foundation Mathematics tag goes in the math
 * section; the user prunes per topic, and may add a section of topic notes
 * (in the concept pass, {@code ## Dependencies}). No rewording. Stateless.
 */
public final class PromptGenerator {

    private PromptGenerator() {}

    /**
     * The base prompt's text of pass {@code tagClass} for a topic with
     * {@code catalogue}.
     */
    public static String generate( TagCatalogue catalogue,
                                   TagClass tagClass ) {

        StringBuilder sb = new StringBuilder() ;
        for( Axis axis : tagClass.getAxes() ) {
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
