package com.sandy.sconsole.qimgtools.apps.nxrclassifier.ui;

import com.sandy.sconsole.qimgtools.apps.nxrclassifier.model.NXRValue;
import com.sandy.sconsole.qimgtools.apps.nxrclassifier.ui.NXRTableModel.Row;
import com.sandy.sconsole.qimgtools.apps.nxrclassifier.model.NXRStatus;
import com.sandy.sconsole.qimgtools.core.server.ServerQuestion;
import com.sandy.sconsole.qimgtools.core.util.ModelNames;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.Border;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The selected question's facts and its labels (the classifier's, the AI's
 * and the one to save) above three large buttons, N, X and R, that label it, and one that
 * clears the user's label. With several questions selected it says only
 * "Multiple selections", and a button labels each of them. Keys 1-4 (and N,
 * X, R) on the frame's table press these buttons ({@link #press}). Use on
 * the EDT.
 */
@SuppressWarnings( "serial" )
final class NXRDecisionPanel extends JPanel {

    /** What the buttons ask for, for the rows shown. */
    interface Actions {

        /** Label the rows {@code value}. */
        void label( List<Row> rows, NXRValue value ) ;

        /** Drop the user's labels of the rows, back to the classifier's. */
        void clearLabel( List<Row> rows ) ;
    }

    /** The button of the label to save. */
    private static final Color CHOSEN_BG = new Color( 0xB9E4BB ) ;

    /**
     * The button of the label the classifier leans to but gave none, its
     * confidence being below the threshold.
     */
    private static final Border GUESS_BORDER =
            BorderFactory.createLineBorder( new Color( 0xEF6C00 ), 3 ) ;

    private static final float BUTTON_FONT_SIZE = 18f ;
    private static final int   BUTTON_HEIGHT    = 56 ;

    private final Actions    actions ;
    private final ModelNames names ;

    private final JLabel heading    = new JLabel( " " ) ;
    private final JLabel facts      = new JLabel( " " ) ;
    private final JLabel classifier = new JLabel( " " ) ;
    private final JLabel ai         = new JLabel( " " ) ;

    private final Map<NXRValue,JButton> buttons =
            new EnumMap<>( NXRValue.class ) ;
    private final JButton clear = new JButton( "4  Clear" ) ;

    /** The table's background for a button not chosen. */
    private Color plainBg ;

    /** A button's border when it is not the classifier's guess. */
    private Border plainBorder ;

    /** The classifier's guess for the row shown (framed); null if none. */
    private NXRValue guess ;

    /** The rows shown; empty when none is. */
    private List<Row> rows = List.of() ;

    /** @param names the names models are shown by */
    NXRDecisionPanel( Actions actions, ModelNames names ) {

        super( new BorderLayout( 0, 6 ) ) ;
        this.actions = actions ;
        this.names   = names ;

        setBorder( BorderFactory.createTitledBorder( "Label" ) ) ;
        heading.setFont( heading.getFont().deriveFont( Font.BOLD ) ) ;

        JPanel labels = new JPanel( new GridLayout( 0, 1 ) ) ;
        labels.add( heading ) ;
        labels.add( facts ) ;
        labels.add( classifier ) ;
        labels.add( ai ) ;

        JPanel row = new JPanel( new GridLayout( 1, 0, 6, 0 ) ) ;
        int    key = 1 ;
        for( NXRValue v : NXRValue.values() ) {
            JButton b = new JButton( key++ + "   " + v.describe() ) ;

            b.setFont( b.getFont().deriveFont( Font.BOLD,
                                               BUTTON_FONT_SIZE ) ) ;
            b.setPreferredSize( new Dimension( 0, BUTTON_HEIGHT ) ) ;
            b.setFocusable( false ) ;
            b.setToolTipText( "Label " + v.getLabel().toLowerCase() +
                              " (key " + ( key - 1 ) + " or " + v.name() +
                              " on the table)" ) ;
            b.addActionListener( e -> actions.label( rows, v ) ) ;
            buttons.put( v, b ) ;
            row.add( b ) ;
        }
        plainBg     = buttons.get( NXRValue.N ).getBackground() ;
        plainBorder = buttons.get( NXRValue.N ).getBorder() ;

        clear.setFocusable( false ) ;
        clear.setToolTipText( "Drop the user's label; the classifier's " +
                              "stands (key 4)" ) ;
        clear.addActionListener( e -> actions.clearLabel( rows ) ) ;
        row.add( clear ) ;

        JLabel hint = new JLabel( "Keys on the table: 1 or N numeric · " +
                                  "2 or X reasoning · 3 or R recall · " +
                                  "Space the framed guess · 4 clear · " +
                                  "5 skip" ) ;
        hint.setFont( hint.getFont().deriveFont(
                hint.getFont().getSize2D() - 1 ) ) ;

        add( labels, BorderLayout.NORTH ) ;
        add( row, BorderLayout.CENTER ) ;
        add( hint, BorderLayout.SOUTH ) ;
        show( List.of() ) ;
    }

    /** Show {@code rows} (empty clears the panel). */
    void show( List<Row> rows ) {

        this.rows = List.copyOf( rows ) ;
        facts.setText( " " ) ;
        classifier.setText( " " ) ;
        ai.setText( " " ) ;
        enableButtons() ;
        markChosen( null ) ;
        markGuess( null ) ;

        if( rows.size() != 1 ) {
            heading.setText( rows.isEmpty() ? "Select a question"
                                            : "Multiple selections" ) ;
            return ;
        }

        Row            row = rows.get( 0 ) ;
        ServerQuestion q   = row.question ;

        heading.setText( row.questionLabel() + "   " + q.getSourceId() +
                         "   (server id " + q.getId() + ")" ) ;
        facts.setText( "Type: " + q.getProblemType() + "    Answer: " +
                       ( q.getAnswer() == null ? "-" : q.getAnswer() ) +
                       "    Syllabus: " + q.getSyllabusName() ) ;
        classifier.setText( "Classifier: " + classifierText( row ) ) ;
        ai.setText( "AI: " + aiText( row ) ) ;
        markChosen( row.value() ) ;
        markGuess( guess( row ) ) ;
    }

    /** The classifier's label of {@code row}, or why there is none. */
    private static String classifierText( Row row ) {

        return switch( row.classifiedStatus ) {
            case PENDING -> "not run yet" ;
            case FAILED  -> "failed" ;
            default      -> row.classifierText() ;
        } ;
    }

    /** The AI's label of {@code row} and its model, or why there is none. */
    private String aiText( Row row ) {

        if( row.ai != null ) {
            return row.ai.describe() + "  - " + names.of( row.ai.model() ) ;
        }
        return row.status == NXRStatus.ASKING_AI ? "asked, no answer yet"
                                                 : "not asked" ;
    }

    /** Re-show the current rows after {@code changed} changed. */
    void refresh( Row changed ) {
        if( rows.contains( changed ) ) show( rows ) ;
    }

    /**
     * Press decision button {@code n} (1 N, 2 X, 3 R, 4 clear), if it is
     * enabled.
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
            case 1  -> buttons.get( NXRValue.N ) ;
            case 2  -> buttons.get( NXRValue.X ) ;
            case 3  -> buttons.get( NXRValue.R ) ;
            case 4  -> clear ;
            default -> null ;
        } ;
    }

    /** Each button is enabled if it applies to any row shown. */
    private void enableButtons() {

        boolean open = rows.stream().anyMatch( Row::isOpen ) ;

        buttons.values().forEach( b -> b.setEnabled( open ) ) ;
        clear.setEnabled( rows.stream().anyMatch(
                r -> r.isOpen() && r.chosen != null ) ) ;
    }

    /**
     * The label the classifier leans to without giving it (low
     * confidence); null if it gave a label, or had no lean.
     */
    private static NXRValue guess( Row row ) {
        return row.result == null || row.suggested != null
               ? null : NXRValue.of( row.result.guess() ) ;
    }

    /**
     * Press the framed button, the classifier's low-confidence guess, if
     * there is one and it is enabled.
     *
     * @return true if it was pressed
     */
    boolean pressGuess() {

        JButton b = guess == null ? null : buttons.get( guess ) ;
        if( b == null || !b.isEnabled() ) return false ;
        b.doClick( 0 ) ;
        return true ;
    }

    /** Frame the button of {@code value}; null frames none. */
    private void markGuess( NXRValue value ) {
        guess = value ;
        buttons.forEach( ( v, b ) -> b.setBorder(
                v == value ? GUESS_BORDER : plainBorder ) ) ;
    }

    /** Tint the button of {@code value}; null tints none. */
    private void markChosen( NXRValue value ) {
        buttons.forEach( ( v, b ) -> {
            b.setBackground( v == value ? CHOSEN_BG : plainBg ) ;
            b.setOpaque( v == value ) ;
        } ) ;
    }
}
