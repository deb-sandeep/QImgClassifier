# tag-audit.jsonl

QTagger's audit trail, the provenance the server doesn't keep. It's in the tagger's `workspace.dir`, one JSON object per line, and is only ever appended to. QTagger never reads it, so `review-auto-tags` can.

**Status:** version 2 (2026-11-18): tagging reads the question's transcription instead of tesseract text (`2026-11-18/tagging-by-xcription/strategy-changes.md`), so `ocrHash` became `textHash`, and the topic's files are identified by their `build`. Version 1 came from step 5 of the [implementation plan](implementation-plan.md); its lines keep `ocrHash` and `cycle` as written.

There are two kinds of line, told apart by `event`. Both start with `time` (UTC, ISO-8601), `event`, `serverId`, `questionId` and `sourceId`.

## `reply`

A model's reply, written when it arrives, whether or not it is saved later.

| Field | Type | Notes |
|---|---|---|
| `scopeTopicId` | number | The topic in scope. |
| `build` | string | The build of the topic's files, such as `c02.v01`. |
| `textHash` | string | SHA-256 of the transcription the LLM was shown, `content` as stored, in UTF-8. |
| `model` | string | The model that replied. |
| `tags` | array of strings | Empty when `noTagFits` is true. |
| `confidence` | string | `high`, `medium` or `low`. |
| `noTagFits` | boolean | True when the model says no tag fits. |
| `framing` | string or null | The question's framing when no tag fits. |
| `otherTopic` | object or null | Where the model would move the question: `topicId`, `topicName` (the server's name for that id) and `reason`. `tags` is then empty and `noTagFits` false. Null otherwise. |
| `reason` | string or null | The words that decided it. |

## `decision`

A decision the server applied. A decision the server refused isn't recorded: its row shows *Error*, and it can be saved again.

| Field | Type | Notes |
|---|---|---|
| `scopeTopicId` | number | The topic in scope. |
| `cycle` | string | The cycle that built the topic's files, the model's `cycle`, such as `02-rebuild`. |
| `build` | string | The build of the topic's files, such as `c02.v01`. |
| `modelHash`, `promptHash` | string | SHA-256 of the `regression-model` and `llm-prompt` files as read when the scope was set. |
| `textHash` | string or null | SHA-256 of the transcription scored, `content` as stored, in UTF-8. The tag workspace's intake compares it with the current transcription, to find questions whose transcription changed after tagging. Null if the question was never scored. |
| `regression` | object or null | Null if the question was never scored. See below. |
| `llm` | object or null | The LLM's latest reply when this was saved, with the fields of `reply` from `model` on. Null if the LLM wasn't asked. |
| `decision` | object | See below. |
| `sent.tagIds` | array of numbers | Every explicit tag sent, other topics' included. |
| `status` | string | The server's answer: `APPLIED`. |
| `message` | string or null | The server's message. |

`regression`:

| Field | Type | Notes |
|---|---|---|
| `route` | string | The tagger's routing: `llm` if nothing was accepted or an `llmIfAcceptedTogether` rule applied, else `apply`. Lines written before 2026-11-05 hold the reference's route, which also sent undecided and triggered tags to the LLM. |
| `accepted`, `undecided` | object | Tag name to score. Implied parents are already dropped from `accepted`. |
| `triggered` | array of strings | `llm-only` tags whose pattern matched. They have no score. |
| `dropped` | array of strings | Undecided and triggered tags not in the saved set. Absent from lines written before 2026-11-05. |

`decision`:

| Field | Type | Notes |
|---|---|---|
| `tags` | array of strings | The topic's tags on the question after saving. A tag of the topic left out here was taken off. |
| `source` | string | Where the set started: `REGRESSION`, `LLM`, `EXISTING` or `USER`. |
| `edited` | boolean | True if the owner changed the set after choosing it. |
| `human` | boolean | True if the owner decided it. False if it was saved as the regression or the LLM left it. |
