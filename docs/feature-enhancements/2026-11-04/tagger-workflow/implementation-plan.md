# QTagger for the tagging workflow: implementation plan

**Status:** in progress. Step 1 is done and committed (`2b66d36`). The plan itself is proposed, and steps 2 to 6 depend on the open decisions in [feature-design.md](feature-design.md#decisions).

The design and its reasons are in [feature-design.md](feature-design.md). Package names below are under `com.sandy.sconsole.qimgtools.apps.tagger`.

## Approach

| Step | Delivers | Decisions it needs | AI? | Server writes? |
|---|---|---|---|---|
| 1. Strip the old logic | A skeleton that loads and shows questions | 1 | No | No |
| 2. Topic files | The topic's model and prompt read from the question-bank folder and checked at each scope | 7 (settled) | No | No |
| 3. OCR and scorer | Every loaded question scored and routed | None | No | No |
| 4. The LLM | Prompt, reply reading, tag check, escalation | 5, 8 | Yes | No |
| 5. Saving | Approval, guards, hold-outs, audit record | 2, 3, 4, 6, 9 | Yes | Dev only |
| 6. Pilot | About 50 Kinematics questions, reviewed | 8 | Yes | Dev only |

Three choices shape the order:

- **Lowest risk first.** Steps 2 and 3 cost nothing and can't change any data.
- **The scorer is proven before anything depends on it.** A wrong score sends questions to the wrong route or applies wrong tags, with no visible error.
- **Each step ends with something the owner can run and see.** The owner commits each step.

The repo has no test framework, and this plan doesn't add one. Checks are one-off comparisons against the reference, and run outside the repo, as the first step's were.

## Step 1: Strip the old logic

Done. Passes, axes, the cascade, answer checks, gaps, dependents, the prompt workflow and unattended mode are gone. Load works against dev, and the classifier, the NXR classifier and the topic reviewer were each confirmed by the owner afterwards.

`qtagger.properties` now points at dev, because the Pi doesn't have the new fetch yet. That change isn't committed yet.

## Step 2: Topic files

The topic's files are read from the question-bank folder each time a scope is set. Nothing is copied into this project or the workspace, and there is no import command. The tag list always comes from the server.

| Layer | Work | Code |
|---|---|---|
| 2a. Settings | `tag.workspace.dir`, the tag workspace under the question-bank folder. Required, like every other key | `util.TaggerConfig` |
| 2b. Locating | Find `topics/*/<topicId>-*`, then the highest-numbered folder in `cycles/` whose `output/` has both files | New `regression.TopicFileFinder` |
| 2c. Model | Read `regression-model.json` into an immutable form: compiled preprocessing patterns, term index, IDF, tags with their routes, implications, `llmIfAcceptedTogether` | New `regression.RegressionModel` |
| 2d. Prompt | Read `llm-prompt.md`: take the text below the `---` line, and parse the allowed names from the Tags table | New `prompt.TopicPrompt` |
| 2e. Bundle | The two together with the cycle folder and each file's hash, or the reason there are none. Runs the checks in the design that need no server | New `regression.TopicFiles`, with `regression.TopicFileException` for the reasons |
| 2f. Server tags | Match the model's and the prompt's tag names against the tags the scope just fetched from the server. A name not on the server makes the topic untaggable, naming the tag. A server tag not in the model is only noted | `QTagger.setScope` |
| 2g. Scope | Setting a scope fetches the tags and reads the files, off the EDT. Only the scoped topic's model is in memory, and the next scope replaces it. The Scope dialog offers only the topics with both files. A saved or offered topic whose files can't be used is refused: a dialog says why and no scope stays in effect, so its questions can't be loaded. The saved scope stays as it was | `QTagger.setScope`, `Controller.topicFilesNotice` |
| 2h. UI | The cycle goes in the title, a dialog says why a topic can't be tagged, and the status bar notes the server's tags the model can't propose | `ui.TaggerFrame` |

`canTag` stays false until step 4, because `TagPrompter` can't run before then. Step 2 changes what the user can see, and doesn't enable Start.

The topic tag panel, existing tags and the tag IDs used for saving all come from the server's list.

### Verification

- Kinematics and Ray Optics load with no problem reported, and the title shows `01-hand-review`.
- Each refusal is triggered once with a doctored copy of the tag workspace outside the question-bank folder, given through `-Dtag.workspace.dir`: wrong topic, wrong version, a short `idf`, a pattern that won't compile, a missing placeholder, a tag not on the server.
- Laws of Motion (104) isn't offered in the Scope dialog, and neither is a syllabus with no topic that has both files. A topic with broken files is offered, and choosing it shows a dialog and unloads the scope, with Load disabled.
- A tag added on the server shows in the panel at the next scope, with no file change.
- A file rebuilt in the tag workspace changes nothing until the scope is next set, and then takes effect.
- No file appears in the project or the workspace.

## Step 3: OCR and scorer

| Layer | Work | Code |
|---|---|---|
| 3a. OCR | Tesseract `--psm 6` per image, in sequence, joined with newlines, with a timeout. Runs tesseract's own process per image, like `com.sandy.sconsole.nxr.Ocr`, which is package-private and uses other options, so it isn't reused | New `ocr.QuestionOcr` |
| 3b. Terms | Lower-case, strip option markers, drop characters, drop short words, tokens, stop words, adjacent pairs formed after stop words are removed | New `regression.Terms` |
| 3c. Scoring | TF-IDF weights, L2 norm, and the logistic score per `model` tag, keeping only the terms present in the question. Stateless, so the pool can share it | New `regression.RegressionScorer` |
| 3d. Deciding | Accepted, undecided, triggered, then dropping implied parents in file order, then `llmIfAcceptedTogether`, and the route, with the reasons it went to the LLM and the scores | `regression.RegressionScorer.decide`, new `regression.Decision` |
| 3e. Run on Load | After a Load, score each row on a small pool off the EDT, as `NXRFrame` does. Load and scope changes wait for it | `ui.TaggerFrame`, `QTagger` |
| 3f. Row state | `Row.decision` from the regression on `apply`, with a new `TagDecision.Source.REGRESSION` and mark `[R]`. A marker for rows that need the LLM. *Needs human* for OCR failures | `ui.TagTableModel`, `model.TagDecision`, `model.TaggerStatus` |
| 3g. Viewing | The regression's view on the selected question, and the OCR text | `ui.TagStrip` or a new panel |
| 3h. Settings | `tesseract.bin`, `ocr.threads`, `ocr.timeout.seconds` | `util.TaggerConfig` |

Settled rows become decidable here, which also removes the limit noted after step 1, where *Pending* rows couldn't be decided on.

### Verification

Against the reference, with `input/ocr-text.json` of both cycles:

- **Scorer:** Java and `score_regression.py` give the same scores to within 1e-9, and the same four lists, for every text in the file.
- **OCR:** Java's OCR of the same questions' images, compared with the file. Differences are counted and reported to the owner, not assumed harmless. Local tesseract is 5.5.1, and the build used in training needs to be confirmed.
- **Routing:** the split on the training questions is compared with `crossValidatedRouting`. It should be close but not equal, because that figure is held out.
- **On dev:** the split on the eligible Kinematics questions, compared with the estimate of about 49%.

## Step 4: The LLM

| Layer | Work | Code |
|---|---|---|
| 4a. Request | Fill the placeholders, using `none` for an empty list, and attach the image | `prompt.TagPrompter.prompt`, `prepareImage` |
| 4b. Reply | Lenient JSON reading (fences, text around the object), then the strict tag check | `prompt.TagPrompter.parse`, `model.TagReply` gains `confidence`, `noTagFits`, `framing`, `reason` |
| 4c. Outcomes | Results as in the design's table. A bad reply is already retried once and then handed over, through `TaggerOutcomes.failed` | `pipeline.TaggerOutcomes`, `pipeline.TaggerListener` |
| 4d. Sending | Start sends only rows routed to the LLM. `canTag` becomes true once usable topic files are loaded | `ui.TaggerFrame` |
| 4e. Showing | The reply's reason and confidence, a low-confidence mark, and `framing` for an escalation | `ui.TagStrip`, `ui.TagTableModel` |
| 4f. Prompt caching | Confirm the prompt is a stable prefix with these placeholders. They sit near its end, so most of it should cache | `TagPrompter`, checked in the token log |

A JSON schema (`TaskPrompter.responseSchema`) isn't used at first. Restricting the reply to the listed tags would hide the very failure the tag check exists to catch, and the pilot should see it.

### Verification

- A handful of Kinematics questions on the free model, read in the transcripts, before any larger run. This is the prompt's first real use.
- Each row of the design's reply table, using canned replies.
- A Ray Optics lens-and-mirror question, with and without the image, as evidence for decision 8.
- Cost per question from `token-usage.csv`, projected for the eligible questions.

## Step 5: Saving

| Layer | Work | Code |
|---|---|---|
| 5a. Stale guard | Compare the tags read at Save with those at Load, and hold back a question that changed, saying why | `QTagger.saveBatch`, `ui.TaggerFrame` |
| 5b. Topic moves | Detect a move that left the tags alone. **To confirm first:** which server read gives current status and topic for a list of questions. If none does, it's a request to the server | `server.TaggerServerClient`, `core.server.ServerQuestion` (`taggingStatus`) |
| 5c. Existing tags | Flag rows that already carry tags, and require a deliberate decision on them | `ui.TagTableModel`, `ui.TaggerFrame` |
| 5d. Hold-outs | Per-topic list, *Escalated* status, a way to read it, release on a new cycle | New `workspace.HoldOuts`, `ui.TaggerFrame` |
| 5e. Audit | The design's fields, documented as a format | `workspace.TagAuditLog`, `workspace.PendingTagging` (adds `cycle` and the files' hashes to its validity) |
| 5f. Review filter | Low confidence, existing tags, escalated | `ui.TaggerFrame.Show` |
| 5g. Reply fields | `/Refresh` also returns `topicDependencies`, which is ignored | `server.RefreshResult` |

### Verification

All on dev, with the owner watching, on a few questions:

- **A real save:** the first in the new code. The status becomes `AUTO_TAGGED`, and the tags on the server are what was shown, with implied parents added by the server.
- **Other-topic tags:** a question with one keeps it.
- **Stale guard:** a hand edit between Load and Save holds the question back.
- **Empty set:** held back, with no server call.
- **Hold-outs:** an escalated question stays out of later Loads from the start, and a new cycle releases it.
- **Audit:** every field is present and reads back.

## Step 6: Pilot

1. Back up the dev database, or confirm a copy can be restored.
2. Run about 50 Kinematics questions through all steps, with the owner approving.
3. Review them by hand, as step 6 of the workflow, and measure: settle rate, exact match by route, escalations, bad replies, cost and time.
4. The owner decides among: go ahead with the topic, adjust thresholds, or fix the prompt through `enhance-tagset`.
5. Tick "Verified with the driver, on one small batch" in the server's plan. Phase 3 can then be committed and deployed to the Pi.

Tagging on production needs that deploy, a check that dev and production question IDs match, and `server.url` pointed back at the Pi.

## Touch points outside the tagger

| Code | Change | Other apps affected |
|---|---|---|
| `core.server.ServerQuestion` | May gain `taggingStatus` (step 5) | No: an extra read-only field |
| `core.scheduler`, `core.ui`, `core.review`, `com.sandy.sconsole.ai` | None planned | No |
| `common/ai-model-config.json` | None planned | No |

No app imports another, so the new OCR and regression code stays in `apps.tagger`, which leaves the NXR classifier untouched. `CLAUDE.md` is updated at each step. The AI hub's javadocs aren't regenerated, since nothing under `com/sandy/sconsole/ai/` changes.

## Checklist

### Step 1: Strip the old logic

- [x] Old logic removed
- [x] Load works on dev
- [x] Other three apps confirmed by the owner
- [x] Committed

### Step 2: Topic files

- [x] Decision 7 settled: read from the question-bank folder, nothing copied
- [x] `tag.workspace.dir`, and finding the latest cycle
- [x] `RegressionModel`, `TopicPrompt`, `TopicFiles`, with their checks
- [x] Scope reads the files and checks them against the server's tags
- [x] The cycle in the title, and a dialog with the reason a topic can't be tagged
- [x] Verified, each refusal included

### Step 3: OCR and scorer

- [ ] `QuestionOcr`
- [ ] Terms, scoring, deciding
- [ ] Scorer matches the reference on both cycles
- [ ] OCR compared with `ocr-text.json`, and the result reported
- [ ] Scoring on Load, with *Accepted* and needs-the-LLM rows
- [ ] Regression's view shown
- [ ] Split on dev compared with the estimate

### Step 4: The LLM

- [ ] Decisions 5 and 8 settled
- [ ] Request and reply
- [ ] Outcomes, each reply case checked
- [ ] Start sends only LLM rows
- [ ] First real replies read in the transcripts
- [ ] Image comparison and cost reported

### Step 5: Saving

- [ ] Decisions 2, 3, 4, 6 and 9 settled
- [ ] Server read for topic moves confirmed
- [ ] Stale guard, and existing-tag flag
- [ ] Hold-outs
- [ ] Audit record, documented
- [ ] Review filter
- [ ] Real save verified on dev

### Step 6: Pilot

- [ ] Dev database backed up
- [ ] About 50 Kinematics questions tagged and reviewed
- [ ] Measurements reported
- [ ] Owner's decision recorded
- [ ] Server plan's driver check ticked

## Revision log

| Rev | Date | Change |
|---|---|---|
| 1 | 2026-11-04 | Initial version, with step 1 done |
| 2 | 2026-11-05 | Step 2 reads the topic files directly from the question-bank folder at each scope, replacing the import. The tag list comes from the server. Step 3 names `RegressionScorer` and the order of its decision steps |
| 3 | 2026-11-05 | Step 2 done. `TopicFiles` lives in `regression`, not `model`, and the frame asks only for `Controller.topicFilesNotice`. Server tags the model has no entry for are a note, not a refusal |
| 4 | 2026-11-05 | Owner's change: a topic without usable files is refused with a dialog and the scope unloaded, instead of a status bar notice with its questions still loadable |
| 5 | 2026-11-05 | Owner's change: the Scope dialog offers only the topics with both files. The refusal stays for files that are there but can't be used |
