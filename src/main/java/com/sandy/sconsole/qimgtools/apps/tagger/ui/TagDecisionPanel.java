package com.sandy.sconsole.qimgtools.apps.tagger.ui;

import com.sandy.sconsole.qimgtools.apps.tagger.model.TagItem;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagReply;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagReply.ConceptPick;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TaggerStatus;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TierReply;
import com.sandy.sconsole.qimgtools.apps.tagger.ui.TagTableModel.Row;
import com.sandy.sconsole.qimgtools.core.util.ModelNames;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.Predicate;

/**
 * The selected question's key, why it was handed over and its hand tags,
 * above the two tiers' replies side by side (answer, tags by axis, each
 * concept tag's reason, the topics it depends on, any gap proposed), with
 * the decisions the user can make on it: take tier 1's or tier 2's tags,
 * edit a set, keep the hand tags, or defer it; a question whose gap
 * proposal waits shows a button to review it. With several questions
 * selected it says only "Multiple selections", and a button decides for
 * each of them it applies to. Keys 1-5 on the frame's table and its
 * right-click menu press these buttons ({@link #press}). Use on the EDT.
 */
@SuppressWarnings( "serial" )
final class TagDecisionPanel extends JPanel {

    /**
     * What the buttons ask for, for the rows shown; the frame carries it out
     * on those it applies to.
     */
    interface Actions {

        /** Give the rows tier {@code tier}'s tags. */
        void useTier( List<Row> rows, int tier ) ;

        /** Let the user edit one tag set for all of the rows. */
        void edit( List<Row> rows ) ;

        /** Keep the rows' hand tags. */
        void keepExisting( List<Row> rows ) ;

        /** Defer the rows, with a note. */
        void defer( List<Row> rows ) ;

        /** Open the gap dialog for {@code row}'s proposal. */
        void reviewGap( Row row ) ;
    }

    private final Actions             actions ;
    private final ModelNames          names ;
    private final IntFunction<String> topicName ;

    private final JLabel heading = new JLabel( " " ) ;
    private final JLabel state   = new JLabel( " " ) ;

    private final Opinion tier1 = new Opinion( TagTableModel.TIER1 ) ;
    private final Opinion tier2 = new Opinion( TagTableModel.TIER2 ) ;

    private final JButton use1     = new JButton( "1  Tier 1's" ) ;
    private final JButton use2     = new JButton( "2  Tier 2's" ) ;
    private final JButton edit     = new JButton( "3  Edit…" ) ;
    private final JButton existing = new JButton( "4  Existing" ) ;
    private final JButton defer    = new JButton( "5  Defer…" ) ;
    private final JButton gap      = new JButton( "Review gap…" ) ;

    /** The rows shown; empty when none is. */
    private List<Row> rows = List.of() ;

    /**
     * @param names     the names models are shown by
     * @param topicName a topic's name by id, for the dependencies
     */
    TagDecisionPanel( Actions actions, ModelNames names,
                      IntFunction<String> topicName ) {

        super( new BorderLayout( 0, 4 ) ) ;
        this.actions   = actions ;
        this.names     = names ;
        this.topicName = topicName ;

        setBorder( BorderFactory.createTitledBorder( "Decision" ) ) ;
        heading.setFont( heading.getFont().deriveFont( Font.BOLD ) ) ;

        use1.setToolTipText( "Use tier 1's tags (key 1 on the table)" ) ;
        use2.setToolTipText( "Use tier 2's tags (key 2)" ) ;
        edit.setToolTipText( "Edit the tags and dependencies (key 3)" ) ;
        existing.setToolTipText( "Keep the hand tags already on it (key 4)" ) ;
        defer.setToolTipText( "Leave it pending on the server, with a note " +
                              "(key 5)" ) ;
        gap.setToolTipText( "Accept, map or reject the tag the model " +
                            "proposed; the run waits for it" ) ;
        use1.addActionListener( e -> actions.useTier( rows, 1 ) ) ;
        use2.addActionListener( e -> actions.useTier( rows, 2 ) ) ;
        edit.addActionListener( e -> actions.edit( rows ) ) ;
        existing.addActionListener( e -> actions.keepExisting( rows ) ) ;
        defer.addActionListener( e -> actions.defer( rows ) ) ;
        gap.addActionListener( e -> actions.reviewGap( rows.get( 0 ) ) ) ;

        JPanel labels = new JPanel( new GridLayout( 0, 1 ) ) ;
        labels.add( heading ) ;
        labels.add( state ) ;

        JPanel opinions = new JPanel( new GridLayout( 1, 0, 4, 0 ) ) ;
        opinions.add( tier1 ) ;
        opinions.add( tier2 ) ;

        JPanel keys = new JPanel( new GridLayout( 1, 0, 4, 0 ) ) ;
        keys.add( use1 ) ;
        keys.add( use2 ) ;
        keys.add( edit ) ;
        keys.add( existing ) ;
        keys.add( defer ) ;

        JLabel hint = new JLabel( "Keys on the table: 1 tier 1's · 2 tier " +
                                  "2's · 3 edit · 4 existing · 5 defer · " +
                                  "6 skip" ) ;
        hint.setFont( hint.getFont().deriveFont( hint.getFont().getSize2D() - 1 ) ) ;

        JPanel hintRow = new JPanel( new BorderLayout() ) ;
        hintRow.add( hint, BorderLayout.CENTER ) ;
        hintRow.add( gap, BorderLayout.EAST ) ;

        JPanel buttons = new JPanel( new GridLayout( 0, 1, 0, 2 ) ) ;
        buttons.add( keys ) ;
        buttons.add( hintRow ) ;

        add( labels, BorderLayout.NORTH ) ;
        add( opinions, BorderLayout.CENTER ) ;
        add( buttons, BorderLayout.SOUTH ) ;
        show( List.of() ) ;
    }

    /** Show {@code rows} (empty clears the panel). */
    void show( List<Row> rows ) {

        this.rows = List.copyOf( rows ) ;
        state.setText( " " ) ;
        tier1.show( null, null ) ;
        tier2.show( null, null ) ;
        enableButtons() ;

        if( rows.size() != 1 ) {
            heading.setText( rows.isEmpty() ? "Select a question"
                                            : "Multiple selections" ) ;
            return ;
        }

        Row     row  = rows.get( 0 ) ;
        TagItem item = row.item ;

        heading.setText( row.questionLabel() + "   " +
                         item.question().getSourceId() + "   (server id " +
                         item.id() + ")" + ( item.dependent()
                                             ? "   dependent of " +
                                               item.question().getTopicName()
                                             : "" ) ) ;
        state.setText( "Key " + item.key() + "  ·  " +
                       TagTableModel.statusText( row ) + "  ·  existing: " +
                       ( row.existing.isEmpty() ? "none"
                                                : String.join( ", ",
                                                               row.existing ) ) ) ;
        tier1.show( row.tier( 1 ), row.opinions == null ? "no reply yet"
                                                        : null ) ;
        tier2.show( row.tier( 2 ), row.opinions == null ? " "
                                   : row.opinions.tier2Failure() != null
                                     ? "no answer: " +
                                       row.opinions.tier2Failure()
                                     : "not asked" ) ;
        tier1.mark( row.correct( 1 ) ) ;
        tier2.mark( row.correct( 2 ) ) ;
    }

    /**
     * Press decision button {@code n} (1 tier 1's tags, 2 tier 2's, 3 edit,
     * 4 keep the hand tags, 5 defer), if it is enabled.
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
            case 1  -> use1 ;
            case 2  -> use2 ;
            case 3  -> edit ;
            case 4  -> existing ;
            case 5  -> defer ;
            default -> null ;
        } ;
    }

    /** Re-show the current rows after {@code changed} changed. */
    void refresh( Row changed ) {
        if( rows.contains( changed ) ) show( rows ) ;
    }

    /** Each button is enabled if it applies to any row shown. */
    private void enableButtons() {

        use1.setEnabled( any( r -> r.canUse( 1 ) ) ) ;
        use2.setEnabled( any( r -> r.canUse( 2 ) ) ) ;
        edit.setEnabled( any( Row::isOpen ) ) ;
        existing.setEnabled( any( Row::canKeepExisting ) ) ;
        defer.setEnabled( any( Row::isOpen ) ) ;
        gap.setVisible( rows.size() == 1
                        && rows.get( 0 ).status == TaggerStatus.GAP_PROPOSED ) ;
    }

    private boolean any( Predicate<Row> test ) {
        return rows.stream().anyMatch( test ) ;
    }

    /** One tier's reply, as text. */
    private final class Opinion extends JPanel {

        private final String    title ;
        private final JLabel    by   = new JLabel( " " ) ;
        private final JTextArea text = new JTextArea( 7, 20 ) ;

        Opinion( String title ) {

            super( new BorderLayout( 0, 2 ) ) ;
            this.title = title ;

            text.setEditable( false ) ;
            text.setLineWrap( true ) ;
            text.setWrapStyleWord( true ) ;

            JScrollPane pane = new JScrollPane( text ) ;
            pane.setPreferredSize( new Dimension( 100, 130 ) ) ;
            add( by, BorderLayout.NORTH ) ;
            add( pane, BorderLayout.CENTER ) ;
        }

        /** Show {@code t}; without one, {@code none} says why. */
        void show( TierReply t, String none ) {

            text.setText( "" ) ;
            if( t == null ) {
                by.setText( none == null || none.isBlank()
                            ? " " : title + ": " + none ) ;
                return ;
            }
            by.setText( title + ": " + names.of( t.model() ) +
                        " - answer " + t.answer() ) ;
            text.setText( describe( t.reply() ) ) ;
            text.setCaretPosition( 0 ) ;
        }

        /** Add whether the answer matches the key to the caption. */
        void mark( boolean correct ) {
            if( !by.getText().contains( " - answer " ) ) return ;
            by.setText( by.getText() + ( correct ? "  ✓" : "  ✗" ) ) ;
        }

        private String describe( TagReply r ) {

            StringBuilder sb = new StringBuilder() ;
            sb.append( "Setup: " ).append( list( r.setup() ) ).append( '\n' ) ;
            sb.append( "Concept:" ) ;
            if( r.concept().isEmpty() ) sb.append( " none" ) ;
            for( ConceptPick c : r.concept() ) {
                sb.append( "\n  " ).append( c.tag() ) ;
                if( c.why() != null ) sb.append( " - " ).append( c.why() ) ;
            }
            sb.append( "\nMath: " ).append( list( r.math() ) ) ;
            if( !r.xdep().isEmpty() ) {
                sb.append( "\nDepends on: " ).append( list(
                        r.xdep().stream().map( topicName::apply ).toList() ) ) ;
            }
            if( r.gap() != null ) {
                sb.append( "\nGap: " ).append( r.gap().tag() ).append( " - " )
                  .append( r.gap().hint() ).append( " (" )
                  .append( r.gap().why() ).append( ')' ) ;
            }
            if( !r.fixes().isEmpty() ) {
                sb.append( "\nFixed: " ).append( list( r.fixes() ) ) ;
            }
            return sb.toString() ;
        }

        private static String list( List<String> values ) {
            return values.isEmpty() ? "none" : String.join( ", ", values ) ;
        }
    }
}
