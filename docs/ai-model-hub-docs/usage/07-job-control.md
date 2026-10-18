# 7. Job control: batches, stop, hold, cancel, re-queue

## Submitting at the front

```java
hub.submit( job, Priority.FRONT ) ;   // ahead of every waiting job
hub.submit( job ) ;                   // Priority.NORMAL: behind them
```

`FRONT` suits work a user asked for explicitly, such as "retry this one now".
Submitting several jobs `FRONT` puts the **last** one first, so submit them
in reverse to keep their order. See
[`Priority`](../javadocs/com/sandy/sconsole/ai/Priority.html). Routing
policies see the queue position as
[`Candidate.order()`](../javadocs/com/sandy/sconsole/ai/Candidate.html).

## Running a batch and waiting for it

The hub has no batch concept; count your jobs' endings instead. Every job
ends with exactly one of `succeeded`, `failed` or `dropped`.

```java
List<Question>  batch = loadQuestions() ;
CountDownLatch  done  = new CountDownLatch( batch.size() ) ;

JobListener<Question,List<Answer>> listener = new TopicListener() {
    @Override public void succeeded( JobContext<Question> j, List<Answer> a,
                                     ModelInfo m, boolean p ) {
        super.succeeded( j, a, m, p ) ;
        done.countDown() ;
    }
    @Override public void failed( JobContext<Question> j, String r,
                                  FailureKind k ) {
        super.failed( j, r, k ) ;
        done.countDown() ;
    }
    @Override public void dropped( JobContext<Question> j ) {
        done.countDown() ;
    }
} ;

for( Question q : batch ) {
    hub.submit( new AIJob<>( q, List.of( "main" ), factory( q ),
                             running::get, listener ) ) ;
}
done.await() ;
```

## Stopping: `keepGoing`

Each job carries a `BooleanSupplier keepGoing`, checked before each attempt
and whenever the job is handed back to the queue. Share one flag across a
batch to stop it all:

```java
AtomicBoolean running = new AtomicBoolean( true ) ;
// … submit jobs with running::get …
running.set( false ) ;   // Stop
```

Once it returns `false`:

| Job state | Result |
|---|---|
| waiting, never started | dropped: `dropped( job )` |
| started (between attempts) | ends with its provisional result if it has one (`succeeded(…, true)`), else `failed` with its last failure |
| a request in flight | the request finishes; then as above |

Stopping never interrupts a request in flight. Only
[`AIHub.shutdown()`](../javadocs/com/sandy/sconsole/ai/AIHub.html) does that.

## Holding, releasing and cancelling one job

`submit` returns a
[`JobHandle`](../javadocs/com/sandy/sconsole/ai/JobHandle.html):

```java
JobHandle h = hub.submit( job ) ;

if( h.hold() ) {             // only while waiting and not started
    // e.g. a user is handling it by hand
    if( userGaveUp ) h.release( true ) ;  // back in, at the front
    else             h.cancel() ;         // ends it: listener.dropped(…)
}
JobHandle.State s = h.state() ;           // WAITING, HELD, RUNNING, DONE
```

| Method | Works when | Effect |
|---|---|---|
| `hold()` | waiting, not started | taken out of scheduling (`HELD`) |
| `release( front )` | held | back among the waiting, at the front or behind |
| `cancel()` | held, or waiting and not started | ends the job; `dropped` is called |

Each returns `false` when the job isn't in a state it applies to.

## Re-queueing failed work

A failed job is over. To try again, submit a new `AIJob` with the same
payload, usually at the front:

```java
List<Question> failed = failedQuestions() ;          // in display order
for( int i = failed.size() - 1 ; i >= 0 ; i-- ) {     // reverse, so FRONT
    Question q = failed.get( i ) ;                    // keeps the order
    hub.submit( new AIJob<>( q, List.of( "main" ), factory( q ),
                             running::get, listener ), Priority.FRONT ) ;
}
```

The new job starts with a fresh retry budget on every model.

## Job counts

[`hub.status().jobs()`](../javadocs/com/sandy/sconsole/ai/HubStatus.Jobs.html)
gives `waiting` (including jobs between attempts), `held`, `running` and
`done` (jobs that ended after starting). It is handy for a queue display.
See [Monitoring](08-monitoring.md).
