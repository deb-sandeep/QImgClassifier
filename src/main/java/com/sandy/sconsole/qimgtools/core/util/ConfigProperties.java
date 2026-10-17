package com.sandy.sconsole.qimgtools.core.util;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Typed reads from a classpath properties file, shared by the applications'
 * configurations ({@link QClassifierConfig}, {@link ReviewConfig}). A JVM
 * system property of the same name overrides the file. There are no
 * defaults: a missing or malformed value throws an
 * {@link IllegalStateException} naming the file and the key. Paths may start
 * with {@code ~/} for the user's home directory.
 */
@Slf4j
public final class ConfigProperties {

    /** Classpath location of the file, used in error messages. */
    private final String     resource ;
    private final Properties props ;

    /** Folder that {@link #workspacePath} resolves relative paths against. */
    private File workspaceDir ;

    private ConfigProperties( String resource, Properties props ) {
        this.resource = resource ;
        this.props    = props ;
    }

    /**
     * Read the properties of classpath {@code resource}.
     *
     * @throws IllegalStateException if the resource is missing or unreadable
     */
    public static ConfigProperties load( String resource ) {

        Properties props = new Properties() ;
        try( InputStream is =
                     ConfigProperties.class.getResourceAsStream( resource ) ) {
            if( is == null ) {
                throw new IllegalStateException(
                        resource + " not found on the classpath" ) ;
            }
            try( Reader r = new InputStreamReader( is, StandardCharsets.UTF_8 ) ) {
                props.load( r ) ;
            }
        }
        catch( IOException e ) {
            throw new IllegalStateException( "Could not read " + resource, e ) ;
        }
        return new ConfigProperties( resource, props ) ;
    }

    /**
     * The common settings of the AI target whose keys start with
     * {@code prefix.}.
     */
    public QClassifierConfig.Target target( String prefix ) {

        return new QClassifierConfig.Target(
                bool( prefix + ".enabled" ),
                str( prefix + ".model" ),
                list( prefix + ".backup.models" ),
                map( prefix + ".display.names" ),
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
    public String value( String key, boolean blankAllowed ) {

        String v = System.getProperty( key, props.getProperty( key ) ) ;
        if( v == null ) throw invalid( key, "is missing" ) ;
        v = v.trim() ;
        if( v.isEmpty() && !blankAllowed ) throw invalid( key, "is blank" ) ;
        return v ;
    }

    public String str( String key ) {
        return value( key, false ) ;
    }

    /** {@code true} or {@code false}, case-insensitive. */
    public boolean bool( String key ) {

        String v = str( key ) ;
        if( v.equalsIgnoreCase( "true" ) )  return true ;
        if( v.equalsIgnoreCase( "false" ) ) return false ;
        throw invalid( key, "must be true or false, not '" + v + "'" ) ;
    }

    /** A whole number of at least 1. */
    public int positiveInt( String key ) {
        return wholeNumber( key, 1 ) ;
    }

    /** A whole number of at least {@code min}. */
    public int wholeNumber( String key, int min ) {
        return wholeNumber( key, min, Integer.MAX_VALUE ) ;
    }

    /** A whole number from {@code min} to {@code max}. */
    public int wholeNumber( String key, int min, int max ) {

        String v = str( key ) ;
        try {
            int n = Integer.parseInt( v ) ;
            if( n >= min && n <= max ) return n ;
        }
        catch( NumberFormatException e ) {
            // reported below
        }
        String range = max == Integer.MAX_VALUE
                     ? "of at least " + min
                     : "from " + min + " to " + max ;
        throw invalid( key, "must be a whole number " + range +
                            ", not '" + v + "'" ) ;
    }

    /** Comma-separated values, trimmed, blanks dropped; may be empty. */
    public List<String> list( String key ) {

        return Arrays.stream( value( key, true ).split( "," ) )
                     .map( String::trim )
                     .filter( s -> !s.isEmpty() )
                     .toList() ;
    }

    /**
     * Comma-separated {@code key = value} pairs, trimmed, in order; may be
     * empty. The pair's key ends at its first {@code =}.
     */
    public Map<String,String> map( String key ) {

        Map<String,String> map = new LinkedHashMap<>() ;
        for( String pair : list( key ) ) {
            int    eq = pair.indexOf( '=' ) ;
            String k  = eq < 0 ? "" : pair.substring( 0, eq ).trim() ;
            String v  = eq < 0 ? "" : pair.substring( eq + 1 ).trim() ;

            if( k.isEmpty() || v.isEmpty() ) {
                throw invalid( key, "must be comma-separated 'name = value' " +
                                    "pairs, not '" + pair + "'" ) ;
            }
            map.put( k, v ) ;
        }
        return map ;
    }

    /** Comma-separated whole numbers; may be empty. */
    public List<Integer> intList( String key ) {

        try {
            return list( key ).stream().map( Integer::valueOf ).toList() ;
        }
        catch( NumberFormatException e ) {
            throw invalid( key, "must be comma-separated whole numbers" ) ;
        }
    }

    /** A positive whole number of seconds. */
    public Duration seconds( String key ) {
        return Duration.ofSeconds( positiveInt( key ) ) ;
    }

    /**
     * A file path, with a leading {@code ~/} expanded to the home directory.
     */
    public File path( String key ) {

        String v = str( key ) ;
        if( v.startsWith( "~/" ) ) {
            return new File( System.getProperty( "user.home" ), v.substring( 2 ) ) ;
        }
        return new File( v ) ;
    }

    /**
     * Read {@code key} as the folder {@link #workspacePath} resolves against.
     */
    public File workspaceDir( String key ) {
        workspaceDir = path( key ) ;
        return workspaceDir ;
    }

    /**
     * A file path as {@link #path}, with a relative path taken as relative to
     * the folder read by {@link #workspaceDir}.
     */
    public File workspacePath( String key ) {
        File f = path( key ) ;
        return f.isAbsolute() ? f : new File( workspaceDir, f.getPath() ) ;
    }

    /** An error about {@code key}: "{@code <resource>: <key> <problem>}". */
    public IllegalStateException invalid( String key, String problem ) {
        return new IllegalStateException( resource + ": " + key + " " + problem ) ;
    }

    /**
     * The trimmed contents of the key file named by {@code key} (see
     * {@link #workspacePath}), or null if it is missing, empty or unreadable
     * (logged).
     */
    public String apiKey( String key ) {

        File keyFile = workspacePath( key ) ;
        if( !keyFile.isFile() ) return null ;
        try {
            String apiKey = Files.readString( keyFile.toPath(),
                                              StandardCharsets.UTF_8 ).trim() ;
            return apiKey.isEmpty() ? null : apiKey ;
        }
        catch( IOException e ) {
            log.error( "Error reading the API key from {}", keyFile, e ) ;
            return null ;
        }
    }
}
