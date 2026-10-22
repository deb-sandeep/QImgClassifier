package com.sandy.sconsole.qimgtools.apps.tagger.ui;

import com.sandy.sconsole.qimgtools.apps.tagger.model.CatalogueTag;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagCatalogue;
import com.sandy.sconsole.qimgtools.apps.tagger.prompt.PromptReview;
import com.sandy.sconsole.qimgtools.apps.tagger.prompt.PromptStore;
import com.sandy.sconsole.qimgtools.apps.tagger.prompt.TagPrompter;
import com.sandy.sconsole.qimgtools.core.model.Topic;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The Prompts menu's dialogs: picking a topic, its prompt's state shown,
 * and reviewing a topic's prompt as the models would get it before it is
 * approved.
 */
final class PromptDialogs {

    private static final String APPROVE = "Approve" ;
    private static final String CLOSE   = "Close" ;

    private PromptDialogs() {}

    /**
     * Ask for a topic of one of {@code syllabusNames}, each marked with
     * where its prompt stands; Foundation Mathematics is left out.
     *
     * @param initial the topic to start on; null for none
     * @return the topic picked; null if the user cancelled
     */
    static Topic pickTopic( Component parent, String title,
                            List<String> syllabusNames,
                            Function<String,List<Topic>> topicsOf,
                            Function<Topic,PromptStore.State> stateOf,
                            Topic initial ) {

        JComboBox<String> syllabus = new JComboBox<>(
                syllabusNames.toArray( new String[0] ) ) ;
        JList<Topic>      list     = new JList<>() ;

        list.setSelectionMode( ListSelectionModel.SINGLE_SELECTION ) ;
        list.setVisibleRowCount( 20 ) ;
        list.setCellRenderer( new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent( JList<?> l,
                    Object value, int index, boolean selected,
                    boolean focus ) {
                Topic t = (Topic) value ;
                return super.getListCellRendererComponent( l,
                        t.getId() + "  " + t.getTopicName() + "   [" +
                        stateOf.apply( t ) + "]", index, selected, focus ) ;
            }
        } ) ;
        syllabus.addActionListener( e -> list.setListData(
                topics( topicsOf, (String) syllabus.getSelectedItem() ) ) ) ;
        if( initial != null ) syllabus.setSelectedItem( initial.getSyllabusName() ) ;
        list.setListData( topics( topicsOf,
                                  (String) syllabus.getSelectedItem() ) ) ;
        if( initial != null ) list.setSelectedValue( initial, true ) ;

        JPanel panel = new JPanel( new BorderLayout( 0, 6 ) ) ;
        panel.add( syllabus, BorderLayout.NORTH ) ;
        panel.add( new JScrollPane( list ), BorderLayout.CENTER ) ;

        int answer = JOptionPane.showConfirmDialog( parent, panel, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE ) ;
        return answer == JOptionPane.OK_OPTION ? list.getSelectedValue()
                                               : null ;
    }

    private static Topic[] topics( Function<String,List<Topic>> topicsOf,
                                   String syllabus ) {
        return topicsOf.apply( syllabus ).stream()
                       .filter( t -> t.getId() != TagCatalogue.FOUNDATION_MATHS )
                       .toArray( Topic[]::new ) ;
    }

    /**
     * Show {@code review}: where the prompt stands, what it would cost, what
     * is wrong with it or left out, and the prompt itself for either kind of
     * question. Approve is offered unless the prompt is approved and current
     * or has problems.
     *
     * @return true if the user chose to approve it
     */
    static boolean review( Component parent, PromptReview review ) {

        PromptStore.State state  = review.state() ;
        int               own    = TagPrompter.estimateTokens( review.own() ) ;
        int               dep    = TagPrompter.estimateTokens(
                                           review.dependent() ) ;
        StringBuilder     sb     = new StringBuilder() ;

        sb.append( "Topic: " ).append( review.topic().getId() ).append( ' ' )
          .append( review.topic().getTopicName() ).append( "\nPrompt: " )
          .append( state ).append( ", version " )
          .append( review.topicPrompt().getInt( "promptVersion", 0 ) )
          .append( "\nEstimated input: about " ).append( own )
          .append( " tokens per question (" ).append( dep )
          .append( " for a dependent), image not counted\n" ) ;
        for( Map.Entry<String,Double> e : review.costPer1000().entrySet() ) {
            sb.append( String.format( "  %s: $%.2f per 1,000 questions " +
                                      "(list price, no cache discount)%n",
                                      e.getKey(), e.getValue() ) ) ;
        }
        if( !review.problems().isEmpty() ) {
            sb.append( "\nProblems (fix them before approving):\n" ) ;
            review.problems().forEach( p -> sb.append( "  " ).append( p )
                                              .append( '\n' ) ) ;
        }
        if( !review.leftOut().isEmpty() ) {
            sb.append( "\nLeft out of the prompt (allowed):\n  " ) ;
            sb.append( String.join( ", ", review.leftOut().stream()
                                              .map( CatalogueTag::tagText )
                                              .toList() ) ).append( '\n' ) ;
        }

        JTextArea summary = text( sb.toString(), 8 ) ;
        JTabbedPane prompts = new JTabbedPane() ;
        prompts.addTab( "Own questions", new JScrollPane(
                text( review.own(), 30 ) ) ) ;
        prompts.addTab( "Dependents", new JScrollPane(
                text( review.dependent(), 30 ) ) ) ;
        prompts.setPreferredSize( new Dimension( 900, 520 ) ) ;

        JPanel panel = new JPanel( new BorderLayout( 0, 6 ) ) ;
        panel.add( new JScrollPane( summary ), BorderLayout.NORTH ) ;
        panel.add( prompts, BorderLayout.CENTER ) ;

        boolean  approvable = review.approvable()
                              && state != PromptStore.State.APPROVED ;
        Object[] options    = approvable ? new Object[] { APPROVE, CLOSE }
                                         : new Object[] { CLOSE } ;
        int      answer     = JOptionPane.showOptionDialog( parent, panel,
                "Review topic prompt", JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, options, CLOSE ) ;

        return approvable && answer == 0 ;
    }

    private static JTextArea text( String s, int rows ) {

        JTextArea t = new JTextArea( s, rows, 80 ) ;
        t.setEditable( false ) ;
        t.setLineWrap( true ) ;
        t.setWrapStyleWord( true ) ;
        t.setFont( new Font( Font.MONOSPACED, Font.PLAIN,
                             t.getFont().getSize() ) ) ;
        t.setCaretPosition( 0 ) ;
        return t ;
    }
}
