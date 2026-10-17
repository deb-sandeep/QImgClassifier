package com.sandy.sconsole.qimgclassifier.ui;

import com.sandy.sconsole.qimgclassifier.model.ReviewStatus;
import com.sandy.sconsole.qimgclassifier.model.ServerQuestion;
import com.sandy.sconsole.qimgclassifier.model.Topic;
import com.sandy.sconsole.qimgclassifier.ui.ReviewTableModel.Row;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;

/**
 * The selected question's server topic beside the model's ranked topics,
 * with the decisions the user can make on it: use the model's topic (the
 * one selected in its list, its best by default), keep the server's, or
 * choose another. Keys 1-3 on the frame's table press these buttons
 * ({@link #press}). Use on the EDT.
 */
@SuppressWarnings( "serial" )
final class ReviewDecisionPanel extends JPanel {

    /** What the buttons ask for; the frame carries it out. */
    interface Actions {

        /** Keep the row's server topic. */
        void keep( Row row ) ;

        /** Replace the row's server topic with {@code topic}. */
        void override( Row row, Topic topic ) ;

        /** Let the user pick any topic of the row's syllabus. */
        void chooseTopic( Row row ) ;
    }

    private final Actions actions ;

    private final JLabel heading  = new JLabel( " " ) ;
    private final JLabel current  = new JLabel( " " ) ;
    private final JLabel modelBy  = new JLabel( " " ) ;
    private final JLabel status   = new JLabel( " " ) ;

    private final DefaultListModel<Topic> aiTopics = new DefaultListModel<>() ;
    private final JList<Topic>            aiList   = new JList<>( aiTopics ) ;

    private final JButton useAi  = new JButton( "1  Use model's" ) ;
    private final JButton keep   = new JButton( "2  Keep server's" ) ;
    private final JButton choose = new JButton( "3  Choose…" ) ;

    /** The row shown; null when none is. */
    private Row row ;

    ReviewDecisionPanel( Actions actions ) {

        super( new BorderLayout( 0, 4 ) ) ;
        this.actions = actions ;

        setBorder( BorderFactory.createTitledBorder( "Decision" ) ) ;
        heading.setFont( heading.getFont().deriveFont( java.awt.Font.BOLD ) ) ;

        aiList.setSelectionMode( ListSelectionModel.SINGLE_SELECTION ) ;
        aiList.setVisibleRowCount( 4 ) ;
        aiList.setCellRenderer( new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent( JList<?> list,
                    Object value, int index, boolean selected, boolean focus ) {

                Topic  t    = (Topic) value ;
                String text = String.format( "%3d%%  %s  (%s)",
                        Math.round( t.getMatchProbability() * 100 ),
                        t.getTopicName(), t.getSection() ) ;
                return super.getListCellRendererComponent(
                        list, text, index, selected, focus ) ;
            }
        } ) ;

        useAi.setToolTipText( "Use the topic selected in the model's list " +
                              "(key 1 on the table)" ) ;
        keep.setToolTipText( "Keep the server's topic (key 2)" ) ;
        choose.setToolTipText( "Choose any topic of the syllabus (key 3)" ) ;
        useAi.addActionListener( e -> {
            Topic t = aiList.getSelectedValue() ;
            if( row != null && t != null ) actions.override( row, t ) ;
        } ) ;
        keep.addActionListener( e -> {
            if( row != null ) actions.keep( row ) ;
        } ) ;
        choose.addActionListener( e -> {
            if( row != null ) actions.chooseTopic( row ) ;
        } ) ;

        JPanel labels = new JPanel( new GridLayout( 0, 1 ) ) ;
        labels.add( heading ) ;
        labels.add( current ) ;
        labels.add( modelBy ) ;

        JPanel row3 = new JPanel( new GridLayout( 1, 0, 4, 0 ) ) ;
        row3.add( useAi ) ;
        row3.add( keep ) ;
        row3.add( choose ) ;

        JLabel hint = new JLabel( "Keys on the table: 1 use model's · " +
                                  "2 keep server's · 3 choose · 4 skip" ) ;
        hint.setFont( hint.getFont().deriveFont( hint.getFont().getSize2D() - 1 ) ) ;

        JPanel buttons = new JPanel( new GridLayout( 0, 1, 0, 2 ) ) ;
        buttons.add( row3 ) ;
        buttons.add( status ) ;
        buttons.add( hint ) ;

        JScrollPane listPane = new JScrollPane( aiList ) ;
        listPane.setPreferredSize( new Dimension( 100, 90 ) ) ;

        add( labels, BorderLayout.NORTH ) ;
        add( listPane, BorderLayout.CENTER ) ;
        add( buttons, BorderLayout.SOUTH ) ;
        show( null ) ;
    }

    /** Show {@code row} (null clears the panel). */
    void show( Row row ) {

        this.row = row ;
        aiTopics.clear() ;

        if( row == null ) {
            heading.setText( "Select a question" ) ;
            current.setText( " " ) ;
            modelBy.setText( " " ) ;
            status.setText( " " ) ;
            enableButtons() ;
            return ;
        }

        ServerQuestion q = row.question ;
        heading.setText( row.questionLabel() + "   " + q.getSourceId() +
                         "   (server id " + q.getId() + ")" ) ;
        current.setText( "Server: " + q.getTopicName() + "   [" +
                         q.provenance() + "]" ) ;

        if( row.verdict == null ) {
            modelBy.setText( "Model: no verdict yet" ) ;
        }
        else {
            modelBy.setText( "Model: " + row.verdict.model() +
                             ( row.verdict.agreesWith( q.getTopicId() )
                               ? " - agrees" : " - differs" ) ) ;
            row.verdict.topics().forEach( aiTopics::addElement ) ;
            aiList.setSelectedIndex( 0 ) ;
        }
        status.setText( row.status.getLabel() +
                        ( row.result != null ? ": " + row.result : "" ) ) ;
        enableButtons() ;
    }

    /**
     * Press decision button {@code n} (1 use the model's topic, 2 keep the
     * server's, 3 choose), if it is enabled.
     */
    void press( int n ) {

        JButton b = switch( n ) {
            case 1  -> useAi ;
            case 2  -> keep ;
            case 3  -> choose ;
            default -> null ;
        } ;
        if( b != null && b.isEnabled() ) b.doClick( 0 ) ;
    }

    /** Re-show the current row after it changed. */
    void refresh( Row changed ) {
        if( changed == row ) show( row ) ;
    }

    private void enableButtons() {

        // Not while the model or the server is still working on it.
        boolean open = row != null
                       && row.status != ReviewStatus.QUEUED
                       && row.status != ReviewStatus.RUNNING
                       && row.status != ReviewStatus.SAVING
                       && !row.status.isDecided() ;
        useAi.setEnabled( open && row.verdict != null ) ;
        keep.setEnabled( open && row.question.getTopicId() != null ) ;
        choose.setEnabled( open ) ;
    }
}
