package com.sandy.sconsole.qimgclassifier.ui;

import com.sandy.sconsole.qimgclassifier.model.QTask;
import com.sandy.sconsole.qimgclassifier.workspace.TranscriptStore;

import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Log window docked to the right of the main window (modelled on
 * QImgExtractor's question sync log panel): shows every saved AI attempt of
 * one question, oldest first, scrolled to the latest. Each attempt shows the
 * response, then the request; the prompt itself is hidden unless "Show
 * prompt" is ticked. Files are read off the EDT; a newer {@link #show}
 * supersedes a slow load.
 */
@Slf4j
@SuppressWarnings( "serial" )
final class TranscriptPanel extends JPanel {

    private static final String PROMPT =
            "Click a question's Result cell to see its AI transcripts." ;

    private final TranscriptStore store ;
    private final JLabel          title   = new JLabel( PROMPT ) ;
    private final JTextArea       text    = new JTextArea() ;
    private final JButton         openDir = new JButton( "Open folder" ) ;
    private final JCheckBox       prompts = new JCheckBox( "Show prompt" ) ;

    /** The question shown; null when the panel is empty. */
    private QTask task ;

    /** What was last read for {@link #task}; null until loaded. */
    private Transcript shown ;

    /**
     * Monotonic token so a slow load for an old question cannot overwrite a
     * newer one.
     */
    private long loadToken ;

    TranscriptPanel( TranscriptStore store ) {

        super( new BorderLayout() ) ;
        this.store = store ;

        text.setEditable( false ) ;
        text.setLineWrap( true ) ;
        text.setWrapStyleWord( true ) ;
        text.setFont( new Font( Font.MONOSPACED, Font.PLAIN, 12 ) ) ;

        JButton clear = new JButton( "Clear" ) ;
        clear.setToolTipText( "Clear the panel (the files are kept)" ) ;
        clear.addActionListener( e -> clear() ) ;
        openDir.setToolTipText( "Open the question's transcript folder" ) ;
        openDir.addActionListener( e -> openFolder() ) ;
        openDir.setEnabled( false ) ;
        prompts.setToolTipText( "Include the prompt sent with each request" ) ;
        prompts.addActionListener( e -> redisplay() ) ;

        JPanel buttons = new JPanel( new FlowLayout( FlowLayout.LEFT ) ) ;
        buttons.add( openDir ) ;
        buttons.add( clear ) ;
        buttons.add( prompts ) ;

        JPanel top = new JPanel( new BorderLayout() ) ;
        title.setBorder( BorderFactory.createEmptyBorder( 0, 6, 0, 0 ) ) ;
        top.add( buttons, BorderLayout.WEST ) ;
        top.add( title, BorderLayout.CENTER ) ;

        add( top, BorderLayout.NORTH ) ;
        add( new JScrollPane( text ), BorderLayout.CENTER ) ;
        setPreferredSize( new Dimension( 700, 200 ) ) ;
    }

    /** Show the transcripts of {@code task}. Call on the EDT. */
    void show( QTask task ) {

        this.task  = task ;
        this.shown = null ;

        openDir.setEnabled( true ) ;
        title.setText( task + " - loading…" ) ;
        load( task ) ;
    }

    /**
     * Reload if {@code task} is the question on show (a new attempt was saved).
     */
    void refreshIf( QTask task ) {
        if( task == this.task ) load( task ) ;
    }

    private void clear() {

        task  = null ;
        shown = null ;

        loadToken++ ;
        openDir.setEnabled( false ) ;
        title.setText( PROMPT ) ;
        text.setText( "" ) ;
    }

    /** Read and join the question's files off the EDT, then show them. */
    private void load( QTask forTask ) {

        long token = ++loadToken ;
        new SwingWorker<Transcript,Void>() {
            @Override protected Transcript doInBackground() throws Exception {
                return read( forTask ) ;
            }

            @Override protected void done() {

                if( token != loadToken ) return ;   // superseded
                try {
                    display( forTask, get() ) ;
                }
                catch( Exception e ) {
                    log.error( "Could not read transcripts of {}", forTask, e ) ;
                    title.setText( forTask + " - could not read transcripts" ) ;
                    text.setText( String.valueOf( e.getMessage() ) ) ;
                }
            }
        }.execute() ;
    }

    /** One attempt's request and response file; either may be missing. */
    private record Attempt( String stem, String request, String response ) {}

    /** A question's attempts, oldest first. */
    private record Transcript( List<Attempt> attempts ) {}

    private Transcript read( QTask forTask ) throws Exception {

        Map<String,String[]> byStem = new LinkedHashMap<>() ;
        for( File f : store.files( forTask ) ) {
            String  name = f.getName() ;
            boolean req  = name.endsWith( TranscriptStore.REQUEST ) ;
            String  stem = name.substring( 0, name.length() - ( req
                                    ? TranscriptStore.REQUEST.length()
                                    : TranscriptStore.RESPONSE.length() ) ) ;
            String  body = Files.readString( f.toPath(), StandardCharsets.UTF_8 ) ;

            byStem.computeIfAbsent( stem, k -> new String[2] )[req ? 0 : 1] = body ;
        }

        List<Attempt> attempts = new ArrayList<>() ;
        byStem.forEach( ( stem, pair ) ->
                attempts.add( new Attempt( stem, pair[0], pair[1] ) ) ) ;
        return new Transcript( attempts ) ;
    }

    private void display( QTask forTask, Transcript t ) {

        shown = t ;
        if( t.attempts().isEmpty() ) {
            title.setText( forTask + " - no transcripts yet" ) ;
            text.setText( "Nothing saved yet in " + store.dirFor( forTask ) ) ;
            return ;
        }
        int n = t.attempts().size() ;
        title.setText( forTask + " - " + n + ( n == 1 ? " attempt" : " attempts" ) ) ;
        redisplay() ;
    }

    /**
     * Render {@link #shown} with or without prompts, the start of the latest
     * attempt at the top of the view.
     */
    private void redisplay() {

        if( shown == null || shown.attempts().isEmpty() ) return ;

        StringBuilder sb          = new StringBuilder() ;
        int           latestStart = 0 ;

        for( Attempt a : shown.attempts() ) {
            latestStart = sb.length() ;
            sb.append( "===== " ).append( a.stem() ).append( " =====\n" ) ;
            sb.append( "----- Response -----\n" )
              .append( a.response() != null ? a.response() : "(not saved)\n" ) ;
            sb.append( "\n----- Request -----\n" )
              .append( a.request() != null ? request( a.request() ) : "(not saved)\n" )
              .append( '\n' ) ;
        }
        text.setText( sb.toString() ) ;
        text.setCaretPosition( text.getDocument().getLength() ) ;
        text.setCaretPosition( latestStart ) ;
    }

    /** The request file, cut to its header unless prompts are shown. */
    private String request( String file ) {

        int rule = file.indexOf( TranscriptStore.RULE ) ;
        if( prompts.isSelected() || rule < 0 ) return file ;
        return file.substring( 0, rule ) + "(prompt hidden - tick Show prompt)\n" ;
    }

    private void openFolder() {

        if( task == null ) return ;
        File dir = store.dirFor( task ) ;
        try {
            if( !dir.isDirectory() ) {
                JOptionPane.showMessageDialog( this, "No transcripts saved yet:\n" + dir,
                        "Transcripts", JOptionPane.INFORMATION_MESSAGE ) ;
                return ;
            }
            Desktop.getDesktop().open( dir ) ;
        }
        catch( Exception e ) {
            log.error( "Could not open {}", dir, e ) ;
            JOptionPane.showMessageDialog( this, "Could not open " + dir + ":\n" +
                    e.getMessage(), "Transcripts", JOptionPane.ERROR_MESSAGE ) ;
        }
    }
}
