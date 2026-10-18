# 5. Routing policies

The config says **which** models a job may use. The routing policy says
**which job** a model takes next. That decision is your app's: it stays in
code, not in the config.

## How binding works

Jobs aren't assigned to a model when you submit them. Each model has
`maxParallel` slots. When a slot is free **and** its model may send (its
limits allow, nothing holds it back, the gateway cap has room), the hub
collects every waiting job that may run on that model and calls:

```java
Candidate pick( ModelSlot slot, List<Candidate> eligible, HubView view ) ;
```

Return one of `eligible`, or `null` to leave the slot idle for now (it asks
again when something changes).

Because binding is this late, a model that slows down frees its slots less
often, and the other models of the root naturally take more of the work.

| Type | What it tells you |
|---|---|
| [`ModelSlot`](../javadocs/com/sandy/sconsole/ai/ModelSlot.html) | the asking model (`model()`, a [`ModelInfo`](../javadocs/com/sandy/sconsole/ai/config/ModelInfo.html)), its requests in flight, its average latency over recent replies |
| [`Candidate`](../javadocs/com/sandy/sconsole/ai/Candidate.html) | a job (`job().payload()` is yours), `via` (why it may run here: `ROOT`, `ESCALATION`, `RETRY`, `HANDED_BACK`, `FALLBACK`), how long it has waited, and `order` (its queue position, lower first) |
| [`HubView`](../javadocs/com/sandy/sconsole/ai/HubView.html) | the other models: `isAvailable`, `canSendNow`, `rootModels`, `isRootAvailable`, `canRootSendNow` |

The policy is called under the hub's lock, so be quick, and never call back
into the hub from it (use `view`). If it throws, the exception is logged
and the slot takes the first candidate in order.

## Scenario: strict queue order

The default. Front-submitted jobs come first, then the rest by submission
order; a job keeps its place between attempts.

```java
new AIHub.Options( history, RoutingPolicy.inOrder(), "my-app" )
```

See [`RoutingPolicy.inOrder()`](../javadocs/com/sandy/sconsole/ai/RoutingPolicy.html).

## Scenario: one root, several models, spread the load

Nothing to write: put the models in one root.

```json
"roots" : {
  "main" : [ "google/gemma-4-31b-it:free", "nvidia/nemotron-3.5-super-vl:free" ]
}
```

Each model pulls work as its slots free up. A fast model or one with more
`maxParallel` takes more jobs, and a slow one fewer. If you want to steer
away from a model that is getting slow, return `null` from its slot:

```java
RoutingPolicy avoidSlow = ( slot, eligible, view ) -> {
    boolean slow = slot.avgLatencyMs() > 60_000 ;
    boolean othersFree = view.rootModels( "main" ).stream()
            .filter( id -> !id.equals( slot.model().id() ) )
            .anyMatch( view::canSendNow ) ;

    if( slow && othersFree ) return null ;        // let a faster model take it
    return RoutingPolicy.inOrder().pick( slot, eligible, view ) ;
} ;
```

## Scenario: two roots with preferences (local vs cloud)

The QImgClassifier app has a `local` root (Gemma on Ollama) and a `cloud`
root (OpenRouter). Every question may start on either, and the policy
shares them out:

- Chemistry goes to the cloud. A local model takes it only while every cloud
  model is held back.
- Physics and Maths go to the local models. A cloud slot takes them only
  when nothing better waits for it and no local model can send right now.
- Continuations (retries, handed-back jobs, fallbacks) and escalations rank
  ahead of new jobs.

```java
public class SubjectRouting implements RoutingPolicy {

    static final String LOCAL = "local", CLOUD = "cloud" ;

    /** The roots a job is submitted with. */
    static List<String> roots( Question q ) {
        return q.isChemistry() ? List.of( CLOUD, LOCAL )
                               : List.of( LOCAL, CLOUD ) ;
    }

    @Override
    public Candidate pick( ModelSlot slot, List<Candidate> eligible,
                           HubView view ) {

        boolean onLocal = view.rootModels( LOCAL )
                              .contains( slot.model().id() ) ;
        boolean localUp = view.isRootAvailable( LOCAL ) ;
        boolean cloudUp = view.isRootAvailable( CLOUD ) ;

        Candidate best     = null ;
        int       bestRank = Integer.MAX_VALUE ;

        for( Candidate c : eligible ) {
            int rank = localUp ? rank( c, onLocal, cloudUp, view ) : 0 ;
            if( rank < 0 ) continue ;                     // not on this slot
            if( rank < bestRank
                || ( rank == bestRank && c.order() < best.order() ) ) {
                best     = c ;
                bestRank = rank ;
            }
        }
        return best ;
    }

    private static int rank( Candidate c, boolean onLocal, boolean cloudUp,
                             HubView view ) {

        boolean chemistry = ( (Question) c.job().payload() ).isChemistry() ;

        if( c.via() == Candidate.Via.ESCALATION ) return 2 ;
        if( c.isContinuation() ) return chemistry || onLocal ? 0 : 3 ;
        if( onLocal ) return chemistry && cloudUp ? -1 : 1 ;
        if( chemistry ) return 1 ;
        return view.canRootSendNow( LOCAL ) ? -1 : 4 ;
    }
}
```

Submit with the job's preferred roots:

```java
hub.submit( new AIJob<>( question, SubjectRouting.roots( question ),
                         factory, keepGoing, listener ) ) ;
```

The full version is `ClassifierRouting` in QImgClassifier.

## What the policy can't do

- It can't send a job to a model the config doesn't allow for it. `eligible`
  only holds jobs that may run on the asking model: a model of their
  roots, their current retry model, or the first available model of their
  fallback guard.
- It can't override limits: a held-back or pacing model's slots don't ask
  at all.
