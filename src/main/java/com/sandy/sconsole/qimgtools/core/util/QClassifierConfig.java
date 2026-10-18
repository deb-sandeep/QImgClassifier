package com.sandy.sconsole.qimgtools.core.util;

import com.sandy.sconsole.ai.config.AIConfig;

import lombok.Getter;

import java.io.File;
import java.time.Duration;

/**
 * Application settings, read from the
 * {@code /apps/classifier/qimgclassifier.properties} classpath resource (see
 * that file for what each key means). A JVM system property of the same
 * name, e.g. {@code -Dquestion.filter=C}, overrides the file (see
 * {@link ConfigProperties}).
 * <p>
 * Every key must be present ({@code question.filter} may be blank); a missing
 * or malformed value fails {@link #load()} with an
 * {@link IllegalStateException} naming the key. The AI models come from the
 * JSON app config {@link #AI_CONFIG} and its parent (see
 * {@code AIConfigLoader}), which fail the same way. Paths may start with
 * {@code ~/} for the user's home directory. The files the application keeps
 * (processed list, request history, usage log) may be given relative to
 * {@code workspace.dir}.
 */
@Getter
public final class QClassifierConfig {

    /** Classpath location of the configuration file. */
    public static final String RESOURCE =
            "/apps/classifier/qimgclassifier.properties" ;

    /** Location of the classifier's AI config (models, roots, fallbacks). */
    public static final String AI_CONFIG =
            "classpath:/apps/classifier/qimgclassifier.ai.json" ;

    /** Root folder searched (recursively) for the source folders. */
    private final File qBankDir ;

    /**
     * Debug filter on question ids: blank for all questions, a single letter
     * for one subject (e.g. "C"), otherwise an exact base or part id.
     */
    private final String questionFilter ;

    /** Names of the sources whose every question has been classified. */
    private final File processedListFile ;

    /**
     * Start times of the day's requests per gateway and model with a daily
     * limit, so the limits survive a restart.
     */
    private final File requestHistoryFile ;

    /** One CSV row per AI attempt with the tokens it used. */
    private final File usageLogFile ;

    /**
     * Root of the per-source, per-question AI request / response transcripts.
     */
    private final File workspaceDir ;

    /**
     * The models, gateways and roots the classifier uses, from
     * {@link #AI_CONFIG} or the file {@code -Dai.app.config} names.
     */
    private final AIConfig ai ;

    /** Base URL of QImgExtractor's remote command API. */
    private final String qImgExtractorApiUrl ;

    /** Base URL of the SConsole server, for the topic sequence. */
    private final String   serverUrl ;
    private final Duration serverConnectTimeout ;
    private final Duration serverRequestTimeout ;

    private QClassifierConfig( ConfigProperties props ) {

        qBankDir           = props.path( "question.bank.dir" ) ;
        questionFilter     = props.value( "question.filter", true ) ;
        workspaceDir       = props.workspaceDir( "workspace.dir" ) ;
        processedListFile  = props.workspacePath( "processed.list.file" ) ;
        requestHistoryFile = props.workspacePath( "request.history.file" ) ;
        usageLogFile       = props.workspacePath( "usage.log.file" ) ;

        serverUrl            = props.str( "server.url" ) ;
        serverConnectTimeout = props.seconds( "server.connect.timeout.seconds" ) ;
        serverRequestTimeout = props.seconds( "server.request.timeout.seconds" ) ;

        ai                  = AIConfigs.load( AI_CONFIG ) ;
        qImgExtractorApiUrl = props.str( "qimgextractor.api.url" ) ;
    }

    /**
     * Read the settings from {@link #RESOURCE} and system properties.
     *
     * @throws IllegalStateException if the resource is missing or unreadable,
     *         or a key is missing or malformed
     */
    public static QClassifierConfig load() {
        return new QClassifierConfig( ConfigProperties.load( RESOURCE ) ) ;
    }
}
