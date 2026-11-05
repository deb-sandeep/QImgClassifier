package com.sandy.sconsole.qimgtools.apps.tagger.ui;

import com.sandy.sconsole.qimgtools.apps.tagger.model.TaggerSettings;

import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import java.awt.Component;

/** Asks for the tagger's settings. Use on the EDT. */
final class SettingsDialog {

    private SettingsDialog() {}

    /** The settings chosen, starting from {@code now}; null if cancelled. */
    static TaggerSettings ask( Component parent, TaggerSettings now ) {

        JCheckBox auto = new JCheckBox(
                "Send questions to the LLM automatically",
                now.autoSendToLlm() ) ;
        JPanel    p    = new JPanel() ;

        p.setLayout( new BoxLayout( p, BoxLayout.Y_AXIS ) ) ;
        p.add( auto ) ;
        p.add( new JLabel( "<html>Once a Load has read the questions, those " +
                           "awaiting the LLM go to it.<br>" +
                           "Off, they wait for Start.</html>" ) ) ;

        int answer = JOptionPane.showConfirmDialog(
                parent, p, "Settings", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE ) ;

        return answer == JOptionPane.OK_OPTION
               ? new TaggerSettings( auto.isSelected() ) : null ;
    }
}
