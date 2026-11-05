package com.sandy.sconsole.qimgtools.apps.tagger.util;

import com.sandy.sconsole.ai.config.AIConfig;
import com.sandy.sconsole.qimgtools.core.util.AIConfigs;
import com.sandy.sconsole.qimgtools.core.util.ConfigProperties;

import lombok.Getter;

import java.io.File;
import java.time.Duration;

/**
 * Settings of the tagger, read from the
 * {@code /apps/tagger/qtagger.properties} classpath resource (see that file
 * for what each key means). A JVM system property of the same name overrides
 * the file (see {@link ConfigProperties}).
 * <p>
 * Every key must be present; a missing or malformed value fails
 * {@link #load} with an {@link IllegalStateException} naming the key. The
 * files that hold the tagger's state may be given relative to
 * {@code workspace.dir}. The AI models come from {@link #AI_CONFIG} and its
 * parent (see {@code AIConfigLoader}), which fail the same way.
 */
@Getter
public final class TaggerConfig {

    /** Classpath location of the configuration file. */
    public static final String RESOURCE = "/apps/tagger/qtagger.properties" ;

    /** Location of the AI config (models, roots, fallbacks). */
    public static final String AI_CONFIG =
            "classpath:/apps/tagger/qtagger.ai.json" ;

    /** Base URL of the SConsole server, e.g. {@code http://localhost:8080}. */
    private final String   serverUrl ;
    private final Duration serverConnectTimeout ;
    private final Duration serverRequestTimeout ;

    /** Root folder searched (recursively) for the source folders. */
    private final File qBankDir ;

    /**
     * The tag workspace in the question-bank folder, where each topic's
     * regression model and LLM prompt are read from; they aren't copied.
     */
    private final File tagWorkspaceDir ;

    /** The tesseract executable. */
    private final File tesseractBin ;

    /** Questions read at once after a Load. */
    private final int ocrThreads ;

    /** How long tesseract may take over one image. */
    private final Duration ocrTimeout ;

    /** Root of the tagger's files, and of its per-question transcripts. */
    private final File workspaceDir ;

    /** The scope last chosen, with its load cursor. */
    private final File scopeFile ;

    /** The user's settings. */
    private final File settingsFile ;

    /** The model's replies on questions not yet saved on the server. */
    private final File pendingTaggingFile ;

    /** Every reply and decision, appended as JSON lines. */
    private final File tagAuditFile ;

    /** Deferrals, appended as JSON lines. */
    private final File taggerNotesFile ;

    /**
     * Start times of the day's requests, so the per-day limit survives a
     * restart.
     */
    private final File requestHistoryFile ;

    /** One CSV row per AI attempt with the tokens it used. */
    private final File usageLogFile ;

    /** Width an image is scaled down to before it is sent; 0 for none. */
    private final int imagePrepWidth ;

    /** Send images in greyscale. */
    private final boolean imagePrepGreyscale ;

    /** The models asked, from {@link #AI_CONFIG} or {@code -Dai.app.config}. */
    private final AIConfig ai ;

    private TaggerConfig( ConfigProperties props ) {

        serverUrl            = props.str( "server.url" ) ;
        serverConnectTimeout = props.seconds( "server.connect.timeout.seconds" ) ;
        serverRequestTimeout = props.seconds( "server.request.timeout.seconds" ) ;

        qBankDir           = props.path( "question.bank.dir" ) ;
        tagWorkspaceDir    = props.path( "tag.workspace.dir" ) ;
        workspaceDir       = props.workspaceDir( "workspace.dir" ) ;
        scopeFile          = props.workspacePath( "scope.file" ) ;
        settingsFile       = props.workspacePath( "settings.file" ) ;
        pendingTaggingFile = props.workspacePath( "pending.tagging.file" ) ;
        tagAuditFile       = props.workspacePath( "tag.audit.file" ) ;
        taggerNotesFile    = props.workspacePath( "tagger.notes.file" ) ;
        usageLogFile       = props.workspacePath( "usage.log.file" ) ;
        requestHistoryFile = props.workspacePath( "request.history.file" ) ;

        tesseractBin = props.path( "tesseract.bin" ) ;
        ocrThreads   = props.wholeNumber( "ocr.threads", 1, 16 ) ;
        ocrTimeout   = props.seconds( "ocr.timeout.seconds" ) ;

        imagePrepWidth     = props.wholeNumber( "image.prep.width", 0 ) ;
        imagePrepGreyscale = props.bool( "image.prep.greyscale" ) ;

        ai = AIConfigs.load( AI_CONFIG ) ;
    }

    /**
     * Read the settings from {@link #RESOURCE} and system properties.
     *
     * @throws IllegalStateException if the resource is missing or unreadable,
     *         or a key is missing or malformed
     */
    public static TaggerConfig load() {
        return new TaggerConfig( ConfigProperties.load( RESOURCE ) ) ;
    }
}
