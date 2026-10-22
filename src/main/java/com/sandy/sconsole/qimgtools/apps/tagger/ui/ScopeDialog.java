package com.sandy.sconsole.qimgtools.apps.tagger.ui;

import com.sandy.sconsole.qimgtools.apps.tagger.model.Scope;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagCatalogue;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagMode;
import com.sandy.sconsole.qimgtools.apps.tagger.prompt.PromptStore;
import com.sandy.sconsole.qimgtools.apps.tagger.server.TaggerServerClient;
import com.sandy.sconsole.qimgtools.core.model.Topic;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Chooses the scope: one topic, from a syllabus' list, each marked with
 * where its prompt stands; the batch size; and the tag mode. Only a topic
 * with an approved prompt can be chosen; Foundation Mathematics, whose tags
 * are every topic's math, never. Whether the approved prompt is still
 * current is checked against the server's catalogue once the dialog closes.
 */
final class ScopeDialog {

    private ScopeDialog() {}

    /**
     * Ask for a scope, starting from {@code current} (null for none).
     *
     * @param problem why a scope is asked for, shown on top; null if the
     *        user asked
     * @return the scope chosen, keeping {@code current}'s cursors if its
     *         topic is unchanged; null if the user cancelled
     */
    static Scope ask( Component parent, Scope current, String problem,
                      List<String> syllabusNames,
                      Function<String,List<Topic>> topicsOf,
                      Function<Topic,PromptStore.State> stateOf ) {

        Map<Integer,PromptStore.State> states = new HashMap<>() ;
        JComboBox<String> syllabus = new JComboBox<>(
                syllabusNames.toArray( new String[0] ) ) ;
        JList<Topic>      list     = new JList<>() ;
        JSpinner          batch    = new JSpinner( new SpinnerNumberModel(
                current != null ? current.batchSize() : 50, 1,
                TaggerServerClient.MAX_LIMIT, 10 ) ) ;
        JComboBox<TagMode> mode    = new JComboBox<>( TagMode.values() ) ;

        list.setSelectionMode( ListSelectionModel.SINGLE_SELECTION ) ;
        list.setVisibleRowCount( 20 ) ;
        list.setCellRenderer( new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent( JList<?> l,
                    Object value, int index, boolean selected,
                    boolean focus ) {

                Topic             t  = (Topic) value ;
                PromptStore.State st = states.get( t.getId() ) ;
                String            why = why( t, st ) ;
                Component c = super.getListCellRendererComponent( l,
                        t.getId() + "  " + t.getTopicName() +
                        "   [" + ( why != null ? why : st ) + "]",
                        index, selected, focus ) ;
                if( why != null && !selected ) c.setForeground( Color.GRAY ) ;
                return c ;
            }
        } ) ;
        syllabus.addActionListener( e -> fill( list, states,
                topicsOf.apply( (String) syllabus.getSelectedItem() ),
                stateOf, null ) ) ;

        if( current != null ) {
            mode.setSelectedItem( current.mode() ) ;
            for( String s : syllabusNames ) {
                if( topicsOf.apply( s ).stream().anyMatch(
                        t -> t.getId() == current.topicId() ) ) {
                    syllabus.setSelectedItem( s ) ;
                }
            }
        }
        fill( list, states, topicsOf.apply( (String) syllabus.getSelectedItem() ),
              stateOf, current != null ? current.topicId() : null ) ;

        JPanel options = new JPanel( new FlowLayout( FlowLayout.LEFT, 8, 0 ) ) ;
        options.add( new JLabel( "Questions per load:" ) ) ;
        options.add( batch ) ;
        options.add( new JLabel( "   Tag mode:" ) ) ;
        options.add( mode ) ;

        JPanel top = new JPanel( new GridLayout( 0, 1, 0, 4 ) ) ;
        if( problem != null ) {
            JLabel p = new JLabel( problem ) ;
            p.setForeground( new Color( 0xC62828 ) ) ;
            top.add( p ) ;
        }
        top.add( syllabus ) ;

        JPanel panel = new JPanel( new BorderLayout( 0, 6 ) ) ;
        panel.add( top, BorderLayout.NORTH ) ;
        panel.add( new JScrollPane( list ), BorderLayout.CENTER ) ;
        panel.add( options, BorderLayout.SOUTH ) ;

        while( true ) {
            int answer = JOptionPane.showConfirmDialog( parent, panel,
                    "Scope", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE ) ;
            if( answer != JOptionPane.OK_OPTION ) return null ;

            Topic  t   = list.getSelectedValue() ;
            String why = t == null ? "Choose a topic"
                                   : why( t, states.get( t.getId() ) ) ;
            if( why != null ) {
                JOptionPane.showMessageDialog( parent, t == null ? why
                        : t.getTopicName() + " can't be chosen: " + why,
                        "Scope", JOptionPane.WARNING_MESSAGE ) ;
                continue ;
            }

            int     size = (Integer) batch.getValue() ;
            TagMode m    = (TagMode) mode.getSelectedItem() ;
            boolean same = current != null && current.topicId() == t.getId() ;

            return new Scope( t.getId(), size, m,
                              same ? current.ownAfterId() : null,
                              same ? current.depAfterId() : null ) ;
        }
    }

    /** Why {@code t} can't be scoped; null if it can. */
    private static String why( Topic t, PromptStore.State state ) {

        if( t.getId() == TagCatalogue.FOUNDATION_MATHS ) {
            return "tagged by hand" ;
        }
        return state == PromptStore.State.APPROVED ? null
               : "prompt " + state ;
    }

    /** Show {@code topics} in {@code list}, selecting {@code select}. */
    private static void fill( JList<Topic> list,
                              Map<Integer,PromptStore.State> states,
                              List<Topic> topics,
                              Function<Topic,PromptStore.State> stateOf,
                              Integer select ) {

        for( Topic t : topics ) {
            states.computeIfAbsent( t.getId(), id -> stateOf.apply( t ) ) ;
        }
        list.setListData( topics.toArray( new Topic[0] ) ) ;
        for( int i = 0 ; i < topics.size() ; i++ ) {
            if( select != null && topics.get( i ).getId() == select ) {
                list.setSelectedIndex( i ) ;
                list.ensureIndexIsVisible( i ) ;
            }
        }
    }
}
