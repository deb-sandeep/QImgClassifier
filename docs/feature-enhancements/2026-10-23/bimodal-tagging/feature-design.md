# QTagger: bi-modal tagging

| Field | Value |
|---|---|
| App | `QTagger` (`apps.tagger`) |
| Builds on | `2026-10-22/auto-tagging/feature-design.md` (the single-pass tagger, rev 17) |
| Server | SConsoleNxt `2026-10-23/bi-modal-auto-tagging` (rev 1, implemented) |
| Status | Rev 3, 2026-10-23: implemented |

The single-pass tagger solves every question with a model strong enough to
get it right, because concept tags come from the solution. Over the ~40k
question bank that costs too much. Tagging is split into two passes, run
separately:

- **Setup**: a cheap model reads the question (no solving) and gives its
  setup tags. Run over the whole corpus.
- **Concept**: today's blind-solve cascade gives the concept and math tags
  and the dependencies. Run only on questions in an exam, so its cost grows
  with the exams.

This document describes only what changes from the single-pass design.
Everything not mentioned here stays as it is.

---

## 1. Concepts

| Term | Meaning |
|---|---|
| **Pass** | `setup` or `concept`: what one QTagger process tags, chosen at launch and fixed until it exits. The server's **tag class**; `TagClass` in code. Not to be confused with the *tag mode* (*Existing tags only* / *Review gaps*), which stays a scope setting within either pass. |
| **Setup tags** | `s.` tags of the question's own topic. The setup pass only. |
| **Concept tags** | `c.` tags of the topic, with the `m.` tags of Foundation Mathematics (124) and the dependencies (`xdep`). The concept pass only. |

The passes are independent: either may run first on a question, and each
leaves the other's tags and flag alone.

---

## 2. Server contract (summary)

| Change | Effect on the client |
|---|---|
| `autoTaggingNeeded` → `autoSetupTaggingNeeded` + `autoConceptTaggingNeeded` (in `QuestionVO` and the Refresh results) | `ServerQuestion`, `RefreshResult` read both |
| `AutoTagging/Pending` requires `tagClass` | Sent from the pass. `concept` returns only questions in an exam; `totalResults` likewise |
| `Refresh` requires a top-level `tagClass` | Sent from the pass; an applied own item clears only that pass's flag, a close item neither |
| `tagIds` is still the question's full explicit set | Each pass sends back every tag it doesn't replace (§6) |
| Absent `dependencyTopicIds` leaves dependencies untouched | The setup pass never sends them |
| `AutoTagging/Dependents` unchanged; not filtered by exam | The concept pass runs every dependent, in an exam or not (few, so accepted) |

---

## 3. Startup

1. **Choose the pass**, before anything else is loaded: a dialog with
   *Setup tagging (whole corpus)* and *Concept tagging (exam questions)*;
   Cancel exits. `-Dtagger.pass=setup|concept` skips it (unattended or
   scripted launches). The pass decides the AI file, the prompts and the
   workspace files, so it comes first.
2. Load `qtagger.properties` and the pass's AI file (§7); its roots must be
   usable, else a dialog and exit, as now.
3. Fetch the topics; restore the pass's saved scope, else open the Scope
   dialog.

The pass is shown everywhere a mix-up could happen:

- **Title**: `QTagger [SETUP] - <scope>` / `QTagger [CONCEPT] - <scope>`,
  the pass first so a truncated title still shows it.
- **Status bar**: a badge at its left end, `SETUP` or `CONCEPT`, on a
  colour of its own per pass.

Switching pass means restarting the app.

---

## 4. Setup pass

### 4.1 Run

- **Queue**: own questions only (`Pending?tagClass=setup`); no dependents,
  one cursor (`ownAfterId`).
- **One tier**, no answer: the model reads the image and gives setup tags;
  no blind solve, no key check, no escalation. The answer key plays no
  part, so MMT questions and keys the checker can't read are sent too
  (*Unsupported* is a concept-pass state only).
- **Reply**: `{"setup": [...], "gap": null}`; any number of setup tags,
  none included. `gap` in *Review gaps* only, and only an `s.` tag.

### 4.2 Judgement

| Outcome | State |
|---|---|
| Valid reply that keeps every setup hand tag (equal or adding to them) | *Accepted* |
| A setup hand tag dropped | *Needs human* (`EXISTING_DIFFERS`) |
| A gap proposed (*Review gaps*) | *Gap proposed*: pauses the run, as now |
| Reply unreadable or cut off after its retries | *Needs human* (`INVALID_OUTPUT`) |
| Anything else | *Failed* |

Accepted rows are saved by Save as now, so the user spot-checks them before
saving. The unattended loop saves them without a look; it is for when the
spot checks have shown the pass can be trusted on a topic.

### 4.3 Decisions and screen

- Keys: **1** the tier's tags, **3** Edit… (setup axis only), **4** keep
  the hand tags, **5** Defer…, **6** skip. **2** (tier 2) is disabled.
- `AnswerStrip`: no key or answers; the hand tags, then the tier's tags.
- Table: no answer or tier 2 columns.

---

## 5. Concept pass

Today's cascade (blind solve, key check, tier 2 on a miss, *Accepted T1 /
T2*, *Unsolved*, *Key suspect*, *Unsupported*), with the setup axis taken
out:

- **Queue**: own questions (`Pending?tagClass=concept`: in an exam only),
  then the dependents, as now (two cursors).
- **Own reply**: `{"ans", "concept", "math", "xdep", "gap"}` — no `setup`.
  1–2 concept tags, 0–2 math, `xdep` at most two topics.
- **Dependent reply**: `{"ans", "concept", "gap"}`: 0–2 concept tags of the
  scoped topic only, no math, no `xdep` (a dependent never adds
  dependencies), no setup tags. The tagger enforces this; the server
  doesn't check it.
- **Gaps**: only a `c.` tag; the rule that an own reply may give no concept
  tag only when its gap is a concept tag stays.
- Hand tags are the question's concept and math tags from the offered set
  (a dependent: the scoped topic's concept tags).

---

## 6. Save

Before each `Refresh` request the batch's tags are read again
(`POST /Master/TagAssociation/QUESTION/Tags`), and the item's `tagIds` are
built from that fresh read, not the one made at Load. Two passes saving the
same question close together then don't drop each other's tags.

| Item | Replaced | Kept (sent back unchanged) | Dependencies |
|---|---|---|---|
| Setup, own | The topic's offered setup tags | Every other explicit tag | Not sent: untouched |
| Concept, own | The topic's offered concept tags and the offered math tags | Every other explicit tag (setup tags included) | Written, with provenance, as now |
| Concept, dependent | The scoped topic's offered concept tags | Every other explicit tag | `forDependencyOf` = the scoped topic, as now |

`tagClass` is the pass. A *Saved* row is one whose item was applied.

---

## 7. Prompts

### 7.1 Folder

```
src/main/resources/prompts/
  qtagger.setup.prompt              setup template
  qtagger.concept.prompt            concept template
  qtagger.concept.axes.prompt       concept axis rules: ## Own, ## Dependent
  IIT Physics/
    104-Laws of Motion/
      setup-tags.generated.prompt
      setup-tags.reviewed.prompt
      concept-tags.generated.prompt
      concept-tags.reviewed.prompt
  IIT Chemistry/
  IIT Maths/
```

`PromptStore` keeps one root and is built for a pass, which picks the
template and the file names it reads and writes. The review and approval
workflow is unchanged and works per pass: Generate topic prompt, Review
topic prompt (assembled prompt, size, cost estimate), Approve, the states
*base only* / *draft* / *approved* / *stale*, only an approved prompt can
be scoped, and an accepted gap is appended and approved again. The Scope
and prompt dialogs' topic tables show the state of the running pass's
prompts.

### 7.2 Templates

- **`qtagger.setup.prompt`**: read the question, don't solve it; give every
  setup tag whose component is present in the figure or stem. The setup
  rule is written in it (one tag list, no axes, no variants).
  Placeholders: `{{SYLLABUS}}`, `{{TOPIC}}`, `{{tags.reviewed.prompt}}`
  (the topic's `setup-tags.reviewed.prompt`), `{{TagPrompter::gapRule}}`,
  `{{TagPrompter::schema}}`.
- **`qtagger.concept.prompt`**: today's `qtagger.prompt` with the setup
  axis taken out of its rules and reply. Placeholders as now, with
  `{{axes.prompt}}` filled from `qtagger.concept.axes.prompt` and
  `{{tags.reviewed.prompt}}` from `concept-tags.reviewed.prompt`.
- **`qtagger.concept.axes.prompt`**: today's `axes.prompt` without its
  setup lines: *Own* gives the concept and math rules, *Dependent* the
  concept rule.

Each template has its own `templateVersion`; the concept one also covers
its axes file. The question type is still appended after the fixed part, so
the cached prefix is per topic, pass and (concept) variant.

### 7.3 Topic prompts

- `setup-tags.*`: the `## Setup tags` section (rule, then
  `- <tagText>: <hint>` lines).
- `concept-tags.*`: `## Concept tags`, `## Math tags` and the optional
  `## Dependencies` section.
- Same header as now. The `catalogueFingerprint` covers only the pass's
  tags: the topic's hinted `s.` tags for setup; its hinted `c.` tags and
  topic 124's hinted `m.` tags for concept. A new concept tag on the server
  makes the concept prompt stale and leaves the setup prompt approved, and
  the other way round.

### 7.4 Migration

Laws of Motion (104) is the only topic with a reviewed prompt. Its
`tags.reviewed.prompt` is split by hand into `setup-tags.reviewed.prompt`
(the setup section) and `concept-tags.reviewed.prompt` (concept, math,
dependencies), both as *draft* (`promptVersion: 0`) for review and
approval again; its `tags.generated.prompt` is regenerated as the two
generated files (fingerprints setup `eb256374fa091a40`, concept
`c35c894f141b4aa2`). The old `qtagger.prompt`, `axes.prompt` and topic
files are removed; `qtagger.concept.prompt` is the old template with the
new placeholders.

---

## 8. AI hub

| Pass | AI file | Roots |
|---|---|---|
| Setup | `apps/tagger/qtagger.setup.ai.json` | `tier1` (the name the code shares with concept): the free Nemotron, reasoning off, `maxOutputTokens` 1500, falling back to the paid one on `rateLimitedRPD`, `serverError` and `passthrough`. To be checked on a first run |
| Concept | `apps/tagger/qtagger.concept.ai.json` | `tier1` and `tier2`, today's `qtagger.ai.json` |

`-Dai.app.config` still overrides the pass's file. `TaggerOutcomes` takes
the pass: in setup it accepts a valid reply with no escalation.

Transcripts carry the pass in their `Context` header.

---

## 9. Workspace and configuration

The files that hold a pass's state live in a folder per pass, so the two
never read each other's:

| Path under `workspace.dir` | Per pass? |
|---|---|
| `<pass>/tagger-scope.json` (one cursor in setup, two in concept) | yes |
| `<pass>/pending-tagging.json` | yes |
| `<pass>/tag-audit.jsonl` (with `tagClass` in each entry) | yes |
| `<pass>/tagger-notes.jsonl` | yes |
| `<pass>/token-usage.csv`, so each pass's cost reads on its own | yes |
| `<pass>/<source>/<questionId>/…` transcripts, so each pass's Transcripts tab shows only its own (the `Context` header names the pass too) | yes |
| `request.history.json` | no |

`qtagger.properties` keeps its keys; the per-pass ones are resolved under
`<workspace.dir>/<pass>/`. Existing files: `pending-tagging.json` is
set aside as `concept/pending-tagging.single-pass.json`, which the app
doesn't read (its replies were made with prompts that no longer exist);
the audit log and token usage move to `concept/`, and the scope to
`concept/tagger-scope.json` with its cursors cleared (they were the old
queue's).

A `pending-tagging.json` entry is valid while the topic and scope are
unchanged, and in concept also the key, as now.

---

## 10. Code (sketch)

| Area | Change |
|---|---|
| `apps.tagger.model` | `TagClass` (`SETUP` / `CONCEPT`, wire values `setup` / `concept`); `Axis` counts per pass; `Scope` unchanged, the pass held by `QTagger` |
| `apps.tagger.server` | `tagClass` on Pending and Refresh; both flags in `ServerQuestion` (`core.server`) and `RefreshResult`; tags read again before each save |
| `apps.tagger.prompt` | `PromptStore`, `PromptGenerator`, `TagPrompter`, `PromptText`, `ReplySchema` per pass; fingerprint per pass (`TagCatalogue`) |
| `apps.tagger.check` | `ReplyValidator` per pass (setup: setup list and an `s.` gap; concept: no setup list, a `c.` gap) |
| `apps.tagger.pipeline` | `TaggerOutcomes`: setup accepts without the key; `TagJudgement` setup outcomes (§4.2) |
| `apps.tagger.model.OfferedSet` | `replaceable` per pass and own / dependent (§6) |
| `apps.tagger.ui` | pass dialog, title and badge; `AnswerStrip`, `TagDecisionPanel`, `TagEditorDialog`, the table and key 2 per pass |
| `apps.tagger.util.TaggerConfig` | pass-relative files, the AI file per pass |
| `QTagger.main` | asks for the pass first |

CLAUDE.md's *Tagger* section is updated with the change.

---

## Appendix A: decisions

| # | Decision |
|---|---|
| B1 | Two passes, independent of each other; setup over the corpus, concept over exam questions. |
| B2 | `m.` tags and `xdep` belong to the concept pass. |
| B3 | Dependents are run by the concept pass only: 0–2 concept tags of the scoped topic, no setup or math tags, no new dependencies; enforced by the tagger. Dependents outside an exam are run too. |
| B4 | The pass is chosen at launch, before the boot completes, and shown in the title; switching means restarting. |
| B5 | Two prompt sets in one tree: `qtagger.<pass>.prompt` at the root, `<pass>-tags.generated/reviewed.prompt` per topic folder; the same review and approval workflow per pass. |
| B6 | Axis rules only for concept, in `qtagger.concept.axes.prompt`; the setup template states its rule itself. |
| B7 | The setup pass doesn't ask for the answer; its replies are accepted without a key check, and the user spot-reviews before saving. |
| B8 | Each save reads the tags again just before `Refresh`; no server change for concurrent passes. |

## Revision log

| Rev | Date | Change |
|---|---|---|
| 1 | 2026-10-23 | Initial version |
| 2 | 2026-10-23 | Implemented: the setup root is `tier1`; migration as done (§7.4, §9) |
| 3 | 2026-10-23 | Transcripts per pass (§9); those already saved split by their `Context` header (the old single-pass ones to `concept/`) |
