package com.sandy.sconsole.qimgtools.apps.tagger.ui;

import com.sandy.sconsole.qimgtools.apps.tagger.model.TagDecision;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagReply;
import com.sandy.sconsole.qimgtools.apps.tagger.ui.TagTableModel.Llm;
import com.sandy.sconsole.qimgtools.apps.tagger.ui.TagTableModel.Row;
import com.sandy.sconsole.qimgtools.core.util.ModelNames;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * The selected question's tags side by side, so they can be compared at a
 * glance: the topic's tags already on it, those the regression accepted, and
 * the LLM's reply, as badges. An undecided tag is left out, and drawn with a
 * dotted red outline between a green tick, which puts it in the chosen set, and
 * a red cross, which turns it down. The LLM's tags are in the chosen set when
 * they arrive, and a click takes one out or puts it back; its button asks the
 * LLM about a question no model has answered. The part the chosen set started
 * from is framed. Blank unless exactly one question is selected. Clicking the
 * proposed part chooses its tags, as key 1 does. Each tag can be dragged to the
 * chosen set
 * ({@link TagBadge}). Use on the EDT.
 */
@SuppressWarnings( "serial" )
final class TagStrip extends JPanel {

    /** What the strip's clicks do. */
    interface Actions {

        /** Decide as key {@code key} does. */
        void decide( int key ) ;

        /** Put undecided {@code tag} in the chosen set, or turn it down. */
        void resolve( String tag, boolean accept ) ;

        /** Add {@code tag} to the chosen set, or take it out. */
        void toggle( String tag, boolean add ) ;

        /** Ask the LLM about {@code row}. */
        void invokeLlm( Row row ) ;

        /** True if the LLM can be asked about {@code row} now. */
        boolean canInvokeLlm( Row row ) ;
    }

    private static final String EXISTING = "Existing" ;
    private static final String PROPOSED = "Proposed" ;
    private static final String LLM      = "LLM" ;

    private static final Color EXISTING_BG = new Color( 0xDCE8F5 ) ;
    private static final Color PROPOSED_BG = new Color( 0xFFF1D6 ) ;
    private static final Color LLM_BG      = new Color( 0xE9E4F5 ) ;
    private static final int   HEIGHT      = 122 ;
    private static final float TITLE_PT    = 13f ;
    private static final float BUTTON_PT   = 11f ;
    private static final float TICK_PT     = 14f ;

    /** Text that stands in for tags. */
    private static final Color NOTE_FG = new Color( 0x757575 ) ;

    /** The frame round the part decided on. */
    private static final Color DECIDED_LINE = new Color( 0x2E7D32 ) ;

    // An undecided tag: its outline while open, and once put in or turned
    // down; its tick and cross, and either when the other is in force.
    private static final Color OPEN_LINE     = new Color( 0xD32F2F ) ;
    private static final Color IN_LINE       = new Color( 0x2E7D32 ) ;
    private static final Color TURNED_DOWN   = new Color( 0xBDBDBD ) ;
    private static final Color TICK_FG       = new Color( 0x2E7D32 ) ;
    private static final Color CROSS_FG      = new Color( 0xD32F2F ) ;
    private static final Color OFF_MARK_FG   = new Color( 0xC4C4C4 ) ;

    private final ModelNames names ;
    private final Actions    actions ;
    private final Part       existing = new Part( EXISTING_BG ) ;
    private final Part       proposed = new Part( PROPOSED_BG ) ;
    private final Part       llm      = new Part( LLM_BG ) ;
    private final JButton    invoke   = new JButton( "Invoke LLM" ) ;

    /** The row shown; null if none. */
    private Row row ;

    TagStrip( ModelNames names, Actions actions ) {

        super( new GridLayout( 1, 0 ) ) ;
        this.names   = names ;
        this.actions = actions ;
        setPreferredSize( new Dimension( 100, HEIGHT ) ) ;
        add( existing ) ;
        add( proposed ) ;
        add( llm ) ;
        proposed.addMouseListener( new MouseAdapter() {
            @Override public void mouseClicked( MouseEvent e ) {
                if( proposed.clickable ) actions.decide( 1 ) ;
            }
        } ) ;
        invoke.setFont( invoke.getFont().deriveFont( BUTTON_PT ) ) ;
        invoke.setMargin( new Insets( 0, 6, 0, 6 ) ) ;
        invoke.setFocusable( false ) ;
        invoke.addActionListener( e -> {
            if( row != null ) actions.invokeLlm( row ) ;
        } ) ;
        llm.head.add( invoke, BorderLayout.EAST ) ;
        show( null ) ;
    }

    /** Show {@code row}'s tags; null clears the strip. */
    void show( Row row ) {

        this.row = row ;
        invoke.setEnabled( row != null && actions.canInvokeLlm( row ) ) ;
        if( row == null ) {
            existing.set( EXISTING, false ) ;
            proposed.set( PROPOSED, false ) ;
            proposed.clickable( null ) ;
            llm.set( LLM, false ) ;
            llm.setToolTipText( null ) ;
            return ;
        }

        TagDecision d = row.chosen() ;

        existing.set( EXISTING + "  ·  " + row.item.type(),
                      d != null && d.source() == TagDecision.Source.EXISTING ) ;
        existing.tags( row.existing, "no tags" ) ;
        showProposed( row, d ) ;
        showLlm( row, d ) ;
    }

    /** Re-show the row shown if it is {@code changed}. */
    void refresh( Row changed ) {
        if( changed != null && changed == row ) show( row ) ;
    }

    private void showProposed( Row row, TagDecision d ) {

        TagDecision  p         = row.proposed() ;
        List<String> undecided = row.undecided() ;

        if( p == null ) {
            proposed.set( PROPOSED, false ) ;
            proposed.clickable( null ) ;
            return ;
        }

        proposed.set( PROPOSED + "  ·  Regression",
                      d != null && d.source() == p.source() ) ;
        proposed.tags( p.tags(), undecided.isEmpty() ? "no tags" : null ) ;
        for( String t : undecided ) {
            proposed.body.add( undecidedTag( t, row, d ) ) ;
        }
        proposed.clickable( p.tags().isEmpty() ? null
                            : "Click to use the proposed tags (1)" ) ;
    }

    /** A tag between its tick and its cross, showing how it stands. */
    private JPanel undecidedTag( String tag, Row row, TagDecision d ) {

        boolean in   = d != null && d.tags().contains( tag ) ;
        boolean down = row.rejected.contains( tag ) && !in ;

        TagBadge badge = new TagBadge( tag, null ).draggable( tag ) ;
        if( in ) badge.outlined( IN_LINE, false ) ;
        else if( down ) badge.dimmed( true ).outlined( TURNED_DOWN, false ) ;
        else badge.outlined( OPEN_LINE, true ) ;

        JPanel p = new JPanel( new FlowLayout( FlowLayout.LEFT, 2, 0 ) ) ;
        p.setOpaque( false ) ;
        p.add( mark( "✓", down ? OFF_MARK_FG : TICK_FG,
                     "Accept " + tag, () -> actions.resolve( tag, true ) ) ) ;
        p.add( badge ) ;
        p.add( mark( "✗", in ? OFF_MARK_FG : CROSS_FG,
                     "Remove " + tag, () -> actions.resolve( tag, false ) ) ) ;
        return p ;
    }

    private static JLabel mark( String text, Color color, String tip,
                                Runnable onClick ) {

        JLabel l = new JLabel( text ) ;

        l.setFont( l.getFont().deriveFont( Font.BOLD, TICK_PT ) ) ;
        l.setForeground( color ) ;
        l.setToolTipText( tip ) ;
        l.setCursor( Cursor.getPredefinedCursor( Cursor.HAND_CURSOR ) ) ;
        l.addMouseListener( new MouseAdapter() {
            @Override public void mouseClicked( MouseEvent e ) {
                onClick.run() ;
            }
        } ) ;
        return l ;
    }

    private void showLlm( Row row, TagDecision d ) {

        llm.setToolTipText( null ) ;
        if( row.llm != Llm.IDLE ) {
            llm.set( LLM, false ) ;
            llm.note( row.llm == Llm.QUEUED ? "Waiting for a model…"
                      : "Asking " + ( row.model != null
                                      ? names.of( row.model ) : "the LLM" ) +
                        "…" ) ;
            return ;
        }
        if( row.llmFailure != null ) {
            llm.set( LLM, false ) ;
            llm.note( row.llmFailure ) ;
            llm.setToolTipText( row.llmFailure ) ;
            return ;
        }
        if( row.proposal == null ) {
            llm.set( LLM, false ) ;
            llm.note( "Not asked" ) ;
            return ;
        }

        TagReply r = row.proposal.reply() ;

        llm.set( LLM + "  ·  " + names.of( row.proposal.model() ) + "  ·  " +
                 r.confidence(),
                 d != null && d.source() == TagDecision.Source.LLM ) ;
        llm.setToolTipText( r.reason() ) ;
        if( r.noTagFits() ) {
            llm.note( "No tag fits" + ( r.framing() != null
                                        ? ": " + r.framing() : "" ) ) ;
            return ;
        }

        List<String> chosen = d == null ? row.startingSet().tags() : d.tags() ;
        for( String t : r.tags() ) {
            boolean  in = chosen.contains( t ) ;
            TagBadge b  = new TagBadge( t, in ? "Click to take it out"
                                              : "Click to choose it" ) ;

            b.draggable( t ).clickable( () -> actions.toggle( t, !in ) ) ;
            llm.body.add( b.dimmed( in ) ) ;
        }
        llm.body.revalidate() ;
        llm.body.repaint() ;
    }

    /** One part: a title above the tags as badges, which wrap. */
    private final class Part extends JPanel {

        final JPanel head = new JPanel( new BorderLayout( 6, 0 ) ) ;
        final JPanel body =
                new JPanel( new FlowLayout( FlowLayout.LEFT, 4, 3 ) ) ;

        private final JLabel title = new JLabel( " " ) ;
        private final Color  bg ;

        /** A click decides; set by {@link #clickable}. */
        boolean clickable ;

        Part( Color bg ) {

            super( new BorderLayout( 0, 2 ) ) ;
            this.bg = bg ;
            setBackground( bg ) ;
            frame( false ) ;
            title.setFont( title.getFont().deriveFont( Font.BOLD, TITLE_PT ) ) ;
            head.setOpaque( false ) ;
            head.add( title, BorderLayout.CENTER ) ;
            body.setOpaque( false ) ;
            add( head, BorderLayout.NORTH ) ;
            add( body, BorderLayout.CENTER ) ;
        }

        /**
         * Show {@code titleText}; {@code decided} frames the part as the one
         * decided on. The body is cleared.
         */
        void set( String titleText, boolean decided ) {

            frame( decided ) ;
            title.setText( titleText ) ;
            body.removeAll() ;
            body.revalidate() ;
            body.repaint() ;
        }

        /** Show {@code tags} as badges to drag; {@code none}, if any, if empty. */
        void tags( List<String> tags, String none ) {

            body.removeAll() ;
            for( String t : tags ) {
                TagBadge b = new TagBadge( t, null ).draggable( t ) ;
                // The badge takes the clicks, so it passes them on.
                b.addMouseListener( new MouseAdapter() {
                    @Override public void mouseClicked( MouseEvent e ) {
                        if( clickable ) actions.decide( 1 ) ;
                    }
                } ) ;
                body.add( b ) ;
            }
            if( tags.isEmpty() && none != null ) note( none ) ;
            body.revalidate() ;
            body.repaint() ;
        }

        /** Show {@code text} in the body, in place of tags. */
        void note( String text ) {

            JLabel l = new JLabel( text ) ;

            l.setFont( l.getFont().deriveFont( Font.ITALIC ) ) ;
            l.setForeground( NOTE_FG ) ;
            body.add( l ) ;
            body.revalidate() ;
            body.repaint() ;
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
