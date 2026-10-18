# 1. Configuration

The configuration comes in two layers, like a parent POM and a child POM:

| File | Owned by | Holds |
|---|---|---|
| **Global** (e.g. `ai-model-config.json`) | whoever runs the models | Facts about providers: gateways, models, switches, credentials, the limits providers enforce. No app choices. |
| **App** (e.g. `my-app.ai.json`) | one application | Its `parent` (the global file), its roots, and its policy for each model it uses. |

Several apps can share one global file, and an app can point at a different
global file (another environment, or a newer version) just by changing
`parent`.

Both files are JSON with `//` and `/* */` comments allowed. Keys are strict:
an unknown key is an error, which catches typos. By convention every
attribute goes on its own line, nested objects (such as `rateLimit`)
included, with the colons of an object aligned.

## The global file

```json
// ai-model-config.json
{
  "gateways" : {
    "openrouter" : {
      "enabled"               : true,
      "type"                  : "openrouter",
      "url"                   : "https://openrouter.ai/api/v1/chat/completions",
      "apiKeyFile"            : "~/secrets/open-router.api.key",
      "connectTimeoutSeconds" : 10,
      "cooldownSeconds"       : 300,
      "rateLimit"             : {
        "rpm" : 20,
        "rpd" : 1000
      }
    },
    "ollama-home" : {
      "enabled"               : true,
      "type"                  : "ollama",
      "url"                   : "http://192.168.0.149:11434/api/generate",
      "connectTimeoutSeconds" : 10,
      "cooldownSeconds"       : 60,
      "maxParallel"           : 5,
      "rateLimit"             : {
        "rpm" : 0,
        "rpd" : 0
      }
    }
  },
  "models" : {
    "nvidia/nemotron-3.5-super-vl:free" : {
      "enabled"     : true,
      "gateway"     : "openrouter",
      "displayName" : "Nemotron 3.5 Super"
    },
    "anthropic/claude-haiku-5.5" : {
      "enabled"     : true,
      "gateway"     : "openrouter",
      "displayName" : "Claude Haiku"
    },
    "gemma4:latest" : {
      "enabled"       : true,
      "gateway"       : "ollama-home",
      "displayName"   : "Gemma 4",
      "contextTokens" : 12288
    }
  }
}
```

### Gateway keys

| Key | Required | Meaning |
|---|---|---|
| `enabled` | yes | `false` switches off the gateway and every model on it, in one place. |
| `type` | yes | `openrouter` or `ollama`: the transport ([`GatewayType`](../javadocs/com/sandy/sconsole/ai/config/GatewayType.html)). |
| `url` | yes | The endpoint requests are posted to. |
| `apiKeyFile` | `openrouter` only | A file holding only the API key. `~/` is the home folder; a relative path is relative to the global file's folder. If the file is missing or empty, the gateway's models are unusable. |
| `connectTimeoutSeconds` | yes | The longest wait for a connection. |
| `cooldownSeconds` | yes | How long to hold back after a rate limit that doesn't say how long, or after failing to connect. |
| `maxParallel` | no | The provider's cap on requests in flight across all the gateway's models. |
| `rateLimit` | yes | `{ "rpm", "rpd" }`: requests per minute and per UTC day (reset at 00:00 UTC) across the gateway's models. `0` means no limit. |

### Model keys (global)

Models are keyed by the **real id** sent to the gateway. That id also
appears in logs, history and status.

| Key | Required | Meaning |
|---|---|---|
| `enabled` | yes | `false` switches off this model alone. |
| `gateway` | yes | The gateway hosting it. |
| `displayName` | no | The name to show users; see [`AIConfig.displayName`](../javadocs/com/sandy/sconsole/ai/config/AIConfig.html). |
| `rateLimit` | no | `{ "rpm", "rpd" }` the provider enforces on this model. |
| `contextTokens` | no | The context window to ask for (Ollama's `num_ctx`). |

## The app file

```json
// my-app.ai.json
{
  "parent" : "classpath:/common/ai-model-config.json",

  "roots" : {
    "main" : [ "nvidia/nemotron-3.5-super-vl:free" ]
  },

  "models" : {
    "nvidia/nemotron-3.5-super-vl:free" : {
      "enabled"               : true,
      "maxParallel"           : 3,
      "requestTimeoutSeconds" : 180,
      "retries"               : 1,
      "reasoning"             : false,
      "promptCaching"         : true,
      "rateLimit"             : {
        "rpm" : 0,
        "rpd" : 500
      },
      "fallback"              : {
        "rateLimited" : [ "anthropic/claude-haiku-5.5" ],
        "unreachable" : [ "anthropic/claude-haiku-5.5" ]
      }
    },
    "anthropic/claude-haiku-5.5" : {
      "enabled"               : true,
      "maxParallel"           : 2,
      "requestTimeoutSeconds" : 120,
      "retries"               : 0,
      "reasoning"             : false,
      "promptCaching"         : true
    }
  }
}
```

| Key | Meaning |
|---|---|
| `parent` | The global file. Either `classpath:/…` or a file path. `~/` is expanded, and a relative path is relative to the app file's folder. |
| `roots` | Named pools of entry models, each a non-empty list of model ids. A job names the roots it may start on. How jobs are shared between roots is up to your [routing policy](05-routing-policies.md), not the config. |
| `models` | The app's policy for **every** model it names anywhere (roots or fallbacks). |

### Which models an app can invoke

Only the models its **roots reach**. Jobs name roots, never models, so a job
starts on a root model and moves on only through fallback lists:

- every model of every root is reachable;
- so is every model in a fallback list (any guard) of a reachable model that
  is usable, and so on down the chain.

Any other model in the app's `models` is **unreachable**. It gets no slots,
is never offered a job, shows as unusable in the status snapshot ("no root of
the app reaches it, directly or by fallback"), and is logged as a WARN at
load if the app enables it. Keeping a model configured but switched off
(`"enabled" : false`), ready to put in a root later, gives no warning.

A model reached only through an unusable model is unreachable too: a job
can't run on the unusable model, so it never follows that model's
fallbacks.

Models that are only in the global file are invisible to the app.

### Model keys (app)

| Key | Required | Meaning |
|---|---|---|
| `enabled` | yes | The app's own switch, on top of the global ones. |
| `maxParallel` | yes | The model's slots: its requests in flight at most, for this app. |
| `requestTimeoutSeconds` | yes | The longest a whole request may take. |
| `retries` | yes | Further attempts a job gets on this model after a failed one, before following its fallback. |
| `reasoning` | yes | Let the model reason before answering. Ollama gets `think`; OpenRouter gets `reasoning: {enabled: false}` when off. |
| `promptCaching` | `openrouter` models | Mark the prompt cacheable on models whose OpenRouter catalogue entry supports it. |
| `rateLimit` | no | The app's own `{ "rpm", "rpd" }` on this model. It combines with the provider's: for each of rpm and rpd, the smaller non-zero value wins. |
| `fallback` | no | Guards: `rateLimited`, `serverError`, `timeout`, `unreachable`, `badReply`, `default`. See [Retries and fallbacks](04-retries-and-fallbacks.md). |

## Loading

```java
import com.sandy.sconsole.ai.config.AIConfig ;
import com.sandy.sconsole.ai.config.AIConfigException ;
import com.sandy.sconsole.ai.config.AIConfigLoader ;

try {
    AIConfig config = AIConfigLoader.load( "classpath:/my-app.ai.json" ) ;
    // or a file: AIConfigLoader.load( "~/my-app/my-app.ai.json" ) ;
}
catch( AIConfigException e ) {
    // names the file and the JSON path, e.g.
    // my-app.ai.json: models."x".fallback.serverError[1] names 'y', which is
    // not in the parent
    System.err.println( e.getMessage() ) ;
    System.exit( 1 ) ;
}
```

See [`AIConfigLoader`](../javadocs/com/sandy/sconsole/ai/config/AIConfigLoader.html)
and [`AIConfigException`](../javadocs/com/sandy/sconsole/ai/config/AIConfigException.html).

A common pattern is to let a system property point at another app file
without rebuilding:

```java
String location = System.getProperty( "ai.app.config",
                                      "classpath:/my-app.ai.json" ) ;
AIConfig config = AIConfigLoader.load( location ) ;
```

### What load checks

The load fails with an `AIConfigException` naming the file and JSON path
when:

- a file is missing or isn't valid JSON;
- a required key is missing, or a key, gateway type or guard name is
  unknown;
- a model named in a root or fallback isn't defined in the parent, or has
  no entry in the app's `models`;
- a **root model the app enables can't be used**: its gateway or the model
  is switched off globally, or the gateway has no API key. For example:
  `roots."main"[0] is model 'x', which can't be used: its gateway
  'openrouter' is disabled (classpath:/common/ai-model-config.json)`.

A model the app switches off itself (`"enabled" : false`) is allowed in a
root; that root is just off. These are only a **WARN**:

- a usable model's fallback names a model that can't be used (that model
  is skipped at run time);
- the app enables a model that no root reaches (it gets no slots).

On success, a summary of both files is logged at INFO on the `ai.config`
logger: gateways, every model with its limits, slots, retries and guards, and
the roots.

## Inspecting the config in code

[`AIConfig`](../javadocs/com/sandy/sconsole/ai/config/AIConfig.html) and its
records are read-only views:

```java
for( ModelInfo m : config.root( "main" ) ) {
    System.out.printf( "%s on %s: %s%n", m.label(), m.gateway().name(),
                       m.usable() ? "usable" : m.whyUnusable() ) ;
}
boolean canRun = config.isRootUsable( "main" ) ;
String  shown  = config.displayName( "anthropic/claude-haiku-5.5" ) ;
```

See [`ModelInfo`](../javadocs/com/sandy/sconsole/ai/config/ModelInfo.html),
[`GatewayInfo`](../javadocs/com/sandy/sconsole/ai/config/GatewayInfo.html),
[`FallbackInfo`](../javadocs/com/sandy/sconsole/ai/config/FallbackInfo.html) and
[`RateLimit`](../javadocs/com/sandy/sconsole/ai/config/RateLimit.html).
