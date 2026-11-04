# tag-audit.jsonl

QTagger's audit trail, the provenance the server doesn't keep. It's in the tagger's `workspace.dir`, one JSON object per line, and is only ever appended to. QTagger never reads it, so `review-auto-tags` can.

**Status:** version 1, from step 5 of the [implementation plan](implementation-plan.md).

There are two kinds of line, told apart by `event`. Both start with `time` (UTC, ISO-8601), `event`, `serverId`, `questionId` and `sourceId`.

## `reply`

A model's reply, written when it arrives, whether or not it is saved later.

| Field | Type | Notes |
|---|---|---|
| `scopeTopicId` | number | The topic in scope. |
| `cycle` | string | The cycle of the topic's files. |
| `model` | string | The model that replied. |
| `tags` | array of strings | Empty when `noTagFits` is true. |
| `confidence` | string | `high`, `medium` or `low`. |
| `noTagFits` | boolean | True when the model says no tag fits. |
| `framing` | string or null | The question's framing when no tag fits. |
| `reason` | string or null | The words that decided it. |

## `decision`

A decision the server applied. A decision the server refused isn't recorded: its row shows *Error*, and it can be saved again.

| Field | Type | Notes |
|---|---|---|
| `scopeTopicId` | number | The topic in scope. |
| `cycle` | string | The cycle of the topic's files. |
| `modelHash`, `promptHash` | string | SHA-256 of `regression-model.json` and `llm-prompt.md` as read when the scope was set. They tell a cycle rebuilt under the same name. |
| `ocrHash` | string or null | SHA-256 of the OCR text in UTF-8. Null if it was never read. |
| `regression` | object or null | Null if the OCR failed or the question was never read. See below. |
| `llm` | object or null | The LLM's opinion when this was saved, with the fields of `reply` from `model` on. The owner may have picked none of it. Null if the LLM wasn't asked. |
| `decision` | object | See below. |
| `sent.tagIds` | array of numbers | Every explicit tag sent, other topics' included. |
| `status` | string | The server's answer: `APPLIED`. |
| `message` | string or null | The server's message. |

`regression`:

| Field | Type | Notes |
|---|---|---|
| `route` | string | `apply` or `llm`. |
| `accepted`, `undecided` | object | Tag name to score. Implied parents are already dropped from `accepted`. |
| `triggered` | array of strings | `llm-only` tags whose pattern matched. They have no score. |

`decision`:

| Field | Type | Notes |
|---|---|---|
| `tags` | array of strings | The topic's tags on the question after saving. A tag of the topic left out here was taken off. |
| `source` | string | Where the set started: `regression`, `existing` or `user`. |
| `edited` | boolean | True if the owner changed the set after choosing it. |
| `human` | boolean | True if the owner decided it. False if it was saved as the regression settled it. |
