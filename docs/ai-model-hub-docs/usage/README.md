# AI Model Hub — usage guide

The AI model hub (`com.sandy.sconsole.ai`) sends an application's AI work to
large language models hosted behind *gateways* (OpenRouter, Ollama). It
handles everything the application shouldn't have to:

- which model takes each job, decided as late as possible;
- how many requests run in parallel on each model;
- provider and app rate limits, including per-day quotas that survive a
  restart;
- retries, and guarded fallbacks to other models;
- escalating a weak answer to a stronger set of models;
- stopping, holding and re-queueing work;
- status snapshots and logging.

The hub knows nothing about your domain. You give it prompts and images, and
it gives back replies, which your own code checks and turns into results.

It depends only on the JDK (17+), Jackson and SLF4J.

API reference: [javadocs](../javadocs/frames.html) (framed; [plain](../javadocs/index.html)) (packages
[`com.sandy.sconsole.ai`](../javadocs/com/sandy/sconsole/ai/package-summary.html)
and
[`com.sandy.sconsole.ai.config`](../javadocs/com/sandy/sconsole/ai/config/package-summary.html)).
`com.sandy.sconsole.ai.internal` is not API: don't use it.

## Scenarios

| # | Guide | Read it when you want to… |
|---|---|---|
| 1 | [Configuration](01-configuration.md) | describe gateways and models, and pick the ones an app uses |
| 2 | [Quick start](02-quick-start.md) | send one prompt and get the answer back |
| 3 | [Checking replies](03-checking-replies.md) | turn replies into typed results, and reject bad ones |
| 4 | [Retries and fallbacks](04-retries-and-fallbacks.md) | survive failures and rate limits without overspending |
| 5 | [Routing policies](05-routing-policies.md) | decide which model takes which job (several roots, load spreading) |
| 6 | [Escalation](06-escalation.md) | get a second opinion when an answer isn't good enough |
| 7 | [Job control](07-job-control.md) | run batches, stop, hold, cancel and re-queue jobs |
| 8 | [Monitoring](08-monitoring.md) | show progress, limits and token costs, and read the logs |

## Concepts in one page

| Term | Meaning |
|---|---|
| **Gateway** | An endpoint that hosts models, of a type (`openrouter`, `ollama`), with its own switch, credentials, rate limits and an optional cap on requests in flight. Defined in the *global* file. |
| **Model** | A model id on a gateway, with its own switch, display name and provider limits (global file), and the app's policy for it: slots, timeout, retries, reasoning, prompt caching, app limits, fallbacks (app file). |
| **Usable** | A model that is switched on in the app and globally, whose gateway is on and has its credentials, and which the app's roots reach (see below). Only usable models get requests. |
| **Reachable** | A model a job can get to: one in a root, or in a fallback list of a usable model a job can get to. An app can invoke only these; any other model in its `models` gets no slots. |
| **Root** | A named pool of entry models in the app file. A job names the roots it may start on. |
| **Slot** | One of a model's `maxParallel` worker threads. A free slot whose model may send asks the routing policy for a job. |
| **Job** | One unit of work ([`AIJob`](../javadocs/com/sandy/sconsole/ai/AIJob.html)): your payload, its roots, a request factory, a stop check and a listener. |
| **Attempt** | One request of a job to one model ([`Attempt`](../javadocs/com/sandy/sconsole/ai/Attempt.html), [`AttemptResult`](../javadocs/com/sandy/sconsole/ai/AttemptResult.html)). |
| **Routing policy** | Your callback that picks which waiting job a free slot takes ([`RoutingPolicy`](../javadocs/com/sandy/sconsole/ai/RoutingPolicy.html)). |
| **Reply check** | Your callback that accepts, rejects or escalates a reply ([`ReplyCheck`](../javadocs/com/sandy/sconsole/ai/ReplyCheck.html)). |
| **Fallback guard** | Per model and per failure kind, the ordered list of models a job moves on to once the model has failed it for good ([`FailureKind`](../javadocs/com/sandy/sconsole/ai/FailureKind.html)). |
| **Held back** | Not allowed to send for now: paused or suspended after a rate limit, at a daily limit, or its gateway unreachable. Pacing under a per-minute limit and busy slots don't count. |

## The life of a job

```
submit ──► WAITING ──(a slot of a model in one of its roots picks it)──► RUNNING
              ▲                                                            │
              │      retry on the same model / handed back / fallback      │
              └──────────────────────────(attempt failed)──────────────────┤
                                                                           │
          reply accepted ─► succeeded      reply escalated ─► WAITING on the
          nothing left    ─► failed        escalation root's models
          stopped/cancelled before starting ─► dropped
```

Every step is reported to the job's
[`JobListener`](../javadocs/com/sandy/sconsole/ai/JobListener.html), on the
hub's worker threads.

## Regenerating the javadocs

```sh
docs/ai-model-hub-docs/generate-javadocs.sh
```
