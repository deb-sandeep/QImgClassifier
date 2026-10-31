package com.sandy.sconsole.qimgtools.apps.classifier.ui;

import com.sandy.sconsole.qimgtools.apps.classifier.workspace.ProcessedListRepair;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Frame;
import java.awt.GridLayout;

/**
 * Modal, non-closable progress view for a processed-list repair run: a
 * progress bar over the sources plus running tallies. The owner disposes it
 * when the run ends.
 */
@SuppressWarnings( "serial" )
final class RepairProgressDialog extends JDialog {

    private final JProgressBar bar       = new JProgressBar() ;
    private final JLabel       current =
            new JLabel( "Starting..." ) ;
    private final JLabel       done      = new JLabel( "-" ) ;
    private final JLabel       remaining = new JLabel( "-" ) ;
    private final JLabel       questions = new JLabel( "-" ) ;
    private final JLabel       added     = new JLabel( "-" ) ;
    private final JLabel       removed   = new JLabel( "-" ) ;
    private final JLabel       notFound  = new JLabel( "-" ) ;

    RepairProgressDialog( Frame owner ) {

        super( owner, "Repairing processed list", true ) ;
        setDefaultCloseOperation( DO_NOTHING_ON_CLOSE ) ;
        bar.setIndeterminate( true ) ;
        bar.setStringPainted( true ) ;
        bar.setString( "" ) ;

        JPanel grid = new JPanel( new GridLayout( 0, 2, 12, 4 ) ) ;
        addRow( grid, "Sources done",      done ) ;
        addRow( grid, "Sources remaining", remaining ) ;
        addRow( grid, "Questions checked", questions ) ;
        addRow( grid, "Added to list",     added ) ;
        addRow( grid, "Removed from list", removed ) ;
        addRow( grid, "Folder not found",  notFound ) ;

        JPanel top = new JPanel( new BorderLayout( 0, 6 ) ) ;
        top.add( current, BorderLayout.NORTH ) ;
        top.add( bar, BorderLayout.CENTER ) ;

        JPanel content = new JPanel( new BorderLayout( 0, 10 ) ) ;
        content.setBorder( BorderFactory.createEmptyBorder( 14, 16, 14, 16 ) ) ;
        content.add( top, BorderLayout.NORTH ) ;
        content.add( grid, BorderLayout.CENTER ) ;
        setContentPane( content ) ;

        pack() ;
        setSize( Math.max( getWidth(), 460 ), getHeight() ) ;
        setLocationRelativeTo( owner ) ;
    }

    /** Show the latest progress snapshot. Call on the EDT. */
    void update( ProcessedListRepair.Progress p ) {

        bar.setIndeterminate( false ) ;
        bar.setMaximum( Math.max( p.getTotal(), 1 ) ) ;
        bar.setValue( p.getDone() ) ;
        bar.setString( p.getDone() + " / " + p.getTotal() ) ;
        current.setText( p.getCurrent() == null ? "Starting..."
                       : p.remaining() == 0     ? "Writing processed list..."
                       : "Checked: " + p.getCurrent() ) ;
        done.setText( String.valueOf( p.getDone() ) ) ;
        remaining.setText( String.valueOf( p.remaining() ) ) ;
        questions.setText( p.getQuestionsChecked() + "  (" +
                           p.getQuestionsUnassociated() + " not associated)" ) ;
        added.setText( String.valueOf( p.getAdded() ) ) ;
        removed.setText( String.valueOf( p.getRemoved() ) ) ;
        notFound.setText( String.valueOf( p.getNotFound() ) ) ;
    }

    private static void addRow( JPanel grid, String label, JLabel value ) {
        grid.add( new JLabel( label + ":" ) ) ;
        grid.add( value ) ;
    }
}
