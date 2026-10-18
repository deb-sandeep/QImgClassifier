package com.sandy.sconsole.qimgtools.core.ui;

import com.sandy.sconsole.qimgtools.core.sequence.TopicSequenceGenerator.Summary;
import com.sandy.sconsole.topicresolver.TopicResolver;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.swing.JOptionPane;
import javax.swing.SwingWorker;
import java.awt.Component;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * The "Generate topic sequence" command both apps offer: regenerates
 * {@link TopicResolver#SEQUENCE_FILE} off the EDT and reports the outcome in
 * a dialog.
 */
@Slf4j
@NoArgsConstructor( access = AccessLevel.PRIVATE )
public final class TopicSequenceDialog {

    /** The command's name, for its menu item and dialogs. */
    public static final String TITLE = "Generate topic sequence" ;

    /**
     * Run {@code generate} off the EDT, then show what it wrote, or why it
     * failed, over {@code parent}. Call on the EDT.
     *
     * @param enable called with false while it runs and true after, to
     *        disable the command meanwhile
     */
    public static void run( Component parent, Callable<Summary> generate,
                            Consumer<Boolean> enable ) {

        enable.accept( false ) ;
        new SwingWorker<Summary,Void>() {
            @Override protected Summary doInBackground() throws Exception {
                return generate.call() ;
            }

            @Override protected void done() {

                enable.accept( true ) ;
                try {
                    Summary s = get() ;
                    JOptionPane.showMessageDialog( parent,
                            "Wrote " + TopicResolver.SEQUENCE_FILE + "\n\n" +
                            s.describe() + "\n\nTopics are chosen with it " +
                            "from now on.", TITLE,
                            JOptionPane.INFORMATION_MESSAGE ) ;
                }
                catch( Exception e ) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e ;
                    log.error( "Could not generate the topic sequence", cause ) ;
                    JOptionPane.showMessageDialog( parent,
                            "Could not generate the topic sequence:\n" +
                            cause.getMessage(), TITLE,
                            JOptionPane.ERROR_MESSAGE ) ;
                }
            }
        }.execute() ;
    }
}
