package com.sandy.sconsole.qimgtools.apps.tagger.ui;

import com.sandy.sconsole.qimgtools.apps.tagger.model.Axis;
import com.sandy.sconsole.qimgtools.apps.tagger.model.CatalogueTag;
import com.sandy.sconsole.qimgtools.apps.tagger.model.OfferedSet;
import com.sandy.sconsole.qimgtools.core.model.Topic;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Edits one tag set of the pass: the offered tags as a group of check boxes
 * per axis of the pass, preset to the tags in view, each with its hint as a
 * tooltip; in the concept pass, for a question of the scoped topic, also
 * the syllabus' other topics it depends on. OK checks the axis counts
 * (setup any number; concept 1-2 and math 0-2; for a dependent, concept
 * 0-2 alone) and asks again until they hold.
 */
final class TagEditorDialog {

    /**
     * The edited set.
     *
     * @param tags         the tags checked, by axis in offered order
     * @param dependencies the topics checked; empty for a dependent
     */
    record Result( List<String> tags, List<Integer> dependencies ) {}

    private TagEditorDialog() {}

    /**
     * Ask for a tag set, preset to {@code tags} and {@code dependencies}.
     *
     * @param dependent true to edit a dependent's set: concept tags alone,
     *        no dependencies
     * @param topics    the topics a dependency may name; empty in the setup
     *        pass, ignored for a dependent
     * @return the set chosen; null if the user cancelled
     */
    static Result ask( Component parent, String title, OfferedSet offered,
                       boolean dependent, List<String> tags,
                       List<Topic> topics, List<Integer> dependencies ) {

        Map<Axis,List<JCheckBox>> boxes = new LinkedHashMap<>() ;
        JPanel                    axes  = new JPanel( new GridLayout( 1, 0, 8, 0 ) ) ;

        for( Axis axis : offered.tagClass().getAxes() ) {
            if( axis.max( dependent ) == 0 ) continue ;
            List<JCheckBox> group = new ArrayList<>() ;
            JPanel          list  = column() ;

            for( CatalogueTag t : offered.tags( axis ) ) {
                JCheckBox b = new JCheckBox( t.tagText(),
                                             tags.contains( t.tagText() ) ) ;
                b.setToolTipText( t.hint() ) ;
                group.add( b ) ;
                list.add( b ) ;
            }
            boxes.put( axis, group ) ;
            axes.add( titled( list, axis.getHeading() + " (" +
                                    axis.countRule( dependent ) + ")" ) ) ;
        }

        List<JCheckBox> deps = new ArrayList<>() ;
        if( !dependent && !topics.isEmpty() ) {
            JPanel list = column() ;
            for( Topic t : topics ) {
                JCheckBox b = new JCheckBox( t.getTopicName(),
                                             dependencies.contains( t.getId() ) ) ;
                b.putClientProperty( "topicId", t.getId() ) ;
                deps.add( b ) ;
                list.add( b ) ;
            }
            axes.add( titled( list, "Depends on" ) ) ;
        }

        JPanel panel = new JPanel( new BorderLayout( 0, 6 ) ) ;
        panel.add( new JLabel( "Hover over a tag for its hint." ),
                   BorderLayout.NORTH ) ;
        panel.add( axes, BorderLayout.CENTER ) ;

        while( true ) {
            int answer = JOptionPane.showConfirmDialog( parent, panel, title,
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE ) ;
            if( answer != JOptionPane.OK_OPTION ) return null ;

            List<String> chosen = new ArrayList<>() ;
            String       wrong  = null ;
            for( Map.Entry<Axis,List<JCheckBox>> e : boxes.entrySet() ) {
                Axis axis = e.getKey() ;
                int  n    = 0 ;
                for( JCheckBox b : e.getValue() ) {
                    if( !b.isSelected() ) continue ;
                    chosen.add( b.getText() ) ;
                    n++ ;
                }
                if( wrong == null && ( n < axis.min( dependent )
                                       || n > axis.max( dependent ) ) ) {
                    wrong = axis.getHeading() + " takes " +
                            axis.countRule( dependent ) + " tags, not " + n ;
                }
            }
            if( wrong != null ) {
                JOptionPane.showMessageDialog( parent, wrong, title,
                                               JOptionPane.WARNING_MESSAGE ) ;
                continue ;
            }
            List<Integer> ids = deps.stream().filter( JCheckBox::isSelected )
                    .map( b -> (Integer) b.getClientProperty( "topicId" ) )
                    .toList() ;
            return new Result( chosen, ids ) ;
        }
    }

    private static JPanel column() {
        JPanel p = new JPanel() ;
        p.setLayout( new BoxLayout( p, BoxLayout.Y_AXIS ) ) ;
        return p ;
    }

    /** {@code list} in a scroll pane, titled {@code title}. */
    private static JComponent titled( JPanel list, String title ) {

        JScrollPane pane = new JScrollPane( list ) ;
        pane.setBorder( BorderFactory.createTitledBorder( title ) ) ;
        pane.setPreferredSize( new Dimension( 260, 420 ) ) ;
        pane.getVerticalScrollBar().setUnitIncrement( 16 ) ;
        return pane ;
    }
}
