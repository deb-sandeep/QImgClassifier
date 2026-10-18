package com.sandy.sconsole.qimgtools.apps.topicreviewer.ui;

import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewCriteria;
import com.sandy.sconsole.qimgtools.core.model.Topic;

import static com.sandy.sconsole.qimgtools.apps.topicreviewer.server.SConsoleServerClient.MAX_LIMIT;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Asks which questions of the review queue to fetch: the syllabuses, or
 * topics of them (which the server uses instead when any are chosen), and
 * how many per fetch. Use on the EDT.
 */
@SuppressWarnings( "serial" )
final class ReviewCriteriaDialog extends JPanel {

    private final Map<String,JCheckBox>        syllabuses = new LinkedHashMap<>() ;
    private final Function<String,List<Topic>> topicsOf ;

    private final DefaultListModel<Topic> topics    = new DefaultListModel<>() ;
    private final JList<Topic>            topicList = new JList<>( topics ) ;
    private final JSpinner                limit ;

    private ReviewCriteriaDialog( ReviewCriteria initial,
                                  List<String> syllabusNames,
                                  Function<String,List<Topic>> topicsOf ) {

        super( new BorderLayout( 0, 8 ) ) ;
        this.topicsOf = topicsOf ;
        this.limit    = new JSpinner( new SpinnerNumberModel(
                Math.min( Math.max( initial.limit(), 1 ), MAX_LIMIT ),
                1, MAX_LIMIT, 10 ) ) ;

        JPanel boxes = new JPanel( new FlowLayout( FlowLayout.LEFT ) ) ;
        boxes.setBorder( BorderFactory.createTitledBorder( "Syllabuses" ) ) ;
        for( String name : syllabusNames ) {
            // A chosen topic's syllabus is ticked, so the topic is listed.
            boolean ticked = initial.syllabusNames().contains( name )
                             || topicsOf.apply( name ).stream().anyMatch(
                                     t -> initial.topicIds().contains( t.getId() ) ) ;
            JCheckBox box = new JCheckBox( name, ticked ) ;
            box.addActionListener( e -> refreshTopics( selectedTopicIds() ) ) ;
            syllabuses.put( name, box ) ;
            boxes.add( box ) ;
        }

        topicList.setSelectionMode(
                ListSelectionModel.MULTIPLE_INTERVAL_SELECTION ) ;
        topicList.setCellRenderer( new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent( JList<?> list,
                    Object value, int index, boolean selected, boolean focus ) {

                Topic t = (Topic) value ;
                String text = t.getSyllabusName() + "  ·  " + t.getSection()
                              + "  ·  " + t.getTopicName() ;
                return super.getListCellRendererComponent(
                        list, text, index, selected, focus ) ;
            }
        } ) ;
        JButton clear = new JButton( "Clear topics" ) ;
        clear.addActionListener( e -> topicList.clearSelection() ) ;

        JScrollPane listPane = new JScrollPane( topicList ) ;
        listPane.setPreferredSize( new Dimension( 520, 380 ) ) ;

        JPanel topicPanel = new JPanel( new BorderLayout( 0, 4 ) ) ;
        topicPanel.setBorder( BorderFactory.createTitledBorder(
                "Topics (optional - when any are selected, only these " +
                "are fetched)" ) ) ;
        topicPanel.add( listPane, BorderLayout.CENTER ) ;
        JPanel clearRow = new JPanel( new FlowLayout( FlowLayout.LEFT, 0, 0 ) ) ;
        clearRow.add( clear ) ;
        clearRow.add( Box.createHorizontalStrut( 12 ) ) ;
        clearRow.add( new JLabel( "Ctrl / Shift + click to select several" ) ) ;
        topicPanel.add( clearRow, BorderLayout.SOUTH ) ;

        JPanel limitRow = new JPanel() ;
        limitRow.setLayout( new BoxLayout( limitRow, BoxLayout.X_AXIS ) ) ;
        limitRow.add( new JLabel( "Batch size (questions per fetch, 1-" +
                                  MAX_LIMIT + "):  " ) ) ;
        limitRow.add( limit ) ;
        limitRow.add( Box.createHorizontalGlue() ) ;

        add( boxes, BorderLayout.NORTH ) ;
        add( topicPanel, BorderLayout.CENTER ) ;
        add( limitRow, BorderLayout.SOUTH ) ;
        refreshTopics( new HashSet<>( initial.topicIds() ) ) ;
    }

    /**
     * Ask for the criteria, starting from {@code initial}.
     *
     * @param topicsOf a syllabus' topics
     * @return the criteria chosen; null if cancelled
     */
    static ReviewCriteria ask( Component parent, ReviewCriteria initial,
                               List<String> syllabusNames,
                               Function<String,List<Topic>> topicsOf ) {

        ReviewCriteriaDialog panel =
                new ReviewCriteriaDialog( initial, syllabusNames, topicsOf ) ;
        while( true ) {
            int answer = JOptionPane.showConfirmDialog( parent, panel,
                    "Questions to review", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE ) ;
            if( answer != JOptionPane.OK_OPTION ) return null ;

            ReviewCriteria chosen = panel.criteria() ;
            if( chosen.isValid() ) return chosen ;
            JOptionPane.showMessageDialog( parent,
                    "Choose at least one syllabus or topic.",
                    "Questions to review", JOptionPane.WARNING_MESSAGE ) ;
        }
    }

    private ReviewCriteria criteria() {

        List<String> names = syllabuses.entrySet().stream()
                                       .filter( e -> e.getValue().isSelected() )
                                       .map( Map.Entry::getKey )
                                       .toList() ;
        return new ReviewCriteria( names, new ArrayList<>( selectedTopicIds() ),
                                   (Integer) limit.getValue() ) ;
    }

    private Set<Integer> selectedTopicIds() {

        Set<Integer> ids = new HashSet<>() ;
        topicList.getSelectedValuesList().forEach( t -> ids.add( t.getId() ) ) ;
        return ids ;
    }

    /**
     * List the topics of the ticked syllabuses, keeping those of
     * {@code selected} still listed selected.
     */
    private void refreshTopics( Set<Integer> selected ) {

        topics.clear() ;
        List<Integer> indices = new ArrayList<>() ;

        for( Map.Entry<String,JCheckBox> e : syllabuses.entrySet() ) {
            if( !e.getValue().isSelected() ) continue ;
            for( Topic t : topicsOf.apply( e.getKey() ) ) {
                if( selected.contains( t.getId() ) ) indices.add( topics.size() ) ;
                topics.addElement( t ) ;
            }
        }
        topicList.setSelectedIndices(
                indices.stream().mapToInt( Integer::intValue ).toArray() ) ;
    }
}
