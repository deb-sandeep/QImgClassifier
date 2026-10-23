# QTagger: design

| Field | Value |
|---|---|
| App | `QTagger`, third entry point of QImgClassifier (`apps.tagger`) |
| Input | `input-docs/autotag-driver-design.md` (the driver outline) |
| Server | SConsoleNxt `2026-10-22/auto-tagging-support` and `2026-10-22/bulk-tag-import` (both implemented); asks in §9.2 |
| Status | Rev 17, 2026-10-23: implemented (§14); approving the first topic prompt and a first run are next |

QTagger associates diagnostic tags with the questions of the SConsole bank,
one topic at a time, through a two-tier LLM cascade gated by a blind solve
checked against the answer key. A human decides only what the automation
can't settle.

**Guiding rule:** wherever a use case overlaps the topic reviewer's
(loading a batch, running it through the hub, staging and saving decisions,
the unattended loop, the decision keys, the status bar, the logs), the tagger
does what the reviewer does. This document describes only where it differs
or adds to it.

---

## 1. Concepts

| Term | Meaning |
|---|---|
| **Axis** | The kind of a tag, given by its prefix: setup `s.` (a component present in the figure or stem, 0..n), concept `c.` (dominant on the intended solution path, 1–2), math `m.` (the technique is the crux, 0–2). A convention of the tag design; the server knows only tags. |
| **Catalogue** | A topic's tags that have an `aiAssociationHint` (`s.` and `c.`), plus Foundation Mathematics' (topic 124) hinted `m.` tags. Legacy tags have no hint and are never offered. |
| **Topic prompt** | The tuned, approved text that offers a topic's tags to the model (§6). The **offered set** is the tags it lists: the catalogue less what the user pruned. |
| **Scope** | The one topic a run works on, with a batch size and a tag mode. |
| **Tag mode** | *Existing tags only* (the model picks from the offered set) or *Review gaps* (it may also propose a missing tag, which pauses the run for the user). |
| **Blind solve** | The model gets the question image and type, never the key, and returns its own answer with its tags. A correct answer is the evidence that its solution path, and so its tags, can be trusted. |
| **Tier 1 / Tier 2** | The first-pass model, and the stronger model that solves again (also blind) when tier 1's answer misses the key. |
| **Dependency** (`xdep`) | Another topic of the same syllabus whose moves a question's solution needs. Recorded on the server and served back when that topic is run, so its tags get applied to the question (§3.10). |
| **Dependent** | A question run under a topic other than its own, because of an open dependency on that topic. |
| **Hand-off** | A question the automation can't settle; it waits for the user's decision and stays pending on the server meanwhile. |

---

## 2. Facts that shaped the design

Checked on 2026-10-22 against the server code and the local `sconsolenxt`
database.

| # | Fact | Consequence |
|---|---|---|
| F1 | The server owns the queue: a question is pending when `autoTaggingNeeded && !topicReviewNeeded` (`GET /Master/Question/AutoTagging/Pending`, same contract as `TopicReview/Pending`, first N by id). The flag is cleared only by `POST /Master/TagAssociation/Refresh`; nothing sets it back. Kinematics (103) starts cleared. | The tagger fetches by flag. A hand-off stays pending until its decision is saved. Questions saved before a new tag existed are not revisited unless re-queued by SQL. |
| F2 | `Refresh` replaces an item's **whole explicit tag set**. Tag reads give `TagVO.explicit`. | Before saving, the tagger reads the batch's current tags (`POST /Master/TagAssociation/QUESTION/Tags`) and sends the explicit tags outside the offered set unchanged, plus the decided ones. It never drops a tag it didn't offer (legacy tags, other topics' tags). |
| F3 | Answer evaluation is server Java (`SCAEvaluator`, `MCAEvaluator`, `NVTEvaluator`); no tolerance or range is stored per question. | A client `AnswerChecker` with the same rules: SCA, ART and LCT case-insensitive equality; MCA exact option-set equality (stricter than the exam's partial credit); NVT `\|key − answer\| ≤ 0.01` (`BigDecimal`), or the answer rounding to the key at the key's decimal places (55 of 194 keys have one place, while the prompt asks for two unless the question says otherwise); IVT exact. |
| F4 | `question` (23,858 rows): every question has an answer. Types SCA, MCA, NVT, IVT, ART, LCT (its own type, 827) and MMT (6, matrix-match `Ap,q#Bt…`). Choice keys use the labels the image prints: letters, or option numbers (`1`–`4`: 2,831 SCA, 9 ART, 8 MCA, 2 LCT). | The model is told to answer with the labels the image shows, and answers are compared as they are. MMT and any key the checker can't parse are *Unsupported*, never sent. All keys are taken as correct; key correction waits for a server API (§12). |
| F5 | The importer logs `tagSetVersion` but doesn't store it; `tag_question_map` holds only `explicit`. | Provenance lives in the tagger's audit log: catalogue fingerprint, template and prompt versions, model, tier, human or not (§10). |
| F6 | Every imported hint begins with its axis' rule ("Setup tag: apply on presence of the component…"), the same sentence in every hint of the axis. | The prompt states each axis rule once; the generated base strips the repeated sentence (§6.2). |
| F7 | `POST /Master/Tag` doesn't take a hint; `POST /Master/Tag/Import` (one topic's `sconsole-tagset` v2 file) does, and only ever adds or updates. | A new tag accepted in gap review is created with a one-tag import file (§3.9). No server change. |
| F8 | QImgExtractor sends `answer` with every question sync. | A key fix made only on the server would be overwritten; part of why key correction is deferred. |
| F9 | 180 pending Laws of Motion questions already carry hinted LoM tags, put on by hand; 9 pending questions elsewhere carry legacy tags. | Hand tags from the offered set are compared with the model's (§3.4); legacy tags are kept by F2's merge. |
| F10 | Foundation Mathematics (124) has 7 pending questions; its tags are the `m.` axis of every topic. | 124 is not offered as a scope; its questions are tagged by hand. |
| F11 | The reviewer's `ServerQuestion` lacks `answer`, `autoTaggingNeeded`, `lctSequence`, `questionNumber`. | It moves to `core.server` (both apps use it) and gains them. |

---

## 3. User journey

### 3.1 Startup

1. Load `qtagger.properties` and `qtagger.ai.json` (§11). The `tier1` and
   `tier2` roots must both be usable, else a dialog and exit, as the
   reviewer.
2. Fetch the topics of the three IIT syllabuses (`GET /Master/Topic/All`).
   Topics are static: never renamed, ids never change.
3. Restore the saved scope (`tagger-scope.json`), else open the Scope
   dialog.

### 3.2 Prepare a topic prompt (once per topic)

Each topic's prompt goes out hundreds to thousands of times to paid models,
so it is generated once, tuned by hand, reviewed, and approved; runs use it
as approved.

1. **Prompts > Generate topic prompt…** — pick a topic; the list marks each
   *none* / *base only* / *draft* / *approved* / *stale*. The tagger fetches
   the topic's and topic 124's hinted tags and writes `tags.generated.prompt` in
   the topic's folder: the axis sections with each rule once, a line per tag
   with its hint less the axis sentence, every 124 tag in the math section.
   Mechanical only, no rewording. The first time, it also copies it to
   `tags.reviewed.prompt`; after that it rewrites only `tags.generated.prompt`, so tuning
   is never lost, and a diff of the two shows what the tuning changed (or,
   after a catalogue change, what is new on the server).
2. **The user tunes `tags.reviewed.prompt`** outside the app: shortens hints, prunes
   the math tags that can't occur in the topic, adds topic notes, reorders.
   Tag lines keep the `- <tagText>:` form (§6.3).
3. **System review** of the tuned files with Claude: the offered set, reply
   validation, token cost, consistency across topics.
4. **Prompts > Review topic prompt…** shows the assembled prompt exactly as
   the model will see it, its estimated input tokens, and per configured
   model the estimated input cost per 1,000 questions (OpenRouter catalogue
   prices, cache reads counted). It validates the file — every listed tag
   exists, has a hint and sits in its axis' section — and warns about hinted
   tags left out (allowed). **Approve** stamps the header (§6.3). The user
   commits the approved file.
5. A topic can be scoped only with an approved, current prompt. It is
   **stale** when the catalogue changed since approval (a tag added, renamed
   or re-hinted on the server: the fingerprint differs), and **draft** again
   when the file was edited (the content hash differs). Either refuses the
   run until it is reviewed and approved again.

### 3.3 Choose the scope (Scope dialog, ⌘K)

- One topic, from a list grouped by syllabus; topic 124 and topics without
  an approved, current prompt are disabled, with the reason shown.
- Batch size 1–500; tag mode (§3.9). Both saved with the scope; the mode is
  shown in the title bar.
- On OK the tagger fetches the catalogue (fingerprint check, tag ids),
  loads the approved prompt, and fills the **Prompt** tab (right side,
  beside Transcripts and Models): the offered tags by axis, the assembled
  prompt, its versions and token estimate.
- The scope can change only with no run going and nothing unsaved; changing
  it clears the table.

### 3.4 Load (⌘L) and Load from start (⇧⌘L)

As the reviewer's Load, with these differences:

- **Cursor.** Load fetches after the highest server id already seen in this
  scope (`afterId`, §9.2), so questions waiting for the user are left behind
  and new ones keep coming. **Load from start** resets the cursor and brings
  back everything still pending, hand-offs included. The topic's own
  questions and its dependents have a cursor each, saved with the scope.
- **Own first, then dependents.** Load brings the topic's own pending
  questions; once they are exhausted, its dependents (§3.10), marked
  *Dependent* in the table. The status bar shows both backlogs.
- For each batch, one read of the questions' current tags (F2).
- Images are found by `QuestionLocator`, as the reviewer; none → *No local
  images*.
- **Pre-check, no AI call:** MMT, or a key the checker can't parse →
  *Unsupported*, with the reason.
- **Stored replies.** A question with parsed replies from an earlier session
  (`pending-tagging.json`) is judged again locally and comes back in the
  state it reached, with no AI call, as the reviewer's pending verdicts.
- **Hand tags.** Explicit tags from the offered set already on the question
  show in the *Existing* column; the question is run like any other and its
  result compared with them (§3.5).

### 3.5 Run (Start ⌘R, send selected, Re-queue failed)

As the reviewer's run: questions go in table order, the hub works through
the models' slots, and the row shows progress. Per question:

```
tier 1 blind solve
  │  reply invalid → local fix, else one resend (§7.1); still invalid → Needs human (INVALID_OUTPUT)
  ▼
answer = key?
  ├─ yes ─► gap proposed (Review gaps mode)? ── yes ─► Gap proposed: the run pauses (§3.9)
  │            │ no
  │            ▼
  │         no concept tag (own question)?     ── yes ─► Needs human (NO_CONCEPT)
  │            │ no
  │            ▼
  │         drops a hand tag already on it?    ── yes ─► Needs human (EXISTING_DIFFERS)
  │            │ no
  │            ▼
  │         Accepted T1  (tags + dependencies staged)
  │
  └─ no ──► tier 2 blind solve (escalation: never sees the key or tier 1's reply)
               answer = key?          ── yes ─► same checks as above ─► Accepted T2
               answer = tier 1's?     ── yes ─► Needs human (KEY_SUSPECT)
               otherwise, or tier 2 gave no usable reply  ─► Needs human (UNSOLVED)
```

Accepted rows are staged, as the reviewer's *Agreed* rows, and saved with
Save (§3.7) or by the unattended loop (§3.8).

### 3.6 Decide the hand-offs

The decision panel (right, above the tabs) shows, for the selected row: the
key, the hand-off reason and any existing hand tags; then tier 1 and tier 2
side by side — answer with ✓/✗, tags by axis, each concept tag's `why`, the
dependencies as topic names. Under them, a **tag editor**: the offered set
as three checkbox groups preset to the proposal in view, enforcing the axis
counts (concept 1–2 and math 0–2; for a dependent, concept 0–2 and no math),
and, for a home-topic row, the syllabus' topics as a checkable dependency
list.

Keys on the table (main row or keypad), as the reviewer's:

| Key | Action |
|---|---|
| 1 | Accept tier 1's tags |
| 2 | Accept tier 2's tags |
| 3 | Edit… — the decision is the edited set |
| 4 | Keep the existing hand tags (rows that have them) |
| 5 | Defer — keep the question pending, with a note (e.g. key suspect) |
| 6 | Skip — move on, no decision |

Decisions are staged (*Decided*), the selection moves on to the next
hand-off, and multi-row selections behave as in the reviewer (1 and 2 per
row; Edit applies one set to all). **Key suspect** rows are decided like
the others: the tiers agree on an answer, so their path is likely sound;
or deferred, which lists the key to check in `tagger-notes.jsonl`.

### 3.7 Save (⌘S)

As the reviewer's Save decisions: every *Accepted* and *Decided* row (or
the selection's, from the row menu), through `Refresh` in requests of at
most 500, off the EDT, with the reviewer's spinners, dots and "then load
again". Each item carries the merged tag set (F2) and, for a home-topic row,
its dependencies; a dependent's item closes its dependency (§9.2).
`APPLIED` → *Saved*; `REJECTED` or a failed request → *Error* with the
message, decision kept. Every applied item goes to the audit log.

### 3.8 Unattended (⌘U)

The reviewer's loop: load, start, save the settled rows every half batch and
refill, save the rest at the end, pause, load the next. Loads and refills go
through the cursor, so hand-offs never stall it. It ends on the reviewer's
conditions, or when both cursors have passed the last pending question. A
gap proposal (§3.9) pauses the loop with the run; it resumes once the
proposal is decided. Hand-offs wait for an attended Load from start.

### 3.9 Tag mode and gap review

| Mode | Prompt | Reply | On a correct answer |
|---|---|---|---|
| *Existing tags only* | Choose only from the offered set | no `gap` field | Accept as usual |
| *Review gaps* | May propose a new tag of this topic when a dominant move has none | `gap`: null, or `{tag, hint, why}` | A proposal pauses the run |

A proposal counts only on a correct answer; a wrong answer follows the
cascade as usual.

**Pause.** No new request is sent; requests in flight finish (further
proposals queue behind the first); the unattended loop pauses; the status
bar says why. Unstarted questions stay queued.

**Gap dialog.** The question, the proposal (tag text, hint, `why`) and the
topic's tags of that axis. The user:

- **accepts** it, editing tag text, full name, description, hint and colour
  as needed. The tagger creates the tag with a one-tag import (dry run, then
  real; F7), appends its line to `tags.reviewed.prompt` and re-stamps the approval
  (`promptVersion` + 1, new fingerprint and hash: accepting the tag approves
  that line), rebuilds the prompt for the questions still to be sent, and
  adds the tag to the question's proposal (*Decided*);
- **maps** it to an existing tag the model missed: the question takes that
  tag, nothing is created;
- **rejects** it: the question keeps the model's other tags (accepted as
  they are).

Every proposal and its fate goes to `tagger-notes.jsonl`. Then the run
resumes; the next queued proposal, if any, opens next. Replies received
under the earlier prompt are not re-sent. Questions saved before the new tag
existed don't get it (F1); the audit log can list them for a re-queue.

### 3.10 Cross-topic dependencies

Other topics' tags are not sent (too costly, and untuned). Instead:

- **Every run** lists the syllabus' other topics (id and name, about 250
  tokens in the cached prefix; Foundation Mathematics 124 and Foundation
  Vectors 125 left out), and the reply carries `xdep`: the topics of the
  same syllabus *critical* to solving the problem, at most two, most
  important first (`ReplyValidator.MAX_DEPENDENCIES`; extra ones are cut,
  noted in the reply's fixes). Most problems touch other topics somewhere;
  as in topic classification, the focus stays on the dominant topic, and a
  dependency is the exception. A routine step never makes one (Laws of Motion's problems all end
  on the constant-acceleration equations, its vertical circles use energy
  conservation): each topic names its routine cases in a `## Dependencies`
  section of `tags.reviewed.prompt`, which the offered set skips and a
  dependent is not sent. Maths dependence is the `m.` axis' job.
- A dependency counts only on a correct answer, can be edited in the
  decision panel, and is saved with the question's tags as an **open**
  dependency on the server.
- When a topic is run, its dependents are loaded after its own questions. A
  dependency found after its topic was run stays open until that topic is
  next run.
- A dependent is sent with the scoped topic's approved prompt, blind solve
  and answer check as usual (concept tags rest on the solution path), with
  only the `s.` and `c.` axes (math was done in its home run), 0–2 concept
  tags, and its own `xdep` ignored (no chaining).
- Saving a dependent replaces only the scoped topic's tags (F2's merge keeps
  the rest) and closes the dependency.

---

## 4. Row states

| Status | Meaning |
|---|---|
| Pending | Loaded, not sent |
| Unsupported | Pre-check failed (MMT, unparseable key); never sent |
| No local images | As the reviewer |
| Running T1 / T2 | Request in flight to that tier (its cell framed in red) |
| Accepted T1 / T2 | Answer matched; tags staged for saving |
| Gap proposed | Review gaps: a new tag proposed; the run is paused |
| Needs human (reason) | `KEY_SUSPECT`, `UNSOLVED`, `INVALID_OUTPUT`, `EXISTING_DIFFERS`, `NO_CONCEPT` (a reply may give no concept tag only while proposing a concept gap; once that gap is rejected, or settled on a setup tag, the user chooses one) |
| Decided | A human decision (or an accepted gap), not yet saved |
| Deferred | Noted for later; stays pending |
| Saving / Saved | Refresh in flight / applied |
| Failed | Infrastructure failure after the hub's retries; Re-queue failed |
| Error | The server rejected the save |

Skip is not a state. *Dependent* is a marker on the row and a filter.

---

## 5. Screen

The reviewer's frame: the table (64% of the width) with the detail area,
preview and a compare strip on the left; the decision panel above the
Transcripts / Models / Prompt tabs and the rate-limit strip on the right;
the status bar with the model queue across the bottom.

- **Columns:** Id, Source, Question, Type, Key, Existing, Tier 1 (answer
  ✓/✗ and tag chips), Tier 2, Status, the saved dot. Header filters and the
  status filter as the reviewer's.
- **Compare strip:** the key and the two tier answers in a large font, green
  where they match the key.
- **Menus:** File (Scope… ⌘K, Load ⌘L, Load from start ⇧⌘L, Run unattended
  ⌘U, Exit), Prompts (Generate topic prompt…, Review topic prompt…), Run
  (Start ⌘R, Stop ⌘., Send selected, Re-queue failed), Decisions (1–6,
  Save ⌘S, Load again after saving, Clear decisions…), View (as the
  reviewer). Commands are shared `Action`s with the toolbar, as there.

---

## 6. Prompts

### 6.1 Folder

Git-tracked in this repo; the path is the `prompts.dir` property.

```
src/main/resources/prompts/
  qtagger.prompt                shared template, all topics
  axes.prompt                   axis rules: ## Own, ## Dependent
  IIT Physics/
    104-Laws of Motion/
      tags.generated.prompt     base; rewritten by Generate
      tags.reviewed.prompt      tuned and approved; the one runs use
  IIT Chemistry/
  IIT Maths/
```

Folders are `<syllabus name>/<topic id>-<topic name>`, as the server names
them (characters a file name can't hold are replaced). The tagger reads and
writes them in the source tree through `prompts.dir`, not the classpath:
Generate and Approve write to them, and a tuned file must take effect
without a rebuild.

### 6.2 Assembly

The fixed part is the first content block, so provider prompt caching keeps
it for the whole run; the question is the second.

| Order | Piece | Source |
|---|---|---|
| 1 | Role, rules (solve it yourself; tags only from the lists; answer with the labels the image prints; reply with the schema only), reply schema for the mode; the per-axis counts | `qtagger.prompt`, `axes.prompt` |
| 2 | Setup, concept and math sections — each rule once, then `- <tagText>: <hint>` lines — and topic notes | `tags.reviewed.prompt` |
| 3 | The syllabus' other topics, `<id>: <name>`, for `xdep` | built from the topic list |
| 4 | *(second block)* `Question type: <type>`, then the image (LCT context stacked on top, as now) | per question |

For a dependent, the math section is left out and the schema allows 0–2
concept tags and no `xdep`; it is a second cached variant of the prefix.

### 6.3 `tags.reviewed.prompt` format

A header of `key: value` lines — `topicId`, `status` (`draft` /
`approved`), `promptVersion`, `catalogueFingerprint` (hash of the hinted
tags' ids, texts and hints at approval), `contentHash`, `generatedAt`,
`approvedAt` — then a `---` line and free text. The text is sent as written;
its `- <tagText>:` lines are how the tagger knows the offered set, and
replies are validated against it. `qtagger.prompt` carries a
`templateVersion`, which also covers `axes.prompt`. Both versions go into
every transcript and audit entry.

### 6.4 Reply

```json
{"ans": "C",
 "setup": ["s.connector.pulley"],
 "concept": [{"t": "c.dyn.sys-choice", "why": "equivalent mass of pulley subsystem"}],
 "math": [],
 "xdep": [105],
 "gap": null}
```

- `ans`: the labels the image prints (`"C"` or `"3"`; MCA `"A,C"` or
  `"1,3"`); NVT and IVT a number, as a string (NVT rounded as the question
  asks, else to two decimal places). `null` when the model can't answer
  reliably (an unreadable figure, missing data, an ambiguous question):
  the rest of the reply is ignored, and the question goes to tier 2 as a
  miss would, so an abstention never has its tags accepted.
- `why`: at most 12 words. `xdep`: topic ids from the list in the prompt
  (unknown ids are dropped with a warning). `gap`: present only in *Review
  gaps*, null or `{"tag": …, "hint": …, "why": …}`.
- Sent as a strict JSON schema (OpenRouter `response_format`, `json_schema`)
  to models whose catalogue entry lists `structured_outputs`: the tag lists
  are `enum`s of the offered tags, `xdep` of the topics offered; the counts
  are still checked locally. A provider may ignore it, so the reply is
  still parsed leniently, as `TopicPrompter.parseTopics` (fences, extra
  fields), and a stand-in `gap` (`"none"`) is ignored.

### 6.5 Image preparation

Optional downscale to a configured width and greyscale, once per question
(`core.util.ImagePrep`); off by default, switched on per the user's own
pilot.

---

## 7. AI hub

### 7.1 Cascade

`qtagger.ai.json` has two roots, routed `inOrder`, as the reviewer's:
`tier1` (the free Nemotron, falling back to the paid one on
`rateLimitedRPD`, as in the reviewer; Gemma later) and `tier2` (GLM, with
reasoning), reached only by escalation, as the reviewer's tie-breaker.

`TaggerOutcomes` (the tagger's `TaskOutcomes`) checks each reply:

1. **Local fixes**, free, only where the mapping is unambiguous: case and
   whitespace, a missing axis prefix, a tag given by its full name
   (`setup.connector.pulley`). Each fix is noted on the attempt.
2. Still unparseable, a tag not in the offered set, wrong axis counts →
   reject (`BAD_REPLY`); the model's `retries: 1` is the one repair, a full
   resend.
3. Tier 1 answer ≠ key → escalate to `tier2`, keeping tier 1's reply as
   provisional.
4. Otherwise accept. Tier 2's outcome (matches, agrees with tier 1, or
   neither) is settled in the outcome handler.

A reply cut off at the length limit fails as `TRUNCATED`: the model's
`truncated` guard, else a hand-off as `INVALID_OUTPUT`. A cheaper repair
that sends the bad reply back with the error is left for later, if the
usage log shows invalid replies are common.

### 7.2 Hub extensions

Backward compatible: every addition is optional, and the classifier's and
reviewer's files behave exactly as today.

| Addition | When absent | Sent as |
|---|---|---|
| Model `reasoningEffort` (`low` / `medium` / `high`), with `reasoning: true` | the model's default, as today | OpenRouter `reasoning: {effort}` |
| Model `reasoningMaxTokens`, with `reasoning: true`; not with `reasoningEffort` | the model's default | OpenRouter `reasoning: {max_tokens}` |
| Model `maxOutputTokens` | no limit sent, as today | OpenRouter `max_tokens`, Ollama `num_predict` |
| `FailureKind.TRUNCATED` (finish reason `length`) and guard `truncated` | a cut-off reply falls back by the `badReply` guard, as today | — |

The hub's javadocs and usage guides are updated with them.

---

## 8. Server calls

| Call | Use |
|---|---|
| `GET /Master/Topic/All?syllabusName=` | Topics |
| `GET /Master/Tag/Topic/{id}` | The scope's and topic 124's tags (catalogue, fingerprint) |
| `GET /Master/Question/AutoTagging/Pending` | Load, refill (with `afterId`, §9.2) |
| `GET /Master/Question/AutoTagging/Dependents` | Dependents (new, §9.2) |
| `POST /Master/TagAssociation/QUESTION/Tags` | Current tags of a batch (F2) |
| `POST /Master/TagAssociation/Refresh` | Save (with the dependency fields, §9.2) |
| `POST /Master/Tag/Import` | Create an accepted gap tag |

---

## 9. Server

### 9.1 Already there

The pending flag and its fetch, the bulk refresh and the bulk tag read, the
tag import with hints. No change is needed for anything but §9.2.

### 9.2 Asks

Sent as **Client Asks A1–A5** in SConsoleNxt
`doc/feature-enhancements/2026-10-22/auto-tagging-support/api-changes.md`,
which holds the contract. **Settled** (rev 7): all accepted, the server's
questions answered, Q4's re-queue rule confirmed. The server folds them into
its contract sections and implements them next; until then the tagger can
build everything except the cursor, dependency writes and the Dependents
fetch.

- **A1** `question_topic_dependency`: question, topic (same syllabus, not
  its own), `open`, provenance. When a topic change moves the question into
  a topic it depends on, the row is deleted; if it was **open**, the
  question is re-queued (`autoTaggingNeeded = true`) and the tagger tags it
  afresh in its new topic. This holds on every path that changes a topic
  (question save, the topic endpoint, topic review); a question flagged for
  topic review stays out of Pending until the review clears.
- **A2** `Refresh`: `dependencyTopicIds` + `dependencyAssocActor` /
  `dependencyAssocAiModel` replace a question's dependencies (every old row
  deleted, each listed topic added as a new open row); `forDependencyOf` applies the
  tags, closes that dependency (idempotent) and leaves the question's flag
  alone. Duplicate topic ids are collapsed. Results carry
  `topicDependencies`, also for a rejected question (its unchanged rows).
- **A3** `GET /Master/Question/AutoTagging/Dependents?topicId=&limit=&afterId=`.
- **A4** optional `afterId` on `AutoTagging/Pending` (and, the server's
  choice, `TopicReview/Pending`); `totalResults` still counts everything.
- **A5** `QuestionVO.topicDependencies` (`topicId`, `topicName`, `open`),
  loaded only in the two `AutoTagging` fetches and the Refresh results (`[]`
  when none); `null` elsewhere means "not loaded".

Asked separately by the user, later: an answer-update API (§12).

---

## 10. Workspace

`workspace.dir` = `~/projects/workspace/qtagger`, created at startup if
missing, separate from the classifier's and the reviewer's. Files are named
by properties relative to it, as in the reviewer.

| Path | Content |
|---|---|
| `<source>/<questionId>/<yyyyMMdd-HHmmss-SSS>-a<n>-<agent>-request.txt` and `-response.txt` | **LLM transcripts**, as the reviewer's (`TranscriptStore`): every attempt's prompt and raw reply or error, with the tokens and any reasoning. Headers add the tier, the scope topic (a dependent's attempts land in its own question's folder, under the topic they were run for), `templateVersion` and `promptVersion`. Shown in the Transcripts tab. |
| `pending-tagging.json` | Per server id, for questions not yet saved: the key judged against, each tier's parsed reply (answer, tags, `why`, `xdep`, `gap`, model), the state and hand-off reason, any deferral note. Rewritten on change, as `pending-verdicts.json`; an entry is dropped once its save is applied. Reused on Load while the key and topic are unchanged. |
| `tag-audit.jsonl` | Append-only, as `review-audit.jsonl`: every proposal and decision with the catalogue fingerprint, template and prompt versions, model, tier, human or not, the tag ids sent and the server's result. The provenance F5 calls for. |
| `tagger-notes.jsonl` | Append-only: gap proposals and their fate, deferrals (e.g. key suspect), for the tag design work. |
| `tagger-scope.json` | Scope topic, batch size, tag mode, the two cursors |
| `token-usage.csv`, `request.history.json` | As the reviewer's |

The API key file is the classifier's, as for the reviewer.

---

## 11. Configuration

`src/main/resources/apps/tagger/qtagger.properties`, read through
`ConfigProperties` with no defaults in code, as the reviewer's:
`server.url`, `server.connect.timeout.seconds`,
`server.request.timeout.seconds`, `question.bank.dir`, `workspace.dir`,
`prompts.dir`, `scope.file`, `pending.tagging.file`, `tag.audit.file`,
`tagger.notes.file`, `request.history.file`, `usage.log.file`,
`image.prep.width` (0 = off), `image.prep.greyscale`.

`src/main/resources/apps/tagger/qtagger.ai.json`: parent
`common/ai-model-config.json`; roots `tier1` and `tier2`; per model
`maxParallel`, `requestTimeoutSeconds`, `retries: 1`, `reasoning` and the
§7.2 settings, `promptCaching`, fallbacks with `rateLimitedRPD`, `truncated`
and the reviewer's guards.

---

## 12. Out of scope (for now)

- **Key correction.** Keys are taken as correct. Once the server has an
  answer-update API, a "Key is wrong…" action writes the key, logs it, and
  re-judges the stored replies against it without an AI call;
  `pending-tagging.json` keeps them from the start for this.
- **Pilot and golden-set reporting**, compare mode, dry run: done by the
  user by hand, from the transcripts, audit log and usage CSV.
- **Audit sampling** of accepted rows: none, as in the reviewer.
- Topic 124 as a scope; re-classifying a question's topic (the reviewer's
  job); retiring or renaming tags; re-queueing saved questions (SQL).

---

## 13. Code structure

### 13.1 Reuse

| Piece | In the tagger |
|---|---|
| `TaskScheduler`, `TaskOutcomes`, the hub, `ModelsPanel`, `TranscriptPanel`, `TranscriptStore`, `RateLimitStrip`, `UsageLog`, `JsonFiles` | As they are |
| `ServerQuestion(Image)`, the pending fetch of `SConsoleServerClient`, `QuestionLocator`, `HeaderFilterController`, `Spinner`, `ToolIcon`, the unattended loop | Moved to `core` (generalised where needed); the reviewer switches to them |
| `ReviewTableModel`, `ReviewDecisionPanel`, `TopicReviewFrame` | Followed as patterns, not shared: their columns and decisions are about topics |

### 13.2 Packages (sketch)

```
core.server      SConsoleServer, ServerQuestion, ServerQuestionImage,
                 PendingQuestions (pending fetch, cursor)
core.review      QuestionLocator, unattended-loop support (if it generalises)
apps.tagger      QTagger (main + controller)
  .model         Axis, CatalogueTag, TagCatalogue, OfferedSet, TagReply,
                 TagProposal, HandOffReason, TagDecision, TaggerStatus, Scope
  .check         AnswerChecker, ReplyValidator (incl. local fixes)
  .prompt        PromptStore (folders, header, stamping), PromptGenerator,
                 TagPrompter (assembly, variants, fingerprint)
  .pipeline      TaggerOutcomes, TaggerListener
  .server        TaggerServerClient (catalogue, current tags, refresh,
                 dependents, import)
  .workspace     PendingTagging, TagAuditLog, TaggerNotes, ScopeStore
  .ui            TaggerFrame, TagTableModel, TagDecisionPanel, TagEditor,
                 ScopeDialog, GapDialog, PromptReviewDialog, PromptTab
  .util          TaggerConfig
```

---

## 14. Implementation status

Implemented as designed, in the packages of §13.2 (CLAUDE.md, *Tagger*, has
the class map), with these differences:

| Where | Design | As built |
|---|---|---|
| §3.2 cost estimate | OpenRouter catalogue prices, cache reads counted | The global AI file's `costPerMillionToken` input prices (`ModelPrices`), list price without the cache discount: the catalogue client is internal to the hub, and the estimate is a ceiling |
| §7.1 / §11 reasoning | Tier 2 with reasoning | Every model of both tiers has `reasoning: true` (tier 1 solves blind too); tier 1 is capped by `maxOutputTokens` 12000, GLM by `reasoningEffort` medium, Haiku by `reasoningMaxTokens` 8000 |
| §3.6 tag editor | Under the tiers in the decision panel | A dialog (`TagEditorDialog`) opened by key 3, *Edit…*, preset to the decided, accepted or latest tier's set; the panel shows the two replies as text |
| §3.6 key 4 | Keep the hand tags | Leaves the question's dependencies as they are (`dependencyTopicIds` not sent) |
| §3.9 gap dialog | Accept, map, reject | Also *Later*: the dialog closes, the run stays paused, Decisions > *Review gap proposal…* reopens it. A gap restored from stored replies on Load pauses and opens the dialog as one arriving in a run |
| §3.4 status bar | Both backlogs | When the topic's own questions fill a Load, a one-question Dependents call gives that backlog's size |
| §13.2 | `PendingQuestions`, unattended support in `core.review`, `PromptReviewDialog`, `PromptTab`, `TagEditor` | `PendingResult` kept (now in `core.server`); the unattended loop lives in `TaggerFrame` as the reviewer's does in its frame; `PromptDialogs`, `PromptPanel`, `TagEditorDialog` |

**Checked against the dev server (localhost:8080):** topics, the catalogue of
Laws of Motion (41 hinted tags with topic 124's), generating its base prompt
(`IIT Physics/104-Laws of Motion/`, a draft `tags.reviewed.prompt`, not approved),
assembly (no problems; about 3,900 input tokens per own question, 3,000 per
dependent), Load with the cursor (562 own pending, 0 dependents), the bulk
tag read and hand tags, image location, and the app's startup.

**Not exercised yet:** a model run (the prompt isn't approved), Save
(`Refresh` writes to the server), and the gap import.

---

## Appendix A: decisions

| # | Decision |
|---|---|
| D1 | Blind solve; local answer check with the server's rules (F3). From the outline. |
| D2 | Two tiers by role, the key as arbiter; tier 2 by escalation. From the outline. |
| D3 | Option labels as the image prints them; no mapping. |
| D4 | Keys taken as correct; key correction waits for a server API. |
| D5 | Prompts generated once per topic as a mechanical base, tuned by the user, reviewed with Claude, approved in the app; runs use only approved, current prompts. |
| D6 | Prompts in `src/main/resources/prompts/<syllabus>/<id>-<name>/` (confirmed over `apps/tagger/prompts/`), read from the source tree; topics are static. |
| D7 | The base offers every Foundation Mathematics tag; the user prunes per topic. |
| D8 | Tag mode per scope; in *Review gaps* a proposal pauses the whole run; an accepted tag is created by import and joins the approved prompt. |
| D9 | Cross-topic needs become dependencies (topic ids), always asked, stored on the server, processed when that topic runs, after its own questions. |
| D10 | Hand tags from the offered set: run and compare; the tier's tags keep every hand tag (equal, or adding to a partial hand tagging) → accept, a hand tag dropped → hand off. |
| D11 | `afterId` cursor; Load and Load from start. |
| D12 | Topic 124 is not a scope. |
| D13 | No pilot, dry run or report in the app. |
| D14 | Invalid replies: local fixes, else one resend; truncation by its own guard. |
| D15 | Hub extensions optional, defaults keep today's behaviour. |
| D16 | Where a use case overlaps the reviewer's, the reviewer's design: staging and saving, unattended loop, no audit sample, workspace and transcripts. |

## Revision log

| Rev | Date | Change |
|---|---|---|
| 1–12 | 2026-10-22 | Drafts: facts from the server and DB, prompts, Q1–Q13 decided one by one |
| 17 | 2026-10-23 | §14: implemented; differences from the design and what was checked |
| 16 | 2026-10-22 | §9.2: server asks settled (server rev 7) |
| 15 | 2026-10-22 | §9.2: server response and client answers assimilated (re-queue on an open dependency deleted by a topic change) |
| 14 | 2026-10-22 | §9.2 points to the server Client Asks A1–A5 |
| 13 | 2026-10-22 | Rewritten as one document; decisions in Appendix A; workspace and transcripts under `~/projects/workspace/qtagger`, per source and question, as the reviewer |
