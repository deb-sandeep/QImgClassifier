package com.sandy.sconsole.qimgtools.apps.tagger.ui;

import com.sandy.sconsole.qimgtools.apps.tagger.model.TagClass;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagDecision;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagReply;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TierReply;
import com.sandy.sconsole.qimgtools.apps.tagger.ui.TagBadge.Payload;
import com.sandy.sconsole.qimgtools.apps.tagger.ui.TagTableModel.Row;
import com.sandy.sconsole.qimgtools.core.server.TopicDependency;
import com.sandy.sconsole.qimgtools.core.util.ModelNames;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/**
 * The selected question's key and the two tiers' answers side by side, so
 * they can be compared at a glance. Each part's title carries its answer at
 * the top right in square brackets: an answer that matches the key is
 * green, one that misses it pink. Below it are tags as badges, coloured by
 * axis: the key's part shows the hand tags already on the question, a
 * tier's the tags it chose (with its gap proposal dashed); after them, in
 * the concept pass for a question of the scoped topic, the topics it
 * depends on, grey. The tier whose tags the chosen set started from is
 * framed. Blank unless exactly one question is selected. Clicking a tier's
 * part chooses its tags as its key does: 1 tier 1's, 2 tier 2's. Each tag
 * and topic can be dragged to the chosen set ({@link TagBadge}). The setup
 * pass, which has no key, answers or tier 2, shows the hand tags and tier
 * 1's tags alone. Use on the EDT.
 */
@SuppressWarnings( "serial" )
final class AnswerStrip extends JPanel {

    private static final Color KEY_BG    = new Color( 0xDCE8F5 ) ;
    private static final Color TIER1_BG  = new Color( 0xFFF1D6 ) ;
    private static final Color TIER2_BG  = new Color( 0xEDE3F5 ) ;
    private static final int   HEIGHT    = 92 ;
    private static final float TITLE_PT  = 13f ;
    private static final float ANSWER_PT = 18f ;

    // Answer colours: matches the key, or misses it.
    private static final Color RIGHT_FG = new Color( 0x2E7D32 ) ;
    private static final Color WRONG_FG = new Color( 0xD81B60 ) ;

    /** Text that stands in for tags: none, or why there are none. */
    private static final Color NOTE_FG = new Color( 0x757575 ) ;

    /** The frame round the part decided on. */
    private static final Color DECIDED_LINE = new Color( 0x2E7D32 ) ;

    private final TagClass            pass ;
    private final ModelNames          names ;
    private final IntFunction<String> topicName ;
    private final IntConsumer         decide ;
    private final Part                key   = new Part( KEY_BG ) ;
    private final Part                tier1 = new Part( TIER1_BG ) ;
    private final Part                tier2 = new Part( TIER2_BG ) ;

    /** The row shown; null if none. */
    private Row row ;

    /**
     * @param pass      the pass the tagger runs
     * @param topicName a topic's name by id, for the dependencies
     * @param decide    called with the decision key a click on a tier
     *        stands for: 1 tier 1's tags, 2 tier 2's
     */
    AnswerStrip( TagClass pass, ModelNames names,
                 IntFunction<String> topicName, IntConsumer decide ) {

        super( new GridLayout( 1, 0 ) ) ;
        this.pass      = pass ;
        this.names     = names ;
        this.topicName = topicName ;
        this.decide    = decide ;
        setPreferredSize( new Dimension( 100, HEIGHT ) ) ;
        add( key ) ;
        add( tier1 ) ;
        if( pass.solves() ) add( tier2 ) ;
        onClick( tier1, 1 ) ;
        onClick( tier2, 2 ) ;
        show( null ) ;
    }

    /** A click on {@code part} decides as key {@code n} while it may. */
    private void onClick( Part part, int n ) {
        part.listenForClicks( new MouseAdapter() {
            @Override public void mouseClicked( MouseEvent e ) {
                if( part.clickable ) decide.accept( n ) ;
            }
        } ) ;
    }

    /** Show {@code row}'s key and answers; null clears the strip. */
    void show( Row row ) {

        String keyTitle = pass.solves() ? "Key" : "Existing" ;

        this.row = row ;
        if( row == null ) {
            key.set( keyTitle, null, null, false ) ;
            tier1.set( TagTableModel.TIER1, null, null, false ) ;
            tier2.set( TagTableModel.TIER2, null, null, false ) ;
            tier1.clickable( null ) ;
            tier2.clickable( null ) ;
            return ;
        }

        TagDecision d = row.chosen() ;
        key.set( keyTitle + "  ·  " + row.item.type(),
                 pass.solves() ? row.item.key() : null, null, false ) ;
        key.tags( row.existing, null, existingDeps( row ), "no hand tags" ) ;
        showTier( tier1, 1, d != null && d.source() == TagDecision.Source.TIER1 ) ;
        showTier( tier2, 2, d != null && d.source() == TagDecision.Source.TIER2 ) ;
    }

    private void showTier( Part part, int n, boolean decided ) {

        TierReply t     = row.tier( n ) ;
        String    title = n == 1 ? TagTableModel.TIER1 : TagTableModel.TIER2 ;

        if( t == null ) {
            String why = n == 2 && row.opinions != null
                         && row.opinions.tier2Failure() != null
                       ? "no answer: " + row.opinions.tier2Failure() : null ;
            part.set( title, null, null, false ) ;
            part.note( why ) ;
            part.clickable( null ) ;
            return ;
        }

        TagReply r        = t.reply() ;
        boolean  declined = pass.solves() && r.isAbstained() ;

        part.set( title + "  ·  " + names.of( t.model() ),
                  pass.solves() ? r.answerText() : null,
                  row.correct( n ) ? RIGHT_FG : WRONG_FG, decided ) ;
        if( declined ) {
            part.note( "declined to answer" ) ;
        }
        else {
            part.tags( r.tags(), r, showsDeps( row ) ? r.xdep() : List.of(),
                       "no tags" ) ;
        }
        part.clickable( declined ? null
                        : "Click to use " + title.toLowerCase() +
                          "'s tags (" + n + ")" ) ;
    }

    /** True if {@code row}'s dependencies are shown: concept, own. */
    private boolean showsDeps( Row row ) {
        return pass.hasDependents() && !row.item.dependent() ;
    }

    /** The topics {@code row} depends on now, on the server. */
    private List<Integer> existingDeps( Row row ) {

        List<TopicDependency> deps = row.item.question().getTopicDependencies() ;
        if( !showsDeps( row ) || deps == null ) return List.of() ;
        return deps.stream().map( TopicDependency::topicId ).toList() ;
    }

    /** Re-show the row shown if it is {@code changed}. */
    void refresh( Row changed ) {
        if( changed == row ) show( row ) ;
    }

    /**
     * One part: a title with the answer at its right, above the tags as
     * badges, which wrap onto further lines.
     */
    private final class Part extends JPanel {

        private final JLabel title  = new JLabel( " " ) ;
        private final JLabel answer = new JLabel( " " ) ;
        private final JPanel body   =
                new JPanel( new FlowLayout( FlowLayout.LEFT, 4, 3 ) ) ;
        private final Color  bg ;

        /** Told of clicks anywhere on the part, badges included. */
        private MouseListener clicks ;

        /** A click decides; set by {@link #clickable}. */
        boolean clickable ;

        Part( Color bg ) {

            super( new BorderLayout( 0, 2 ) ) ;
            this.bg = bg ;
            setBackground( bg ) ;
            frame( false ) ;
            title.setFont( title.getFont().deriveFont( Font.BOLD, TITLE_PT ) ) ;
            answer.setFont( answer.getFont().deriveFont( Font.BOLD,
                                                         ANSWER_PT ) ) ;

            JPanel top = new JPanel( new BorderLayout( 8, 0 ) ) ;
            top.setOpaque( false ) ;
            top.add( title, BorderLayout.CENTER ) ;
            top.add( answer, BorderLayout.EAST ) ;
            body.setOpaque( false ) ;
            add( top, BorderLayout.NORTH ) ;
            add( body, BorderLayout.CENTER ) ;
        }

        /** Tell {@code l} of clicks on the part and on whatever it shows. */
        void listenForClicks( MouseListener l ) {
            clicks = l ;
            addMouseListener( l ) ;
            body.addMouseListener( l ) ;
        }

        /**
         * Show {@code titleText} with {@code value} in square brackets at
         * its right (none if null), coloured {@code fg} (null for the usual
         * colour); {@code decided} frames the part as the one decided on.
         * The body is cleared.
         */
        void set( String titleText, String value, Color fg,
                  boolean decided ) {

            frame( decided ) ;
            title.setText( titleText ) ;
            answer.setText( value == null ? " " : "[" + value + "]" ) ;
            answer.setForeground( fg != null ? fg : title.getForeground() ) ;
            note( null ) ;
        }

        /**
         * Show {@code tags} as badges, with {@code reply}'s concept reasons
         * as their tooltips and its gap proposal dashed after them (null
         * for neither), then the topics {@code deps}; {@code none} if there
         * are no tags. The tags and topics can be dragged.
         */
        void tags( List<String> tags, TagReply reply, List<Integer> deps,
                   String none ) {

            body.removeAll() ;
            for( String t : tags ) {
                add( new TagBadge( t, whyOf( reply, t ), false )
                             .draggable( Payload.tag( t ) ) ) ;
            }
            if( reply != null && reply.gap() != null ) {
                TagReply.GapProposal g = reply.gap() ;
                add( new TagBadge( "+ " + g.tag(), g.hint(), true ) ) ;
            }
            for( int id : deps ) {
                add( new TagBadge( "→ " + topicName.apply( id ),
                                   "Depends on topic " + id, false )
                             .draggable( Payload.topic( id ) ) ) ;
            }
            if( body.getComponentCount() == 0 ) addNote( none ) ;
            body.revalidate() ;
            body.repaint() ;
        }

        /** Show {@code text} in place of tags; null for nothing. */
        void note( String text ) {

            body.removeAll() ;
            if( text != null ) addNote( text ) ;
            body.revalidate() ;
            body.repaint() ;
        }

        private void addNote( String text ) {

            JLabel l = new JLabel( text ) ;
            l.setFont( l.getFont().deriveFont( Font.ITALIC ) ) ;
            l.setForeground( NOTE_FG ) ;
            body.add( l ) ;
        }

        private void add( TagBadge b ) {
            // A badge with a tooltip takes the clicks, so it passes them on.
            if( clicks != null ) b.addMouseListener( clicks ) ;
            body.add( b ) ;
        }

        private static String whyOf( TagReply reply, String tag ) {

            if( reply == null ) return null ;
            return reply.concept().stream()
                        .filter( c -> c.tag().equals( tag ) )
                        .map( TagReply.ConceptPick::why )
                        .findFirst().orElse( null ) ;
        }

        /** A thick green frame when decided on, else a thin one. */
        private void frame( boolean decided ) {

            int   w    = decided ? 3 : 1 ;
            Color line = decided ? DECIDED_LINE : bg.darker() ;

            setBorder( BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder( line, w ),
                    BorderFactory.createEmptyBorder( 4 - w, 10, 5 - w, 10 ) ) ) ;
        }

        /**
         * Make a click decide, with {@code tip} as the tooltip and a hand
         * cursor; null makes it inert.
         */
        void clickable( String tip ) {
            clickable = tip != null ;
            setToolTipText( tip ) ;
            setCursor( Cursor.getPredefinedCursor(
                    clickable ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR ) ) ;
        }
    }
}
