package com.sandy.sconsole.qimgtools.apps.tagger.ui;

import com.sandy.sconsole.qimgtools.apps.tagger.model.Scope;
import com.sandy.sconsole.qimgtools.apps.tagger.server.TaggerServerClient;
import com.sandy.sconsole.qimgtools.core.model.Topic;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JCheckBox;
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
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.List;
import java.util.function.Function;

/**
 * Chooses the scope: one topic of a syllabus, and the batch size. With a
 * cursor on the topic in effect, it says where the next Load continues and
 * offers a way back to the first question.
 */
final class ScopeDialog {

    private static final Dimension LIST_SIZE = new Dimension( 420, 360 ) ;

    private ScopeDialog() {}

    /**
     * Ask for a scope, starting from {@code current} (null for none).
     *
     * @param problem why a scope is asked for, shown on top; null if the
     *        user asked
     * @return the scope chosen, keeping {@code current}'s cursor if its topic
     *         is unchanged and "Start from the first question" is not ticked;
     *         null if the user cancelled
     */
    static Scope ask( Component parent, Scope current, String problem,
                      List<String> syllabusNames,
                      Function<String,List<Topic>> topicsOf ) {

        JComboBox<String> syllabus = new JComboBox<>(
                syllabusNames.toArray( new String[0] ) ) ;
        JList<Topic>      topics   = new JList<>() ;
        JSpinner          batch    = new JSpinner( new SpinnerNumberModel(
                current != null ? current.batchSize() : 50, 1,
                TaggerServerClient.MAX_LIMIT, 10 ) ) ;

        topics.setSelectionMode( ListSelectionModel.SINGLE_SELECTION ) ;
        topics.setCellRenderer( new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean selected,
                    boolean focus ) {

                Topic t = (Topic) value ;
                return super.getListCellRendererComponent(
                        list, t.getId() + "  " + t.getTopicName(), index,
                        selected, focus ) ;
            }
        } ) ;
        syllabus.addActionListener( e -> show(
                topics, topicsOf.apply( (String) syllabus.getSelectedItem() ),
                null ) ) ;

        if( current != null ) {
            for( String s : syllabusNames ) {
                if( topicsOf.apply( s ).stream().anyMatch(
                        t -> t.getId() == current.topicId() ) ) {
                    syllabus.setSelectedItem( s ) ;
                }
            }
        }
        show( topics, topicsOf.apply( (String) syllabus.getSelectedItem() ),
              current != null ? current.topicId() : null ) ;

        JPanel options = new JPanel( new FlowLayout( FlowLayout.LEFT, 8, 0 ) ) ;
        options.add( new JLabel( "Questions per load:" ) ) ;
        options.add( batch ) ;

        // Where the next load continues, and a way back to the start.
        JCheckBox reset  = new JCheckBox( "Start from the first question" ) ;
        JPanel    bottom = new JPanel( new GridLayout( 0, 1, 0, 4 ) ) ;
        bottom.add( options ) ;
        if( current != null && current.afterId() != null ) {
            JPanel cursor = new JPanel( new FlowLayout( FlowLayout.LEFT, 8, 0 ) ) ;
            cursor.add( new JLabel( "Topic " + current.topicId() +
                                    ": the next load continues after " +
                                    "question " + current.afterId() ) ) ;
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

        JScrollPane list = new JScrollPane( topics ) ;
        list.setPreferredSize( LIST_SIZE ) ;

        JPanel panel = new JPanel( new BorderLayout( 0, 6 ) ) ;
        panel.add( top, BorderLayout.NORTH ) ;
        panel.add( list, BorderLayout.CENTER ) ;
        panel.add( bottom, BorderLayout.SOUTH ) ;

        while( true ) {
            int answer = JOptionPane.showConfirmDialog( parent, panel, "Scope",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE ) ;
            if( answer != JOptionPane.OK_OPTION ) return null ;

            Topic t = topics.getSelectedValue() ;
            if( t == null ) {
                JOptionPane.showMessageDialog( parent, "Choose a topic",
                        "Scope", JOptionPane.WARNING_MESSAGE ) ;
                continue ;
            }

            boolean same = current != null && current.topicId() == t.getId()
                           && !reset.isSelected() ;

            return new Scope( t.getId(), (Integer) batch.getValue(),
                              same ? current.afterId() : null ) ;
        }
    }

    /** Show {@code all} in {@code list}, selecting topic {@code id}. */
    private static void show( JList<Topic> list, List<Topic> all, Integer id ) {

        list.setListData( all.toArray( new Topic[0] ) ) ;
        if( id == null ) return ;
        for( int i = 0 ; i < all.size() ; i++ ) {
            if( all.get( i ).getId() != id ) continue ;
            list.setSelectedIndex( i ) ;
            list.ensureIndexIsVisible( i ) ;
        }
    }
}
