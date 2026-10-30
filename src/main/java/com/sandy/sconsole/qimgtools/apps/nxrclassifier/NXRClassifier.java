package com.sandy.sconsole.qimgtools.apps.nxrclassifier;

import com.sandy.sconsole.qimgtools.apps.nxrclassifier.model.NXRItem;
import com.sandy.sconsole.qimgtools.apps.nxrclassifier.model.NXRSaveResult;
import com.sandy.sconsole.qimgtools.apps.nxrclassifier.model.NXRUpdate;
import com.sandy.sconsole.qimgtools.apps.nxrclassifier.server.NXRServerClient;
import com.sandy.sconsole.qimgtools.apps.nxrclassifier.ui.NXRFrame;
import com.sandy.sconsole.qimgtools.apps.nxrclassifier.util.NXRConfig;
import com.sandy.sconsole.qimgtools.apps.nxrclassifier.workspace.NXRAuditLog;
import com.sandy.sconsole.qimgtools.core.review.QuestionLocator;
import com.sandy.sconsole.qimgtools.core.server.PendingResult;
import com.sandy.sconsole.qimgtools.core.server.SConsoleServer;
import com.sandy.sconsole.qimgtools.core.server.SConsoleServer.ServerException;
import com.sandy.sconsole.qimgtools.core.util.FileHelper;
import com.sandy.sconsole.qimgtools.core.util.ImageCollator;
import com.sandy.sconsole.nxr.NXRException;
import com.sandy.sconsole.nxr.NXRResult;

import lombok.extern.slf4j.Slf4j;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Entry point and controller of the NXR classifier: labels the questions on
 * the SConsole server N (numeric), X (reasoning) or R (recall), and saves
 * the labels on the server. No AI model is used.
 * <p>
 * Load fetches the first questions with no label
 * ({@code GET /Master/Question/NXR/Pending}); each question's images are
 * found in the local question bank ({@link QuestionLocator}), stacked into
 * one ({@link ImageCollator}) and given, with the question's subject,
 * type and topic, to the local {@link com.sandy.sconsole.nxr.NXRClassifier}
 * (which shares this class's name). Its label is taken as it is; the user
 * labels the questions it leaves, or overrides it, in the {@link NXRFrame}. Save sends the labels in batches
 * ({@code POST /Master/Question/NXR}) and notes each one the server took in
 * the {@link NXRAuditLog}. Settings come from
 * {@code nxrclassifier.properties} ({@link NXRConfig}).
 */
@Slf4j
public class NXRClassifier implements NXRFrame.Controller {

    /** True for an LCT group's context image, left out of what is sent. */
    private static final Predicate<File> CONTEXT = f -> FileHelper
            .baseQuestionId( FileHelper.extractQuestionId( f ) )
            .endsWith( "_Ctx" ) ;

    private final NXRServerClient server ;
    private final QuestionLocator locator ;
    private final NXRAuditLog     audit ;

    /** The local classifier; thread-safe. */
    private final com.sandy.sconsole.nxr.NXRClassifier classifier ;

    public NXRClassifier( NXRConfig config, NXRServerClient server,
                          com.sandy.sconsole.nxr.NXRClassifier classifier ) {
        this.server     = server ;
        this.classifier = classifier ;
        this.locator    = new QuestionLocator( config.getQBankDir() ) ;
        this.audit      = new NXRAuditLog( config.getAuditFile() ) ;
    }

    public static void main( String[] args ) {

        NXRConfig config ;
        try {
            config = NXRConfig.load() ;
        }
        catch( IllegalStateException e ) {
            fail( "Invalid configuration", e.getMessage(), e ) ;
            return ;
        }

        File workspaceDir = config.getWorkspaceDir() ;
        if( !workspaceDir.isDirectory() && !workspaceDir.mkdirs() ) {
            log.error( "Could not create the workspace folder {}", workspaceDir ) ;
        }

        com.sandy.sconsole.nxr.NXRClassifier classifier ;
        try {
            classifier = com.sandy.sconsole.nxr.NXRClassifier.load(
                    config.getModelFile().toPath(),
                    config.getTesseractBin().toPath() ) ;
        }
        catch( NXRException e ) {
            fail( "NXR classifier", "Could not load the classifier:\n" +
                  e.getMessage(), e ) ;
            return ;
        }

        NXRServerClient server = new NXRServerClient(
                new SConsoleServer( config.getServerUrl(),
                                    config.getServerConnectTimeout(),
                                    config.getServerRequestTimeout() ) ) ;
        try {
            SwingUtilities.invokeAndWait( () -> {
                NXRFrame      frame = new NXRFrame( config.getBatchSize() ) ;
                NXRClassifier app   = new NXRClassifier( config, server,
                                                         classifier ) ;

                frame.setController( app ) ;
                frame.setVisible( true ) ;
            } ) ;
        }
        catch( Exception e ) {
            log.error( "Could not start UI", e ) ;
        }
    }

    /** Report a startup failure and exit. */
    private static void fail( String title, String message, Exception e ) {
        log.error( "{}: {}", title, message, e ) ;
        JOptionPane.showMessageDialog( null, message, title,
                                       JOptionPane.ERROR_MESSAGE ) ;
        System.exit( 1 ) ;
    }

    // ---- Controller ----------------------------------------------------

    @Override
    public NXRFrame.Loaded load( int batchSize ) throws ServerException {

        PendingResult r = server.pending( Math.max( 1, Math.min(
                batchSize, NXRServerClient.MAX_BATCH ) ) ) ;
        List<NXRItem> items = r.getQuestions().stream()
                               .map( locator::locate )
                               .map( NXRItem::of )
                               .toList() ;

        log.info( "Loaded {} questions, {} await a label", items.size(),
                  r.getTotalResults() ) ;
        return new NXRFrame.Loaded( items, r.getTotalResults() ) ;
    }

    /**
     * The classifier's result for {@code item}: its own images stacked into
     * one (an LCT group's shared context left out, as the classifier asks),
     * with its subject, type and server topic.
     */
    @Override
    public NXRResult classify( NXRItem item ) throws IOException {

        List<File> own = item.task().getImgFiles().stream()
                             .filter( CONTEXT.negate() )
                             .toList() ;
        NXRResult  r   = classifier.classify(
                ImageCollator.collate( own ),
                item.task().getSubject().getCode(),
                item.question().getProblemType(),
                item.question().getTopicId() ) ;

        log.debug( "{}: {} {} {} - {}", item, r.value(), r.confidence(),
                   r.method(), r.reason() ) ;
        return r ;
    }

    /**
     * Send {@code updates} to the server, at most
     * {@link NXRServerClient#MAX_UPDATES} per request, and note each one it
     * took in the audit log. A request that fails refuses all of its
     * updates, with the reason.
     */
    @Override
    public List<NXRSaveResult> save( List<NXRUpdate> updates ) {

        List<NXRSaveResult> results = new ArrayList<>() ;
        int             size    = NXRServerClient.MAX_UPDATES ;

        for( int i = 0 ; i < updates.size() ; i += size ) {
            results.addAll( saveBatch( updates.subList(
                    i, Math.min( updates.size(), i + size ) ) ) ) ;
        }
        return results ;
    }

    private List<NXRSaveResult> saveBatch( List<NXRUpdate> batch ) {

        List<NXRSaveResult> results ;
        try {
            results = server.update( batch ) ;
        }
        catch( ServerException e ) {
            log.error( "Could not save {} labels: {}", batch.size(),
                       e.getMessage() ) ;
            return batch.stream()
                        .map( u -> NXRSaveResult.failed( u.item().id(),
                                                     e.getMessage() ) )
                        .toList() ;
        }

        for( int i = 0 ; i < batch.size() ; i++ ) {
            NXRUpdate u = batch.get( i ) ;
            NXRSaveResult r = results.get( i ) ;

            log.info( "{}: {} - {}{}", u.item(), u.describe(), r.status(),
                      r.accepted() ? "" : ": " + r.message() ) ;
            if( r.accepted() ) record( u, r ) ;
        }
        return results ;
    }

    /** Note the label in the audit log; a failure is only logged. */
    private void record( NXRUpdate u, NXRSaveResult r ) {
        try {
            audit.saved( u, r ) ;
        }
        catch( Exception e ) {
            log.error( "{}: could not record the label in the audit log",
                       u.item(), e ) ;
        }
    }
}
