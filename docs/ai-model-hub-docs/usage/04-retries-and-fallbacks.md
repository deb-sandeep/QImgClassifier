# 4. Retries and fallbacks

Failures are handled entirely in the app file: there is no code to write.
Each model has a number of `retries` and an optional `fallback`, which picks
the next models by **why** the attempt failed.

## Failure kinds

Every failed attempt has a
[`FailureKind`](../javadocs/com/sandy/sconsole/ai/FailureKind.html), which
names the guard used:

| Kind | Guard | Typical cause | Uses a retry? |
|---|---|---|---|
| `RATE_LIMITED` | `rateLimited` | HTTP 429, or a quota used up | **no**: handed back |
| `UNREACHABLE` | `unreachable` | connection refused, connect timeout | **no**: handed back |
| `SERVER_ERROR` | `serverError` | 5xx, upstream provider error | yes |
| `TIMEOUT` | `timeout` | no reply within `requestTimeoutSeconds` | yes |
| `BAD_REPLY` | `badReply` | empty or cut-off reply, refusal, or your `check` rejected it | yes |
| `OTHER` | (`default` only) | e.g. the gateway refused the request (4xx) | yes |

A kind without its own guard uses `default`.

## Failures that use a retry

1. The job retries **the same model** while it has `retries` left for it.
2. After that it follows the model's guard for the failure kind (else
   `default`).
3. If there is no guard, or every model in it can't be used or is used up
   for this job, the job **fails**:
   `failed( job, reason, kind )` reports the last failure.

There is no wrap-round, and a model is never tried more than
`1 + retries` times by one job, so cycles between models are harmless.

## Rate limits and unreachable gateways: handed back

These aren't the job's fault, so they use no retry. The job waits for the
**same model** to be allowed to send again. If the model has a
`rateLimited` (or `unreachable`) guard, or a `default` guard, the models in
it may take the job while every model before them in the order is held
back.

Without such a guard the job simply waits, however long the hold lasts.
After a daily limit that means until 00:00 UTC.

## Ordered lists: a later model is a last resort

In a guard list, a later model takes the job only while **every earlier one
is unavailable**:

- **Unavailable**: paused or suspended after a rate limit, at a daily limit,
  or its gateway unreachable.
- **Not unavailable**: merely busy (all slots taken) or pacing under its
  per-minute limit. In that case the job waits for the earlier model.

This is what keeps a paid model out of plain overflow. It is used only
during an outage of the models ahead of it.

## Recipes

### Use a paid model only while the free one is held back

```json
"nvidia/nemotron-3.5-super-vl:free" : {
  "enabled" : true, "maxParallel" : 3, "requestTimeoutSeconds" : 180,
  "retries" : 1, "reasoning" : false, "promptCaching" : true,
  "fallback" : {
    "rateLimited" : [ "nvidia/nemotron-3.5-super-vl" ],
    "unreachable" : [ "nvidia/nemotron-3.5-super-vl" ]
  }
}
```

Timeouts, server errors and bad replies retry the free model once and then
fail. The paid model is never used for them.

### Different routes for different failures

```json
"fallback" : {
  "rateLimited" : [ "google/gemma-4-31b-it:free",
                    "google/gemma-4-26b-a4b-it:free" ],
  "badReply"    : [ "anthropic/claude-haiku-5.5" ],
  "default"     : [ "google/gemma-4-31b-it:free" ]
}
```

- 429 → the 31B model, or the 26B one while the 31B is also held back.
- A reply your `check` rejected → Claude Haiku, which follows the format
  better.
- Anything else (timeout, 5xx) → the 31B model.

### Chains

Once a job has moved to a fallback model, **that model's** `retries` and
`fallback` apply to its next failure, not the rest of the earlier list:

```json
"model-a" : { …, "fallback" : { "default" : [ "model-b" ] } },
"model-b" : { …, "fallback" : { "default" : [ "model-c" ] } },
"model-c" : { … }
```

a → b → c. A model the job has already used up is skipped.

### Local first, cloud when the local server is down

```json
"gemma4:latest" : {
  "enabled" : true, "maxParallel" : 5, "requestTimeoutSeconds" : 300,
  "retries" : 1, "reasoning" : false,
  "fallback" : { "unreachable" : [ "nvidia/nemotron-3.5-super-vl:free" ],
                 "default"     : [ "nvidia/nemotron-3.5-super-vl:free" ] }
}
```

## Rate limits in detail

- **Combined limits**: a model sends only when its own limiter (provider and
  app `rateLimit` combined, the smaller non-zero value of each winning), its
  gateway's limiter and its gateway's `maxParallel` cap all allow it.
- **Pacing**: with an `rpm`, request starts are spaced at least
  `60 s / rpm × 1.05` apart, so requests flow evenly just under the limit
  rather than in bursts.
- **Daily limits**: `rpd` counts requests per UTC day. The counts are saved
  to `AIHub.Options.historyFile` on every request and restored at startup.
  Keys are the gateway name and `<gateway>/<model>`.
- **429 handling** depends on what the provider says was limited:

  | Scope | Typical cause | Effect |
  |---|---|---|
  | model | the model's upstream provider is busy; any Ollama 429 | that model is paused; its siblings carry on |
  | gateway | an account-wide limit (e.g. OpenRouter's `free-models-per-min`) | every model on the gateway is held |
  | day | a per-day quota | as for gateway, marked *daily* |

  A hold lasts for the response's `Retry-After`, else OpenRouter's
  `X-RateLimit-Reset`, else the gateway's `cooldownSeconds`.
- **Unreachable gateway**: it is held for `cooldownSeconds`. After that it
  sends one request at a time (a probe) until one gets any answer.

Holds show up in [`HubStatus.notices()`](../javadocs/com/sandy/sconsole/ai/HubStatus.html);
see [Monitoring](08-monitoring.md).
