package com.sandy.sconsole.qimgtools.apps.nxrclassifier.util;

import com.sandy.sconsole.ai.config.AIConfig;
import com.sandy.sconsole.qimgtools.core.util.AIConfigs;
import com.sandy.sconsole.qimgtools.core.util.ConfigProperties;

import lombok.Getter;

import java.io.File;
import java.time.Duration;

import static com.sandy.sconsole.qimgtools.apps.nxrclassifier.server.NXRServerClient.MAX_BATCH;

/**
 * Settings of the NXR classifier, read from the
 * {@code /apps/nxrclassifier/nxrclassifier.properties} classpath resource
 * (see that file for what each key means). A JVM system property of the
 * same name overrides the file (see {@link ConfigProperties}). The AI models
 * asked about the questions the classifier leaves undecided come from the
 * JSON app config {@link #AI_CONFIG} and its parent.
 * <p>
 * Every key must be present; a missing or malformed value fails
 * {@link #load()} with an {@link IllegalStateException} naming the key.
 */
@Getter
public final class NXRConfig {

    /** Classpath location of the configuration file. */
    public static final String RESOURCE =
            "/apps/nxrclassifier/nxrclassifier.properties" ;

    /** Location of the app's AI config, unless {@code -Dai.app.config}. */
    /** The most questions classified at once. */
    public static final int MAX_CLASSIFY_THREADS = 16 ;

    /** Location of the app's AI config, unless {@code -Dai.app.config}. */
    public static final String AI_CONFIG =
            "classpath:/apps/nxrclassifier/nxrclassifier.ai.json" ;

    /** Base URL of the SConsole server, e.g. {@code http://localhost:8080}. */
    private final String   serverUrl ;
    private final Duration serverConnectTimeout ;
    private final Duration serverRequestTimeout ;

    /** Root folder searched (recursively) for the source folders. */
    private final File qBankDir ;

    /** Root of the app's files. */
    private final File workspaceDir ;

    /** Every label saved, appended as JSON lines. */
    private final File auditFile ;

    /** Questions fetched per Load, to start with (1-100). */
    private final int batchSize ;

    /** The classifier's trained model (JSON). */
    private final File modelFile ;

    /** The tesseract executable the classifier runs for OCR. */
    private final File tesseractBin ;

    /** Questions classified at once (1-{@value #MAX_CLASSIFY_THREADS}). */
    private final int classifyThreads ;

    /** Request times of the current UTC day, behind the per-day limits. */
    private final File requestHistoryFile ;

    /** One row per AI attempt with its token counts. */
    private final File usageLogFile ;

    /**
     * The models asked about undecided questions, from {@link #AI_CONFIG}
     * or the file {@code -Dai.app.config} names.
     */
    private final AIConfig ai ;

    private NXRConfig( ConfigProperties props ) {

        serverUrl            = props.str( "server.url" ) ;
        serverConnectTimeout = props.seconds( "server.connect.timeout.seconds" ) ;
        serverRequestTimeout = props.seconds( "server.request.timeout.seconds" ) ;

        qBankDir     = props.path( "question.bank.dir" ) ;
        workspaceDir = props.workspaceDir( "workspace.dir" ) ;
        auditFile    = props.workspacePath( "nxr.audit.file" ) ;
        batchSize    = props.wholeNumber( "nxr.batch.size", 1, MAX_BATCH ) ;
        modelFile    = props.path( "nxr.model.file" ) ;
        tesseractBin = props.path( "nxr.tesseract.bin" ) ;

        classifyThreads = props.wholeNumber( "nxr.classify.threads", 1,
                                             MAX_CLASSIFY_THREADS ) ;

        requestHistoryFile = props.workspacePath( "request.history.file" ) ;
        usageLogFile       = props.workspacePath( "usage.log.file" ) ;

        ai = AIConfigs.load( AI_CONFIG ) ;
    }

    /**
     * Read the settings from {@link #RESOURCE} and system properties.
     *
     * @throws IllegalStateException if the resource is missing or unreadable,
     *         a key is missing or malformed, or the AI config is invalid
     */
    public static NXRConfig load() {
        return new NXRConfig( ConfigProperties.load( RESOURCE ) ) ;
    }
}
