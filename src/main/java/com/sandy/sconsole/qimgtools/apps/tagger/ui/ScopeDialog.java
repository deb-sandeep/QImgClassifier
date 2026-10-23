package com.sandy.sconsole.qimgtools.apps.tagger.ui;

import com.sandy.sconsole.qimgtools.apps.tagger.model.Scope;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagCatalogue;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagClass;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagMode;
import com.sandy.sconsole.qimgtools.apps.tagger.prompt.PromptStore;
import com.sandy.sconsole.qimgtools.apps.tagger.server.TaggerServerClient;
import com.sandy.sconsole.qimgtools.core.model.Topic;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.List;
import java.util.function.Function;

/**
 * Chooses the scope of the pass: one topic, from a syllabus'
 * {@link TopicTable} showing where each one's prompt of the pass stands (a
 * topic that can't be chosen greyed, with why); the batch size; and the tag
 * mode. Only a topic with an approved prompt can be chosen; Foundation
 * Mathematics, whose tags are every topic's math, never. Whether the
 * approved prompt is still current is checked against the server's
 * catalogue once the dialog closes.
 */
final class ScopeDialog {

    private ScopeDialog() {}

    /**
     * Ask for a scope, starting from {@code current} (null for none).
     *
     * @param problem why a scope is asked for, shown on top; null if the
     *        user asked
     * @return the scope chosen, keeping {@code current}'s cursors if its
     *         topic is unchanged and "Start from the first question" is
     *         not ticked; null if the user cancelled
     */
    static Scope ask( Component parent, TagClass pass, Scope current,
                      String problem,
                      List<String> syllabusNames,
                      Function<String,List<Topic>> topicsOf,
                      Function<Topic,PromptStore.Summary> summaryOf ) {

        JComboBox<String>  syllabus = new JComboBox<>(
                syllabusNames.toArray( new String[0] ) ) ;
        TopicTable         table    = new TopicTable(
                summaryOf, ScopeDialog::why, "Can't scope" ) ;
        JSpinner           batch    = new JSpinner( new SpinnerNumberModel(
                current != null ? current.batchSize() : 50, 1,
                TaggerServerClient.MAX_LIMIT, 10 ) ) ;
        JComboBox<TagMode> mode     = new JComboBox<>( TagMode.values() ) ;

        syllabus.addActionListener( e -> table.show(
                topicsOf.apply( (String) syllabus.getSelectedItem() ),
                null ) ) ;

        if( current != null ) {
            mode.setSelectedItem( current.mode() ) ;
            for( String s : syllabusNames ) {
                if( topicsOf.apply( s ).stream().anyMatch(
                        t -> t.getId() == current.topicId() ) ) {
                    syllabus.setSelectedItem( s ) ;
                }
            }
        }
        table.show( topicsOf.apply( (String) syllabus.getSelectedItem() ),
                    current != null ? current.topicId() : null ) ;

        JPanel options = new JPanel( new FlowLayout( FlowLayout.LEFT, 8, 0 ) ) ;
        options.add( new JLabel( "Questions per load:" ) ) ;
        options.add( batch ) ;
        options.add( new JLabel( "   Tag mode:" ) ) ;
        options.add( mode ) ;

        // Where the next load continues, and a way back to the start.
        JCheckBox reset  = new JCheckBox( "Start from the first question" ) ;
        JPanel    bottom = new JPanel( new GridLayout( 0, 1, 0, 4 ) ) ;
        bottom.add( options ) ;
        if( current != null && current.hasCursors() ) {
            JPanel cursor = new JPanel( new FlowLayout( FlowLayout.LEFT, 8, 0 ) ) ;
            cursor.add( new JLabel( cursorText( pass, current, topicsOf,
                                                syllabusNames ) ) ) ;
            cursor.add( reset ) ;
            bottom.add( cursor ) ;
        }

        JPanel top = new JPanel( new GridLayout( 0, 1, 0, 4 ) ) ;
        if( problem != null ) {
            JLabel p = new JLabel( problem ) ;
            p.setForeground( new Color( 0xC62828 ) ) ;
            top.add( p ) ;
        }
        top.add( syllabus ) ;

        JPanel panel = new JPanel( new BorderLayout( 0, 6 ) ) ;
        panel.add( top, BorderLayout.NORTH ) ;
        panel.add( table.component(), BorderLayout.CENTER ) ;
        panel.add( bottom, BorderLayout.SOUTH ) ;

        while( true ) {
            int answer = JOptionPane.showConfirmDialog( parent, panel,
                    pass + " scope", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE ) ;
            if( answer != JOptionPane.OK_OPTION ) return null ;

            Topic  t   = table.selected() ;
            String why = t == null ? "Choose a topic"
                                   : table.selectedBlocked() ;
            if( why != null ) {
                JOptionPane.showMessageDialog( parent, t == null ? why
                        : t.getTopicName() + " can't be chosen: " + why,
                        "Scope", JOptionPane.WARNING_MESSAGE ) ;
                continue ;
            }

            int     size = (Integer) batch.getValue() ;
            TagMode m    = (TagMode) mode.getSelectedItem() ;
            boolean same = current != null && current.topicId() == t.getId()
                           && !reset.isSelected() ;

            return new Scope( t.getId(), size, m,
                              same ? current.ownAfterId() : null,
                              same ? current.depAfterId() : null ) ;
        }
    }

    /**
     * Where the next load of {@code scope}'s topic continues: after the
     * last question of each queue already loaded (the setup pass has one).
     */
    private static String cursorText( TagClass pass, Scope scope,
                                      Function<String,List<Topic>> topicsOf,
                                      List<String> syllabusNames ) {

        String name = syllabusNames.stream()
                .flatMap( s -> topicsOf.apply( s ).stream() )
                .filter( t -> t.getId() == scope.topicId() )
                .map( Topic::getTopicName ).findFirst()
                .orElse( "Topic " + scope.topicId() ) ;
        return name + ": the next load continues " +
               after( scope.ownAfterId() ) +
               ( pass.hasDependents() ? " (dependents: " +
                                        after( scope.depAfterId() ) + ")"
                                      : "" ) ;
    }

    private static String after( Integer id ) {
        return id == null ? "from the first" : "after question " + id ;
    }

    /** Why {@code t} can't be scoped; null if it can. */
    private static String why( Topic t, PromptStore.State state ) {

        if( t.getId() == TagCatalogue.FOUNDATION_MATHS ) {
            return "tagged by hand" ;
        }
        return state == PromptStore.State.APPROVED ? null
               : "prompt " + state ;
    }
}
