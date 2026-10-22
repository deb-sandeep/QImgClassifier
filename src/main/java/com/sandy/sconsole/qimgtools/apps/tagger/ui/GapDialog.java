package com.sandy.sconsole.qimgtools.apps.tagger.ui;

import com.sandy.sconsole.qimgtools.apps.tagger.model.Axis;
import com.sandy.sconsole.qimgtools.apps.tagger.model.CatalogueTag;
import com.sandy.sconsole.qimgtools.apps.tagger.model.NewTag;
import com.sandy.sconsole.qimgtools.apps.tagger.model.OfferedSet;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagReply.GapProposal;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Decides on a tag a model proposed for the scoped topic: accept it (as
 * edited: tag text, full name, description, hint and colour), map it to an
 * offered tag the model missed, or reject it; or leave it for later, the
 * run staying paused. Shows the question, the proposal and the topic's tags
 * of the proposal's axis.
 */
final class GapDialog {

    /** What becomes of the proposal. */
    enum Fate {

        ACCEPTED( "accepted" ), MAPPED( "mapped" ), REJECTED( "rejected" ) ;

        /** As noted in {@code tagger-notes.jsonl}. */
        final String label ;

        Fate( String label ) {
            this.label = label ;
        }
    }

    /**
     * The user's choice.
     *
     * @param fate   what becomes of the proposal
     * @param tag    the tag to create, if accepted; null otherwise
     * @param mapTo  the offered tag the question takes, if mapped; null
     *               otherwise
     */
    record Choice( Fate fate, NewTag tag, String mapTo ) {}

    private static final String ACCEPT = "Accept as new tag" ;
    private static final String MAP    = "Map to existing" ;
    private static final String REJECT = "Reject" ;
    private static final String LATER  = "Later" ;

    private static final Pattern TAG_TEXT = Pattern.compile( "[a-z0-9.-]+" ) ;
    private static final Pattern COLOR    = Pattern.compile( "#[0-9A-Fa-f]{6}" ) ;

    private GapDialog() {}

    /**
     * Ask what becomes of {@code gap}, proposed on {@code question} by
     * {@code model}.
     *
     * @return the choice; null to decide later
     */
    static Choice ask( Component parent, String question, String model,
                       GapProposal gap, OfferedSet offered ) {

        Axis               axis    = Axis.of( gap.tag() ) ;
        List<CatalogueTag> sameAxis = axis == null ? offered.all()
                                                   : offered.tags( axis ) ;

        JTextField tagText  = new JTextField( gap.tag(), 40 ) ;
        JTextField fullName = new JTextField( fullName( gap.tag() ), 40 ) ;
        JTextField descr    = new JTextField( gap.hint(), 40 ) ;
        JTextArea  hint     = new JTextArea( gap.hint(), 3, 40 ) ;
        JTextField color    = new JTextField( sameAxis.isEmpty() ? "#607D8B"
                                              : sameAxis.get( 0 ).color(), 8 ) ;
        JComboBox<String> mapTo = new JComboBox<>( offered.all().stream()
                .map( CatalogueTag::tagText ).toArray( String[]::new ) ) ;

        hint.setLineWrap( true ) ;
        hint.setWrapStyleWord( true ) ;
        if( !sameAxis.isEmpty() ) mapTo.setSelectedItem( sameAxis.get( 0 )
                                                                 .tagText() ) ;

        JTextArea about = new JTextArea(
                question + ": " + model + " proposes\n\n  " + gap.tag() +
                "\n  hint: " + gap.hint() + "\n  why: " + gap.why() +
                "\n\nThe topic's " + ( axis != null ? axis.getWord() : "" ) +
                " tags now: " + String.join( ", ", sameAxis.stream()
                        .map( CatalogueTag::tagText ).toList() ), 8, 60 ) ;
        about.setEditable( false ) ;
        about.setLineWrap( true ) ;
        about.setWrapStyleWord( true ) ;

        JPanel fields = new JPanel( new GridBagLayout() ) ;
        fields.setBorder( BorderFactory.createTitledBorder(
                "The new tag (" + ACCEPT + "); its axis rule is put " +
                "before the hint" ) ) ;
        int row = 0 ;
        row = field( fields, row, "Tag text", tagText ) ;
        row = field( fields, row, "Full name", fullName ) ;
        row = field( fields, row, "Description", descr ) ;
        row = field( fields, row, "Hint", new JScrollPane( hint ) ) ;
        field( fields, row, "Colour", color ) ;

        JPanel map = new JPanel( new BorderLayout( 6, 0 ) ) ;
        map.setBorder( BorderFactory.createTitledBorder( MAP ) ) ;
        map.add( new JLabel( "Offered tag:" ), BorderLayout.WEST ) ;
        map.add( mapTo, BorderLayout.CENTER ) ;

        JPanel south = new JPanel( new BorderLayout( 0, 6 ) ) ;
        south.add( fields, BorderLayout.CENTER ) ;
        south.add( map, BorderLayout.SOUTH ) ;

        JPanel panel = new JPanel( new BorderLayout( 0, 8 ) ) ;
        panel.add( new JScrollPane( about ), BorderLayout.NORTH ) ;
        panel.add( south, BorderLayout.CENTER ) ;

        Object[] options = { ACCEPT, MAP, REJECT, LATER } ;
        while( true ) {
            int answer = JOptionPane.showOptionDialog( parent, panel,
                    "Gap proposed", JOptionPane.DEFAULT_OPTION,
                    JOptionPane.PLAIN_MESSAGE, null, options, LATER ) ;
            if( answer < 0 || options[answer] == LATER ) return null ;
            if( options[answer] == REJECT ) {
                return new Choice( Fate.REJECTED, null, null ) ;
            }
            if( options[answer] == MAP ) {
                return new Choice( Fate.MAPPED, null,
                                   (String) mapTo.getSelectedItem() ) ;
            }

            NewTag tag = new NewTag( tagText.getText().strip(),
                                     fullName.getText().strip(),
                                     descr.getText().strip(),
                                     hint.getText().strip(),
                                     color.getText().strip() ) ;
            String wrong = check( tag, axis, offered ) ;
            if( wrong == null ) return new Choice( Fate.ACCEPTED, tag, null ) ;
            JOptionPane.showMessageDialog( parent, wrong, "Gap proposed",
                                           JOptionPane.WARNING_MESSAGE ) ;
        }
    }

    /** What is wrong with {@code tag}; null if nothing. */
    private static String check( NewTag tag, Axis axis, OfferedSet offered ) {

        String t = tag.tagText() ;
        if( !TAG_TEXT.matcher( t ).matches() ) {
            return "The tag text must be lower case letters, digits, dots " +
                   "and dashes" ;
        }
        if( tag.axis() == null || tag.axis() == Axis.MATH ) {
            return "The tag text must start with s. or c.: a setup or " +
                   "concept tag of the topic" ;
        }
        if( axis != null && tag.axis() != axis ) {
            return "The model proposed a " + axis.getWord() + " tag; this " +
                   "is a " + tag.axis().getWord() + " tag" ;
        }
        if( offered.contains( t ) ) return t + " is already offered" ;
        if( segments( tag.fullName() ) != segments( t ) ) {
            return "The full name must have as many dot-separated parts as " +
                   "the tag text (" + segments( t ) + ")" ;
        }
        if( tag.description().isEmpty() || tag.hint().isEmpty() ) {
            return "The description and the hint can't be blank" ;
        }
        if( !COLOR.matcher( tag.color() ).matches() ) {
            return "The colour must be #RRGGBB" ;
        }
        return null ;
    }

    private static int segments( String s ) {
        return s.split( "\\.", -1 ).length ;
    }

    /** {@code s.connector.x} as {@code setup.connector.x}, as a start. */
    private static String fullName( String tag ) {
        Axis a = Axis.of( tag ) ;
        return a == null ? tag
                         : a.getWord() + "." + tag.substring( a.getPrefix()
                                                               .length() ) ;
    }

    private static int field( JPanel p, int row, String label,
                              Component field ) {

        GridBagConstraints c = new GridBagConstraints() ;
        c.gridx  = 0 ;
        c.gridy  = row ;
        c.insets = new Insets( 2, 4, 2, 4 ) ;
        c.anchor = GridBagConstraints.NORTHWEST ;
        p.add( new JLabel( label ), c ) ;

        c.gridx   = 1 ;
        c.fill    = GridBagConstraints.HORIZONTAL ;
        c.weightx = 1 ;
        p.add( field, c ) ;
        return row + 1 ;
    }
}
