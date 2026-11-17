# 3. Checking replies

Every reply goes through your listener's
`check` before the hub
does anything with it. `check` returns a
`ReplyCheck<R>`, where
`R` is your result type:

| Verdict | Effect |
|---|---|
| `ReplyCheck.accept( value )` | `value` is the job's result: `succeeded( job, value, model, false )`. |
| `ReplyCheck.reject( reason )` | The attempt fails as `FailureKind.BAD_REPLY`. The job retries on the same model, or follows that model's `badReply` (else `default`) fallback, like any other failure. |
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

`AIReply` carries `text()`,
`reasoning()` (the model's reasoning when the gateway returned it:
OpenRouter's `message.reasoning` or `reasoning_details`, Ollama's
`thinking`; else `null`) and `usage()`: a `TokenUsage`
with input, output, reasoning and cached tokens and the cost in USD, or
`null` if the gateway didn't report it. The same usage and reasoning are in
the attempt's
`AttemptResult`,
reported to `attemptFinished` (see [Monitoring](08-monitoring.md)).

## Token log probabilities

A model whose app entry sets `logprobs` (Ollama only) returns each reply
token with its log probability and the `logprobs` most likely alternatives
at its place, in `AIReply.tokens()` (`null` otherwise): a list of
`TokenLogprob( token, logprob, bytes, top )`, where `top` is a list of
`TokenLogprob.Alternative( token, logprob, bytes )`. `bytes` are the
token's UTF-8 bytes as the model gave them: a token can be part of a
multi-byte character, which its text shows as `�`.

They let the app judge how sure the model was. Read them in the reply
check: a `TaskPrompter`-style adapter overrides a parse that takes the
whole `AIReply`, and the `JobListener.check` can then escalate a reply the
model was unsure of (see [Escalation](06-escalation.md)).

Log probabilities only mean something if the model writes its most likely
token. Ollama models often ship with sampling and penalties in their
Modelfile (e.g. `temperature 1`, `presence_penalty 1.5`), so set a greedy,
unpenalised `sampler` with them:

```json
"qwen3.5:27b-q8_0" : {
  "logprobs" : 5,
  "sampler"  : {
    "temperature"      : 0,
    "topK"             : 1,
    "presencePenalty"  : 0,
    "frequencyPenalty" : 0,
    "repeatPenalty"    : 1
  }
}
```

Logprobs slow generation (about 40% on Qwen 3.5 27B at one request in
flight). They are not written to the transcripts: a long reply's list is
large. Keep what you need from them yourself.

