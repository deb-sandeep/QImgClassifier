# Tagging by transcription: strategy changes

| Field | Value |
|---|---|
| Component | `QTagger` (`com.sandy.sconsole.qimgtools.apps.tagger`), the tagging driver |
| Changes | `2026-11-04/tagger-workflow/feature-design.md` (format 3 model, version 1 prompt, tesseract input) |
| Server | `SConsoleNxt/doc/feature-enhancements/2026-11-17/question-transcription/` (data model: `question_xcription`, read from the local database) |
| File formats | `tag-workspace/file-formats/regression-model.md` (**format version 4**) and `llm-prompt.md` (**version 2**) |
| Status | Decided by the owner 2026-11-18. The tag workspace side is built and tested; the tagger side is not started |

`tag-workspace` below means
`/Users/sandeep/Documents/StudyNotes/question-bank/supporting-artefacts/tag-workspace/`.

## Why

Every question is being transcribed by QTranscriber into markdown: maths in
LaTeX, chemistry in `\ce{}`, each figure described in a `> Figure:` blockquote.
The transcription is far more faithful than tesseract, which loses exponents,
Greek, symbols and every figure. Tagging now reads the transcription, and
tesseract text is never used for tagging again.

Measured on Kinematics (531 reviewed questions, held-out): the transcription
model settles about as many questions without the LLM as the tesseract model
did, at a stricter threshold (0.95 precision instead of 0.90), with half the
wrong extra tags (11 instead of 25). Most of the gain comes from the figure
descriptions. For Maths and Chemistry, tesseract was close to unusable, so the
transcription is what makes tagging those subjects possible.

## Decisions (owner, 2026-11-18)

| # | Decision |
|---|---|
| 1 | **Only transcribed questions are tagged.** A question with `xcriptionNeeded` true is never scored, sent to an LLM or saved with tags. This is a hard precondition |
| 2 | **No tesseract text** anywhere in tagging: not for the model, not for the LLM, not for the audit |
| 3 | **What a figure shows counts as stated in the question.** Figure blocks are part of the question text, for the model and the LLM alike |
| 4 | **Existing Physics tags stay.** Questions already tagged are not redone. Their tags are the training labels for the rebuilt models, which tag only untagged (null) questions |
| 5 | **Maths tags are redone**, in a separate iteration. The owner will reset their tagging status so the tagger picks them up again. Not part of this change |
| 6 | **LLMs:** local Ollama models and the OpenRouter free quota are the main LLMs. The prompt is written for text alone, so an image is optional |
| 7 | **Transcribe before tagging** is an expectation, not a precondition. The owner aims to transcribe a topic fully before tagging it, but in a partly transcribed topic the transcribed questions are tagged as normal. Only decision 1 applies per question. The tagger shows the gap (see *Pending fetch*) |
| 8 | **Order:** Physics first (Kinematics, Laws of Motion, then Ray Optics and Work, Energy and Power). Theory of Equations later |

## What changes in QTagger

### 1. Input: the transcription replaces OCR

- **Read each question's text from the local database** (owner's decision,
  2026-11-18), not from the server: the `sconsolenxt` MySQL on this machine,
  which the owner keeps in sync with production. One query per Load, for all
  its questions:

  ```sql
  SELECT question_id, content FROM question_xcription WHERE question_id IN (...)
  ```

  `question_id` is the question table id (`QuestionVO.id`, the pending fetch's
  `id`). Use `content` **exactly as stored**: read it as a UTF-8 string, with no
  trimming or normalising. An LCT question's `content` already holds its
  passage, then the line `<!-- question -->`, then its own part. Don't add the
  passage again. Connection settings come from the same `DB_HOST`, `DB_USER`
  and `DB_PASSWORD` environment variables the tag workspace scripts use (or the
  driver's properties). The driver only reads; it never writes to the database.
- **No row means not transcribed.** A question with no `question_xcription`
  row in the local database is *Not transcribed*, even if the server says it
  is: the local copy is behind, and the owner syncs it. The pending fetch's
  `xcriptionNeeded` can be shown beside it as a hint that a sync is due.
- `QuestionOcr`, `tesseract.bin`, `ocr.threads` and `ocr.timeout.seconds`
  go. The byte-identical-to-`ocr-text.json` rule is replaced by: the text is
  the stored `content`, which is also what `input/question-text.json` holds in
  the cycle.
- A question with `xcriptionNeeded` true (on its `QuestionVO`), or a read that
  fails with *has no transcription*, gets a new state such as *Not
  transcribed*. It is not scored, not sent to the LLM, not saved, and has no
  deferral or escalation. It replaces *Needs human* for an OCR failure.
- **Pending fetch** (owner's choice, 2026-11-18: option b). `GET /Master/Question/AutoTagging/Pending`
  doesn't filter on transcription, and no server filter is asked for. A Load
  shows untranscribed questions as *Not transcribed* and moves the cursor past
  them; *Load from start* reaches them once they are transcribed. The visible
  *Not transcribed* rows are the intended feedback: transcribe the topic before
  tagging it. The owner expects to transcribe a topic fully before tagging
  it (an expectation, not a precondition: transcribed questions are tagged
  whatever the topic's coverage), so the tagger should make the gap obvious: count the *Not
  transcribed* rows in the status bar, and warn at scope or Load when the topic
  has untranscribed questions (repo status `numUntranscribed` for the topic,
  `GET /Master/Question/RepoStatus`). A notice only: it never stops the
  transcribed questions from being scored, sent to the LLM or saved.
- **Why the local database:** the model and the workspace scripts
  (`start_cycle.py`, `auto_review_intake.py`) read the same table, so the
  driver scores exactly the text they do, and a Load needs one query instead of
  one server request per question.

### 2. Regression model: format version 4

`tag-workspace/file-formats/regression-model.md` is the spec, and
`tag-workspace/scripts/score_regression.py` is the reference implementation,
rewritten so that it reads every pattern from the model file. The build checks
it against sklearn (max difference 5×10⁻⁷ on Kinematics).

- **Accept only `formatVersion` 4.** A format 3 model was trained on tesseract
  text, and must be refused (`TopicFileException`, *wrong format or
  version*). Until a topic's new cycle is built, it has no usable model, so it
  isn't offered in the Scope dialog. That's intended.
- **`TopicFileFinder` changes: the files moved** (owner's decision,
  2026-11-18). Cycle folders no longer have an `output/`. Every published file
  is in the topic's own `output/` folder, `topics/<subject>/<id>-<name>/output/`,
  named `<name>.c<cycle>.v<version>.<ext>`, e.g.
  `regression-model.c02.v01.json` and `llm-prompt.c02.v01.md`. One build
  publishes all its files under one `c`/`v`, and published files are never
  overwritten; a new build gets the next version (`c02.v02`), and a new cycle
  starts again at `v01` (`c03.v01`). The tagger uses **the highest cycle, then
  the highest version, for which both `regression-model` and `llm-prompt`
  exist**, and always the pair with the same `c`/`v`. The `cycle` in the model
  is the cycle folder's name (e.g. `02-rebuild`); its number is the `c`. Show
  `c02.v01` in the title instead of the cycle folder. Files from before the
  change were moved there as their cycle's `v01`, so the latest today is
  Kinematics `c02.v01`, Laws of Motion `c02.v01` and Ray Optics `c03.v01`, all
  format 4. The same folder holds `scoring-test-vectors.c<NN>.v<NN>.json`.
- Scoring, thresholds, the decision rule, implications, `llmIfAcceptedTogether`,
  `fallback` and the tag entries are **unchanged**. Only the text to terms step
  changes. The new `preprocessing` fields:

| Field | Use |
|---|---|
| `questionMarker` | `<!-- question -->`. Before its first occurrence is the passage zone, after it the body. Without it, all body |
| `figureLinePattern`, `figureStripPattern` | A line matching the first at its start is figure text, with the first match of the second removed |
| `optionLinePattern` | A body line matching it at its start is options text. Other body lines are stem, other passage-zone lines passage |
| `partOrder`, `partUse`, `partPrefix` | Assembly: the stem's words, then each part in `partOrder` as `partUse` says (`plain`, `prefixed` with `partPrefix`, `both`, `drop`) |
| `blankOut` | Strings replaced with a space first (`<!--`, `-->`) |
| `layoutCommandPattern` | LaTeX layout commands (`\frac`, `\mathrm`, `\ce`, ...) replaced with a space |
| `commandPattern` | Any other command becomes a word: replace with a space, group 1, a space (`\sin` → ` sin `). Then lower-case |
| `optionMarkerPattern`, `dropPattern`, `minWordLength`, `tokenPattern`, `stopWords` | As in format 3, applied to each part's text |

  The words step runs per part. Terms (single tokens and adjacent pairs after
  stop words are removed) are made from the assembled list, so pairs run
  across part boundaries.

- **Java notes for the port:**
  - Python `re.match` anchors at the start: use `Matcher.lookingAt()`, not
    `matches()` or `find()`.
  - `figureStripPattern` is removed once: `replaceFirst`.
  - The `commandPattern` replacement is `" $1 "` in Java.
  - Lower-case with `toLowerCase(Locale.ROOT)`.
  - Split lines on `\n`, and split words on runs of whitespace after trimming.
  - Compiling with `UNICODE_CHARACTER_CLASS`, as now, is fine. After
    `dropPattern` the text holds only `a-z`, apostrophes and spaces.
- **Test vectors.** Each build publishes `scoring-test-vectors.c<NN>.v<NN>.json`:
  25 questions with the `textSha256` of their transcription, the term count,
  every tag's expected score (to 1e-6), and the accepted, undecided and
  triggered tags with the reference `route`. They are in-sample, so they check
  the port, not accuracy.

### 3. LLM prompt: version 2

- The placeholder `{{ocr_text}}` is now **`{{question_text}}`**, filled with the
  `content` as returned. The other four placeholders and the reply schema
  (`tags`, `confidence`, `noTagFits`, `framing`, `otherTopic`, `reason`) are
  unchanged. Refuse a prompt that still has `{{ocr_text}}`.
- The prompt explains the markdown, the figure blocks and the LCT marker, and
  tells the LLM to tag an LCT question by what its own part asks.
- **No image needed.** The figure is described in the text. Attaching the
  image to a vision model is allowed but not required, so text-only models
  (local Ollama, OpenRouter free models) can take the tagging. The model list
  in `apps/tagger/qtagger.ai.json` is the owner's choice. Because the LLM
  costs nothing per question, routing more questions to it is acceptable if
  that improves accuracy.

### 4. Audit record

In `tag-audit-format.md`, replace `ocrHash` with **`textHash`**: the SHA-256 of
the transcription `content` in UTF-8, as used. The step 6 intake
(`tag-workspace/scripts/auto_review_intake.py`) compares `textHash` to the
current transcription, to find questions whose transcription changed after
tagging. Entries written before the change keep `ocrHash`. The intake ignores
it, and won't rescore with a format 3 model.

### 5. Display (optional)

The Regression tab or a detail pane can show the transcription rendered, as
QTranscriber does, so the owner reads the same text the model and LLM read.

## What doesn't change

- Pending selection (null `taggingStatus`, not awaiting topic review), the
  routing rule on top of the port (decision 11 of the tagger design),
  decisions, Save through `/Refresh`, keeping other topics' tags, escalation,
  topic moves, deferrals and `PendingTagging`.
- Tags are still identified by name; the server's tag list is still fetched at
  scope.

## Transition

| Topic | Untagged | Transcribed of those | Model |
|---|---|---|---|
| 103 Kinematics | 107 | 86 | Rebuilt: `output/*.c02.v01.*` |
| 104 Laws of Motion | 170 | 116 | Rebuilt: `output/*.c02.v01.*` |
| 110 Ray Optics | 117 | 0 | Interim `output/*.c03.v01.*` on 364 transcribed questions; rebuild after the transcriber covers the topic |
| 105 Work, Energy and Power | 729 | 729 | First model after its topic review (no model yet) |
| 82 Theory of Equations | (to be reset) | 293 of 849 | Separate iteration |

Counts from dev, 2026-11-18. Until a topic's cycle 02 exists, the tagger has
nothing to scope for it, by the format 4 rule. Once it exists, the transcribed
untagged questions are tagged; the rest wait as *Not transcribed*. The tag workspace
builds the cycles, and the owner uploads nothing new for a rebuild: tags and
descriptions are unchanged, only the model and prompt are new.

## Workspace side, already done (2026-11-18)

- `scripts/xtext.py` reads and splits transcriptions; `start_cycle.py` writes
  `input/question-text.json` and stops if a question in scope has no
  transcription (`--exclude-untranscribed` leaves such questions out instead).
- `build_cycle_output.py` builds format 4 models and version 2 prompts;
  `score_regression.py` is the format 4 reference.
- `workflow.md` (section *Question text: transcriptions only*), the
  `enhance-tagset` skill and `file-formats/` are updated.
