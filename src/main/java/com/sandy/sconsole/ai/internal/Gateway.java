package com.sandy.sconsole.ai.internal;

import com.sandy.sconsole.ai.AIReply;
import com.sandy.sconsole.ai.AIRequest;
import com.sandy.sconsole.ai.FailureKind;
import com.sandy.sconsole.ai.config.GatewayInfo;
import com.sandy.sconsole.ai.config.ModelInfo;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The transport to one gateway: sends a single request to one of its models
 * and returns the raw reply. Thread-safe: the gateway's models send
 * concurrently.
 */
abstract class Gateway {

    /** The reason of an attempt aborted with {@link Call#abort}. */
    static final String ABORTED = "request terminated by the user" ;

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
     * @param call the request in flight, to abort it with
     * @throws GatewayException with a short reason and its kind when the
     *         request fails; {@link GatewayException.RateLimited} for a rate
     *         limit, {@link GatewayException.Unreachable} when the gateway
     *         can't be reached
     */
    abstract AIReply complete( ModelInfo model, AIRequest request, Call call )
            throws GatewayException ;

    /**
     * Send {@code request} and read its whole reply within {@code timeout}.
     * {@link HttpRequest#timeout} alone only bounds the wait for the reply's
     * headers: a gateway that answers at once and then keeps the connection
     * alive while the model works (OpenRouter sends whitespace) would never
     * time out.
     *
     * @throws HttpTimeoutException when the whole reply took too long
     * @throws GatewayException     when {@code call} was aborted
     */
    protected HttpResponse<String> send( HttpRequest request,
                                         Duration timeout, Call call )
            throws Exception {

        CompletableFuture<HttpResponse<String>> exchange = httpClient.sendAsync(
                request, HttpResponse.BodyHandlers.ofString() ) ;
        call.attach( exchange ) ;
        try {
            return exchange.get( timeout.toMillis(), TimeUnit.MILLISECONDS ) ;
        }
        catch( TimeoutException e ) {
            exchange.cancel( true ) ;
            throw new HttpTimeoutException( "no complete reply in " +
                                            timeout.toSeconds() + " s" ) ;
        }
        catch( CancellationException e ) {
            if( !call.isAborted() ) throw e ;
            throw new GatewayException( FailureKind.TIMEOUT, ABORTED, null ) ;
        }
        catch( InterruptedException e ) {
            exchange.cancel( true ) ;
            throw e ;
        }
        catch( ExecutionException e ) {
            if( call.isAborted() ) {
                throw new GatewayException( FailureKind.TIMEOUT, ABORTED,
                                            null ) ;
            }
            throw e.getCause() instanceof Exception cause ? cause : e ;
        }
    }

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
