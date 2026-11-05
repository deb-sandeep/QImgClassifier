package com.sandy.sconsole.qimgtools.apps.tagger.ui;

import com.sandy.sconsole.qimgtools.apps.tagger.model.TaggerSettings;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.SpinnerNumberModel;
import java.awt.Component;
import java.awt.FlowLayout;
import java.text.ParseException;

/**
 * Asks for the tagger's settings, a tab for each kind: <i>Loading</i> and
 * <i>LLM</i>. Use on the EDT.
 */
final class SettingsDialog {

    private static final int BATCH_STEP = 10 ;
    private static final int PAD        = 12 ;

    private SettingsDialog() {}

    /**
     * The settings chosen, starting from {@code now}; null if cancelled.
     *
     * @param maxBatch the largest batch size allowed
     */
    static TaggerSettings ask( Component parent, TaggerSettings now,
                               int maxBatch ) {

        JSpinner  batch = new JSpinner( new SpinnerNumberModel(
                now.batchSize(), 1, maxBatch, BATCH_STEP ) ) ;
        JCheckBox auto  = new JCheckBox(
                "Send questions to the LLM automatically",
                now.autoSendToLlm() ) ;

        JPanel row = new JPanel( new FlowLayout( FlowLayout.LEFT, 0, 0 ) ) ;
        row.add( new JLabel( "Questions per load:  " ) ) ;
        row.add( batch ) ;

        JTabbedPane tabs = new JTabbedPane() ;
        tabs.addTab( "Loading", tab(
                row,
                new JLabel( "Applies from the next Load." ) ) ) ;
        tabs.addTab( "LLM", tab(
                auto,
                new JLabel( "<html>Once a Load has read the questions, those " +
                            "awaiting the LLM go to it.<br>" +
                            "Off, they wait for Start.</html>" ) ) ) ;

        int answer = JOptionPane.showConfirmDialog(
                parent, tabs, "Settings", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE ) ;
        if( answer != JOptionPane.OK_OPTION ) return null ;

        try {
            // A number typed but not yet entered counts.
            batch.commitEdit() ;
        }
        catch( ParseException e ) {
            // Not a number: the spinner keeps its last good value.
        }
        return new TaggerSettings( auto.isSelected(),
                                   (Integer) batch.getValue() ) ;
    }

    /** One tab: {@code parts} one below the other, flush left. */
    private static JPanel tab( JComponent... parts ) {

        JPanel p = new JPanel() ;

        p.setLayout( new BoxLayout( p, BoxLayout.Y_AXIS ) ) ;
        p.setBorder( BorderFactory.createEmptyBorder( PAD, PAD, PAD, PAD ) ) ;
        for( JComponent c : parts ) {
            c.setAlignmentX( Component.LEFT_ALIGNMENT ) ;
            p.add( c ) ;
        }
        return p ;
    }
}
