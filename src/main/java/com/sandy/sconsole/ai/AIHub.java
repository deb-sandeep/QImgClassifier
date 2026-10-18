package com.sandy.sconsole.ai;

import com.sandy.sconsole.ai.config.AIConfig;
import com.sandy.sconsole.ai.config.AIConfigLoader;
import com.sandy.sconsole.ai.internal.Engine;

import java.io.File;

/**
 * The entry point of the AI model hub: sends an app's jobs to the models of
 * its {@link AIConfig}, binding each job to a model as late as possible,
 * with per-model slots, provider and app rate limits, retries and guarded
 * fallbacks, and reports through each job's {@link JobListener}.
 * <pre>
 * AIConfig config = AIConfigLoader.load( "classpath:/app.ai.json" ) ;
 * AIHub    hub    = AIHub.create( config, new AIHub.Options(
 *                           historyFile, RoutingPolicy.inOrder(), "my-app" ) ) ;
 * JobHandle h = hub.submit( new AIJob&lt;&gt;( payload, List.of( "main" ),
 *                           model -&gt; request, () -&gt; true, listener ) ) ;
 * </pre>
 * Logs on {@code ai.config} (the configuration, at load), {@code ai.engine}
 * (each job's binding, attempts, retries, fallbacks, escalations and end;
 * limits and state changes) and {@code ai.gateway.<name>} (transport
 * errors). Thread-safe.
 *
 * @see AIConfigLoader
 */
public final class AIHub implements AutoCloseable {

    /**
     * How a hub runs.
     *
     * @param historyFile where request times behind the daily limits are
     *                    kept across restarts; null to keep them in memory
     * @param routing     picks a job for a free model slot; null for
     *                    {@link RoutingPolicy#inOrder()}
     * @param appName     sent to gateways that attribute requests to an app
     *                    (OpenRouter's {@code X-Title}); may be null
     */
    public record Options( File historyFile, RoutingPolicy routing,
                           String appName ) {

        public Options {
            if( routing == null ) routing = RoutingPolicy.inOrder() ;
        }
    }

    private final Engine engine ;

    private AIHub( Engine engine ) {
        this.engine = engine ;
    }

    /** A hub for {@code config}, its workers started. */
    public static AIHub create( AIConfig config, Options options ) {

        Engine engine = new Engine( config, options.historyFile(),
                                    options.routing(), options.appName() ) ;
        engine.start() ;
        return new AIHub( engine ) ;
    }

    public AIConfig config() { return engine.config() ; }

    /** Queue {@code job} behind the jobs waiting. */
    public <P,R> JobHandle submit( AIJob<P,R> job ) {
        return submit( job, Priority.NORMAL ) ;
    }

    /**
     * Queue {@code job}. A job none of whose roots has a usable model fails
     * at once: its listener's {@link JobListener#failed} is called before
     * this returns.
     *
     * @throws IllegalArgumentException if a root is not in the config
     */
    public <P,R> JobHandle submit( AIJob<P,R> job, Priority priority ) {
        return engine.submit( job, priority ) ;
    }

    /** A snapshot of the gateways, models and jobs. */
    public HubStatus status() { return engine.status() ; }

    /** Stop the workers, interrupting requests in flight. */
    public void shutdown() { engine.shutdown() ; }

    @Override
    public void close() { shutdown() ; }
}
