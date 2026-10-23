package com.sandy.sconsole.qimgtools.apps.tagger.util;

import com.sandy.sconsole.ai.config.AIConfig;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagClass;
import com.sandy.sconsole.qimgtools.core.util.AIConfigs;
import com.sandy.sconsole.qimgtools.core.util.ConfigProperties;

import lombok.Getter;

import java.io.File;
import java.time.Duration;

/**
 * Settings of the tagger for one pass, read from the
 * {@code /apps/tagger/qtagger.properties} classpath resource (see that file
 * for what each key means). A JVM system property of the same name overrides
 * the file (see {@link ConfigProperties}).
 * <p>
 * Every key must be present; a missing or malformed value fails
 * {@link #load} with an {@link IllegalStateException} naming the key. The
 * files that hold a pass' state (scope, pending replies, audit log, notes,
 * token usage) may be given relative to the pass' folder in
 * {@code workspace.dir} ({@code setup/}, {@code concept/}), so the passes
 * never read each other's; the request history, relative to
 * {@code workspace.dir} itself. The AI models come from the pass' JSON app
 * config ({@link #aiConfig}) and its parent (see {@code AIConfigLoader}),
 * which fail the same way.
 */
@Getter
public final class TaggerConfig {

    /** Classpath location of the configuration file. */
    public static final String RESOURCE = "/apps/tagger/qtagger.properties" ;

    /** The pass this process runs. */
    private final TagClass tagClass ;

    /** Base URL of the SConsole server, e.g. {@code http://localhost:8080}. */
    private final String   serverUrl ;
    private final Duration serverConnectTimeout ;
    private final Duration serverRequestTimeout ;

    /** Root folder searched (recursively) for the source folders. */
    private final File qBankDir ;

    /** The topic prompts and the shared template, in the source tree. */
    private final File promptsDir ;

    /** Root of the tagger's files and per-question transcripts. */
    private final File workspaceDir ;

    /** The pass' folder in the workspace, holding its own files. */
    private final File passDir ;

    /** The scope last chosen, with its load cursors. */
    private final File scopeFile ;

    /** The tiers' replies on questions not yet saved on the server. */
    private final File pendingTaggingFile ;

    /** Every judgement and decision, appended as JSON lines. */
    private final File tagAuditFile ;

    /** Gap proposals and deferrals, appended as JSON lines. */
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

    /**
     * The models the pass asks, from {@link #aiConfig} or the file
     * {@code -Dai.app.config} names.
     */
    private final AIConfig ai ;

    private TaggerConfig( ConfigProperties props, TagClass tagClass ) {

        this.tagClass        = tagClass ;
        serverUrl            = props.str( "server.url" ) ;
        serverConnectTimeout = props.seconds( "server.connect.timeout.seconds" ) ;
        serverRequestTimeout = props.seconds( "server.request.timeout.seconds" ) ;

        qBankDir           = props.path( "question.bank.dir" ) ;
        promptsDir         = props.path( "prompts.dir" ).getAbsoluteFile() ;
        workspaceDir       = props.workspaceDir( "workspace.dir" ) ;
        passDir            = new File( workspaceDir, tagClass.wire() ) ;
        scopeFile          = passPath( props, "scope.file" ) ;
        pendingTaggingFile = passPath( props, "pending.tagging.file" ) ;
        tagAuditFile       = passPath( props, "tag.audit.file" ) ;
        taggerNotesFile    = passPath( props, "tagger.notes.file" ) ;
        usageLogFile       = passPath( props, "usage.log.file" ) ;
        requestHistoryFile = props.workspacePath( "request.history.file" ) ;

        imagePrepWidth     = props.wholeNumber( "image.prep.width", 0 ) ;
        imagePrepGreyscale = props.bool( "image.prep.greyscale" ) ;

        ai = AIConfigs.load( aiConfig( tagClass ) ) ;
    }

    /** {@code key}'s path, a relative one taken from the pass' folder. */
    private File passPath( ConfigProperties props, String key ) {
        File f = props.path( key ) ;
        return f.isAbsolute() ? f : new File( passDir, f.getPath() ) ;
    }

    /**
     * Location of pass {@code tagClass}' AI config (models, roots,
     * fallbacks), e.g. {@code classpath:/apps/tagger/qtagger.setup.ai.json}.
     */
    public static String aiConfig( TagClass tagClass ) {
        return "classpath:/apps/tagger/qtagger." + tagClass.wire() +
               ".ai.json" ;
    }

    /**
     * Read the settings of pass {@code tagClass} from {@link #RESOURCE} and
     * system properties.
     *
     * @throws IllegalStateException if the resource is missing or unreadable,
     *         or a key is missing or malformed
     */
    public static TaggerConfig load( TagClass tagClass ) {
        return new TaggerConfig( ConfigProperties.load( RESOURCE ),
                                 tagClass ) ;
    }
}
