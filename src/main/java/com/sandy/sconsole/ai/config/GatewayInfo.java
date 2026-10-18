package com.sandy.sconsole.ai.config;

import java.io.File;
import java.time.Duration;

/**
 * A gateway as the global config defines it: where models are hosted and
 * what the provider enforces.
 *
 * @param name           the gateway's name in the config
 * @param type           the transport it uses
 * @param enabled        false switches it off with all its models
 * @param url            the endpoint requests are sent to
 * @param apiKeyFile     the file holding the API key; null if none is
 *                       configured
 * @param apiKey         the key read from {@code apiKeyFile}; null if there
 *                       is none, or the file is missing or empty
 * @param connectTimeout the longest wait for a connection
 * @param cooldown       how long to hold back after a rate limit that
 *                       doesn't say how long, or after failing to connect
 * @param maxParallel    the provider's cap on requests in flight across the
 *                       gateway's models; 0 for none
 * @param rateLimit      the provider's limits across the gateway's models
 */
public record GatewayInfo( String name, GatewayType type, boolean enabled,
                           String url, File apiKeyFile, String apiKey,
                           Duration connectTimeout, Duration cooldown,
                           int maxParallel, RateLimit rateLimit ) {

    /** True unless the transport needs an API key and there is none. */
    public boolean hasCredentials() {
        return type != GatewayType.OPENROUTER || apiKey != null ;
    }

    /** Leaves the API key out. */
    @Override
    public String toString() {

        return name + " (" + type + ", " + ( enabled ? "on" : "OFF" ) +
               ", " + url + ", " + rateLimit +
               ( maxParallel > 0 ? ", max " + maxParallel + " in flight" : "" ) +
               ( hasCredentials() ? "" : ", NO API KEY" ) + ")" ;
    }
}
