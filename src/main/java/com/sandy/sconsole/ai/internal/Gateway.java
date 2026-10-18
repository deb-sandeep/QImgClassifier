package com.sandy.sconsole.ai.internal;

import com.sandy.sconsole.ai.AIReply;
import com.sandy.sconsole.ai.AIRequest;
import com.sandy.sconsole.ai.config.GatewayInfo;
import com.sandy.sconsole.ai.config.ModelInfo;

import java.net.http.HttpClient;

/**
 * The transport to one gateway: sends a single request to one of its models
 * and returns the raw reply. Thread-safe: the gateway's models send
 * concurrently.
 */
abstract class Gateway {

    protected final GatewayInfo info ;
    protected final HttpClient  httpClient ;

    protected Gateway( GatewayInfo info ) {

        this.info       = info ;
        this.httpClient = HttpClient.newBuilder()
                                    .connectTimeout( info.connectTimeout() )
                                    .build() ;
    }

    /**
     * Send {@code request} to {@code model}.
     *
     * @throws GatewayException with a short reason and its kind when the
     *         request fails; {@link GatewayException.RateLimited} for a rate
     *         limit, {@link GatewayException.Unreachable} when the gateway
     *         can't be reached
     */
    abstract AIReply complete( ModelInfo model, AIRequest request )
            throws GatewayException ;

    /**
     * The transport for {@code info}.
     *
     * @param appName sent to gateways that attribute requests to an app
     *        (OpenRouter's {@code X-Title}); may be null
     */
    static Gateway of( GatewayInfo info, String appName ) {
        return switch( info.type() ) {
            case OPENROUTER -> new OpenRouterGateway( info, appName ) ;
            case OLLAMA     -> new OllamaGateway( info ) ;
        } ;
    }
}
