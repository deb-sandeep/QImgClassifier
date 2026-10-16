package com.sandy.sconsole.qimgclassifier.util;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Application settings, read from the {@code /qimgclassifier.properties}
 * classpath resource (see that file for what each key means). A JVM system
 * property of the same name, e.g. {@code -Dopenrouter.model=...}, overrides
 * the file.
 * <p>
 * Every key must be present ({@code question.filter} may be blank); a missing
 * or malformed value fails {@link #load()} with an
 * {@link IllegalStateException} naming the key. Paths may start with {@code ~/}
 * for the user's home directory. The files the application keeps (processed
 * list, request history, usage log, API key) may be given relative to
 * {@code workspace.dir}.
 */
@Slf4j
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

    @Getter( AccessLevel.NONE )
    private final Properties props ;

    private QClassifierConfig( Properties props ) {

        this.props = props ;

        qBankDir           = path( "question.bank.dir" ) ;
        questionFilter     = value( "question.filter", true ) ;
        workspaceDir       = path( "workspace.dir" ) ;
        processedListFile  = workspacePath( "processed.list.file" ) ;
        requestHistoryFile = workspacePath( "request.history.file" ) ;
        usageLogFile       = workspacePath( "usage.log.file" ) ;

        gemma          = target( "gemma" ) ;
        gemmaServerUrl = str( "gemma.server.url" ) ;

        openRouter       = target( "openrouter" ) ;
        openRouterApiKey = readKey( workspacePath( "openrouter.api.key.file" ) ) ;
        openRouterPromptCaching = bool( "openrouter.prompt.caching" ) ;

        qImgExtractorApiUrl = str( "qimgextractor.api.url" ) ;
    }

    /**
     * Read the settings from {@link #RESOURCE} and system properties.
     *
     * @throws IllegalStateException if the resource is missing or unreadable,
     *         or a key is missing or malformed
     */
    public static QClassifierConfig load() {

        Properties props = new Properties() ;
        try( InputStream is =
                     QClassifierConfig.class.getResourceAsStream( RESOURCE ) ) {
            if( is == null ) {
                throw new IllegalStateException(
                        RESOURCE + " not found on the classpath" ) ;
            }
            try( Reader r = new InputStreamReader( is, StandardCharsets.UTF_8 ) ) {
                props.load( r ) ;
            }
        }
        catch( IOException e ) {
            throw new IllegalStateException( "Could not read " + RESOURCE, e ) ;
        }
        return new QClassifierConfig( props ) ;
    }

    // ---- typed accessors ---------------------------------------------

    /**
     * The common settings of the target whose keys start with {@code prefix.}.
     */
    private Target target( String prefix ) {

        return new Target( bool( prefix + ".enabled" ),
                           str( prefix + ".model" ),
                           list( prefix + ".backup.models" ),
                           positiveInt( prefix + ".max.parallel" ),
                           wholeNumber( prefix + ".max.retries", 0 ),
                           seconds( prefix + ".connect.timeout.seconds" ),
                           seconds( prefix + ".request.timeout.seconds" ),
                           wholeNumber( prefix + ".rate.limit.rpm", 0 ),
                           wholeNumber( prefix + ".rate.limit.rpd", 0 ),
                           seconds( prefix + ".rate.limit.cooldown.seconds" ),
                           bool( prefix + ".reasoning" ) ) ;
    }

    /**
     * Trimmed value of {@code key}; a system property wins over the file.
     *
     * @param blankAllowed whether an empty value is acceptable
     */
    private String value( String key, boolean blankAllowed ) {

        String v = System.getProperty( key, props.getProperty( key ) ) ;
        if( v == null ) throw invalid( key, "is missing" ) ;
        v = v.trim() ;
        if( v.isEmpty() && !blankAllowed ) throw invalid( key, "is blank" ) ;
        return v ;
    }

    private String str( String key ) {
        return value( key, false ) ;
    }

    /** {@code true} or {@code false}, case-insensitive. */
    private boolean bool( String key ) {

        String v = str( key ) ;
        if( v.equalsIgnoreCase( "true" ) )  return true ;
        if( v.equalsIgnoreCase( "false" ) ) return false ;
        throw invalid( key, "must be true or false, not '" + v + "'" ) ;
    }

    /** A whole number of at least 1. */
    private int positiveInt( String key ) {
        return wholeNumber( key, 1 ) ;
    }

    /** A whole number of at least {@code min}. */
    private int wholeNumber( String key, int min ) {

        String v = str( key ) ;
        try {
            int n = Integer.parseInt( v ) ;
            if( n >= min ) return n ;
        }
        catch( NumberFormatException e ) {
            // reported below
        }
        throw invalid( key, "must be a whole number of at least " + min +
                            ", not '" + v + "'" ) ;
    }

    /** Comma-separated values, trimmed, blanks dropped; may be empty. */
    private List<String> list( String key ) {

        return Arrays.stream( value( key, true ).split( "," ) )
                     .map( String::trim )
                     .filter( s -> !s.isEmpty() )
                     .toList() ;
    }

    /** A positive whole number of seconds. */
    private Duration seconds( String key ) {
        return Duration.ofSeconds( positiveInt( key ) ) ;
    }

    /**
     * A file path, with a leading {@code ~/} expanded to the home directory.
     */
    private File path( String key ) {

        String v = str( key ) ;
        if( v.startsWith( "~/" ) ) {
            return new File( System.getProperty( "user.home" ), v.substring( 2 ) ) ;
        }
        return new File( v ) ;
    }

    /**
     * A file path as {@link #path}, with a relative path taken as relative to
     * {@code workspace.dir}.
     */
    private File workspacePath( String key ) {
        File f = path( key ) ;
        return f.isAbsolute() ? f : new File( workspaceDir, f.getPath() ) ;
    }

    private static IllegalStateException invalid( String key, String problem ) {
        return new IllegalStateException( RESOURCE + ": " + key + " " + problem ) ;
    }

    /**
     * The key file's trimmed contents, or null if it is missing, empty or
     * unreadable (logged).
     */
    private static String readKey( File keyFile ) {

        if( !keyFile.isFile() ) return null ;
        try {
            String key = Files.readString( keyFile.toPath(),
                                           StandardCharsets.UTF_8 ).trim() ;
            return key.isEmpty() ? null : key ;
        }
        catch( IOException e ) {
            log.error( "Error reading OpenRouter API key from {}", keyFile, e ) ;
            return null ;
        }
    }
}
