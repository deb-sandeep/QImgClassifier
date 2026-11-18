package com.sandy.sconsole.qimgtools.apps.transcriber.ui;

import com.sandy.sconsole.qimgtools.apps.transcriber.gate.ConfidenceScore.Contest;
import com.sandy.sconsole.qimgtools.core.ui.ImagePreview;
import com.sandy.sconsole.qimgtools.core.ui.MarkdownView;

import lombok.extern.slf4j.Slf4j;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.File;
import java.util.List;

/**
 * The Check tab (feature-design.md §8.1, §8.4): the selected question's
 * image above its transcription, rendered ({@link MarkdownView}) or, with
 * the <i>Markdown</i> toggle, as raw markdown with Qwen's contested
 * positions highlighted; the spot-check marks as buttons. The buttons never
 * take the focus, so the keys stay with the table.
 * <p>
 * A question given more than one transcription offers them in a drop-down
 * above the transcription (the one in use marked), and <i>Use this one</i>
 * takes the one shown instead. A second view, beside the first, starts
 * closed: drag the divider from the right edge (or click its arrow) to
 * open it, with its own drop-down, to see two transcriptions side by
 * side.
 * <p>
 * {@link #edit} turns the lower half into an editor beside the rendered
 * view, which follows the text after a short pause; the well-formed
 * problems are shown under it, and <i>Done</i> is enabled only without
 * any.
 */
@Slf4j
@SuppressWarnings( "serial" )
final class CheckPanel extends JPanel {

    /** What the buttons do: the marks, by their keys, and Edit passage. */
    interface Marks {

        /** Press mark key {@code n} (1-4) for the selected rows. */
        void press( int n ) ;

        /** True if mark key {@code n} applies to the selected rows. */
        boolean canPress( int n ) ;

        /** Edit the selected LCT row's passage. */
        void editPassage() ;

        /** True if the selected row's passage can be edited. */
        boolean canEditPassage() ;

        /** Use the transcription of choice {@code index} instead. */
        void choose( int index ) ;

        /** True if the selected row may take another transcription. */
        boolean canChoose() ;
    }

    /**
     * One of a question's transcriptions, as the drop-downs offer it.
     *
     * @param label    its name in the drop-downs
     * @param markdown its whole content, as shown
     * @param current  true for the one in use
     */
    record Choice( String label, String markdown, boolean current ) {}

    /** An edit under way. */
    interface Editing {

        /** The well-formed problems of {@code text}; empty if none. */
        List<String> problems( String text ) ;

        /** Keep {@code text}. */
        void done( String text ) ;

        /** Drop the edit. */
        void cancelled() ;
    }

    /** The page that renders a transcription, this app's own. */
    private static final String VIEW_PAGE = "/apps/transcriber/web/view.html" ;

    private static final int    IMAGE_HEIGHT = 320 ;
    private static final int    IMAGE_WIDTH  = 900 ;
    private static final double IMAGE_SHARE  = 0.45 ;
    private static final int    RENDER_DELAY = 400 ;

    private static final String RENDERED = "rendered" ;
    private static final String RAW      = "raw" ;
    private static final String MARKS    = "marks" ;
    private static final String EDITING  = "editing" ;

    private static final Color CONTEST_BG = new Color( 0xFFCC80 ) ;
    private static final Color PROBLEM_FG = new Color( 0xC62828 ) ;

    private static final String[] BUTTONS = {
            "1  Correct", "2  Faulty: next model", "3  Faulty passage",
            "4  Edit"
    } ;

    private final ImagePreview  image       =
            new ImagePreview( IMAGE_HEIGHT, IMAGE_WIDTH ) ;
    private final JTextArea     editor      = textArea( true ) ;
    private final JButton[]     marks       = new JButton[BUTTONS.length] ;
    private final JButton       editPassage = new JButton( "Edit passage" ) ;
    private final JToggleButton markdown    = new JToggleButton( "Markdown" ) ;
    private final JLabel        problems    = new JLabel( " " ) ;
    private final JButton       done        = new JButton( "Done" ) ;
    private final JButton       cancel      = new JButton( "Cancel" ) ;
    private final Side          left        = new Side() ;
    private final Side          right       = new Side() ;
    private final JSplitPane    compare     = new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT, left.panel, right.panel ) ;
    private final Marks         actions ;

    // The lower half: the two views side by side, the second closed at
    // first; or while editing the editor beside the first view's rendering.
    private final JPanel lower   = new JPanel( new BorderLayout() ) ;
    private final JPanel buttons = new JPanel( new CardLayout() ) ;
    private final Timer  rerender ;

    /** The edit under way; null when not editing. */
    private Editing editing ;

    /** The choices offered; fewer than two offers none. */
    private List<Choice> choices = List.of() ;

    /** The transcription in use: its contests, and their shift. */
    private List<Contest> contests = List.of() ;
    private int           shift ;

    /** True while the drop-downs are refilled: their events are ignored. */
    private boolean filling ;

    CheckPanel( Marks actions ) {

        super( new BorderLayout() ) ;
        this.actions = actions ;

        right.panel.setMinimumSize( new Dimension( 0, 0 ) ) ;
        compare.setResizeWeight( 1.0 ) ;
        compare.setContinuousLayout( true ) ;
        compare.setOneTouchExpandable( true ) ;
        compare.addComponentListener( new ComponentAdapter() {
            @Override public void componentResized( ComponentEvent e ) {
                if( compare.getWidth() <= 0 ) return ;
                compare.setDividerLocation( 1.0 ) ;
                compare.removeComponentListener( this ) ;
            }
        } ) ;
        lower.add( compare, BorderLayout.CENTER ) ;

        JSplitPane split = new JSplitPane( JSplitPane.VERTICAL_SPLIT, image,
                                           lower ) ;
        split.setResizeWeight( IMAGE_SHARE ) ;
        split.setContinuousLayout( true ) ;

        markdown.setFocusable( false ) ;
        markdown.setToolTipText( "Show the raw markdown, Qwen's contested " +
                                 "positions highlighted" ) ;
        markdown.addActionListener( e -> showCards() ) ;
        editPassage.setFocusable( false ) ;
        editPassage.addActionListener( e -> actions.editPassage() ) ;

        JPanel markBar = new JPanel( new FlowLayout( FlowLayout.LEFT ) ) ;
        for( int i = 0 ; i < BUTTONS.length ; i++ ) {
            int n = i + 1 ;
            marks[i] = new JButton( BUTTONS[i] ) ;
            marks[i].setFocusable( false ) ;
            marks[i].addActionListener( e -> actions.press( n ) ) ;
            markBar.add( marks[i] ) ;
        }
        markBar.add( editPassage ) ;
        markBar.add( markdown ) ;

        buttons.add( markBar, MARKS ) ;
        buttons.add( editBar(), EDITING ) ;

        rerender = new Timer( RENDER_DELAY, e -> editorPaused() ) ;
        rerender.setRepeats( false ) ;
        editor.getDocument().addDocumentListener( new DocumentListener() {
            @Override public void insertUpdate( DocumentEvent e ) {
                rerender.restart() ;
            }

            @Override public void removeUpdate( DocumentEvent e ) {
                rerender.restart() ;
            }

            @Override public void changedUpdate( DocumentEvent e ) {}
        } ) ;

        add( split, BorderLayout.CENTER ) ;
        add( buttons, BorderLayout.SOUTH ) ;
        refreshButtons() ;
    }

    private static JTextArea textArea( boolean editable ) {

        JTextArea t = new JTextArea() ;

        t.setEditable( editable ) ;
        t.setLineWrap( true ) ;
        t.setWrapStyleWord( true ) ;
        t.setFont( new Font( Font.MONOSPACED, Font.PLAIN, 13 ) ) ;
        t.setBorder( BorderFactory.createEmptyBorder( 6, 8, 6, 8 ) ) ;
        return t ;
    }

    /** The problems, Done and Cancel, shown while editing. */
    private JPanel editBar() {

        JPanel bar  = new JPanel( new BorderLayout() ) ;
        JPanel keys = new JPanel( new FlowLayout( FlowLayout.RIGHT ) ) ;

        problems.setForeground( PROBLEM_FG ) ;
        problems.setBorder( BorderFactory.createEmptyBorder( 0, 8, 0, 8 ) ) ;
        done.addActionListener( e -> finishEdit( true ) ) ;
        cancel.addActionListener( e -> finishEdit( false ) ) ;
        keys.add( done ) ;
        keys.add( cancel ) ;
        bar.add( problems, BorderLayout.CENTER ) ;
        bar.add( keys, BorderLayout.EAST ) ;
        return bar ;
    }

    /**
     * Show {@code images} and {@code markdown}, with {@code contests}
     * highlighted in its raw text, each {@code shift} characters further
     * on (the passage before an LCT question part); null images clear the
     * image, null markdown shows {@code note} instead (e.g. why there is
     * no transcription). With two or more {@code choices}, both views offer
     * them in a drop-down: the first shows the one in use, the second the
     * latest other. Ignored while editing.
     */
    void show( List<File> images, String markdown, List<Contest> contests,
               int shift, String note, List<Choice> choices ) {

        if( editing != null ) return ;
        image.show( images ) ;
        this.contests = contests == null ? List.of() : contests ;
        this.shift    = shift ;
        this.choices  = choices == null || choices.size() < 2
                        ? List.of() : List.copyOf( choices ) ;

        int inUse = 0 ;
        int other = -1 ;
        for( int i = 0 ; i < this.choices.size() ; i++ ) {
            if( this.choices.get( i ).current() ) inUse = i ;
            else other = i ;
        }
        if( other < 0 ) other = inUse ;

        filling = true ;
        try {
            left.fill( inUse ) ;
            right.fill( other ) ;
        }
        finally {
            filling = false ;
        }
        left.display( markdown, true, note ) ;
        if( this.choices.isEmpty() ) right.display( markdown, true, note ) ;
        else right.showChoice() ;
        refreshButtons() ;
    }

    /** Each view rendered, or raw if toggled or for a note. */
    private void showCards() {
        left.showCard() ;
        right.showCard() ;
    }

    /** Highlight {@code contests} in {@code raw}, {@code shift} further on. */
    private static void highlight( JTextArea raw, List<Contest> contests,
                                   int shift ) {

        Highlighter h = raw.getHighlighter() ;
        Highlighter.HighlightPainter paint =
                new DefaultHighlighter.DefaultHighlightPainter( CONTEST_BG ) ;
        int length = raw.getDocument().getLength() ;

        h.removeAllHighlights() ;
        for( Contest c : contests ) {
            int from = shift + c.offset() ;
            int to   = Math.min( length,
                                 from + Math.max( 1, c.chosen().length() ) ) ;
            if( from < 0 || from >= length ) continue ;
            try {
                h.addHighlight( from, to, paint ) ;
            }
            catch( BadLocationException e ) {
                log.debug( "Contest at {} out of the text", from ) ;
            }
        }
    }

    /**
     * Edit {@code text} beside its rendering, as {@code editing} says; the
     * marks are replaced by Done and Cancel until one is pressed.
     */
    void edit( String text, Editing editing ) {

        this.editing = editing ;
        editor.setText( text == null ? "" : text ) ;
        editor.setCaretPosition( 0 ) ;

        JSplitPane side = new JSplitPane( JSplitPane.HORIZONTAL_SPLIT,
                                          new JScrollPane( editor ),
                                          left.view ) ;
        side.setResizeWeight( 0.5 ) ;
        side.setContinuousLayout( true ) ;
        lower.removeAll() ;
        lower.add( side, BorderLayout.CENTER ) ;
        lower.revalidate() ;
        lower.repaint() ;

        ( (CardLayout) buttons.getLayout() ).show( buttons, EDITING ) ;
        editorPaused() ;
        editor.requestFocusInWindow() ;
    }

    /** True while an edit is under way. */
    boolean isEditing() {
        return editing != null ;
    }

    /** The editor paused: render its text and show its problems. */
    private void editorPaused() {

        if( editing == null ) return ;
        String       text  = editor.getText() ;
        List<String> found = editing.problems( text ) ;

        left.view.show( text ) ;
        problems.setText( found.isEmpty() ? " "
                                          : "Can't keep: " +
                                            String.join( "; ", found ) ) ;
        problems.setToolTipText( found.isEmpty() ? null
                                                 : problems.getText() ) ;
        done.setEnabled( found.isEmpty() ) ;
    }

    /** Done ({@code keep}) or Cancel: back to the marks. */
    private void finishEdit( boolean keep ) {

        if( editing == null ) return ;
        String  text = editor.getText() ;
        Editing e    = editing ;

        if( keep && !e.problems( text ).isEmpty() ) return ;
        rerender.stop() ;
        editing = null ;

        left.shown.add( left.view, RENDERED ) ;
        lower.removeAll() ;
        lower.add( compare, BorderLayout.CENTER ) ;
        lower.revalidate() ;
        lower.repaint() ;
        ( (CardLayout) buttons.getLayout() ).show( buttons, MARKS ) ;
        showCards() ;

        if( keep ) e.done( text ) ;
        else e.cancelled() ;
    }

    /** Enable the buttons as the marks apply to the selection. */
    void refreshButtons() {
        for( int i = 0 ; i < marks.length ; i++ ) {
            marks[i].setEnabled( actions.canPress( i + 1 ) ) ;
        }
        editPassage.setEnabled( actions.canEditPassage() ) ;
        left.refresh() ;
        right.refresh() ;
    }

    /**
     * One view: a drop-down choosing one of the question's transcriptions,
     * Use this one, and the transcription rendered or raw.
     */
    private final class Side {

        final MarkdownView      view  = new MarkdownView( VIEW_PAGE ) ;
        final JTextArea         raw   = textArea( false ) ;
        final JPanel            shown = new JPanel( new CardLayout() ) ;
        final JComboBox<String> pick  = new JComboBox<>() ;
        final JButton           use   = new JButton( "Use this one" ) ;
        final JPanel            bar   = new JPanel( new FlowLayout(
                FlowLayout.LEFT, 6, 2 ) ) ;
        final JPanel            panel = new JPanel( new BorderLayout() ) ;

        /** The markdown shown; null for a note. */
        String text ;

        Side() {

            shown.add( view, RENDERED ) ;
            shown.add( new JScrollPane( raw ), RAW ) ;
            pick.setFocusable( false ) ;
            pick.setToolTipText( "The question's transcriptions; ● is the " +
                                 "one in use" ) ;
            pick.addActionListener( e -> {
                if( !filling ) showChoice() ;
            } ) ;
            use.setFocusable( false ) ;
            use.setToolTipText( "Use the transcription shown instead, as " +
                                "checked" ) ;
            use.addActionListener( e -> {
                int i = pick.getSelectedIndex() ;
                if( i >= 0 ) actions.choose( i ) ;
            } ) ;
            bar.add( pick ) ;
            bar.add( use ) ;
            bar.setVisible( false ) ;
            panel.add( bar, BorderLayout.NORTH ) ;
            panel.add( shown, BorderLayout.CENTER ) ;
        }

        /** Offer the choices, {@code selected} selected; hidden for none. */
        void fill( int selected ) {

            pick.removeAllItems() ;
            for( Choice c : choices ) {
                pick.addItem( c.current() ? "● " + c.label() : c.label() ) ;
            }
            if( !choices.isEmpty() ) pick.setSelectedIndex( selected ) ;
            bar.setVisible( !choices.isEmpty() ) ;
            bar.revalidate() ;
        }

        /** Show the choice selected; contests only for the one in use. */
        void showChoice() {

            int i = pick.getSelectedIndex() ;
            if( i < 0 || i >= choices.size() ) return ;
            Choice c = choices.get( i ) ;
            display( c.markdown(), c.current(), null ) ;
            refreshButtons() ;
        }

        /**
         * Show {@code markdown}, its contests highlighted if it is the one
         * in use ({@code inUse}), or {@code note} without it.
         */
        void display( String markdown, boolean inUse, String note ) {

            text = markdown ;
            raw.setText( markdown != null ? markdown
                                          : note == null ? "" : note ) ;
            raw.setCaretPosition( 0 ) ;
            highlight( raw, markdown == null || !inUse ? List.of()
                                                       : contests, shift ) ;
            view.show( markdown ) ;
            showCard() ;
        }

        /** Rendered, or raw if toggled or for a note. */
        void showCard() {
            ( (CardLayout) shown.getLayout() ).show(
                    shown, markdown.isSelected() || text == null ? RAW
                                                                 : RENDERED ) ;
        }

        /** Use this one only for one not in use, if a choice may be made. */
        void refresh() {
            int i = pick.getSelectedIndex() ;
            use.setEnabled( i >= 0 && i < choices.size()
                            && !choices.get( i ).current()
                            && actions.canChoose() ) ;
        }
    }
}
