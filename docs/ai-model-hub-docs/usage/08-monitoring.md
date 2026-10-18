# 8. Monitoring: status, limits, attempts, costs and logs

## Status snapshots

[`AIHub.status()`](../javadocs/com/sandy/sconsole/ai/AIHub.html) returns an
immutable [`HubStatus`](../javadocs/com/sandy/sconsole/ai/HubStatus.html).
It is cheap enough to poll every second, e.g. from a Swing `Timer`:

```java
new javax.swing.Timer( 1000, e -> {
    HubStatus s = hub.status() ;
    HubStatus.Jobs j = s.jobs() ;

    queueLabel.setText( j.done() + " done, " + j.running() + " running, "
                        + j.waiting() + " waiting" ) ;

    long waitMs = s.nextSendAt() - System.currentTimeMillis() ;
    paceLabel.setText( s.nextSendAt() == Long.MAX_VALUE ? ""
                       : waitMs <= 0 ? "next request: now"
                       : "next request in " + ( waitMs + 999 ) / 1000 + " s" ) ;
} ).start() ;
```

| Part | Holds |
|---|---|
| `gateways()` | [`GatewayStatus`](../javadocs/com/sandy/sconsole/ai/HubStatus.GatewayStatus.html): enabled, reachable, held until / why, held for the day, requests today, in flight vs cap |
| `models()` | [`ModelStatus`](../javadocs/com/sandy/sconsole/ai/HubStatus.ModelStatus.html): usable, available, held until / why, in flight vs slots, jobs waiting for it, average latency, attempts succeeded and failed |
| `jobs()` | [`Jobs`](../javadocs/com/sandy/sconsole/ai/HubStatus.Jobs.html): waiting, held, running, done |
| `nextSendAt()` | when some model with waiting work may next send (`Long.MAX_VALUE` if no work waits) |
| `notices()` | what is held back right now, and why |

## Showing rate-limit holds

[`Notice`](../javadocs/com/sandy/sconsole/ai/HubStatus.Notice.html) lists
each gateway (model `null`) or single model that is held back, with a
readable reason, when it lifts, and whether it is a daily hold. Per-minute
pacing is routine and is never a notice.

```java
DateTimeFormatter hhmm = DateTimeFormatter.ofPattern( "HH:mm" )
                                          .withZone( ZoneId.systemDefault() ) ;
for( HubStatus.Notice n : hub.status().notices() ) {
    String who = n.model() == null ? n.gateway()
                                   : hub.config().displayName( n.model() ) ;
    System.out.printf( "%s held: %s (until %s)%s%n", who, n.reason(),
                       hhmm.format( Instant.ofEpochMilli( n.until() ) ),
                       n.daily() ? " [daily]" : "" ) ;
}
```

An unattended run can stop itself once a daily hold appears:

```java
boolean dailyHold = hub.status().notices().stream()
                       .anyMatch( HubStatus.Notice::daily ) ;
```

## Following attempts

The listener's attempt callbacks see every request:

```java
@Override
public void attemptStarted( JobContext<Question> job, Attempt a ) {
    log.info( "[{}] attempt {} on {}", job.payload(), a.number(),
              a.model().label() ) ;
}

@Override
public void attemptFinished( JobContext<Question> job, AttemptResult r ) {
    saveTranscript( job.payload(), r.attempt().request(), r.response() ) ;
    if( !r.isSuccess() ) {
        log.warn( "[{}] {} ({}): {}", job.payload(), r.outcome(), r.kind(),
                  r.failure() ) ;
    }
}
```

| Type | Holds |
|---|---|
| [`Attempt`](../javadocs/com/sandy/sconsole/ai/Attempt.html) | job id, attempt number (handed-back attempts aren't counted, so the next one reuses the number), the model, the exact request, the start time |
| [`AttemptResult`](../javadocs/com/sandy/sconsole/ai/AttemptResult.html) | elapsed ms; outcome `OK` / `FAILED` / `RATE_LIMITED` / `UNREACHABLE`; the raw reply or raw error body; a readable failure reason; the [`FailureKind`](../javadocs/com/sandy/sconsole/ai/FailureKind.html); token usage |

## Token usage and cost

[`TokenUsage`](../javadocs/com/sandy/sconsole/ai/TokenUsage.html) is on
`AIReply.usage()` and `AttemptResult.usage()`. It holds `inputTokens`,
`outputTokens`, `reasoningTokens`, `cachedTokens` and `cost` (USD).
Optional values are `null` when the gateway didn't report them, and the
whole usage is `null` when nothing was reported. Failed attempts can carry
usage too, e.g. a reply that broke off.

```java
@Override
public void attemptFinished( JobContext<Question> job, AttemptResult r ) {
    TokenUsage u = r.usage() ;
    if( u != null ) {
        usageCsv.append( r.attempt().model().id(), u.inputTokens(),
                         u.outputTokens(), u.totalTokens(), u.cost() ) ;
    }
}
```

## Logging

The hub logs through SLF4J on these loggers:

| Logger | Level | What |
|---|---|---|
| `ai.config` | INFO | the loaded config: files, gateways, models with limits, slots, retries and guards, roots |
| `ai.config` | WARN | fallbacks naming a model that can't be used |
| `ai.engine` | INFO | each job's binding, every retry / fallback with its guard and reason, escalations, the job's end; holds, unreachable / reachable again, daily limits |
| `ai.engine` | DEBUG | slot pulls and limiter waits |
| `ai.gateway.<name>` | WARN / ERROR | 429s and transport errors from that gateway |

Lines are tagged `[job <id>]` and `[<gateway>/<model>]`, so one job can be
followed with grep. For example, in `logback.xml`:

```xml
<logger name="ai.engine" level="INFO"/>
<logger name="ai.gateway" level="WARN"/>
```
