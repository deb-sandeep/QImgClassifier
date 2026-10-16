package com.sandy.sconsole.qimgclassifier.util;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

/**
 * Application settings, read from {@code ~/.qclassifier.properties} when it
 * exists. Every key is optional; a JVM system property of the same name
 * (e.g. {@code -Dopenrouter.model=...}) overrides the file.
 *
 * <pre>
 * question.bank.dir       = /Users/sandeep/Documents/StudyNotes/question-bank
 * question.filter         =     # debug: one question id / subject letter
 *
 * gemma.enabled           = true
 * gemma.server.url        = http://192.168.0.149:11434/api/generate
 * gemma.model             = gemma4:latest
 * gemma.max.parallel      = 5
 *
 * openrouter.enabled      = true     # default: true if the API key file exists
 * openrouter.model        = google/gemini-2.5-flash
 * openrouter.max.parallel = 5
 * openrouter.api.key.file = ~/.open-router.api.key
 * </pre>
 */
@Slf4j
@Getter
public final class QClassifierConfig {

    public static final File CONFIG_FILE = userFile( ".qclassifier.properties" ) ;

    /**
     * Names of the sources whose every question has been classified, one per
     * line.
     */
    public static final File PROCESSED_LIST_FILE =
            userFile( ".qclassifier.processed.list" ) ;

    private static final String DEFAULT_Q_BANK_DIR  =
            "/Users/sandeep/Documents/StudyNotes/question-bank" ;
    private static final String DEFAULT_GEMMA_URL   =
            "http://192.168.0.149:11434/api/generate" ;
    private static final String DEFAULT_GEMMA_MODEL = "gemma4:latest" ;
    private static final String DEFAULT_OR_MODEL    = "google/gemini-2.5-flash" ;
    private static final String DEFAULT_OR_KEY_FILE = "~/.open-router.api.key" ;
    private static final int    DEFAULT_PARALLEL    = 5 ;

    /** Root folder searched (recursively) for the source folders. */
    private final File    qBankDir ;

    /**
     * Debug filter on question ids: blank for all questions, a single letter
     * for one subject (e.g. "C"), otherwise an exact base or part id.
     */
    private final String  questionFilter ;

    private final boolean gemmaEnabled ;
    private final String  gemmaServerUrl ;
    private final String  gemmaModel ;
    private final int     gemmaMaxParallel ;

    private final boolean openRouterEnabled ;
    private final String  openRouterModel ;
    private final int     openRouterMaxParallel ;

    /** Null when the key file is missing or empty. */
    private final String  openRouterApiKey ;

    @Getter( AccessLevel.NONE )
    private final Properties props = new Properties() ;

    private QClassifierConfig() {

        if( CONFIG_FILE.isFile() ) {
            try( Reader r = new FileReader( CONFIG_FILE, StandardCharsets.UTF_8 ) ) {
                props.load( r ) ;
            }
            catch( Exception e ) {
                log.error( "Error reading {} - using defaults", CONFIG_FILE, e ) ;
            }
        }

        qBankDir       = expandHome( str( "question.bank.dir",
                                          DEFAULT_Q_BANK_DIR ) ) ;
        questionFilter = str( "question.filter", "" ) ;

        gemmaEnabled     = bool( "gemma.enabled", true ) ;
        gemmaServerUrl   = str( "gemma.server.url", DEFAULT_GEMMA_URL ) ;
        gemmaModel       = str( "gemma.model", DEFAULT_GEMMA_MODEL ) ;
        gemmaMaxParallel = num( "gemma.max.parallel", DEFAULT_PARALLEL ) ;

        openRouterApiKey      = readKey( expandHome(
                                    str( "openrouter.api.key.file",
                                         DEFAULT_OR_KEY_FILE ) ) ) ;
        openRouterEnabled     = bool( "openrouter.enabled",
                                      openRouterApiKey != null ) ;
        openRouterModel       = str( "openrouter.model", DEFAULT_OR_MODEL ) ;
        openRouterMaxParallel = num( "openrouter.max.parallel", DEFAULT_PARALLEL ) ;
    }

    /** Read the settings afresh from the config file and system properties. */
    public static QClassifierConfig load() {
        return new QClassifierConfig() ;
    }

    /**
     * Trimmed value of {@code key}, or {@code def} when it is unset or blank.
     */
    private String str( String key, String def ) {
        String v = System.getProperty( key, props.getProperty( key ) ) ;
        return v == null || v.isBlank() ? def : v.trim() ;
    }

    private boolean bool( String key, boolean def ) {
        String v = str( key, null ) ;
        return v == null ? def : Boolean.parseBoolean( v ) ;
    }

    /**
     * A positive whole number; {@code def} when unset or malformed, at least 1
     * otherwise.
     */
    private int num( String key, int def ) {

        String v = str( key, null ) ;
        if( v == null ) return def ;
        try {
            return Math.max( 1, Integer.parseInt( v ) ) ;
        }
        catch( NumberFormatException e ) {
            log.error( "{} '{}' is not a whole number - using {}", key, v, def ) ;
            return def ;
        }
    }

    private static File expandHome( String path ) {
        if( path.startsWith( "~/" ) ) return userFile( path.substring( 2 ) ) ;
        return new File( path ) ;
    }

    /** {@code name} inside the user's home directory. */
    private static File userFile( String name ) {
        return new File( System.getProperty( "user.home" ), name ) ;
    }

    /**
     * The key file's trimmed contents, or null if it is missing, empty or
     * unreadable.
     */
    private static String readKey( File keyFile ) {

        if( !keyFile.isFile() ) return null ;
        try {
            String key = Files.readString( keyFile.toPath(),
                                           StandardCharsets.UTF_8 ).trim() ;
            return key.isEmpty() ? null : key ;
        }
        catch( Exception e ) {
            log.error( "Error reading OpenRouter API key from {}", keyFile, e ) ;
            return null ;
        }
    }
}
