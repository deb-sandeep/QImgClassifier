# QTagger for the tagging workflow: feature design

| Field | Value |
|---|---|
| Component | `QTagger` (`com.sandy.sconsole.qimgtools.apps.tagger`), the "tagging driver" of the workflow |
| Baseline | `SConsoleNxt/doc/feature-enhancements/2026-11-03/tagging-workflow/feature-design.md` (rev 19), with its `api-changes.md` |
| File formats | `tag-workspace/file-formats/regression-model.md` (format version 3) and `llm-prompt.md` (version 1) |
| Replaces | `2026-10-22/auto-tagging` and `2026-10-23/bimodal-tagging`, kept as history |
| Status | **Proposed**, rev 2, 2026-11-05. Reworking in place and reading the topic files from the question-bank folder are decided, and the old logic is removed. The decisions in [Decisions](#decisions) marked *Proposed* await the owner |

The how is in [implementation-plan.md](implementation-plan.md).

`tag-workspace` below means
`/Users/sandeep/Documents/StudyNotes/question-bank/supporting-artefacts/tag-workspace/`.

## Purpose

QTagger carries out step 5 of the tagging workflow: it tags a topic's questions
that have no tagging status, and writes the tags to the server once the owner
approves.

The owner's hand tags are the reference, so the tagger follows how the owner
tags: it reads the question's words first. A regression model trained on the
owner's tags settles what it can at no cost, and an LLM handles only the rest.
QTagger no longer solves questions, checks answer keys or works in passes.

## Scope

**In scope:** reading a topic's model and prompt, OCR, scoring, routing, the
LLM, the owner's review, saving, escalations, and the audit record.

**Not in scope:**

| Item | Where it belongs |
|---|---|
| Building the model, the prompt and the bulk files | The `enhance-tagset` skill |
| Uploading `tag-import.json` and `bulk-tag-corrections.json` | The owner, directly to the server |
| Step 6 review of `AUTO_TAGGED` questions | The owner and the planned `review-auto-tags` skill. QTagger only leaves it the audit record |
| Topic dependencies | Parked by the workflow |
| An unattended run | Removed in the first step, see decision 2 |

## How a question is tagged

```
Load ──> locate images ──> OCR each image ──> score ──┬─ settled ───────> Accepted
                                                      │
                                                      └─ needs the LLM ─> Start ─> LLM
                                                                                    │
         ┌────────────────────┬─────────────────────────┬──────────────────────────┤
    tags, checked        no tag fits          bad reply, after a retry        no answer
         │                    │                         │                          │
      Accepted          Needs human,             Needs human                    Failed
         │              held out locally                                    (re-queue)
         v
    Owner reviews ──> Save ──> /Refresh ──> AUTO_TAGGED on the server
```

Nothing reaches the server until the owner presses Save.

### 1. Load

Unchanged from the first step: the next questions after the scope's cursor from
`GET /Master/Question/AutoTagging/Pending?topicId=&limit=&afterId=`, and their
current tags from the bulk tag read. The server returns null-status questions
that aren't awaiting topic review, and `totalResults` ignores the cursor.

### 2. OCR

Tesseract with `--psm 6` and no language option, on **each image separately**,
in the server's `sequence` order, each result followed by a newline, with
`OMP_THREAD_LIMIT=1`. The model
learned tesseract's output, so these settings must match training exactly. The
image order puts an LCT passage first, as training had it.

This differs from the NXR classifier, which stacks the images into one and sets
a language. The two can't share an OCR routine.

OCR and scoring are local and free, so they run as soon as a Load finishes, off
the EDT, as the NXR classifier does. The owner sees the split between settled
and LLM questions before spending anything.

### 3. Scoring and routing

A Java port of `tag-workspace/scripts/score_regression.py`, following
`regression-model.md`. For each question it gives the score per tag and four
lists: accepted, undecided, triggered and the route.

| Result | Row becomes |
|---|---|
| Route `apply` | *Accepted*, on the regression's tags |
| Route `llm` | *Pending*, marked as needing the LLM |
| OCR failed, or gave no text | *Needs human*, with the reason |

A tag whose `route` is `never` is never proposed by the regression or offered to
the LLM. Whether the tag panel shows it is decision 10.

Python and Java differ in ways that matter here, so the port is checked against
the reference on real data (see the plan):

- `(?u)` in `tokenPattern` means Unicode classes in Python and Unicode case
  folding in Java. It has no effect on tokens, because `dropPattern` has already
  left only `a-z`, apostrophes and spaces.
- `triggerPattern` runs on text that hasn't been through `dropPattern`, so `\d`,
  `\w` and `\b` can differ. Triggers are compiled with
  `Pattern.UNICODE_CHARACTER_CLASS` to match Python.
- Lower-casing uses `Locale.ROOT`.

### 4. The LLM

Start sends the questions that need the LLM, in table order, through the
existing `AIHub` and `TaskScheduler`, on the `llm` root (the free Nemotron,
falling back to the paid one). Reasoning stays off, because the model reads and
doesn't solve.

The request is the text below the `---` line of `llm-prompt.md`, with its five
placeholders filled in, and the question's image attached (decision 8).

The reply is `{tags, confidence, noTagFits, framing, reason}`:

| Reply | Result |
|---|---|
| Valid, with tags | *Accepted*, on the LLM's tags |
| Valid, with `confidence` low | *Accepted*, and marked for the owner's attention (decision 5) |
| `noTagFits` true | *Needs human*, showing `framing`, and held out (see [Escalations](#escalations)) |
| Invalid JSON, or any tag not in the prompt's table | Rejected as a bad reply. One retry, then *Needs human*, with no tags applied (decision 5) |
| `noTagFits` false with no tags | Treated as a bad reply |
| `noTagFits` true with tags | Treated as a bad reply |
| No answer: rate limits, errors, timeouts | *Failed*, to re-queue |

The tag check is strict, as the format requires: one unknown name rejects the
whole answer. The old tagger's mending of replies is not brought back. The
allowed names come from the prompt's own table, not the server's list.

### 5. The owner's review

The review surface is the existing one: the table, the image, `TagStrip` and
`ChosenTagsBar`, with the decision keys. It gains:

- **Where a tag set came from:** a new source, *regression*, so that the Tags
  column reads `[R]`, `[M]` (LLM), `[E]` (existing) or `[U]` (user), with `*`
  once edited.
- **A filter on what needs a look:** low confidence, tags already on the
  question, and escalations.
- **The regression's view on the selected question:** accepted, undecided and
  triggered tags with their scores, and the LLM's `reason` when there is one.

A settled question is not assumed right. The cross-validated estimate for the
regression alone is 83% exact on Kinematics and 92% on Ray Optics, and those
figures are somewhat optimistic. So regression tags are reviewable like any
other, and the audit record keeps them apart.

### 6. Saving

Save sends the approved rows through `POST /Master/TagAssociation/Refresh`, which
sets `AUTO_TAGGED`. `/Refresh` has no guard and replaces the whole explicit tag
set, so QTagger protects the data itself:

- **Tags from other topics stay.** Each item's set is the question's explicit tags
  that aren't the topic's, plus the decided tags.
- **Empty sets are held back.** An empty `tagIds` clears the question's tags and
  sets it to null, which is never what a Save means.
- **Tags already on a null question need approval.** A topic move can leave tags
  on a null question. Such a row is flagged, and never counts as settled without
  the owner seeing it (decision 4).
- **A question that changed since Load is held back.** See decision 3.

## Topic files

A topic can be tagged only with its `regression-model.json` and `llm-prompt.md`.
Without usable ones the topic can be loaded and looked at, and Start stays
disabled. Today Kinematics (103) and Ray Optics (110) have them, from cycle
`01-hand-review`. Laws of Motion (104) doesn't.

They're read directly from the question-bank folder, and nothing is copied into
this project or the workspace. `tag.workspace.dir` names the tag workspace, and
each time a scope is set QTagger:

1. finds `topics/*/<topicId>-*`;
2. takes the highest-numbered folder in `cycles/` whose `output/` has both files;
3. reads and checks them, and holds them in memory until the next scope.

A rebuilt file therefore can't change a run in progress: it takes effect when the
scope is next set, even to the same topic. The cycle name and each file's hash go
in the audit record. A rebuild under the same cycle name shows up as a different
hash, but the old file is gone unless the tag workspace keeps it.

Any failed check makes the topic untaggable, with the reason:

| Check | Why |
|---|---|
| `format` is `sconsole-tag-regression`, and `formatVersion` is 3 | The port implements this version only |
| `topicId` and `topicName` match the scoped topic | A model applies only to its own topic |
| `idf` and each `coefficients` have one entry per `vocabulary` term | A mismatch would score silently wrong |
| Every pattern compiles in Java | Patterns are written for Python |
| Every placeholder occurs exactly once in the prompt | The format promises it |
| Every tag in the prompt's table is in the model, and neither has one marked `never` | The two files must agree |
| Every tag the model can apply is on the server, by name | A tag can't be saved otherwise. The `river-crossing` rename, before its bulk file was uploaded, was such a case |

### The tag list

The server is the only source of the tag list, fetched each time a scope is set.
The files in the question-bank folder refer to tags by name only.

| Use | Source |
|---|---|
| Tag panel, existing tags, tag IDs for saving | Server |
| Which tags the regression can score | Model, matched to the server by name |
| Which tags the LLM is offered | The prompt's table, because that is the text it's sent |

| Case | Result |
|---|---|
| The topic has no model or prompt | The Scope dialog doesn't offer it |
| Its model or prompt can't be used | A dialog says why and the scope is unloaded, so its questions can't be loaded |
| The model or prompt names a tag not on the server | The same, and the tag is named |
| A tag on the server isn't in the model | Allowed. It can be applied by hand, and the model can't propose it. A note says so |
| A tag marked `never` is still on the server | Neither the regression nor the LLM proposes it |

## Escalations

The server has no escalated state, so an escalated question stays null and comes
back in every fetch from the start. QTagger keeps a local list per topic: the
question, its cycle, the `framing`, and the time.

- A held-out question shows as *Escalated*, isn't sent again, and isn't counted
  as pending work.
- The list can be read in the app, because the owner resolves each one by creating
  a tag or subsuming the framing, as the workflow describes.
- A new cycle releases the topic's hold-outs: the tag set or rules have changed,
  so the question deserves another try.
- A question hand-tagged meanwhile stops being null, and leaves the fetch by
  itself.

## Audit record

The server stores only the status, and step 6 may focus on low-confidence
questions and disagreements. So `tag-audit.jsonl` records, per question: the
cycle, the hashes of the model and prompt, the OCR text's hash, the route, the accepted, undecided and triggered tags
with their scores, the LLM's model, tags, confidence and reason, the owner's
decision, and what was sent and how the server answered.

`review-auto-tags` should be able to read this file, so its shape is treated as a
format of its own and documented.

## Decisions

Decisions 2 to 9 use the numbering from the original discussion.

| # | Question | Position | Status |
|---|---|---|---|
| 1 | Rework QTagger or build a new app? | Rework in place, removing the old logic first | **Decided**, done |
| 2 | How is each sync approved? | The owner presses Save for each batch. Nothing saves by itself, so unattended mode was removed. A later run that tags batches and stops before saving could return | Proposed. Removal was the implementer's call |
| 3 | How are stale writes prevented? | Re-read the tags at Save, and hold back a question whose tags changed since Load. A topic move that leaves the tags alone needs its status or topic read too | Proposed. The API to use for that read is still to be confirmed |
| 4 | What about tags already on a null question? | Keep other-topic tags, as the workflow says. Keep same-topic ones too, and flag the row for approval: the workflow doesn't cover a question moved away and back | Proposed |
| 5 | What happens to bad or unsure LLM replies? | Unknown tag or invalid JSON: one retry, then the owner, with nothing applied. Low confidence: stage the tags and mark the row | Proposed |
| 6 | How are escalations held out? | Locally, per topic and cycle, released by a new cycle | Proposed |
| 7 | Where do the topic files come from? | Read directly from the question-bank folder at each scope, with nothing copied. The tag list comes from the server | **Decided** by the owner |
| 8 | What does the LLM see, and is there a pilot? | The image as well as the text, because Ray Optics has questions where only the figure tells a lens from a mirror. A pilot of about 50 questions before a whole topic | Proposed. Both are also open in `workflow.md` |
| 9 | What is recorded locally? | See [Audit record](#audit-record) | Proposed |
| 10 | Does the tag panel show tags marked `never`? | Yes: it shows the server's list unchanged, as normal badges. Hiding them would stop a retired tag being applied by accident, but the model would then filter the server's list | Proposed |

Calls made while removing the old logic, for the owner to confirm:

- All of a topic's tags are offered in the tag panel, instead of only those with an
  AI hint. Decision 10 covers those marked `never`.
- Dropping a hand tag no longer sends a question to the owner. Null questions
  rarely have hand tags, and decision 4 covers those that do.

## Risks

| Risk | Effect | Handling |
|---|---|---|
| The prompt has never been tried on a model | Replies may be poor, or the free model may not suit it | Step 4 tries it on a few questions before the pilot. The result may send work back to `enhance-tagset` |
| Tagged questions may not represent untagged ones | Real settle rates and accuracy could be below the estimates | The pilot measures them |
| A different tesseract build from training | Slightly different text, so different scores | The port's check compares OCR with the cycle's `input/ocr-text.json`: identical for every question of both cycles on 5.5.1 |
| The owner hasn't validated the hints and thresholds | A systematic error would affect a whole topic | The pilot comes before any whole-topic run |
| Running on production before the check with the driver | The server's phase 3 is on dev only | The pilot on dev completes the server plan's last check, and only then is phase 3 deployed to the Pi |

## Expected volumes

From the first-run estimates and the counts on dev on 2026-11-04. They are
indicative only.

| | Kinematics (103) | Ray Optics (110) |
|---|---|---|
| Questions eligible on dev | 160 | 371 |
| Settled by the regression (estimate) | 49%, about 78 | 37%, about 137 |
| Settled by the regression (measured on dev, 2026-11-05) | 48%, 77 of 160 | 13%, 48 of 371 |
| Sent to the LLM | about 82 | about 234 |

## Revision log

| Rev | Date | Change |
|---|---|---|
| 1 | 2026-11-04 | Initial version |
| 2 | 2026-11-05 | Owner's decision 7: topic files are read directly from the question-bank folder, with nothing copied, and the tag list comes from the server at each scope. Added decision 10 |
| 3 | 2026-11-05 | Owner's change: a topic without usable files is refused with a dialog and the scope unloaded |
| 4 | 2026-11-05 | Owner's change: the Scope dialog offers only the topics with both files |
| 5 | 2026-11-05 | Step 3 measured: OCR identical to training, and Ray Optics settles far fewer than estimated |
