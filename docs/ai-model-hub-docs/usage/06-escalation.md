# 6. Escalation: a second opinion

Sometimes a reply is usable but not convincing: a cheap model answered with
low confidence, say. Instead of rejecting it, your `check` can **escalate**:
keep the reply as a provisional result and ask the models of another root.

```java
ReplyCheck.escalate( value, "cloud", "top confidence below 90" )
```

See [`ReplyCheck.Escalate`](../javadocs/com/sandy/sconsole/ai/ReplyCheck.Escalate.html).

## What happens

1. The attempt counts as a success (`AttemptResult.Outcome.OK`), and
   `value` is kept as the job's **provisional** result.
2. The job waits for the models of the escalation root. The model that
   replied is not used again. `JobContext.escalationRoot()` now names the
   root, and slots see the job with `Candidate.Via.ESCALATION`.
3. The first escalation model whose reply your `check` **accepts** gives the
   result: `succeeded( job, value, model, false )`.
4. If no escalation model gives an accepted reply (they fail for good and
   nothing is left, or the job is stopped), the provisional result is used:
   `succeeded( job, provisional, firstModel, true )`. The `true` flag means
   provisional.
5. If the escalation root has no usable model at all, the provisional
   result is taken at once.

Escalation models follow their own retries and fallbacks as usual. Rate
limits on them are waited out, so a provisional result can wait a long time
(until 00:00 UTC after a daily limit) unless you stop the job.

## Example: escalate weak local answers

```java
@Override
public ReplyCheck<List<Answer>> check( JobContext<Question> job,
                                       Attempt attempt, AIReply reply ) {

    List<Answer> answers = parse( reply.text() ) ;   // throws → reject
    boolean      local   = attempt.model().gateway().type()
                           == GatewayType.OLLAMA ;

    if( local && job.escalationRoot() == null && !confident( answers ) ) {
        return ReplyCheck.escalate( answers, "cloud",
                                    "the local answer isn't confident" ) ;
    }
    return ReplyCheck.accept( answers ) ;
}

@Override
public void succeeded( JobContext<Question> job, List<Answer> answers,
                       ModelInfo model, boolean provisional ) {
    // provisional: the cloud gave no answer, so this is the local reply
    store( job.payload(), answers, model.id(), provisional ) ;
}
```

Check `job.escalationRoot() == null` so that an escalated job is never
escalated again.

## Routing escalations

Escalated jobs reach your [routing policy](05-routing-policies.md) as
candidates with `via() == ESCALATION`. Rank them to suit. QImgClassifier
puts second opinions after continuations and Chemistry, but ahead of new
Physics and Maths questions on cloud slots.
