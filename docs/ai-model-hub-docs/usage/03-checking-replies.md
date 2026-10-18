# 3. Checking replies

Every reply goes through your listener's
[`check`](../javadocs/com/sandy/sconsole/ai/JobListener.html) before the hub
does anything with it. `check` returns a
[`ReplyCheck<R>`](../javadocs/com/sandy/sconsole/ai/ReplyCheck.html), where
`R` is your result type:

| Verdict | Effect |
|---|---|
| `ReplyCheck.accept( value )` | `value` is the job's result: `succeeded( job, value, model, false )`. |
| `ReplyCheck.reject( reason )` | The attempt fails as [`FailureKind.BAD_REPLY`](../javadocs/com/sandy/sconsole/ai/FailureKind.html). The job retries on the same model, or follows that model's `badReply` (else `default`) fallback, like any other failure. |
| `ReplyCheck.escalate( value, root, why )` | The reply will do, but another root should be asked. See [Escalation](06-escalation.md). |

A `check` that throws a `RuntimeException` (logged at ERROR) or returns
`null` also rejects the reply, with the reason "the reply could not be
checked: …" or "the reply was not checked". Return an explicit `reject` with
a readable reason instead.

## Example: parse JSON into a typed result

```java
record Answer( String topicId, int confidence ) {}

class TopicListener implements JobListener<Question,List<Answer>> {

    private static final ObjectMapper MAPPER = new ObjectMapper() ;

    @Override
    public ReplyCheck<List<Answer>> check( JobContext<Question> job,
                                           Attempt attempt, AIReply reply ) {
        try {
            List<Answer> answers = MAPPER.readValue(
                    stripFences( reply.text() ),
                    new TypeReference<List<Answer>>() {} ) ;

            if( answers.isEmpty() ) {
                return ReplyCheck.reject( "the model named no topic" ) ;
            }
            return ReplyCheck.accept( answers ) ;
        }
        catch( JsonProcessingException e ) {
            return ReplyCheck.reject( "the reply was not a topic list" ) ;
        }
    }

    @Override
    public void succeeded( JobContext<Question> job, List<Answer> answers,
                           ModelInfo model, boolean provisional ) {
        store( job.payload(), answers, model.id() ) ;
    }

    @Override
    public void failed( JobContext<Question> job, String reason,
                        FailureKind kind ) {
        markFailed( job.payload(), reason ) ;
    }
}
```

## Good practice

- **Reject, don't fix up, a reply you can't trust.** A rejection uses one of
  the model's `retries`. After that the job follows the `badReply` guard, so
  you can send unparsable replies to a model that follows instructions
  better:

  ```json
  "fallback" : {
    "badReply" : [ "anthropic/claude-haiku-5.5" ]
  }
  ```

- **Keep the reason short and readable.** It becomes
  `AttemptResult.failure()` and, on the last attempt, the `reason` passed to
  `failed`. It is fine to show it to users.
- **Use `attempt.model()`** if the parsing depends on the model.
- `check` runs on a hub worker thread while that model's slot is still
  taken, so keep it quick.

## What you get alongside the reply

[`AIReply`](../javadocs/com/sandy/sconsole/ai/AIReply.html) carries `text()`
and `usage()`: a [`TokenUsage`](../javadocs/com/sandy/sconsole/ai/TokenUsage.html)
with input, output, reasoning and cached tokens and the cost in USD, or
`null` if the gateway didn't report it. The same usage is in the attempt's
[`AttemptResult`](../javadocs/com/sandy/sconsole/ai/AttemptResult.html),
reported to `attemptFinished` (see [Monitoring](08-monitoring.md)).
