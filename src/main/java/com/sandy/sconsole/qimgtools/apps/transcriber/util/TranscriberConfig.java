package com.sandy.sconsole.qimgtools.apps.transcriber.util;

import com.sandy.sconsole.ai.config.AIConfig;
import com.sandy.sconsole.qimgtools.apps.transcriber.gate.ConfidenceScore;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.TranscriptionCriteria;
import com.sandy.sconsole.qimgtools.core.util.AIConfigs;
import com.sandy.sconsole.qimgtools.core.util.ConfigProperties;

import lombok.Getter;

import java.io.File;
import java.time.Duration;

import com.sandy.sconsole.qimgtools.apps.transcriber.server.TranscriberServerClient;

/**
 * Settings of the transcriber, read from the
 * {@code /apps/transcriber/qtranscriber.properties} classpath resource (see
 * that file for what each key means). A JVM system property of the same
 * name overrides the file (see {@link ConfigProperties}). The AI models
 * come from the JSON app config {@link #AI_CONFIG} and its parent.
 * <p>
 * Every key must be present; a missing or malformed value fails
 * {@link #load()} with an {@link IllegalStateException} naming the key.
 */
@Getter
public final class TranscriberConfig {

    private static final int MAX_BATCH = TranscriberServerClient.MAX_BATCH ;

    /** Classpath location of the configuration file. */
    public static final String RESOURCE =
            "/apps/transcriber/qtranscriber.properties" ;

    /** Location of the app's AI config, unless {@code -Dai.app.config}. */
    public static final String AI_CONFIG =
            "classpath:/apps/transcriber/qtranscriber.ai.json" ;

    /** Base URL of the SConsole server, e.g. {@code http://localhost:8080}. */
    private final String   serverUrl ;
    private final Duration serverConnectTimeout ;
    private final Duration serverRequestTimeout ;

    /** Root folder searched (recursively) for the source folders. */
    private final File qBankDir ;

    /** Root of the app's files. */
    private final File workspaceDir ;

    /** The criteria used until some are saved in the app. */
    private final TranscriptionCriteria criteria ;

    /** The gate's margin: C counts the positions contested within it. */
    private final double gateMargin ;

    /** True to send a Qwen transcription with C above {@link #gateCMax} on. */
    private final boolean gateRouteOnC ;
    private final int     gateCMax ;

    /** Classpath location of the prompt file. */
    private final String promptFile ;

    private final File criteriaFile ;
    private final File stagedFile ;
    private final File heldPassagesFile ;
    private final File auditFile ;

    /** The unattended run's load count, as last chosen. */
    private final File unattendedFile ;

    /** Request times of the current UTC day, behind the per-day limits. */
    private final File requestHistoryFile ;

    /** One row per AI attempt with its token counts. */
    private final File usageLogFile ;

    /** The models, from {@link #AI_CONFIG} or {@code -Dai.app.config}. */
    private final AIConfig ai ;

    private TranscriberConfig( ConfigProperties props ) {

        serverUrl            = props.str( "server.url" ) ;
        serverConnectTimeout = props.seconds( "server.connect.timeout.seconds" ) ;
        serverRequestTimeout = props.seconds( "server.request.timeout.seconds" ) ;

        qBankDir     = props.path( "question.bank.dir" ) ;
        workspaceDir = props.workspaceDir( "workspace.dir" ) ;
        criteria     = new TranscriptionCriteria(
                props.list( "fetch.syllabus.names" ),
                props.intList( "fetch.topic.ids" ),
                props.wholeNumber( "fetch.batch.size", 1, MAX_BATCH ) ) ;

        gateMargin   = margin( props ) ;
        gateRouteOnC = props.bool( "gate.route.on.c" ) ;
        gateCMax     = props.wholeNumber( "gate.c.max", 0 ) ;
        promptFile   = props.str( "prompt.file" ) ;

        criteriaFile     = props.workspacePath( "criteria.file" ) ;
        stagedFile       = props.workspacePath( "staged.file" ) ;
        heldPassagesFile = props.workspacePath( "held.passages.file" ) ;
        auditFile        = props.workspacePath( "audit.file" ) ;
        unattendedFile   = props.workspacePath( "unattended.file" ) ;

        requestHistoryFile = props.workspacePath( "request.history.file" ) ;
        usageLogFile       = props.workspacePath( "usage.log.file" ) ;

        ai = AIConfigs.load( AI_CONFIG ) ;
    }

    /** {@code gate.margin}: above 0, at most {@link ConfidenceScore#MAX_MARGIN}. */
    private static double margin( ConfigProperties props ) {

        String key = "gate.margin" ;
        double m ;
        try {
            m = Double.parseDouble( props.str( key ) ) ;
        }
        catch( NumberFormatException e ) {
            throw props.invalid( key, "is not a number" ) ;
        }
        if( !( m > 0 && m <= ConfidenceScore.MAX_MARGIN ) ) {
            throw props.invalid( key, "must be above 0 and at most " +
                                      ConfidenceScore.MAX_MARGIN ) ;
        }
        return m ;
    }

    /**
     * Read the settings from {@link #RESOURCE} and system properties.
     *
     * @throws IllegalStateException if the resource is missing or unreadable,
     *         a key is missing or malformed, or the AI config is invalid
     */
    public static TranscriberConfig load() {
        return new TranscriberConfig( ConfigProperties.load( RESOURCE ) ) ;
    }
}
