package com.sandy.sconsole.qimgtools.apps.tagger.ui;

import com.sandy.sconsole.qimgtools.apps.tagger.model.Axis;
import com.sandy.sconsole.qimgtools.apps.tagger.model.CatalogueTag;
import com.sandy.sconsole.qimgtools.apps.tagger.prompt.TagPrompter;
import com.sandy.sconsole.qimgtools.apps.tagger.prompt.TagPrompter.Assembly;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Font;

/**
 * The Prompt tab: the scope's prompt in use, with its versions, the tags it
 * offers by axis and its estimated size, above the prompt itself as a
 * question of the topic gets it. Use on the EDT.
 */
@SuppressWarnings( "serial" )
final class PromptPanel extends JPanel {

    private final JTextArea text = new JTextArea() ;

    PromptPanel() {

        super( new BorderLayout() ) ;
        text.setEditable( false ) ;
        text.setLineWrap( true ) ;
        text.setWrapStyleWord( true ) ;
        text.setFont( new Font( Font.MONOSPACED, Font.PLAIN,
                                text.getFont().getSize() ) ) ;
        add( new JScrollPane( text ), BorderLayout.CENTER ) ;
        show( null ) ;
    }

    /** Show {@code a}; null says no scope is set. */
    void show( Assembly a ) {

        if( a == null ) {
            text.setText( "No scope: choose one with File > Scope…" ) ;
            return ;
        }

        StringBuilder sb = new StringBuilder() ;
        sb.append( a.topic().getId() ).append( ' ' )
          .append( a.topic().getTopicName() ).append( "  -  " )
          .append( a.tagClass() ).append( " pass, " )
          .append( a.mode() ).append( "\nPrompt v" )
          .append( a.promptVersion() ).append( ", template v" )
          .append( a.templateVersion() ).append( ", catalogue " )
          .append( a.catalogue().fingerprint( a.tagClass() ) )
          .append( "\nAbout " )
          .append( TagPrompter.estimateTokens( a.own() ) )
          .append( " input tokens per question" ) ;
        if( a.tagClass().hasDependents() ) {
            sb.append( ", " )
              .append( TagPrompter.estimateTokens( a.dependent() ) )
              .append( " per dependent" ) ;
        }
        sb.append( " (image not counted)\n" ) ;
        for( Axis axis : a.tagClass().getAxes() ) {
            sb.append( '\n' ).append( axis.getHeading() ).append( " (" )
              .append( a.offered().tags( axis ).size() ).append( "): " )
              .append( String.join( ", ", a.offered().tags( axis ).stream()
                                           .map( CatalogueTag::tagText )
                                           .toList() ) ) ;
        }
        sb.append( "\n\n================ prompt (own question) " +
                   "================\n\n" ).append( a.own() ) ;
        text.setText( sb.toString() ) ;
        text.setCaretPosition( 0 ) ;
    }
}
