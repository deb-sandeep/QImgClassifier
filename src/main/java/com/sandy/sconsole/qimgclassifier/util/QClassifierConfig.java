package com.sandy.sconsole.qimgclassifier.util;

import lombok.Getter;
import lombok.Value;

import java.io.File;
import java.time.Duration;
import java.util.List;

/**
 * Application settings, read from the {@code /qimgclassifier.properties}
 * classpath resource (see that file for what each key means). A JVM system
 * property of the same name, e.g. {@code -Dopenrouter.model=...}, overrides
 * the file (see {@link ConfigProperties}).
 * <p>
 * Every key must be present ({@code question.filter} may be blank); a missing
 * or malformed value fails {@link #load()} with an
 * {@link IllegalStateException} naming the key. Paths may start with {@code ~/}
 * for the user's home directory. The files the application keeps (processed
 * list, request history, usage log, API key) may be given relative to
 * {@code workspace.dir}.
 */
@Getter
public final class QClassifierConfig {

    /** Classpath location of the configuration file. */
    public static final String RESOURCE = "/qimgclassifier.properties" ;

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
     * Start times of the last day's requests per target, so per-day rate
     * limits survive a restart.
     */
    private final File requestHistoryFile ;

    /** One CSV row per AI attempt with the tokens it used. */
    private final File usageLogFile ;

    /**
     * Root of the per-source, per-question AI request / response transcripts.
     */
    private final File workspaceDir ;

    /**
     * Settings of one AI invocation target ({@code gemma.*},
     * {@code openrouter.*}).
     */
    @Value
    public static class Target {

        boolean enabled ;

        /**
         * Primary model, used for a question's first attempt on this target.
         */
        String model ;

        /** Models tried, in order, on retries on this target; may be empty. */
        List<String> backupModels ;

        /** Requests that may be in flight at once. */
        int maxParallel ;

        /**
         * Retries a question may get on this target after a failed attempt on
         * it.
         */
        int maxRetries ;

        Duration connectTimeout ;

        /** Limit on a whole request, from sending it to the full reply. */
        Duration requestTimeout ;

        /** Requests per minute; 0 for no limit. */
        int rateLimitRpm ;

        /** Requests per rolling 24 hours; 0 for no limit. */
        int rateLimitRpd ;

        /**
         * Pause after the backend reports a rate limit without saying how long.
         */
        Duration rateLimitCooldown ;

        /**
         * Let the model reason before answering; when false it is asked not
         * to, saving the reasoning tokens and time.
         */
        boolean reasoning ;
    }

    private final Target gemma ;

    /** Full URL of Ollama's generate endpoint. */
    private final String gemmaServerUrl ;

    private final Target openRouter ;

    /** Null when the key file is missing or empty. */
    private final String openRouterApiKey ;

    /** Mark the prompt cacheable on OpenRouter models that support caching. */
    private final boolean openRouterPromptCaching ;

    /** Base URL of QImgExtractor's remote command API. */
    private final String qImgExtractorApiUrl ;

    private QClassifierConfig( ConfigProperties props ) {

        qBankDir           = props.path( "question.bank.dir" ) ;
        questionFilter     = props.value( "question.filter", true ) ;
        workspaceDir       = props.workspaceDir( "workspace.dir" ) ;
        processedListFile  = props.workspacePath( "processed.list.file" ) ;
        requestHistoryFile = props.workspacePath( "request.history.file" ) ;
        usageLogFile       = props.workspacePath( "usage.log.file" ) ;

        gemma          = props.target( "gemma" ) ;
        gemmaServerUrl = props.str( "gemma.server.url" ) ;

        openRouter       = props.target( "openrouter" ) ;
        openRouterApiKey = props.apiKey( "openrouter.api.key.file" ) ;
        openRouterPromptCaching = props.bool( "openrouter.prompt.caching" ) ;

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
