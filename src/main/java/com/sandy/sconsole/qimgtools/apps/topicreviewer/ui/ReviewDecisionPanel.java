package com.sandy.sconsole.qimgtools.apps.topicreviewer.ui;

import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ReviewVerdict;
import com.sandy.sconsole.qimgtools.core.server.ServerQuestion;
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
 * The selected question's server topic above the review model's and the
 * tie-breaker's ranked topics, with the decisions the user can make on it:
 * keep the server's topic, use the reviewer's or the tie-breaker's (the one
 * selected in its list, its chosen topic by default), or choose another.
 * With several questions selected it says only "Multiple selections", and a
 * button decides for each of them that it applies to: the server's topic,
 * each one's reviewer or tie-breaker topic, or one chosen topic. Keys 1-4 on
 * the frame's table and its right-click menu press these buttons
 * ({@link #press}). Use on the EDT.
 */
@SuppressWarnings( "serial" )
final class ReviewDecisionPanel extends JPanel {

    /**
     * What the buttons ask for, for the rows shown; the frame carries it out
     * on those it applies to.
     */
    interface Actions {

        /** Keep the rows' server topics. */
        void keep( List<Row> rows ) ;

        /**
         * Give the rows the review model's topic: {@code topic} (picked in
         * the list of a single row), or each row's chosen one if null.
         */
        void useReviewer( List<Row> rows, Topic topic ) ;

        /** As {@link #useReviewer}, with the tie-breaker's topics. */
        void useTieBreaker( List<Row> rows, Topic topic ) ;

        /** Let the user pick a topic of the rows' syllabus for all of them. */
        void chooseTopic( List<Row> rows ) ;
    }

    private final Actions    actions ;
    private final ModelNames names ;

    private final JLabel heading = new JLabel( " " ) ;
    private final JLabel current = new JLabel( " " ) ;

    private final Opinion reviewer   = new Opinion( ReviewTableModel.REVIEWER ) ;
    private final Opinion tieBreaker = new Opinion( ReviewTableModel.TIE_BREAKER ) ;

    private final JButton keep          = new JButton( "1  Server's" ) ;
    private final JButton useReviewer   = new JButton( "2  Reviewer's" ) ;
    private final JButton useTieBreaker = new JButton( "3  Tie breaker's" ) ;
    private final JButton choose        = new JButton( "4  Choose…" ) ;

    /** The rows shown; empty when none is. */
    private List<Row> rows = List.of() ;

    /** @param names the names models are shown by */
    ReviewDecisionPanel( Actions actions, ModelNames names ) {

        super( new BorderLayout( 0, 4 ) ) ;
        this.actions = actions ;
        this.names   = names ;

        setBorder( BorderFactory.createTitledBorder( "Decision" ) ) ;
        heading.setFont( heading.getFont().deriveFont( java.awt.Font.BOLD ) ) ;

        keep.setToolTipText( "Keep the server's topic (key 1 on the table)" ) ;
        useReviewer.setToolTipText( "Use the topic selected in the " +
                                    "reviewer's list (key 2)" ) ;
        useTieBreaker.setToolTipText( "Use the topic selected in the " +
                                      "tie-breaker's list (key 3)" ) ;
        choose.setToolTipText( "Choose any topic of the syllabus (key 4)" ) ;
        keep.addActionListener( e -> actions.keep( rows ) ) ;
        useReviewer.addActionListener( e -> use( reviewer,
                                                 actions::useReviewer ) ) ;
        useTieBreaker.addActionListener( e -> use( tieBreaker,
                                                   actions::useTieBreaker ) ) ;
        choose.addActionListener( e -> actions.chooseTopic( rows ) ) ;

        JPanel labels = new JPanel( new GridLayout( 0, 1 ) ) ;
        labels.add( heading ) ;
        labels.add( current ) ;

        JPanel opinions = new JPanel( new GridLayout( 1, 0, 4, 0 ) ) ;
        opinions.add( reviewer ) ;
        opinions.add( tieBreaker ) ;

        JPanel row4 = new JPanel( new GridLayout( 1, 0, 4, 0 ) ) ;
        row4.add( keep ) ;
        row4.add( useReviewer ) ;
        row4.add( useTieBreaker ) ;
        row4.add( choose ) ;

        JLabel hint = new JLabel( "Keys on the table: 1 server's · " +
                                  "2 reviewer's · 3 tie breaker's · " +
                                  "4 choose · 5 skip" ) ;
        hint.setFont( hint.getFont().deriveFont( hint.getFont().getSize2D() - 1 ) ) ;

        JPanel buttons = new JPanel( new GridLayout( 0, 1, 0, 2 ) ) ;
        buttons.add( row4 ) ;
        buttons.add( hint ) ;

        add( labels, BorderLayout.NORTH ) ;
        add( opinions, BorderLayout.CENTER ) ;
        add( buttons, BorderLayout.SOUTH ) ;
        show( List.of() ) ;
    }

    /**
     * Decide with {@code use} on the topic selected in {@code opinion}'s
     * list for a single row, else on each row's chosen one.
     */
    private void use( Opinion opinion, UseAction use ) {

        Topic t = rows.size() == 1 ? opinion.list.getSelectedValue() : null ;
        if( rows.size() == 1 && t == null ) return ;
        use.apply( rows, t ) ;
    }

    /** {@link Actions#useReviewer} or {@link Actions#useTieBreaker}. */
    private interface UseAction {
        void apply( List<Row> rows, Topic topic ) ;
    }

    /** Show {@code rows} (empty clears the panel). */
    void show( List<Row> rows ) {

        this.rows = List.copyOf( rows ) ;
        current.setText( " " ) ;
        reviewer.show( null, null, null ) ;
        tieBreaker.show( null, null, null ) ;
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

        reviewer.show( row.reviewer(), q.getTopicId(),
                       row.opinions == null ? "no verdict yet" : null ) ;
        tieBreaker.show( row.tieBreaker(), q.getTopicId(),
                         row.opinions == null ? " "
                         : row.opinions.tieBreakerFailed()
                           ? "no answer" : "not asked" ) ;
    }

    /**
     * Press decision button {@code n} (1 keep the server's topic, 2 use the
     * reviewer's, 3 the tie-breaker's, 4 choose), if it is enabled.
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
            case 1  -> keep ;
            case 2  -> useReviewer ;
            case 3  -> useTieBreaker ;
            case 4  -> choose ;
            default -> null ;
        } ;
    }

    /** Re-show the current rows after {@code changed} changed. */
    void refresh( Row changed ) {
        if( rows.contains( changed ) ) show( rows ) ;
    }

    /** Each button is enabled if it applies to any row shown. */
    private void enableButtons() {
        keep.setEnabled( any( Row::canKeep ) ) ;
        useReviewer.setEnabled( any( Row::canUseReviewer ) ) ;
        useTieBreaker.setEnabled( any( Row::canUseTieBreaker ) ) ;
        choose.setEnabled( any( Row::isOpen ) ) ;
    }

    private boolean any( Predicate<Row> test ) {
        return rows.stream().anyMatch( test ) ;
    }

    /**
     * One model's opinion: its name and whether it agrees with the server,
     * above its ranked topics.
     */
    private final class Opinion extends JPanel {

        private final String                  title ;
        private final JLabel                  by     = new JLabel( " " ) ;
        private final DefaultListModel<Topic> topics = new DefaultListModel<>() ;
        private final JList<Topic>            list   = new JList<>( topics ) ;

        /** The verdict shown; null if none. */
        private ReviewVerdict verdict ;

        Opinion( String title ) {

            super( new BorderLayout( 0, 2 ) ) ;
            this.title = title ;

            list.setSelectionMode( ListSelectionModel.SINGLE_SELECTION ) ;
            list.setVisibleRowCount( 4 ) ;
            list.setCellRenderer( new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent( JList<?> l,
                        Object value, int index, boolean selected,
                        boolean focus ) {

                    Topic  t    = (Topic) value ;
                    String text = String.format( "%3d%%  %s  (%s)",
                            Math.round( t.getMatchProbability() * 100 ),
                            t.getTopicName(), t.getSection() ) ;

                    // Mark the chosen topic when the sequence chose one the
                    // model did not score highest
                    if( index > 0 && verdict != null
                        && verdict.agreesWith( t.getId() ) ) {
                        text += "  - chosen" ;
                    }
                    return super.getListCellRendererComponent(
                            l, text, index, selected, focus ) ;
                }
            } ) ;

            JScrollPane pane = new JScrollPane( list ) ;
            pane.setPreferredSize( new Dimension( 100, 90 ) ) ;
            add( by, BorderLayout.NORTH ) ;
            add( pane, BorderLayout.CENTER ) ;
        }

        /**
         * Show {@code v}, compared with the server's topic {@code topicId};
         * without one, {@code none} says why.
         */
        void show( ReviewVerdict v, Integer topicId, String none ) {

            verdict = v ;
            topics.clear() ;
            if( v == null ) {
                by.setText( none == null || none.isBlank()
                            ? " " : title + ": " + none ) ;
                return ;
            }
            by.setText( title + ": " + names.of( v.model() ) +
                        ( v.agreesWith( topicId ) ? " - agrees"
                                                  : " - differs" ) ) ;
            v.topics().forEach( topics::addElement ) ;
            list.setSelectedValue( v.chosen(), true ) ;
        }
    }
}
