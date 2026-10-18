package com.sandy.sconsole.ai.config;

import java.time.Duration;

/**
 * A model as an app uses it: the global definition (where it is hosted, the
 * provider's limits) merged with the app's policy for it.
 *
 * @param id                the model id sent to the gateway
 * @param gateway           the gateway hosting it
 * @param displayName       the name to show it by; null to show the id
 * @param enabledGlobally   its switch in the global config
 * @param enabledInApp      its switch in the app config
 * @param contextTokens     the context window to ask for (Ollama's
 *                          {@code num_ctx}); null for the server's default
 * @param providerRateLimit the provider's limits on this model
 * @param appRateLimit      the app's own limits on this model
 * @param maxParallel       the app's requests in flight on it at most
 * @param requestTimeout    the longest a whole request may take
 * @param retries           the attempts a job gets on it after a failed one,
 *                          before following its fallback
 * @param reasoning         let it reason before answering
 * @param promptCaching     mark the prompt cacheable where the model
 *                          supports it
 * @param fallback          where a job goes after it fails the job for good
 */
public record ModelInfo( String id, GatewayInfo gateway, String displayName,
                         boolean enabledGlobally, boolean enabledInApp,
                         Integer contextTokens, RateLimit providerRateLimit,
                         RateLimit appRateLimit, int maxParallel,
                         Duration requestTimeout, int retries,
                         boolean reasoning, boolean promptCaching,
                         FallbackInfo fallback ) {

    /**
     * True if the model can be sent requests: it is switched on in the app
     * and globally, its gateway is on, and the gateway has its credentials.
     */
    public boolean usable() { return whyUnusable() == null ; }

    /** Why the model is not {@link #usable()}; null if it is. */
    public String whyUnusable() {

        if( !enabledInApp ) return "it is disabled in the app config" ;
        if( !enabledGlobally ) return "it is disabled in the global config" ;
        if( !gateway.enabled() ) {
            return "its gateway '" + gateway.name() + "' is disabled" ;
        }
        if( !gateway.hasCredentials() ) {
            return "its gateway '" + gateway.name() + "' has no API key (" +
                   gateway.apiKeyFile() + ")" ;
        }
        return null ;
    }

    /** The provider's and the app's limits together. */
    public RateLimit rateLimit() {
        return providerRateLimit.and( appRateLimit ) ;
    }

    /** The display name, else the id. */
    public String label() { return displayName != null ? displayName : id ; }

    /** {@code <gateway>/<model id>}, the tag used in logs. */
    public String tag() { return gateway.name() + "/" + id ; }

    @Override
    public String toString() {

        String state = usable() ? "usable" : "UNUSABLE: " + whyUnusable() ;

        return tag() + " (" + state + ", " + maxParallel + " parallel, " +
               "model limit: " + rateLimit() + ", timeout " +
               requestTimeout.toSeconds() + "s, " + retries +
               ( retries == 1 ? " retry" : " retries" ) +
               ", reasoning " + ( reasoning ? "on" : "off" ) +
               ( promptCaching ? ", prompt caching" : "" ) +
               ", fallback " + fallback + ")" ;
    }
}
