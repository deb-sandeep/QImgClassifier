package com.sandy.sconsole.qimgtools.apps.transcriber.ui;

import com.sandy.sconsole.qimgtools.apps.transcriber.model.TranscriptionCriteria;
import com.sandy.sconsole.qimgtools.core.model.Topic;

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

import com.sandy.sconsole.qimgtools.apps.transcriber.server.TranscriberServerClient;

/**
 * Asks which pending questions to load, as the topic reviewer's dialog
 * does: the syllabuses, or topics of them (which the server uses instead
 * when any are chosen), and how many per load. Use on the EDT.
 */
@SuppressWarnings( "serial" )
final class CriteriaDialog extends JPanel {

    private static final int MAX_BATCH = TranscriberServerClient.MAX_BATCH ;

    private final Map<String,JCheckBox>        syllabuses = new LinkedHashMap<>() ;
    private final Function<String,List<Topic>> topicsOf ;

    private final DefaultListModel<Topic> topics    = new DefaultListModel<>() ;
    private final JList<Topic>            topicList = new JList<>( topics ) ;
    private final JSpinner                batch ;

    private CriteriaDialog( TranscriptionCriteria initial,
                            List<String> syllabusNames,
                            Function<String,List<Topic>> topicsOf ) {

        super( new BorderLayout( 0, 8 ) ) ;
        this.topicsOf = topicsOf ;
        this.batch    = new JSpinner( new SpinnerNumberModel(
                Math.min( Math.max( initial.batchSize(), 1 ), MAX_BATCH ),
                1, MAX_BATCH, 10 ) ) ;

        JPanel boxes = new JPanel( new FlowLayout( FlowLayout.LEFT ) ) ;
        boxes.setBorder( BorderFactory.createTitledBorder( "Syllabuses" ) ) ;
        for( String name : syllabusNames ) {
            // A chosen topic's syllabus is ticked, so the topic is listed.
            boolean ticked = initial.syllabusNames().contains( name )
                             || topicsOf.apply( name ).stream().anyMatch(
                                     t -> initial.topicIds()
                                                 .contains( t.getId() ) ) ;
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

                Topic  t    = (Topic) value ;
                String text = t.getSyllabusName() + "  ·  " + t.getSection() +
                              "  ·  " + t.getTopicName() ;
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
                "are loaded)" ) ) ;
        topicPanel.add( listPane, BorderLayout.CENTER ) ;

        JPanel clearRow = new JPanel( new FlowLayout( FlowLayout.LEFT, 0, 0 ) ) ;
        clearRow.add( clear ) ;
        clearRow.add( Box.createHorizontalStrut( 12 ) ) ;
        clearRow.add( new JLabel( "Ctrl / Shift + click to select several" ) ) ;
        topicPanel.add( clearRow, BorderLayout.SOUTH ) ;

        JPanel batchRow = new JPanel() ;
        batchRow.setLayout( new BoxLayout( batchRow, BoxLayout.X_AXIS ) ) ;
        batchRow.add( new JLabel( "Batch size (questions per load, 1-" +
                                  MAX_BATCH + "):  " ) ) ;
        batchRow.add( batch ) ;
        batchRow.add( Box.createHorizontalGlue() ) ;

        add( boxes, BorderLayout.NORTH ) ;
        add( topicPanel, BorderLayout.CENTER ) ;
        add( batchRow, BorderLayout.SOUTH ) ;
        refreshTopics( new HashSet<>( initial.topicIds() ) ) ;
    }

    /**
     * Ask for the criteria, starting from {@code initial}.
     *
     * @param topicsOf a syllabus' topics
     * @return the criteria chosen; null if cancelled
     */
    static TranscriptionCriteria ask( Component parent,
                                      TranscriptionCriteria initial,
                                      List<String> syllabusNames,
                                      Function<String,List<Topic>> topicsOf ) {

        CriteriaDialog panel =
                new CriteriaDialog( initial, syllabusNames, topicsOf ) ;
        while( true ) {
            int answer = JOptionPane.showConfirmDialog( parent, panel,
                    "Questions to transcribe", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE ) ;
            if( answer != JOptionPane.OK_OPTION ) return null ;

            TranscriptionCriteria chosen = panel.criteria() ;
            if( chosen.isValid() ) return chosen ;
            JOptionPane.showMessageDialog( parent,
                    "Choose at least one syllabus or topic.",
                    "Questions to transcribe", JOptionPane.WARNING_MESSAGE ) ;
        }
    }

    private TranscriptionCriteria criteria() {

        List<String> names = syllabuses.entrySet().stream()
                                       .filter( e -> e.getValue().isSelected() )
                                       .map( Map.Entry::getKey )
                                       .toList() ;
        return new TranscriptionCriteria(
                names, new ArrayList<>( selectedTopicIds() ),
                (Integer) batch.getValue() ) ;
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

        List<Integer> indices = new ArrayList<>() ;

        topics.clear() ;
        for( Map.Entry<String,JCheckBox> e : syllabuses.entrySet() ) {
            if( !e.getValue().isSelected() ) continue ;
            for( Topic t : topicsOf.apply( e.getKey() ) ) {
                if( selected.contains( t.getId() ) ) {
                    indices.add( topics.size() ) ;
                }
                topics.addElement( t ) ;
            }
        }
        topicList.setSelectedIndices(
                indices.stream().mapToInt( Integer::intValue ).toArray() ) ;
    }
}
