package com.sandy.sconsole.qimgtools.apps.topicreviewer.ui;

import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ServerQuestion;
import com.sandy.sconsole.qimgtools.core.model.Topic;
import com.sandy.sconsole.qimgtools.core.util.ModelNames;
import com.sandy.sconsole.qimgtools.apps.topicreviewer.ui.ReviewTableModel.Row;

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
import java.util.List;
import java.util.function.Predicate;

/**
 * The selected question's server topic beside the model's ranked topics,
 * with the decisions the user can make on it: use the model's topic (the
 * one selected in its list, its best by default), keep the server's, or
 * choose another. With several questions selected it says only "Multiple
 * selections", and a button decides for each of them that it applies to:
 * the model's best topic, the server's topic, or one chosen topic. Keys 1-3
 * on the frame's table and its right-click menu press these buttons
 * ({@link #press}). Use on the EDT.
 */
@SuppressWarnings( "serial" )
final class ReviewDecisionPanel extends JPanel {

    /**
     * What the buttons ask for, for the rows shown; the frame carries it out
     * on those it applies to.
     */
    interface Actions {

        /**
         * Give the rows the model's topic: {@code topic} (picked in the list
         * of a single row), or each row's best if null.
         */
        void useModel( List<Row> rows, Topic topic ) ;

        /** Keep the rows' server topics. */
        void keep( List<Row> rows ) ;

        /** Let the user pick a topic of the rows' syllabus for all of them. */
        void chooseTopic( List<Row> rows ) ;
    }

    private final Actions    actions ;
    private final ModelNames names ;

    private final JLabel heading  = new JLabel( " " ) ;
    private final JLabel current  = new JLabel( " " ) ;
    private final JLabel modelBy  = new JLabel( " " ) ;
    private final JLabel status   = new JLabel( " " ) ;

    private final DefaultListModel<Topic> aiTopics = new DefaultListModel<>() ;
    private final JList<Topic>            aiList   = new JList<>( aiTopics ) ;

    private final JButton useAi  = new JButton( "1  Use model's" ) ;
    private final JButton keep   = new JButton( "2  Keep server's" ) ;
    private final JButton choose = new JButton( "3  Choose…" ) ;

    /** The rows shown; empty when none is. */
    private List<Row> rows = List.of() ;

    /** @param names the names models are shown by */
    ReviewDecisionPanel( Actions actions, ModelNames names ) {

        super( new BorderLayout( 0, 4 ) ) ;
        this.actions = actions ;
        this.names   = names ;

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

                // Mark the chosen topic when the sequence chose one the
                // model did not score highest
                if( index > 0 && isChosen( t ) ) text += "  - chosen" ;
                return super.getListCellRendererComponent(
                        list, text, index, selected, focus ) ;
            }
        } ) ;

        useAi.setToolTipText( "Use the topic selected in the model's list " +
                              "(key 1 on the table)" ) ;
        keep.setToolTipText( "Keep the server's topic (key 2)" ) ;
        choose.setToolTipText( "Choose any topic of the syllabus (key 3)" ) ;
        useAi.addActionListener( e -> {
            Topic t = rows.size() == 1 ? aiList.getSelectedValue() : null ;
            if( rows.size() == 1 && t == null ) return ;
            actions.useModel( rows, t ) ;
        } ) ;
        keep.addActionListener( e -> actions.keep( rows ) ) ;
        choose.addActionListener( e -> actions.chooseTopic( rows ) ) ;

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
        show( List.of() ) ;
    }

    /** True if {@code t} is the chosen topic of the one row shown. */
    private boolean isChosen( Topic t ) {
        return rows.size() == 1 && rows.get( 0 ).verdict != null
               && rows.get( 0 ).verdict.chosen().getId() == t.getId() ;
    }

    /** Show {@code rows} (empty clears the panel). */
    void show( List<Row> rows ) {

        this.rows = List.copyOf( rows ) ;
        aiTopics.clear() ;
        current.setText( " " ) ;
        modelBy.setText( " " ) ;
        status.setText( " " ) ;
        enableButtons() ;

        if( rows.size() != 1 ) {
            heading.setText( rows.isEmpty() ? "Select a question"
                                            : "Multiple selections" ) ;
            return ;
        }

        Row            row = rows.get( 0 ) ;
        ServerQuestion q   = row.question ;

        heading.setText( row.questionLabel() + "   " + q.getSourceId() +
                         "   (server id " + q.getId() + ")" ) ;
        String by = q.provenance( names ) ;
        current.setText( "Server: " + q.getTopicName() +
                         ( by.isEmpty() ? "" : "   [" + by + "]" ) ) ;

        if( row.verdict == null ) {
            modelBy.setText( "Model: no verdict yet" ) ;
        }
        else {
            modelBy.setText( "Model: " + names.of( row.verdict.model() ) +
                             ( row.verdict.agreesWith( q.getTopicId() )
                               ? " - agrees" : " - differs" ) ) ;
            row.verdict.topics().forEach( aiTopics::addElement ) ;
            aiList.setSelectedValue( row.verdict.chosen(), true ) ;
        }
        status.setText( row.status.getLabel() +
                        ( row.result != null ? ": " + row.result : "" ) ) ;
    }

    /**
     * Press decision button {@code n} (1 use the model's topic, 2 keep the
     * server's, 3 choose), if it is enabled.
     */
    void press( int n ) {
        JButton b = button( n ) ;
        if( b != null && b.isEnabled() ) b.doClick( 0 ) ;
    }

    /** True if decision button {@code n} is enabled. */
    boolean canPress( int n ) {
        JButton b = button( n ) ;
        return b != null && b.isEnabled() ;
    }

    private JButton button( int n ) {
        return switch( n ) {
            case 1  -> useAi ;
            case 2  -> keep ;
            case 3  -> choose ;
            default -> null ;
        } ;
    }

    /** Re-show the current rows after {@code changed} changed. */
    void refresh( Row changed ) {
        if( rows.contains( changed ) ) show( rows ) ;
    }

    /** Each button is enabled if it applies to any row shown. */
    private void enableButtons() {
        useAi.setEnabled( any( Row::canUseModel ) ) ;
        keep.setEnabled( any( Row::canKeep ) ) ;
        choose.setEnabled( any( Row::isOpen ) ) ;
    }

    private boolean any( Predicate<Row> test ) {
        return rows.stream().anyMatch( test ) ;
    }
}
