# QTranscriber: question transcription driver

| Field | Value |
|---|---|
| App | `QTranscriber` (`com.sandy.sconsole.qimgtools.apps.transcriber`), the fifth qimgtools app |
| Server | SConsoleNxt question transcription (`SConsoleNxt/doc/feature-enhancements/2026-11-17/question-transcription/`, `api-changes.md` rev 3), implemented on dev |
| Builds on | The topic reviewer's design (search, staged saves, unattended run); the AI hub |
| Companion docs | `logprob-confidence.md` (the gate), `calibration.md` (setting it), `prompt-review.md`, the prompt `src/main/resources/apps/transcriber/qtranscriber.prompt` |
| Status | Rev 1, 2026-11-17: for approval. No code yet |

QTranscriber copies each question image into markdown, in the server's
content format, and stores it on the server. Qwen (local, no token cost)
transcribes every question. When Qwen isn't confident of a transcription,
the question goes to Nemotron, whose transcription is taken instead. The
user spot-checks transcriptions in a rendered view beside the image, and
sends a faulty one to Nemotron again.

---

## 1. Decisions

Agreed with the user on 2026-11-17, one question at a time.

| # | Decision |
|---|---|
| 1 | The app follows the topic reviewer's design wherever the use cases overlap: search criteria, table, staged saves, unattended run |
| 2 | **Search** as in the reviewer: syllabuses and a topic list. Results come in server id order behind an `afterId` cursor; a new search resets it. The server's `Transcription/Pending` takes the criteria (`required-server-apis.md` §4) |
| 3 | **No agreement between models.** Qwen transcribes. Calibrated (`logprob-confidence.md` rev 3 §0): a Qwen reply goes to the free Nemotron (paid once the free quota is used up) only if it is malformed (§4.1) or Ollama fails the request; Nemotron's transcription is accepted as it is. Qwen's confidence score C is recorded and orders spot checks, but doesn't route |
| 4 | **Qwen down**: questions fall back to the free Nemotron, by the AI file's fallback guards. An unattended run stops once Qwen is down *and* the free Nemotron is held for the day, so a long outage can't run up the paid model; an attended run goes on to the paid one |
| 5 | **Spot checks** in a JavaFX `WebView`: markdown-it + MathJax 3 + mhchem, figures (blockquotes, rev 1.6) as framed boxes, `smiles:` lines drawn as structures (SmilesDrawer). A transcription marked faulty goes to the next model in the chain Qwen → Nemotron → GLM: Qwen's to Nemotron, Nemotron's to GLM (the paid Nemotron is the same model, so no help). A faulty GLM transcription stays unsaved |
| 6 | **Saving** as in the reviewer: staged in the table, saved by Save (or by the unattended run) |
| 7 | **LCT questions are self-contained**: an LCT question's `content` is its group's passage, the marker `<!-- question -->`, then its question part (`required-server-apis.md` §5). The passage is transcribed once per group, held locally until Save, and sent as `lctCtx` with the group's first question saved |
| 8 | **Images** from the local question bank (`QuestionLocator`), as the other apps; the server's image data is not used |
| 9 | **One prompt file**, with subject, type and task sections; the same prompt goes to Qwen and Nemotron |
| 10 | **Calibration** happens outside the app (`calibration.md`). The gate's `MARGIN` and `C_MAX` are properties, set from its results |
| 11 | **The AI hub is extended**, app-neutral: per-model sampler options and logprobs |
| 12 | **`aiModel`** sent to the server is the id of the model whose transcription was accepted; for a passage, `lctCtx.aiModel` is the passage's model; `user` for a hand-edited text |
| 13 | **Names**: `QTranscriber`, `apps.transcriber`, `apps/transcriber/` resources, `~/projects/workspace/qtranscriber` |
| 14 | **Purpose and tolerance**: a transcription is there to make the tagger (and search) more effective, not to be a picture-perfect copy. Faults that leave the question about the same thing (a digit, exponent, sign) are tolerated; the gate and spot checks aim at faults that would change a tag (`calibration.md` §3.4) |
| 15 | **Hand editing** in v1 (§8.4) |

Parked: Q7 of `logprob-confidence.md` (tesseract overlap and length checks
as extra spot-check signals).

## 2. Concepts

| Term | Meaning |
|---|---|
| **Row** | One pending server question in the table, with its local images (`QuestionLocator.Located`) |
| **Passage** | An LCT group's shared text, transcribed from its `_Ctx` images. Keyed by `lctCtxId` |
| **Question part** | What an LCT question's own images say, transcribed with the passage given as context |
| **Content** | What is saved: the transcription; for an LCT question, `passage + "\n\n<!-- question -->\n\n" + questionPart` |
| **Gate** | The checks of `logprob-confidence.md` §4 on a Qwen reply: well formed (§4.1), and C, its count of contested positions (§4.2), at most `C_MAX` |
| **Route** | How a transcription came about: `Qwen`; `Qwen → Nemotron` (escalated, with the reason); `Nemotron (Qwen down)`; `Nemotron (re-sent)`; `GLM (re-sent)` |
| **Held passage** | A passage transcribed in this app but not yet on the server |
| **Staged** | A transcription accepted but not saved |

## 3. Server API used

All in `api-changes.md` rev 3.

| Call | Use |
|---|---|
| `GET /Master/Topic/All` | Topics of the three IIT syllabuses, for the criteria dialog |
| `GET /Master/Question/Transcription/Pending?batchSize=1..100&afterId=&syllabusNames=&topicIds=` | Load. Items `{question, lctCtxId, lctCtxTranscribed}`; `totalResults` ignores `afterId` |
| `GET /Master/Question/Transcription/LctCtx?lctCtxId=` | A group's stored passage (or null) and its questions with `transcribed` / `current` |
| `POST /Master/Question/Transcription` | Save, at most 100 items a request; per item APPLIED / REJECTED |
| `GET /Master/Question/Transcription/{questionId}` | A saved question's content, for rebuilding it after its passage is replaced (§7.4) and for spot checks of saved questions (§8.3) |

## 4. Settings and files

### 4.1 Properties

`src/main/resources/apps/transcriber/qtranscriber.properties`, read by
`apps.transcriber.util.TranscriberConfig` through `ConfigProperties`
(no defaults in code; a missing key stops the app, as in the others):

| Key | Value |
|---|---|
| `server.url`, `server.connect.timeout.seconds`, `server.request.timeout.seconds` | The SConsole server |
| `question.bank.dir` | For `QuestionLocator` |
| `workspace.dir` | `~/projects/workspace/qtranscriber` |
| `fetch.batch.size` | Default questions per load, 1–100 |
| `gate.route.on.c`, `gate.margin`, `gate.c.max` | The gate (note §4.2, §4.3): false, 0.3, 0. C is computed at `gate.margin` by the md-minor rule; it routes only if `gate.route.on.c` is true |
| `prompt.file` | `/apps/transcriber/qtranscriber.prompt` (classpath) |
| `criteria.file`, `staged.file`, `held.passages.file`, `audit.file`, `request.history.file`, `usage.log.file` | Workspace files (§4.3), relative to `workspace.dir` |

### 4.2 AI file

`src/main/resources/apps/transcriber/qtranscriber.ai.json`, parent
`common/ai-model-config.json`:

```json
{
  "parent" : "classpath:/common/ai-model-config.json",

  "roots" : {
    "transcribe" : [ "qwen3.5:27b-q8_0" ],
    "escalate"   : [ "nvidia/nemotron-3.5-super-vl:free" ],
    "recheck"    : [ "z-ai/glm-5.3-flash" ]
  },

  "models" : {
    "qwen3.5:27b-q8_0" : {
      "enabled"               : true,
      "maxParallel"           : 3,
      "requestTimeoutSeconds" : 180,
      "retries"               : 0,
      "reasoning"             : false,
      "maxOutputTokens"       : 4096,
      "logprobs"              : 5,
      "sampler"               : {
        "temperature"      : 0,
        "topK"             : 1,
        "presencePenalty"  : 0,
        "frequencyPenalty" : 0,
        "repeatPenalty"    : 1
      },
      "fallback"              : {
        "unreachable" : [ "nvidia/nemotron-3.5-super-vl:free" ],
        "serverError" : [ "nvidia/nemotron-3.5-super-vl:free" ],
        "timeout"     : [ "nvidia/nemotron-3.5-super-vl:free" ],
        "badReply"    : [ "nvidia/nemotron-3.5-super-vl:free" ],
        "truncated"   : [ "nvidia/nemotron-3.5-super-vl:free" ]
      }
    },
    "nvidia/nemotron-3.5-super-vl:free" : {
      "enabled"               : true,
      "maxParallel"           : 4,
      "requestTimeoutSeconds" : 180,
      "retries"               : 1,
      "reasoning"             : false,
      "maxOutputTokens"       : 4096,
      "promptCaching"         : true,
      "fallback"              : {
        "rateLimitedRPD" : [ "nvidia/nemotron-3.5-super-vl" ],
        "serverError"    : [ "nvidia/nemotron-3.5-super-vl" ],
        "passthrough"    : [ "nvidia/nemotron-3.5-super-vl" ]
      }
    },
    "nvidia/nemotron-3.5-super-vl" : {
      "enabled"               : true,
      "maxParallel"           : 3,
      "requestTimeoutSeconds" : 180,
      "retries"               : 1,
      "reasoning"             : false,
      "maxOutputTokens"       : 4096,
      "promptCaching"         : true
    },
    "z-ai/glm-5.3-flash" : {
      "enabled"               : true,
      "maxParallel"           : 3,
      "requestTimeoutSeconds" : 180,
      "retries"               : 1,
      "reasoning"             : false,
      "maxOutputTokens"       : 4096,
      "promptCaching"         : true
    }
  }
}
```

`recheck` is reached only by a re-send of a faulty Nemotron transcription
(§8.2). GLM's reasoning is off for a transcription; if GLM can't answer
without it (the hub's "can't answer without reasoning" failure), it is
turned on with a `reasoningMaxTokens` cap.

Qwen's `maxParallel` is 3, from the calibration's throughput step (4
gains nothing). The
`transcribe` root must be usable, else a dialog and exit (it is a root even
while `ollama-studio` is unreachable: that is a run-time state, handled by
the fallback). An unusable `escalate` root is also fatal: without it no
question could escalate. An unusable `recheck` root only disables re-sending
Nemotron's transcriptions, with a warning.

### 4.3 Workspace

`~/projects/workspace/qtranscriber`:

| File | Contents |
|---|---|
| `transcriber-criteria.json` | The search criteria in effect (`{syllabusNames, topicIds, batchSize, savedAt}`), restored at startup, as the reviewer's |
| `staged-transcriptions.json` | Accepted, unsaved transcriptions by server id: content (question part for LCT), model, route, C and the gate's metrics, prompt SHA-256. Reused on the next Load without asking a model, while the prompt is unchanged. Dropped on APPLIED |
| `held-passages.json` | Held passages by `lctCtxId`: content, model, route, C, prompt SHA-256. Dropped once the server stores the passage |
| `transcription-audit.jsonl` | Append-only: every transcription accepted, every save (with the server's status and the server question, images included, so the spot check can locate it again), every spot-check mark, re-send and edit. Never read by the app except by the saved-questions spot check (§8.3) |
| `request.history.json`, `token-usage.csv` | As in the other apps |
| `<source>/<questionId>/…` | Per-attempt transcripts (`TranscriptStore`), each response with the gate's note as a `Confidence:` section; a passage's under `<source>/<S>_LCT_<n>_Ctx/` |

The OpenRouter key is the shared one, as in the other apps.

## 5. AI hub changes (app-neutral)

All optional; the other apps' files don't change.

1. **Sampler** (`ModelInfo.sampler`, app file key `sampler`): an object of
   `temperature`, `topK`, `topP`, `presencePenalty`, `frequencyPenalty`,
   `repeatPenalty`, each optional. `OllamaGateway` puts them in `options`
   (`temperature`, `top_k`, `top_p`, `presence_penalty`,
   `frequency_penalty`, `repeat_penalty`); `OpenRouterGateway` sends the
   ones OpenRouter takes (`temperature`, `top_k`, `top_p`,
   `presence_penalty`, `frequency_penalty`, `repetition_penalty`).
   Validation: numbers, `topK` a positive integer; unknown keys refused.
2. **Logprobs** (`ModelInfo.logprobs`, app file key `logprobs`: the number
   of alternatives, 1–20; absent = off). `OllamaGateway` sends
   `logprobs: true, top_logprobs: k`; OpenRouter models refuse the key at
   load in v1 (a WARN would hide a gate that never runs).
3. **`AIReply.tokens`**: `List<TokenLogprob>`, null when not asked for,
   where `TokenLogprob( String token, double logprob,
   List<Alternative> top )` and `Alternative( String token, double
   logprob )`. Filled by `OllamaGateway` from the response's `logprobs`.
   The reply's text stays `response`; the app checks that the tokens add up
   to it.
4. **Reaching the app's check.** `TaskPrompter` gets a default
   `parse( AIReply reply, QTask task )` calling the existing
   `parse( reply.text(), task )`; `TaskScheduler`'s `check` calls the new
   one. `TaskOutcomes.secondOpinionReason` gets a per-task overload
   (`secondOpinionReason( task, value )`, defaulting to the present one),
   so the escalation names why (e.g. "C = 2: `5f^4` vs `5f^{14}`").
5. **Transcripts**: the raw token list is not written to the response
   transcript (a 1,000-token reply would be ~200 KB of JSON). The app adds
   its own Confidence section (§6.4).

The usage guides under `docs/ai-model-hub-docs/usage/` get a section on
sampler settings and one on logprobs.

## 6. Transcribing a question

### 6.1 Request

The prompt is built by `apps.transcriber.prompt.TranscriptionPrompter` from
`qtranscriber.prompt` (sections, placeholders and comment lines as its
header says), with the question's subject, type and task. The image is the
question's own images stacked (`ImageCollator`); for an LCT question part
the `_Ctx` images are left out and the passage text goes in `{{PASSAGE}}`;
for a passage, only the `_Ctx` images. Images go as stored (no
`ImagePrep`): small subscripts need every pixel.

### 6.2 Qwen's reply: the gate

`TranscriptionPrompter.parse( AIReply, task )`:

1. Strip trailing whitespace. A code fence wrapping the whole reply is
   not stripped: the well-formed check refuses it (a bad fence).
2. **Well formed** (note §4.1). For any model: cut off, empty, unbalanced
   `$` / `$$` / `{}` in maths, a bad fence, an empty `diagram` block, a
   placeholder, meta or solution text (the calibration replay's checks);
   then the app's own: a `smiles:` line CDK can't parse, and since rev 1.6
   a figure with no description, a figure not followed by a blank line
   (markdown would take the next line into it), or a `diagram` fence (the
   old form). Maths inside a figure is checked like any other.
   A failure is a bad reply (`BadReply`). For Qwen it goes straight to the
   free Nemotron, with no retry: at temperature 0 a retry would give the
   same reply. Qwen has `retries: 0`, and its `badReply` and `truncated`
   guards (as well as `unreachable`, `serverError` and `timeout`) fall back
   to the free Nemotron (§4.2). The route reads `Qwen → Nemotron` with the
   failure as the reason. For Nemotron and GLM a bad reply is retried, then
   falls back or fails, as in the other apps.
3. **Score** (note §4.2 with the rev 3 additions of §4.2a: the md-minor
   rule), for a reply with tokens: C, the count of contested positions at
   `gate.margin`, plus the metrics recorded (§6.4). An Ollama HTTP 500
   (e.g. `token repeat limit reached`) is a server error on that question,
   which Qwen's `serverError` guard sends to Nemotron; it doesn't mark Qwen
   down.
4. The value is a `Transcription( content, model, c, metrics, contested )`.

`TranscriberOutcomes.secondOpinionRoot` returns null: after calibration a
well-formed reply is accepted whatever its C (note rev 3 §0). With
`gate.route.on.c` true it returns `escalate` for a Qwen reply with
`c > gate.c.max`. Nemotron's reply is always accepted ungated, once it has
passed the well-formed check. The `escalate` root is then reached only by
a re-send (§8.2) or with routing on C; Qwen's failures reach the free
Nemotron by its fallback guards (§4.2).

If Nemotron gives no transcription (fails, held, Stop), the hub keeps Qwen's
as provisional. The row is then **Unconfirmed**: shown, never saved
automatically; the user can check it and accept it (key 1) or re-send it.

### 6.3 Qwen down

The hub's `unreachable` guard moves Qwen's jobs to the free Nemotron (as a
stand-in, before any attempt, once `ollama-studio` is held as unreachable).
Their route reads `Nemotron (Qwen down)`. While Qwen is unreachable, the
status bar says so in red; the Models tab shows the gateway's state.

### 6.4 What is recorded

- The response transcript gets a **Confidence** section after the reply:
  the well-formed result, C, the geometric-mean and minimum token
  probability, and every candidate position: the token chosen and its
  probability, the rival and its probability, the compared continuation,
  and contested or same reading.
- Escalated questions get a **Qwen–Nemotron difference** (note §7): the
  share of the normalised texts that differ outside the contested
  positions. It orders spot checks (§8).
- `token-usage.csv` and the hub's request history, as in the other apps.

## 7. LCT groups

### 7.1 The passage first

The pending fetch gives each LCT question its `lctCtxId` and
`lctCtxTranscribed`. Before any question of a group is sent, the app needs
the group's passage, from (first that has it):

1. a held passage for the group (`held-passages.json`), if its prompt
   SHA-256 is the current one;
2. the server: `GET /Transcription/LctCtx` when `lctCtxTranscribed` is
   true (its `content`, exactly as returned: the server compares the
   prefix exactly);
3. otherwise a **passage job**: the `_Ctx` images, `TASK LCT_PASSAGE`,
   through the same gate and escalation as a question. Its result becomes
   the held passage.

One passage job per group: the group's questions wait for it (status
*Waiting for passage*) and are submitted when it ends. If it fails, they
fail with "the passage could not be transcribed". The group's first
question in the table shows the passage job's progress in its detail line;
the passage's attempts are in the Transcripts tab under that question.

### 7.2 The question part

Each LCT question is then sent with its own images and the passage in
`{{PASSAGE}}` (`TASK LCT_QUESTION`). The gate scores the question part
only. A question part that starts by repeating the passage's first line
fails the well-formed check ("repeats the passage").

### 7.3 Content and saving

The row's content is `passage + "\n\n<!-- question -->\n\n" +
questionPart`, built when the row is shown or saved, so a new passage
reaches the group's staged rows without asking a model again. On Save the
items are ordered so each group's questions are together; the first item of
a group in a request carries `lctCtx` (content and `aiModel`) if the
passage is held, not stored. After an APPLIED item with `lctCtx`, the held
passage is dropped.

### 7.4 A faulty passage

A passage marked faulty (key 3 on any of its group's rows) is re-sent to
the next model, as for a question (§8.2): Qwen's to Nemotron, Nemotron's
to GLM. Its group's staged rows are rebuilt from the new one.
If the old passage was already on the server, the new one is held, and on
the next Save: the group's first staged question carries it as `lctCtx`;
if none is staged, the app re-saves the group's saved questions instead.
Either way, after the save it reads `GET /Transcription/LctCtx` and
re-saves every question with `current: false`: its question part is cut at
the marker from `GET /Transcription/{questionId}`, and the new passage put
in front. No model is asked.

## 8. Spot checks

### 8.1 The view

The right side's **Check** tab shows the selected row: the image
(scrollable, full width) above the rendered content in a `WebView`, in a
vertical split. A *Markdown* toggle shows the raw markdown instead, with
the contested positions highlighted. For an escalated row a third pane can
show Qwen's rendered transcription beside Nemotron's.

`apps.transcriber.ui.MarkdownView` is a `JFXPanel` holding the `WebView`,
loading `web/view.html` from the classpath once (markdown-it, MathJax 3
with mhchem, SmilesDrawer, a stylesheet; all bundled under
`src/main/resources/apps/transcriber/web/`, no network). It shows a content
by calling a page function with the markdown (passed as a JSON string, on
the FX thread). A figure (a blockquote) renders as a framed box with its
description, maths drawn as anywhere else; a `> smiles:` line inside is
taken out before markdown-it and drawn as a structure, or shows "SMILES
not drawable" in red. An old `diagram` fence is framed the same way, its
text as written. The LCT marker renders as a
thin rule, so the passage and question part are told apart.

### 8.2 Marks

Keys on the table (and the row menu, and the Check tab's buttons):

| Key | Action |
|---|---|
| **1** | Checked: correct. A *Unconfirmed* row becomes *Transcribed* (Qwen's, accepted by the user) |
| **2** | Faulty: re-send the question (part) to the next model: Qwen's to Nemotron (the `escalate` root), Nemotron's to GLM (the `recheck` root). A GLM transcription marked faulty becomes *Faulty* |
| **3** | Faulty passage (LCT only): §7.4 |
| **4** | Edit (§8.4) |
| **5** | Skip to the next row to check |

A checked row shows a tick in a Checked column. Marks are written to the
audit log; they are kept with the staged transcription until it is saved.
After a mark the selection moves on in **check order**: escalated rows by
their Qwen–Nemotron difference, largest first; then *Unconfirmed*; then
Qwen's by C, largest first (ties by lowest token probability). After
calibration nearly everything is Qwen's, so C is the order that matters.

A re-sent row keeps its old transcription until the new one arrives. The
new one is accepted as it is, like an escalated one, and its route reads
`Nemotron (re-sent)` or `GLM (re-sent)`. A *Faulty* row is never saved and
stays pending on the server; the user can transcribe it some other way.

### 8.3 Saved questions

Unattended runs save without the user looking. **File > Spot-check saved
questions…** lists the transcriptions saved in a period (from the audit
log: server id, question, route, metrics, saved at), in check order, and
shows each from `GET /Transcription/{questionId}` in the same Check view.
Marking one faulty re-sends it to the next model (§8.2) and stages the new transcription
in the main table, where Save replaces the stored one (the POST is an
upsert).

### 8.4 Editing

**Edit** (key 4, the row menu, a button on the Check tab) opens the row's
raw markdown in an editor beside the rendered view, which follows the text
as it is typed (re-rendered after a short pause). For an LCT question only
the question part is edited; the passage is edited from any row of its
group with **Edit passage**, and the group's staged rows are rebuilt from
it (§7.3–7.4).

- **Done** keeps the edit: the row is *Transcribed*, its route `Edited`
  (with the model whose text was edited, e.g. `Edited (Qwen)`), and it is
  staged like any other transcription. **Cancel** drops it.
- The edited text passes the well-formed checks (§6.2) before it is kept;
  a failure is shown in the editor and the edit can't be kept until it is
  fixed or cancelled.
- Saved with `aiModel` `user`, so the server's record shows a hand
  correction. An edited passage goes as `lctCtx.aiModel` `user`.
- The audit log keeps the text before and after.
- A *Faulty* row (§8.2) can be edited too, which is how it gets saved.
- A saved question can be edited from the saved-questions spot check
  (§8.3); the edit is staged in the main table and saved over the stored
  one.

Given decision 14, an edit is for a material fault; minor ones are left.

## 9. Table and window

As the reviewer's `TopicReviewFrame`, unless said otherwise.

- **Columns**: Id, Source, Question, Type, Route, C, Status, Checked, dot
  (red until saved, green once saved). Sorting and header filters as in the
  reviewer.
- **Statuses**: *Pending*, *No local images*, *Waiting for passage*,
  *Transcribing* (with the model asked in the detail line), *Transcribed*
  (staged), *Unconfirmed*, *Faulty* (GLM's transcription marked faulty:
  no model left to ask), *Failed*, *Saving*, *Saved*, *Error*. One background colour
  each.
- **Detail line** above the Check tab: route, C and why it escalated, the
  failure, or the server's message.
- **Right side**: tabs *Check* (selected at startup), *Transcripts*,
  *Models*, above the rate-limit strip.
- **Status bar**: rows by status, the cursor (`afterId`), how many await
  transcription for the criteria (`totalResults`), Qwen's state, and the
  model queue with the next request time, as in the reviewer.
- **Menus** (Swing `Action`s shared with the toolbar): File (Criteria…,
  Load ⌘L, Load from start ⇧⌘L, Run unattended ⌘U, Spot-check saved
  questions…, Exit), Transcribe (Start ⌘R, Stop ⌘., Edit, Edit passage, Send selected to
  to the next model, Re-queue failed questions), Check (1, 2, 3, 5 as items), Save
  (Save ⌘S, then load again), View (status filter, clear filters, select
  all ⌘A, rate-limit bar).

## 10. Load, Start, Save, unattended

- **Criteria…** as the reviewer's dialog: syllabuses, topics, batch size
  1–100. Saving new criteria resets the cursor.
- **Load** fetches the next `batchSize` pending questions after the cursor
  and moves it past them; **Load from start** resets it first. Load
  replaces the table's rows, asking first if staged transcriptions would be
  lost (they are kept in `staged-transcriptions.json` anyway and come back
  when the question is next loaded). Staged transcriptions for loaded
  questions are restored without asking a model.
- **Start** sends every *Pending* row in table order (passage jobs first
  for their groups). A send while a run goes joins it, as in the reviewer.
- **Save** sends every *Transcribed* row (Qwen's accepted, escalated,
  Nemotron's), checked or not, in requests of at most 100. *Unconfirmed*
  rows are saved only once checked. APPLIED → *Saved*; REJECTED or a failed
  request → *Error* with the message, staged transcription kept.
- **Unattended** as the reviewer's: asks for the number of loads; Load,
  Start; each time half a batch is *Transcribed*, save those, drop the
  saved rows and fetch half a batch more after the cursor; when the run
  ends, save the rest and pause 5 s before the next load. *Unconfirmed*
  and *Failed* rows are left for the user. It ends after its loads, when
  nothing is pending, on a failed load, when a batch saves nothing, or when
  Qwen is down **and** the free Nemotron is held for the day (decision 4).
  Logged with `[unattended]`.

## 11. Dependencies

| Library | Use |
|---|---|
| `org.openjfx:javafx-web`, `javafx-swing` (21.x, classifier `mac-aarch64`) | `WebView` in a `JFXPanel` |
| `org.openscience.cdk:cdk-smiles` (+ `cdk-silent`) | The SMILES well-formed check |
| markdown-it 14.1.0, MathJax 3.2.2 (`tex-svg-full`: SVG output needs no font files, mhchem included), SmilesDrawer 2.1.7 (drawn with `SvgDrawer`) | In `web/lib/`, pinned versions with their licences, offline |

`exec:java` needs the JavaFX modules on the module path or launching
through a non-`Application` main with JavaFX on the class path; the build
notes go in CLAUDE.md with the run command.

## 12. Open points

| # | Point | Proposal |
|---|---|---|
| Q7 | Tesseract overlap and length checks (note §8) | Parked by the user |

## 13. Implementation order

1. AI hub: sampler, logprobs, `AIReply.tokens`, the `TaskPrompter` /
   `TaskOutcomes` hooks; usage guides.
2. The gate: well-formed checks (with CDK) and the score. First
   `calibration/replay.py` gets the rev 3 dash and byte rules and the
   golden cases are regenerated; then the Java gate must reproduce them
   (`Cmdx` and the §4.1 results), checked at build time.
3. Prompter (prompt file parser), outcomes, server client, workspace
   stores.
4. Frame, table, criteria, Load / Start / Save; LCT passage jobs.
5. `MarkdownView` with the bundled renderer; Check tab, marks and editing.
6. Unattended run; saved-questions spot check.
7. CLAUDE.md section, run command.

Each step compiles and runs; the app is first tried on dev with a handful
of questions per type, LCT groups included.

## Revision log

| Rev | Date | Change |
|---|---|---|
| 1 | 2026-11-17 | First version, for approval |
| 1.1 | 2026-11-17 | O1 settled: a faulty Nemotron transcription goes to GLM (`recheck` root), not the paid Nemotron (same model) |
| 1.2 | 2026-11-17 | Decision 14 (purpose: the tagger; wide tolerance), decision 15 and §8.4 (hand editing in v1); O2 settled |
| 1.3 | 2026-11-17 | O3 settled: Qwen has no retries; a malformed, cut-off or timed-out Qwen reply falls back to the free Nemotron at once (§4.2, §6.2) |
| 1.4 | 2026-11-17 | Calibrated (note rev 3): C doesn't route, it orders spot checks; `gate.route.on.c`; Qwen `maxParallel` 3; prompt v3; golden cases; O4 settled |
| 1.5 | 2026-11-17 | As implemented: MathJax's SVG build instead of CHTML (§11); a passage's transcripts under its `_Ctx` id and the saved audit lines keep the server question (§4.3); the spot check loads the chosen saved questions into the main table (§8.3) |
| 1.6 | 2026-11-17 | Figures are blockquotes (`> ` lines, a blank line after; `>` used for nothing else), not `diagram` fences, so their maths is LaTeX and renders: a code block shows LaTeX raw, which is why prompt v3 kept descriptions to Unicode plain text (prompt review P8). Prompt v4. The gate reads a blockquote's lines as it read a fence's description lines (not scored), and still reads `diagram` fences, so the golden cases replay unchanged; the app refuses a `diagram` fence, a figure without description and one not followed by a blank line (§6.2). The server stores and indexes the markdown as given, so it needs no change (its format doc, rule 4, updated) |
| 1.7 | 2026-11-17 | §8.2: the row menu's *Transcribe with ▸* sends the selected questions to any usable model alone (pinned: its retries, no fallback or escalation), route `CHOSEN`; an LCT question only once its passage is known. Qwen gets a `passthrough` guard to the free Nemotron (Cascade in the Models tab) |
| 1.8 | 2026-11-17 | §8.1: a question given more than one transcription offers them in a drop-down in the Check tab (tabs at first, but they overflowed the width), read back from the audit log's `transcribed` lines (no new store), the one in use marked; *Use this one* takes the one shown instead, checked, if it is well formed. Not a diff view (the user's call: too heavy), but a second view beside the first, closed until its divider is dragged open, with its own drop-down, so any two transcriptions show side by side |
| 1.9 | 2026-11-17 | §8.3: a search box in the toolbar finds saved questions by the text of their transcriptions (the server's transcription search, a `text` Lucene query on `POST /Master/Question/Search`), optionally of one type and within the criteria's topics, and loads them straight into the table, 100 a page, to check as the spot check's are. For testing the search API and revisiting earlier questions. A hit with no stored transcription (a stale index entry) is left out, not a failed load |
