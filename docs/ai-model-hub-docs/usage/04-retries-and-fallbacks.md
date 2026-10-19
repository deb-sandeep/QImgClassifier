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
| `RATE_LIMITED_RPM` | `rateLimitedRPM` | HTTP 429 short of a daily limit: requests per minute, or the model's provider busy | **no**: handed back |
| `RATE_LIMITED_RPD` | `rateLimitedRPD` | HTTP 429 for the requests per day | **no**: handed back |
| `UNREACHABLE` | `unreachable` | connection refused, connect timeout | **no**: handed back |
| `SERVER_ERROR` | `serverError` | 5xx, upstream provider error | yes |
| `TIMEOUT` | `timeout` | no reply within `requestTimeoutSeconds` | yes |
| `BAD_REPLY` | `badReply` | empty or cut-off reply, refusal, or your `check` rejected it | yes |
| `OTHER` | (`default` only) | e.g. the gateway refused the request (4xx) | yes |

A kind without its own guard uses `default`. One more guard,
`passthrough` (`FailureKind.PASSTHROUGH`), is never an attempt's failure:
see [Passing a model's jobs through](#passing-a-models-jobs-through).

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
`rateLimitedRPM`, `rateLimitedRPD` or `unreachable` guard (or a `default`
guard), the models in it may take the job while every model before them in
the order is held back.

Without such a guard the job simply waits, however long the hold lasts.
After a daily limit that means until 00:00 UTC.

## While a model is held back

The guards also apply when no attempt failed: the hub itself holds a model
back once a limit in the config is reached. While a model is held back, the
jobs waiting for it go by its guard for **why** it is held:

| The model is held back because | Guard |
|---|---|
| its own `rpd`, or its gateway's, is used up for the (UTC) day, or a per-day 429 suspended its gateway | `rateLimitedRPD` |
| a 429 paused it or suspended its gateway (per minute, provider busy) | `rateLimitedRPM` |
| its gateway can't be reached | `unreachable` |
| the app set it to pass its jobs through | `passthrough` |

This covers:

- **Jobs not tried yet**: once every model of their roots is held back,
  the first available model of a root model's guard takes them, as a
  [`Candidate.Via.STAND_IN`](../javadocs/com/sandy/sconsole/ai/Candidate.Via.html).
  They are still new jobs (`isContinuation()` is false).
- **Jobs between attempts** on the held model: one waiting to retry it, or
  one handed back by it. The guard for the model's current hold is used,
  which may differ from the one its last attempt failed with (e.g. a
  per-minute 429, then its `rpd` used up).

Routine per-minute pacing is not a hold: a model at its `rpm` cap keeps its
jobs.

## Passing a model's jobs through

The app can hold a model back by hand, e.g. to stop using a free model whose
replies have turned slow, without editing the config:

```java
hub.setPassthrough( "nvidia/nemotron-3.5-super-vl:free", true ) ;
...
hub.setPassthrough( "nvidia/nemotron-3.5-super-vl:free", false ) ;
```

While it is set the model takes no jobs, and counts as held back (above)
with the guard `passthrough` (else `default`), whatever else may hold it:

```json
"nvidia/nemotron-3.5-super-vl:free" : {
  "fallback" : {
    "rateLimitedRPD" : [ "nvidia/nemotron-3.5-super-vl" ],
    "passthrough"    : [ "nvidia/nemotron-3.5-super-vl" ]
  }
}
```

New jobs of its roots, and jobs waiting to retry it or handed back by it,
go to that guard's models; without the guard they wait for it. Its requests
in flight finish. The setting is kept in memory only, per hub, and shows as
`ModelStatus.passthrough()` in the [status](08-monitoring.md);
`ModelStatus.passthroughGuard()` says whether the model has the guard, e.g.
to offer the setting only where it has somewhere to send the jobs. See
[`AIHub.setPassthrough`](../javadocs/com/sandy/sconsole/ai/AIHub.html#setPassthrough(java.lang.String,boolean)).

## Ordered lists: a later model is a last resort

In a guard list, a later model takes the job only while **every earlier one
is unavailable**:

- **Unavailable**: paused or suspended after a rate limit, at a daily limit,
  its gateway unreachable, or set to pass its jobs through.
- **Not unavailable**: merely busy (all slots taken) or pacing under its
  per-minute limit. In that case the job waits for the earlier model.

This is what keeps a paid model out of plain overflow. It is used only
during an outage of the models ahead of it.

## Recipes

### Use a paid model once the free one's daily quota is used up

```json
"nvidia/nemotron-3.5-super-vl:free" : {
  "enabled"               : true,
  "maxParallel"           : 3,
  "requestTimeoutSeconds" : 180,
  "retries"               : 1,
  "reasoning"             : false,
  "promptCaching"         : true,
  "rateLimit"             : {
    "rpm" : 0,
    "rpd" : 1000
  },
  "fallback"              : {
    "rateLimitedRPD" : [ "nvidia/nemotron-3.5-super-vl" ]
  }
}
```

After the free model's 1000th request of the (UTC) day, its waiting and new
jobs go to the paid model until 00:00 UTC. A per-minute 429 on the free
model just makes its jobs wait; timeouts, server errors and bad replies
retry it once and then fail. The paid model is never used for them.

Put the daily quota on the free **model**, not on its gateway: a gateway's
`rpd` holds back every model on it, the paid one too, so the paid model
could not take over.

### Different routes for different failures

```json
"fallback" : {
  "rateLimitedRPM" : [
    "google/gemma-4-31b-it:free",
    "google/gemma-4-26b-a4b-it:free"
  ],
  "rateLimitedRPD" : [ "anthropic/claude-haiku-5.5" ],
  "badReply"       : [ "anthropic/claude-haiku-5.5" ],
  "default"        : [ "google/gemma-4-31b-it:free" ]
}
```

- A per-minute 429 → the 31B model, or the 26B one while the 31B is also
  held back.
- The daily quota used up → Claude Haiku.
- A reply your `check` rejected → Claude Haiku, which follows the format
  better.
- Anything else (timeout, 5xx) → the 31B model.

### Chains

Once a job has moved to a fallback model, **that model's** `retries` and
`fallback` apply to its next failure, not the rest of the earlier list:

```json
"model-a" : {
  …
  "fallback" : {
    "default" : [ "model-b" ]
  }
},
"model-b" : {
  …
  "fallback" : {
    "default" : [ "model-c" ]
  }
},
"model-c" : {
  …
}
```

a → b → c. A model the job has already used up is skipped.

### Local first, cloud when the local server is down

```json
"gemma4:latest" : {
  "enabled"               : true,
  "maxParallel"           : 5,
  "requestTimeoutSeconds" : 300,
  "retries"               : 1,
  "reasoning"             : false,
  "fallback"              : {
    "unreachable" : [ "nvidia/nemotron-3.5-super-vl:free" ],
    "default"     : [ "nvidia/nemotron-3.5-super-vl:free" ]
  }
}
```

## Rate limits in detail

- **Combined limits**: a model sends only when the provider's and the
  app's `rateLimit` on it (the smaller non-zero value of each winning), its
  gateway's limit and its gateway's `maxParallel` cap all allow it.
- **Pacing**: with an `rpm`, request starts are spaced at least
  `60 s / rpm × 1.05` apart, so requests flow evenly just under the limit
  rather than in bursts.
- **Daily limits**: `rpd` counts requests per UTC day. This app's counts
  are saved to `AIHub.Options.historyFile` on every request and restored at
  startup; with a usage ledger, the providers' counts are kept there
  instead (see below). Keys are the gateway name and `<gateway>/<model>`.
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

## Limits shared across apps

The limits in the global file are the provider's, and a provider counts
every request from the account, whichever app sent it. With
`usageLedger` set in the global file, every app using that file counts
those limits in one ledger:

- Before each request, a model's slot asks the ledger, under an OS file
  lock, whether its provider limits (the model's and its gateway's
  `rateLimit`) allow one more. If they do, the request is counted there
  and sent; if not (another app took the room), the slot waits, as for its
  own limits. Pacing (`60 s / rpm × 1.05`) is kept across apps too.
- A provider's rate-limit hold (a 429, with its `Retry-After`) is written
  to the ledger, and every app holds the model or gateway back until it
  lifts. A connection failure is not shared: each app finds for itself
  when the gateway answers again.
- An app's own `rateLimit` (in its app file) is counted in that app only,
  so it is its share of the provider's limit.
- Each hub reads the ledger at least once a second, so its status (today's
  count, holds) shows every app's use; a daily limit reached by all apps
  reads "daily limit of N requests reached (all apps)".
- At startup, the request times of provider-limited keys in the app's
  history file are added to the ledger, so counts from before the ledger
  existed aren't lost (a time already there isn't added twice).

The ledger works for apps on one machine. Apps on several machines would
need the count kept on a server.
