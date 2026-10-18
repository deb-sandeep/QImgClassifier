# 2. Quick start: one prompt, one answer

The hub's API is asynchronous: you submit a job and hear about it on a
listener. To make a blocking call, complete a `CompletableFuture` from the
listener.

## Steps

1. Load the config ([Configuration](01-configuration.md)).
2. Create one [`AIHub`](../javadocs/com/sandy/sconsole/ai/AIHub.html) per
   application and keep it for the app's lifetime.
3. Submit an [`AIJob`](../javadocs/com/sandy/sconsole/ai/AIJob.html) with a
   [`JobListener`](../javadocs/com/sandy/sconsole/ai/JobListener.html).
4. Shut the hub down when the app exits.

## Example

```java
import com.sandy.sconsole.ai.* ;
import com.sandy.sconsole.ai.config.AIConfig ;
import com.sandy.sconsole.ai.config.AIConfigLoader ;
import com.sandy.sconsole.ai.config.ModelInfo ;

import java.io.File ;
import java.util.List ;
import java.util.concurrent.CompletableFuture ;

public class Ask {

    public static void main( String[] args ) throws Exception {

        AIConfig config = AIConfigLoader.load( "classpath:/my-app.ai.json" ) ;
        File     history = new File( "request.history.json" ) ;

        try( AIHub hub = AIHub.create( config, new AIHub.Options(
                history, RoutingPolicy.inOrder(), "my-app" ) ) ) {

            System.out.println( ask( hub, "Name three noble gases." ) ) ;
        }
    }

    /** Send {@code prompt} to the "main" root and wait for the answer. */
    static String ask( AIHub hub, String prompt ) throws Exception {

        CompletableFuture<String> answer = new CompletableFuture<>() ;
        AIRequest                 request = new AIRequest( prompt, List.of() ) ;

        JobListener<Void,String> listener = new JobListener<>() {

            @Override
            public ReplyCheck<String> check( JobContext<Void> job,
                                             Attempt attempt, AIReply reply ) {
                return ReplyCheck.accept( reply.text() ) ;
            }

            @Override
            public void succeeded( JobContext<Void> job, String value,
                                   ModelInfo model, boolean provisional ) {
                answer.complete( value ) ;
            }

            @Override
            public void failed( JobContext<Void> job, String reason,
                                FailureKind kind ) {
                answer.completeExceptionally(
                        new RuntimeException( kind + ": " + reason ) ) ;
            }

            @Override
            public void dropped( JobContext<Void> job ) {
                answer.cancel( false ) ;
            }
        } ;

        hub.submit( new AIJob<>( null, List.of( "main" ),
                                 model -> request, () -> true, listener ) ) ;
        return answer.get() ;
    }
}
```

## What each part does

| Part | Notes |
|---|---|
| `AIHub.Options( historyFile, routing, appName )` | `historyFile` keeps today's request times so per-day limits survive a restart (`null` keeps them in memory only). `routing` defaults to [`RoutingPolicy.inOrder()`](../javadocs/com/sandy/sconsole/ai/RoutingPolicy.html) when null. `appName` is sent to gateways that attribute traffic to an app (OpenRouter's `X-Title`). See [`AIHub.Options`](../javadocs/com/sandy/sconsole/ai/AIHub.Options.html). |
| `new AIJob<>( payload, roots, request, keepGoing, listener )` | `payload` is your own object, handed back in every callback as `job.payload()`. `roots` are the roots it may start on. `request` builds the request for each attempt (see below). `keepGoing` is checked before each attempt: return `false` to stop the job ([Job control](07-job-control.md)). |
| `check(…)` | **Required.** Decides what a reply means. Here every reply is accepted; see [Checking replies](03-checking-replies.md). |
| `succeeded` / `failed` / `dropped` | Exactly one of them ends every job. |

## Building requests

[`AIJob.RequestFactory`](../javadocs/com/sandy/sconsole/ai/AIJob.RequestFactory.html)
is called before **every** attempt with the model about to be asked, so a
prompt can depend on the model. It is called again on retries, so cache
anything costly, such as encoded images:

```java
byte[]          png   = Files.readAllBytes( Path.of( "question.png" ) ) ;
AIRequest.Image image = AIRequest.Image.of( png, "image/png" ) ; // encoded once

AIJob.RequestFactory factory = model -> new AIRequest(
        model.gateway().type() == GatewayType.OLLAMA ? SHORT_PROMPT : PROMPT,
        List.of( image ) ) ;
```

Gateways send the prompt first and the images after it. A prompt shared by
many requests is therefore a stable prefix, which prompt caching can reuse
(see `promptCaching` in [Configuration](01-configuration.md)). See
[`AIRequest`](../javadocs/com/sandy/sconsole/ai/AIRequest.html) and
[`AIRequest.Image`](../javadocs/com/sandy/sconsole/ai/AIRequest.Image.html).

If the factory throws, the job fails.

## Threading rules

- Listener calls arrive on the hub's worker threads. Calls for one job come
  one at a time and in order; different jobs run concurrently. Make
  listeners thread-safe, and hand UI updates to your UI thread (e.g.
  `SwingUtilities.invokeLater`).
- An exception thrown by a listener callback is logged and ignored, except
  in `check`, where throwing rejects the reply.
- `submit` never blocks. If none of a job's roots has a usable model, the
  job fails at once: `failed` is called before `submit` returns. An unknown
  root name throws `IllegalArgumentException`.

## Shutting down

`AIHub` is `AutoCloseable`. [`shutdown()`](../javadocs/com/sandy/sconsole/ai/AIHub.html)
stops the workers and interrupts requests in flight. To let work finish
first, stop submitting and wait for your jobs to end (see
[Job control](07-job-control.md)).
