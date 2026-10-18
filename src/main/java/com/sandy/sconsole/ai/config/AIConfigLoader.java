package com.sandy.sconsole.ai.config;

/**
 * Loads an app's AI configuration: its app file and the global file the app
 * file names as its {@code parent}.
 * <p>
 * A location is either {@code classpath:/path/to/file.json}, a classpath
 * resource, or a file path; {@code ~/} is expanded to the home directory.
 * A relative {@code parent} is taken relative to the app file's folder, so
 * it needs the app file to be on disk. Both files are JSON (comments
 * allowed). See {@code ConfigParser} for the rules, which are checked on
 * load: a missing or invalid file, a missing required value, an unknown
 * key, a reference to an undefined model, or a root model the app enables
 * that cannot be used (its gateway or the model switched off globally, or
 * no API key) is an {@link AIConfigException} naming the file and the JSON
 * path. The loaded configuration is logged at INFO on the {@code ai.config}
 * logger.
 */
public final class AIConfigLoader {

    private AIConfigLoader() {}

    /**
     * Read and validate the app file at {@code location} and its parent.
     *
     * @throws AIConfigException if a file is missing or invalid, or a rule
     *         is broken
     */
    public static AIConfig load( String location ) {
        return new ConfigParser().load( location ) ;
    }
}
