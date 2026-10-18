package com.sandy.sconsole.ai.config;

import com.sandy.sconsole.ai.FailureKind;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads and validates the two config files.
 * <p>
 * <b>Global (parent) file</b>: {@code gateways} by name, each with
 * {@code enabled}, {@code type} ({@code openrouter} / {@code ollama}),
 * {@code url}, {@code connectTimeoutSeconds}, {@code cooldownSeconds},
 * {@code rateLimit} ({@code {rpm, rpd}}), optional {@code maxParallel}, and
 * {@code apiKeyFile} (required for {@code openrouter}); and {@code models}
 * by id, each with {@code enabled}, {@code gateway}, optional
 * {@code displayName}, {@code rateLimit} and {@code contextTokens}.
 * <p>
 * <b>App file</b>: {@code parent} (the global file), {@code roots} (name to
 * a non-empty list of model ids) and {@code models} by id, each with
 * {@code enabled}, {@code maxParallel}, {@code requestTimeoutSeconds},
 * {@code retries}, {@code reasoning}, {@code promptCaching} (required on
 * {@code openrouter} models), optional {@code rateLimit} and
 * {@code fallback} (guard name to a list of model ids; the guards are the
 * {@link FailureKind} guard names and {@code default}).
 * <p>
 * Every model the app names must be defined in the parent and in the app's
 * {@code models}. A root model the app enables must be usable
 * ({@link ModelInfo#whyUnusable}); any other model the app enables that is
 * not usable is only warned about, and is skipped at run time.
 */
final class ConfigParser {

    private static final Logger log = LoggerFactory.getLogger( "ai.config" ) ;

    private static final String CLASSPATH = "classpath:" ;
    private static final String DEFAULT   = "default" ;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable( JsonParser.Feature.ALLOW_COMMENTS ) ;

    private static final Set<String> GATEWAY_KEYS = Set.of(
            "enabled", "type", "url", "apiKeyFile", "connectTimeoutSeconds",
            "cooldownSeconds", "maxParallel", "rateLimit" ) ;
    private static final Set<String> GLOBAL_MODEL_KEYS = Set.of(
            "enabled", "gateway", "displayName", "rateLimit", "contextTokens" ) ;
    private static final Set<String> APP_MODEL_KEYS = Set.of(
            "enabled", "maxParallel", "requestTimeoutSeconds", "retries",
            "reasoning", "promptCaching", "rateLimit", "fallback" ) ;
    private static final Set<String> RATE_LIMIT_KEYS = Set.of( "rpm", "rpd" ) ;

    /** A config file's text and where it came from. */
    private record Source( String location, String text, File dir ) {}

    /** A model as the global file defines it. */
    private record GlobalModel( String id, GatewayInfo gateway,
                                String displayName, boolean enabled,
                                RateLimit rateLimit, Integer contextTokens ) {}

    /** The file being parsed, for error messages. */
    private String file ;

    AIConfig load( String location ) {

        Source   app     = read( location ) ;
        JsonNode appRoot = parse( app ) ;

        file = app.location() ;
        checkKeys( appRoot, "", Set.of( "parent", "roots", "models" ) ) ;

        String   parentAt = resolve( text( appRoot, "", "parent" ), app ) ;
        Source   parent   = read( parentAt ) ;
        JsonNode global   = parse( parent ) ;

        file = parent.location() ;
        checkKeys( global, "", Set.of( "gateways", "models" ) ) ;

        Map<String,GatewayInfo> gateways = gateways( global, parent ) ;
        Map<String,GlobalModel> defined  = globalModels( global, gateways ) ;

        file = app.location() ;
        Map<String,ModelInfo>    models = appModels( appRoot, defined ) ;
        Map<String,List<String>> roots  = roots( appRoot, models ) ;

        AIConfig config = new AIConfig( app.location(), parent.location(),
                                        gateways, models, roots ) ;
        checkUsable( config ) ;
        logSummary( config ) ;
        return config ;
    }

    // ---- global file ---------------------------------------------------

    private Map<String,GatewayInfo> gateways( JsonNode global, Source src ) {

        Map<String,GatewayInfo> gateways = new LinkedHashMap<>() ;
        JsonNode all = object( global, "", "gateways" ) ;

        for( Iterator<String> it = all.fieldNames() ; it.hasNext() ; ) {
            String   name = it.next() ;
            String   path = "gateways." + quote( name ) ;
            JsonNode g    = all.get( name ) ;

            requireObject( g, path ) ;
            checkKeys( g, path, GATEWAY_KEYS ) ;

            String      typeKey = text( g, path, "type" ) ;
            GatewayType type    = GatewayType.ofKey( typeKey ) ;
            if( type == null ) {
                throw error( path + ".type", "is '" + typeKey + "'; it must " +
                             "be openrouter or ollama" ) ;
            }

            File   keyFile = null ;
            String apiKey  = null ;
            if( g.has( "apiKeyFile" ) ) {
                keyFile = relativeTo( text( g, path, "apiKeyFile" ), src ) ;
                apiKey  = readKey( keyFile ) ;
            }
            else if( type == GatewayType.OPENROUTER ) {
                throw error( path, "has no apiKeyFile, which an openrouter " +
                             "gateway needs" ) ;
            }

            gateways.put( name, new GatewayInfo(
                    name, type, bool( g, path, "enabled" ),
                    text( g, path, "url" ), keyFile, apiKey,
                    seconds( g, path, "connectTimeoutSeconds" ),
                    seconds( g, path, "cooldownSeconds" ),
                    g.has( "maxParallel" ) ? integer( g, path, "maxParallel", 1 )
                                           : 0,
                    rateLimit( g, path ) ) ) ;
        }
        return gateways ;
    }

    private Map<String,GlobalModel> globalModels(
                        JsonNode global, Map<String,GatewayInfo> gateways ) {

        Map<String,GlobalModel> models = new LinkedHashMap<>() ;
        JsonNode all = object( global, "", "models" ) ;

        for( Iterator<String> it = all.fieldNames() ; it.hasNext() ; ) {
            String   id   = it.next() ;
            String   path = "models." + quote( id ) ;
            JsonNode m    = all.get( id ) ;

            requireObject( m, path ) ;
            checkKeys( m, path, GLOBAL_MODEL_KEYS ) ;

            String      gwName  = text( m, path, "gateway" ) ;
            GatewayInfo gateway = gateways.get( gwName ) ;
            if( gateway == null ) {
                throw error( path + ".gateway", "names '" + gwName + "', " +
                             "which is not one of the gateways " +
                             gateways.keySet() ) ;
            }
            models.put( id, new GlobalModel(
                    id, gateway,
                    m.has( "displayName" ) ? text( m, path, "displayName" )
                                           : null,
                    bool( m, path, "enabled" ),
                    m.has( "rateLimit" ) ? rateLimit( m, path ) : RateLimit.NONE,
                    m.has( "contextTokens" )
                            ? integer( m, path, "contextTokens", 1 ) : null ) ) ;
        }
        return models ;
    }

    // ---- app file ------------------------------------------------------

    private Map<String,ModelInfo> appModels( JsonNode app,
                                             Map<String,GlobalModel> defined ) {

        JsonNode all = object( app, "", "models" ) ;

        // Fallback references are checked against the whole JSON list, so a
        // model may fall back to one defined after it.
        Map<String,ModelInfo> models = new LinkedHashMap<>() ;
        for( Iterator<String> it = all.fieldNames() ; it.hasNext() ; ) {
            String   id   = it.next() ;
            String   path = "models." + quote( id ) ;
            JsonNode m    = all.get( id ) ;

            requireObject( m, path ) ;
            checkKeys( m, path, APP_MODEL_KEYS ) ;

            GlobalModel def = defined.get( id ) ;
            if( def == null ) {
                throw error( path, "is not defined in the parent file" ) ;
            }

            boolean openRouter = def.gateway().type() == GatewayType.OPENROUTER ;
            boolean caching    = openRouter ? bool( m, path, "promptCaching" )
                               : m.has( "promptCaching" )
                                 && bool( m, path, "promptCaching" ) ;

            models.put( id, new ModelInfo(
                    id, def.gateway(), def.displayName(), def.enabled(),
                    bool( m, path, "enabled" ), def.contextTokens(),
                    def.rateLimit(),
                    m.has( "rateLimit" ) ? rateLimit( m, path ) : RateLimit.NONE,
                    integer( m, path, "maxParallel", 1 ),
                    seconds( m, path, "requestTimeoutSeconds" ),
                    integer( m, path, "retries", 0 ),
                    bool( m, path, "reasoning" ), caching,
                    fallback( m, path, all ) ) ) ;
        }
        return models ;
    }

    private FallbackInfo fallback( JsonNode model, String modelPath,
                                   JsonNode appModels ) {

        if( !model.has( "fallback" ) ) return FallbackInfo.NONE ;

        String   path = modelPath + ".fallback" ;
        JsonNode f    = model.get( "fallback" ) ;
        requireObject( f, path ) ;

        Map<FailureKind,List<String>> guards   = new EnumMap<>( FailureKind.class ) ;
        List<String>                  defaults = List.of() ;

        for( Iterator<String> it = f.fieldNames() ; it.hasNext() ; ) {
            String       guard = it.next() ;
            String       gPath = path + "." + guard ;
            List<String> ids   = modelList( f.get( guard ), gPath, appModels ) ;

            if( guard.equals( DEFAULT ) ) {
                defaults = ids ;
                continue ;
            }
            FailureKind kind = FailureKind.ofGuard( guard ) ;
            if( kind == null ) {
                throw error( gPath, "is not a guard; the guards are " +
                             guardNames() ) ;
            }
            guards.put( kind, ids ) ;
        }
        return new FallbackInfo( guards, defaults ) ;
    }

    private Map<String,List<String>> roots( JsonNode app,
                                            Map<String,ModelInfo> models ) {

        Map<String,List<String>> roots = new LinkedHashMap<>() ;
        JsonNode all = object( app, "", "roots" ) ;

        if( all.isEmpty() ) throw error( "roots", "is empty" ) ;
        for( Iterator<String> it = all.fieldNames() ; it.hasNext() ; ) {
            String name = it.next() ;
            String path = "roots." + quote( name ) ;
            List<String> ids = modelList( all.get( name ), path, null ) ;

            for( int i = 0 ; i < ids.size() ; i++ ) {
                if( !models.containsKey( ids.get( i ) ) ) {
                    throw error( path + "[" + i + "]", "names '" + ids.get( i ) +
                                 "', which is not in the app's models" ) ;
                }
            }
            if( ids.isEmpty() ) throw error( path, "is empty" ) ;
            roots.put( name, ids ) ;
        }
        return roots ;
    }

    /**
     * A JSON array of distinct model ids, each one in {@code appModels}
     * when that is not null.
     */
    private List<String> modelList( JsonNode n, String path,
                                    JsonNode appModels ) {

        if( n == null || !n.isArray() ) {
            throw error( path, "must be a list of model ids" ) ;
        }
        List<String> ids = new ArrayList<>() ;
        for( int i = 0 ; i < n.size() ; i++ ) {
            JsonNode e  = n.get( i ) ;
            String   at = path + "[" + i + "]" ;

            if( !e.isTextual() ) throw error( at, "must be a model id" ) ;
            String id = e.asText() ;
            if( appModels != null && !appModels.has( id ) ) {
                throw error( at, "names '" + id + "', which is not in the " +
                             "app's models" ) ;
            }
            if( ids.contains( id ) ) throw error( at, "repeats '" + id + "'" ) ;
            ids.add( id ) ;
        }
        return ids ;
    }

    /**
     * A root model the app enables must be usable; a fallback that names a
     * model that can't be used (for any reason, the app's own switch
     * included) is warned about, once per model and fallback list.
     */
    private void checkUsable( AIConfig config ) {

        config.roots().forEach( ( name, ids ) -> {
            for( int i = 0 ; i < ids.size() ; i++ ) {
                ModelInfo m = config.models().get( ids.get( i ) ) ;
                if( m.enabledInApp() && !m.usable() ) {
                    throw error( "roots." + quote( name ) + "[" + i + "]",
                                 "is model '" + m.id() + "', which can't be " +
                                 "used: " + m.whyUnusable() + " (" +
                                 config.parentLocation() + ")" ) ;
                }
            }
        } ) ;
        config.models().values().forEach( m -> {
            FallbackInfo fb = m.fallback() ;
            Set<String>  named = new LinkedHashSet<>( fb.defaults() ) ;

            fb.guards().values().forEach( named::addAll ) ;
            for( String id : named ) {
                ModelInfo to = config.models().get( id ) ;
                if( to.usable() ) continue ;
                log.warn( "{}: model '{}' falls back to '{}', which can't " +
                          "be used and will be skipped: {}",
                          config.appLocation(), m.id(), id,
                          to.whyUnusable() ) ;
            }
        } ) ;
    }

    private void logSummary( AIConfig config ) {

        log.info( "AI config {} (parent {})", config.appLocation(),
                  config.parentLocation() ) ;
        config.gateways().values().forEach( g -> log.info( "  gateway {}", g ) ) ;
        config.models().values().forEach( m -> log.info( "  model {}", m ) ) ;
        config.roots().forEach( ( name, ids ) -> {
            boolean on = config.isRootUsable( name ) ;
            log.info( "  root {} {}{}", name, ids,
                      on ? "" : " - OFF: none of its models can be used" ) ;
        } ) ;
    }

    // ---- reading -------------------------------------------------------

    private static Source read( String location ) {

        if( location.startsWith( CLASSPATH ) ) {
            String path = location.substring( CLASSPATH.length() ) ;
            try( InputStream is =
                         ConfigParser.class.getResourceAsStream( path ) ) {
                if( is == null ) {
                    throw new AIConfigException( location + " not found on " +
                                                 "the classpath" ) ;
                }
                return new Source( location,
                        new String( is.readAllBytes(), StandardCharsets.UTF_8 ),
                        null ) ;
            }
            catch( IOException e ) {
                throw new AIConfigException( "Could not read " + location, e ) ;
            }
        }

        File f = expand( location ) ;
        if( !f.isFile() ) throw new AIConfigException( f + " not found" ) ;
        try {
            return new Source( f.getPath(), Files.readString( f.toPath() ),
                               f.getAbsoluteFile().getParentFile() ) ;
        }
        catch( IOException e ) {
            throw new AIConfigException( "Could not read " + f, e ) ;
        }
    }

    private static JsonNode parse( Source src ) {

        try {
            JsonNode root = MAPPER.readTree( src.text() ) ;
            if( root == null || !root.isObject() ) {
                throw new AIConfigException( src.location() + " is not a " +
                                             "JSON object" ) ;
            }
            return root ;
        }
        catch( JsonProcessingException e ) {
            throw new AIConfigException( src.location() + " is not valid " +
                                         "JSON: " + e.getOriginalMessage(), e ) ;
        }
    }

    /** The location of the parent named {@code parent} by {@code app}. */
    private String resolve( String parent, Source app ) {

        if( parent.startsWith( CLASSPATH ) ) return parent ;

        File f = expand( parent ) ;
        if( f.isAbsolute() ) return f.getPath() ;
        if( app.dir() == null ) {
            throw error( "parent", "is the relative path '" + parent + "', " +
                         "which needs the app file on disk; use " +
                         "classpath:/... or an absolute path" ) ;
        }
        return new File( app.dir(), parent ).getPath() ;
    }

    /**
     * {@code path} with {@code ~/} expanded, and when relative, taken
     * relative to {@code src}'s folder if it is on disk.
     */
    private static File relativeTo( String path, Source src ) {

        File f = expand( path ) ;
        if( f.isAbsolute() || src.dir() == null ) return f ;
        return new File( src.dir(), path ) ;
    }

    private static File expand( String path ) {

        if( path.startsWith( "~/" ) ) {
            return new File( System.getProperty( "user.home" ),
                             path.substring( 2 ) ) ;
        }
        return new File( path ) ;
    }

    /**
     * The trimmed contents of {@code keyFile}; null if it is missing, empty
     * or unreadable (logged).
     */
    private static String readKey( File keyFile ) {

        if( !keyFile.isFile() ) return null ;
        try {
            String key = Files.readString( keyFile.toPath() ).trim() ;
            return key.isEmpty() ? null : key ;
        }
        catch( IOException e ) {
            log.error( "Could not read the API key file {}", keyFile, e ) ;
            return null ;
        }
    }

    // ---- typed values --------------------------------------------------

    private JsonNode object( JsonNode parent, String path, String key ) {

        JsonNode n = required( parent, path, key ) ;
        requireObject( n, join( path, key ) ) ;
        return n ;
    }

    private void requireObject( JsonNode n, String path ) {
        if( !n.isObject() ) throw error( path, "must be a JSON object" ) ;
    }

    private JsonNode required( JsonNode parent, String path, String key ) {

        JsonNode n = parent.get( key ) ;
        if( n == null || n.isNull() ) {
            throw error( join( path, key ), "is missing" ) ;
        }
        return n ;
    }

    private String text( JsonNode parent, String path, String key ) {

        JsonNode n = required( parent, path, key ) ;
        if( !n.isTextual() || n.asText().isBlank() ) {
            throw error( join( path, key ), "must be a non-blank string" ) ;
        }
        return n.asText().trim() ;
    }

    private boolean bool( JsonNode parent, String path, String key ) {

        JsonNode n = required( parent, path, key ) ;
        if( !n.isBoolean() ) {
            throw error( join( path, key ), "must be true or false" ) ;
        }
        return n.asBoolean() ;
    }

    private int integer( JsonNode parent, String path, String key, int min ) {

        JsonNode n = required( parent, path, key ) ;
        if( !n.isIntegralNumber() || n.asLong() < min
            || n.asLong() > Integer.MAX_VALUE ) {
            throw error( join( path, key ), "must be a whole number of at " +
                         "least " + min ) ;
        }
        return n.asInt() ;
    }

    private Duration seconds( JsonNode parent, String path, String key ) {
        return Duration.ofSeconds( integer( parent, path, key, 1 ) ) ;
    }

    private RateLimit rateLimit( JsonNode parent, String path ) {

        JsonNode n  = object( parent, path, "rateLimit" ) ;
        String   at = join( path, "rateLimit" ) ;

        checkKeys( n, at, RATE_LIMIT_KEYS ) ;
        return new RateLimit( integer( n, at, "rpm", 0 ),
                              integer( n, at, "rpd", 0 ) ) ;
    }

    private void checkKeys( JsonNode n, String path, Set<String> allowed ) {

        for( Iterator<String> it = n.fieldNames() ; it.hasNext() ; ) {
            String key = it.next() ;
            if( !allowed.contains( key ) ) {
                throw error( join( path, key ), "is not a known setting; " +
                             "expected one of " + allowed ) ;
            }
        }
    }

    private AIConfigException error( String path, String problem ) {
        return new AIConfigException( file + ": " + path + " " + problem ) ;
    }

    private static String join( String path, String key ) {
        return path.isEmpty() ? key : path + "." + key ;
    }

    private static String quote( String name ) {
        return "\"" + name + "\"" ;
    }

    private static List<String> guardNames() {

        List<String> names = new ArrayList<>() ;
        for( FailureKind k : FailureKind.values() ) {
            if( k.guard() != null ) names.add( k.guard() ) ;
        }
        names.add( DEFAULT ) ;
        return names ;
    }
}
