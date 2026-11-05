package com.sandy.sconsole.qimgtools.apps.tagger.ui;

import com.sandy.sconsole.qimgtools.apps.tagger.model.TagProposal;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagReply;
import com.sandy.sconsole.qimgtools.apps.tagger.regression.Assessment;
import com.sandy.sconsole.qimgtools.apps.tagger.regression.Decision;
import com.sandy.sconsole.qimgtools.apps.tagger.ui.TagTableModel.Row;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.awt.Font;
import java.util.List;
import java.util.Locale;

/**
 * The local view of the selected question: the LLM's reply if it has one, where
 * it goes and why, the accepted, undecided and triggered tags with their scores,
 * and the OCR text it read, under "--- title ---" headers. The lines wrap, so
 * there is no sideways scrolling. Blank unless exactly one question is selected.
 * Use on the EDT.
 */
@SuppressWarnings( "serial" )
final class RegressionPanel extends JPanel {

    private static final int FONT_PT = 12 ;

    private final JTextArea text = new JTextArea() ;

    /** The row shown; null if none. */
    private Row row ;

    RegressionPanel() {

        super( new BorderLayout() ) ;
        text.setEditable( false ) ;
        text.setLineWrap( true ) ;
        text.setWrapStyleWord( true ) ;
        text.setFont( new Font( Font.MONOSPACED, Font.PLAIN, FONT_PT ) ) ;
        text.setBorder( BorderFactory.createEmptyBorder( 6, 8, 6, 8 ) ) ;
        add( new JScrollPane( text,
                              ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                              ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER ),
             BorderLayout.CENTER ) ;
    }

    /** Show {@code row}'s assessment; null clears the panel. */
    void show( Row row ) {

        this.row = row ;
        text.setText( row == null ? "" : describe( row ) ) ;
        text.setCaretPosition( 0 ) ;
    }

    /** Re-show the row shown if it is {@code changed}. */
    void refresh( Row changed ) {
        if( changed == row ) show( row ) ;
    }

    private static String describe( Row row ) {

        Assessment a = row.assessment ;
        if( a == null ) {
            return row.item.hasImages() ? "Not read yet" : "No local images" ;
        }
        if( a.failure() != null ) return "OCR failed: " + a.failure() ;

        Decision      d = a.decision() ;
        StringBuilder b = new StringBuilder() ;

        if( row.proposal != null ) reply( b, row.proposal ) ;

        header( b, "Regression" ) ;
        if( d.sendsToLlm() ) {
            b.append( "Goes to the LLM: " ).append( d.llmReason() ).append( '\n' ) ;
        }
        else if( d.notAccepted().isEmpty() ) {
            b.append( "Settled: the regression's tags are accepted\n" ) ;
        }
        else {
            b.append( "Partial: the accepted tags stand, the others are " +
                      "dropped\n" ) ;
        }
        scored( b, "Accepted", d.accepted(), d ) ;
        scored( b, "Undecided", d.undecided(), d ) ;
        if( !d.triggered().isEmpty() ) {
            b.append( '\n' ) ;
            header( b, "Triggered" ) ;
            d.triggered().forEach( t -> b.append( "         " ).append( t )
                                         .append( '\n' ) ) ;
        }
        b.append( '\n' ) ;
        header( b, "OCR text" ) ;
        return b.append( a.ocrText().strip() ).toString() ;
    }

    private static void header( StringBuilder b, String title ) {
        b.append( "--- " ).append( title ).append( " ---\n" ) ;
    }

    private static void reply( StringBuilder b, TagProposal p ) {

        TagReply r = p.reply() ;
        header( b, "LLM reply" ) ;
        b.append( p.model() )
         .append( ", confidence " ).append( r.confidence() ).append( '\n' ) ;
        if( r.noTagFits() ) b.append( "  no tag fits\n" ) ;
        else b.append( "  " ).append( String.join( ", ", r.tags() ) )
              .append( '\n' ) ;
        if( r.framing() != null ) {
            b.append( "  framing: " ).append( r.framing() ).append( '\n' ) ;
        }
        if( r.reason() != null ) {
            b.append( "  reason: " ).append( r.reason() ).append( '\n' ) ;
        }
        b.append( '\n' ) ;
    }

    private static void scored( StringBuilder b, String title, List<String> tags,
                                Decision d ) {

        if( tags.isEmpty() ) return ;
        b.append( '\n' ) ;
        header( b, title ) ;
        for( String t : tags ) {
            b.append( String.format( Locale.ROOT, "  %.3f  %s%n",
                                     d.scores().get( t ), t ) ) ;
        }
    }
}
