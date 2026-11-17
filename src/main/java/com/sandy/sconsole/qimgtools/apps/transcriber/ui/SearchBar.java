package com.sandy.sconsole.qimgtools.apps.transcriber.ui;

import com.sandy.sconsole.qimgtools.apps.transcriber.server.TranscriptionSearch;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The toolbar's search over the stored transcriptions (the question
 * search's {@code text}, a Lucene query; the server's
 * {@code doc/feature-enhancements/2026-11-17/transcription-search/}): a
 * query field, a question type, and whether to keep to the criteria's
 * topics. Enter asks the frame to load the first page of what the query
 * finds straight into the table; Next its following page. Use on the EDT.
 */
@SuppressWarnings( "serial" )
final class SearchBar extends JPanel {

    /**
     * A search to run.
     *
     * @param text       the Lucene query
     * @param inCriteria true to search the criteria's topics only
     * @param types      the question types to search; empty: any
     * @param page       the page to load, from 0
     */
    record Request( String text, boolean inCriteria, List<String> types,
                    int page ) {

        Request {
            types = List.copyOf( types ) ;
        }

        Request next() {
            return new Request( text, inCriteria, types, page + 1 ) ;
        }

        /** E.g. {@code "wedge" (SCA, criteria's topics)}. */
        String describe() {

            List<String> within = new ArrayList<>( types ) ;
            if( inCriteria ) within.add( "criteria's topics" ) ;
            return "\"" + text + "\"" +
                   ( within.isEmpty() ? ""
                                      : " (" + String.join( ", ", within ) +
                                        ")" ) ;
        }

        /**
         * What {@code found} brought, for the status bar: e.g.
         * {@code search "wedge": 120 found, page 1 of 2}.
         */
        String summary( TranscriptionSearch found ) {

            String s = "search " + describe() + ": " +
                       found.totalResults() + " found" ;
            return found.totalPages() > 1
                   ? s + ", page " + ( found.pageNumber() + 1 ) + " of " +
                     found.totalPages()
                   : s ;
        }
    }

    private static final String ANY_TYPE = "Any type" ;

    private static final String HINT =
            "<html>Search the stored transcriptions; Enter loads what it " +
            "finds into the table.<br>electric field: both words &nbsp; " +
            "\"electric field\": the phrase &nbsp; a OR b &nbsp; " +
            "a -b: a without b<br>magnet*: words starting so &nbsp; " +
            "\\frac or frac: LaTeX commands</html>" ;

    private final JTextField        query  = new JTextField( 18 ) ;
    private final JComboBox<String> type   = new JComboBox<>( new String[] {
            ANY_TYPE, "SCA", "MCA", "NVT", "IVT", "LCT", "ART" } ) ;
    private final JCheckBox         inCrit = new JCheckBox( "In criteria" ) ;
    private final JButton           next   = new JButton( "Next" ) ;

    private Request             last ;
    private TranscriptionSearch lastFound ;

    /** @param onSearch runs a search; the frame decides whether it may */
    SearchBar( Consumer<Request> onSearch ) {

        super( new FlowLayout( FlowLayout.LEFT, 4, 0 ) ) ;
        setOpaque( false ) ;
        setBorder( BorderFactory.createEmptyBorder() ) ;

        query.setToolTipText( HINT ) ;
        type.setToolTipText( "Only questions of this type" ) ;
        type.setMaximumSize( type.getPreferredSize() ) ;
        type.setFocusable( false ) ;
        inCrit.setToolTipText( "Only the topics of the criteria in effect" ) ;
        inCrit.setFocusable( false ) ;
        next.setToolTipText( "Load the next page of the last search" ) ;
        next.setFocusable( false ) ;
        next.setEnabled( false ) ;

        query.addActionListener( e -> {
            String text = query.getText().trim() ;
            if( text.isEmpty() ) return ;
            String t = (String) type.getSelectedItem() ;
            onSearch.accept( new Request(
                    text, inCrit.isSelected(),
                    ANY_TYPE.equals( t ) ? List.of() : List.of( t ), 0 ) ) ;
        } ) ;
        next.addActionListener( e -> {
            if( last != null ) onSearch.accept( last.next() ) ;
        } ) ;

        add( new JLabel( "Search:" ) ) ;
        add( query ) ;
        add( type ) ;
        add( inCrit ) ;
        add( next ) ;
        setMaximumSize( getPreferredSize() ) ;
    }

    /** Put the caret in the query, its text selected (File > Search). */
    void focusQuery() {
        query.requestFocusInWindow() ;
        query.selectAll() ;
    }

    /** Note what {@code request} found, for Next. */
    void searched( Request request, TranscriptionSearch found ) {
        last      = request ;
        lastFound = found ;
        refresh() ;
    }

    @Override
    public void setEnabled( boolean enabled ) {

        super.setEnabled( enabled ) ;
        query.setEnabled( enabled ) ;
        type.setEnabled( enabled ) ;
        inCrit.setEnabled( enabled ) ;
        refresh() ;
    }

    private void refresh() {
        next.setEnabled( isEnabled() && lastFound != null
                         && lastFound.hasMore() ) ;
    }
}
