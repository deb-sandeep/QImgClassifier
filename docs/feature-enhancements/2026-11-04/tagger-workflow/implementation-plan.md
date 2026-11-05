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
| 5. Saving | Approval, existing tags, hold-outs, audit record | 2, 4, 6, 9 | Yes | Dev only |

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
| 3a. OCR | Tesseract `--psm 6` per image, in sequence, each followed by a newline, with `OMP_THREAD_LIMIT=1` and a timeout, as `start_cycle.py` runs it. Runs tesseract's own process per image, like `com.sandy.sconsole.nxr.Ocr`, which is package-private and uses other options, so it isn't reused | New `ocr.QuestionOcr` |
| 3b. Terms | Lower-case, strip option markers, drop characters, drop short words, tokens, stop words, adjacent pairs formed after stop words are removed | New `regression.Terms` |
| 3c. Scoring | TF-IDF weights, L2 norm, and the logistic score per `model` tag, keeping only the terms present in the question. Stateless, so the pool can share it | New `regression.RegressionScorer` |
| 3d. Deciding | Accepted, undecided, triggered, then dropping implied parents in file order, then `llmIfAcceptedTogether`, and the route, with the reasons it went to the LLM and the scores | `regression.RegressionScorer.decide`, new `regression.Decision` |
| 3e. Run on Load | After a Load, score each row on a small pool off the EDT, as `NXRFrame` does. Load and scope changes wait for it | `ui.TaggerFrame`, `QTagger` |
| 3f. Row state | `Row.assessment` holds the OCR text and decision, and `Row.proposed()` gives the regression's set on `apply` until a model's reply replaces it, with a new `TagDecision.Source.REGRESSION` and mark `[R]`. `[LLM]` marks rows that need the LLM, with a *For the LLM* filter and count. A new *Reading* status while it runs. *Needs human* for OCR failures | `ui.TagTableModel`, `model.TagDecision`, `model.TaggerStatus` |
| 3g. Viewing | The regression's view on the selected question, and the OCR text | New `ui.RegressionPanel`, a *Regression* tab |
| 3i. Saving held back | `Controller.canSave` stays false until step 5. Settled rows would otherwise be the first that could reach `/Refresh`, without step 5's guards | `QTagger`, `ui.TaggerFrame` |
| 3h. Settings | `tesseract.bin`, `ocr.threads`, `ocr.timeout.seconds` | `util.TaggerConfig` |

Settled rows become decidable here, which also removes the limit noted after step 1, where *Pending* rows couldn't be decided on.

### Verification

Against the reference, with `input/ocr-text.json` of both cycles:

- **Scorer:** Java and `score_regression.py` give the same scores to within 1e-9, and the same four lists, for every text in the file.
- **OCR:** Java's OCR of the same questions' images, compared with the file. Differences are counted and reported to the owner, not assumed harmless. Local tesseract is 5.5.1, and the build used in training needs to be confirmed.
- **Routing:** the split on the training questions is compared with `crossValidatedRouting`. Scoring a model on its own training questions settles far more than the held-out figure (Kinematics 467 against 265, Ray Optics 183 against 121), so this is not the useful comparison: the split on dev is.
- **On dev:** the split on the eligible questions, compared with the estimates.

### Results

| Check | Kinematics | Ray Optics |
|---|---|---|
| Scorer against `score_regression.py`, on every training text | 537 questions, worst difference 2.8e-16, no list or route differs | 326 questions, worst difference 2.2e-16, no list or route differs |
| OCR against `ocr-text.json`, byte for byte | 537 of 537 identical (560 images) | 326 of 326 identical (340 images) |
| Route, Java against Python, on the dev questions | 0 of 160 differ | 0 of 371 differ |
| Settled on dev, against the estimate | 77 of 160 (48%), estimate 49% | **48 of 371 (13%), estimate 37%** |

Ray Optics' shortfall is in the model, not the port. On its untagged questions nothing is accepted for 57% (16% on its training questions), and something is undecided for 70% (29%). The questions come largely from the same sources and are of similar length. So about 323 questions go to the LLM rather than about 234, which step 4's cost figures need to allow for.

## Step 4: The LLM

| Layer | Work | Code |
|---|---|---|
| 4a. Request | Fill the placeholders, using `none` for an empty list, and attach the image | `prompt.TagPrompter.prompt`, `prepareImage` |
| 4b. Reply | Lenient JSON reading (fences, text around the object), then the strict tag check | `prompt.TagPrompter.parse`, `model.TagReply` gains `confidence`, `noTagFits`, `framing`, `reason` |
| 4c. Outcomes | Results as in the design's table. A bad reply is already retried once and then handed over, through `TaggerOutcomes.failed` | `pipeline.TaggerOutcomes`, `pipeline.TaggerListener` |
| 4d. Sending | Start sends only rows routed to the LLM. `canTag` becomes true once usable topic files are loaded | `ui.TaggerFrame` |
| 4e. Showing | The reply's reason and confidence, a low-confidence mark, and `framing` for an escalation | `ui.TagStrip`, `ui.TagTableModel` |
| 4f. Prompt caching | Confirm the prompt is a stable prefix with these placeholders. They sit near its end, so most of it should cache | `TagPrompter`, checked in the token log |

A JSON schema (`TaskPrompter.responseSchema`) isn't used at first. Restricting the reply to the listed tags would hide the very failure the tag check exists to catch, and that failure should stay visible.

### Verification

- A handful of Kinematics questions on the free model, read in the transcripts, before any larger run. This is the prompt's first real use.
- Each row of the design's reply table, using canned replies.
- A Ray Optics lens-and-mirror question, with and without the image, as evidence for decision 8.
- Cost per question from `token-usage.csv`, projected for the eligible questions.

### Results

All on the free model, pinned, so nothing was charged. Nothing was saved on the server.

| Check | Result |
|---|---|
| Canned replies, each row of the design's table | 4 accepted and 9 rejected as they should be |
| Five Kinematics questions | All accepted on the first attempt, with the prompt's format followed. About 2,960 tokens in and 78 out, 1.6 to 14 s |
| Eight Ray Optics questions whose text names no lens or mirror | All accepted on the first attempt. About 4,100 tokens in and 78 out |
| The same eight without the image | 5 gave the same tags. 3 differed, see below |
| A stored reply on the next Load | 5 of 5 restored, with no new request |
| Start | Sends exactly the rows routed to the LLM |

Without the image, `AITS-14-M-PT4://P/SCA/21` was tagged `refraction.sph-surface`, its reason citing a figure the model hadn't been given, though the surface is plane. `AITS-17-M-FT4://P/SCA/7` gained `plane-mirror.combination` with no mirror in the question. `AITS-15-M-CT3://P/SCA/29` lost `refraction.light-speed`, though the question asks for a traversal time. This supports sending the image, but it is a small sample, and none was the lens-or-mirror confusion decision 8 names.

**Cost if every LLM question went to the paid model**, at $0.15 and $0.50 per million tokens with no caching: about $0.0005 a question and $0.04 for Kinematics' 83, about $0.00065 a question and $0.21 for Ray Optics' 323.

**Prompt caching isn't confirmed (4f).** The free model reports no cached tokens, so this needs a few requests on the paid model, and matters little at these costs.

These replies have not been judged for correctness.

## Step 5: Saving

| Layer | Work | Code |
|---|---|---|
| 5c. Existing tags | They start in the chosen set with the proposed ones, and what the set holds at Save is saved. The chosen tags bar shows them | `model.TagDecision.withExisting`, `ui.TagTableModel.Row.proposedSet`, `startingSet` |
| 5d. Hold-outs | *Escalated* status, held by the reply stored in `pending-tagging.json` for the topic and cycle until the tags are saved. **Load escalated** walks the topic's queue for them, and drops the hold of one gone from it. A new cycle releases them | `workspace.PendingTagging`, `QTagger.loadEscalated`, `ui.TaggerFrame` |
| 5e. Audit | The design's fields, documented in [`tag-audit-format.md`](tag-audit-format.md). Stored replies stay valid by cycle only, not by the files' hashes, so rebuilding a cycle doesn't silently lose its holds | `workspace.TagAuditLog` |
| 5f. Review filter | Low confidence, existing tags, escalated | `ui.TaggerFrame.Show` |
| 5g. Reply fields | `/Refresh` also returns `topicDependencies`, which is ignored | `server.RefreshResult` |

### Verification

All on dev, with the owner watching, on a few questions:

Checked first against a stub for `/Refresh` alone, with every read going to dev (see Results). The real save is still to do:

- **A real save:** the first in the new code. The status becomes `AUTO_TAGGED`, and the tags on the server are what was shown, with implied parents added by the server.
- **Other-topic tags:** a question with one keeps it.
- **Empty set:** held back, with no server call.
- **Hold-outs:** an escalated question stays out of later Loads from the start, and a new cycle releases it.
- **Audit:** every field is present and reads back.

### Results

Through the frame, off-screen, on Kinematics. Writes went to a stub, so nothing reached dev.

| Check | Result |
|---|---|
| A same-topic tag already on a question | Starts in the chosen set: `[R] graph-of-motion, projectile-motion`, sent as both |
| The owner deletes it | Sent without it: `[53]` |
| An other-topic tag on a question | Kept: `[78, 55]`, and it doesn't show among the topic's tags |
| An empty set | Held back with its message, and not sent |
| A rejected item | *Error*, with the server's message and the decision kept |
| No tag fits | *Escalated*, not sendable, and still decidable |
| Load from start | Comes back *Escalated*, with no request |
| Load escalated | Just that question |
| Tagged by hand and saved | *Saved*, and the hold is gone |
| A held question gone from the queue | The hold is dropped by the next Load escalated |
| A new cycle | The hold is released, and the question goes back to `[LLM]` |
| Audit | Every field present on the 4 decisions |

**Real save on dev, by the owner.** Two regression-settled Kinematics questions, both *Saved*; the owner verified them on the server and the database agrees:

| Question | Sent | On the server |
|---|---|---|
| 25612, `AL-M-21://P/SCA/13` | `linear-kinematics` | The same, `AUTO_TAGGED` |
| 26395, `GMP-P://P/MCA/6` | `moving-medium` | That, plus `relative-motion` the server implied, `AUTO_TAGGED` |

Their audit lines have every field, and the hashes match the files. A real save of an LLM reply, a deleted tag or another topic's tag hasn't happened yet: those were checked against the stub only.

## Changes after step 5

Asked for by the owner after step 5, changing what steps 4 and 5 built:

| Change | Code |
|---|---|
| A route `llm` question is *Partially undecided*: Proposed shows the accepted tags and the undecided ones (triggered included), dotted red | `model.TaggerStatus`, `ui.TagTableModel.Row`, `ui.TagBadge.outlined` |
| A green tick and a red cross on each undecided tag. Save leaves out a question with any open | `ui.TagStrip`, `ui.TaggerFrame.resolveUndecided` |
| An LLM part with an **Invoke LLM** button. The reply is only shown, and clicks or drags move its tags to the chosen set | `ui.TagStrip`, `ui.TaggerFrame.replied` |
| Asking is apart from the status, so *Queued*, *Running* and *Failed* are gone | `Row.llm`, `Row.llmFailure` |
| The LLM no longer proposes a set, so the `MODEL` source and a decision's `model` are gone | `model.TagDecision` |

Checked through the frame against the stub, with one real call to the free model: 29 of 40 Kinematics questions partial, ticks and clearing, Save leaving a partial row out, the reply leaving status and chosen set alone, and no tag fits followed by tags. The strip was rendered and looked at. It hasn't been used in the real window.

### Decision 11: routing and acceptance

Replaces the *Partially undecided* flow above, and the LLM as an opinion.

| Change | Code |
|---|---|
| The tagger's routing rule on top of the port, which is unchanged | `regression.Decision.sendsToLlm`, `RegressionScorer` |
| *Partially undecided* and *Decided* go. *Awaiting LLM* is new, and everything with tags is *Accepted* | `model.TaggerStatus`, `ui.TagTableModel.Row.stand` |
| A From column (PAR, LLM, USR), and only `[E]` and `? a, b` left in Tags | `ui.TagTableModel` |
| LLM tags join the chosen set, and a new reply overrides the last | `Row.automaticSet`, `model.TagDecision.llm`, `ui.TaggerFrame.replied` |
| Questions go to the LLM after a Load, with a saved setting to turn it off | `ui.SettingsDialog`, `model.TaggerSettings`, `workspace.SettingsStore` |
| Send to model hides the model behind the current reply. DeepSeek, Haiku and GLM Flash added, on a root only the menu reaches | `Row.answeredBy`, `ui.TaggerFrame.fillSendTo`, `qtagger.ai.json` |
| The audit's `route` is the tagger's, with a new `dropped` | `workspace.TagAuditLog` |
| The Regression tab wraps, with `--- title ---` headers | `ui.RegressionPanel` |

Checked through the frame against dev's questions, with saves stubbed and replies injected: routing, automatic sending and the setting, ticks and crosses, adding and overriding, a hand edit surviving a reply, no tag fits, stored replies, Save and the audit lines. Of the first 60 Kinematics questions, 44 are accepted and 16 await the LLM. Of the first 200 Ray Optics questions, 79 are accepted and 121 await it, 2 of those under the accepted-together rule.

Not done yet: a real call to DeepSeek, Haiku or GLM Flash with reasoning off, a real save of LLM tags, and use in the real window.

## Going to production

The owner removed the pilot this plan used to end with. What it would have settled for production still stands:

1. Tick "Verified with the driver, on one small batch" in the server's plan, once a batch has gone through on dev. Phase 3 can then be committed and deployed to the Pi.
2. Check that dev and production question IDs match.
3. Point `server.url` back at the Pi.

## Touch points outside the tagger

| Code | Change | Other apps affected |
|---|---|---|
| `core.server.ServerQuestion` | May gain `taggingStatus` (step 5) | No: an extra read-only field |
| `core.scheduler`, `core.ui`, `core.review`, `com.sandy.sconsole.ai` | None planned | No |
| `common/ai-model-config.json` | None | No |

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

- [x] `QuestionOcr`
- [x] Terms, scoring, deciding
- [x] Scorer matches the reference on both cycles
- [x] OCR compared with `ocr-text.json`, and the result reported
- [x] Scoring on Load, with *Accepted* and needs-the-LLM rows
- [x] Regression's view shown
- [x] Split on dev compared with the estimate

### Step 4: The LLM

- [x] Decision 5 and the image part of decision 8 settled, as proposed
- [x] Request and reply
- [x] Outcomes, each reply case checked
- [x] Start sends only LLM rows
- [x] First real replies read in the transcripts
- [x] Image comparison and cost reported

### Step 5: Saving

- [x] Decision 3 settled: no stale-write guard
- [x] Decisions 2, 4, 6 and 9 settled
- [x] Existing tags in the chosen set
- [x] Hold-outs
- [x] Audit record, documented
- [x] Review filter
- [x] Real save verified on dev

### Going to production

- [ ] Server plan's driver check ticked
- [ ] Phase 3 committed and deployed to the Pi
- [ ] Dev and production question IDs match
- [ ] `server.url` pointed back at the Pi

## Revision log

| Rev | Date | Change |
|---|---|---|
| 1 | 2026-11-04 | Initial version, with step 1 done |
| 2 | 2026-11-05 | Step 2 reads the topic files directly from the question-bank folder at each scope, replacing the import. The tag list comes from the server. Step 3 names `RegressionScorer` and the order of its decision steps |
| 3 | 2026-11-05 | Step 2 done. `TopicFiles` lives in `regression`, not `model`, and the frame asks only for `Controller.topicFilesNotice`. Server tags the model has no entry for are a note, not a refusal |
| 4 | 2026-11-05 | Owner's change: a topic without usable files is refused with a dialog and the scope unloaded, instead of a status bar notice with its questions still loadable |
| 5 | 2026-11-05 | Owner's change: the Scope dialog offers only the topics with both files. The refusal stays for files that are there but can't be used |
| 6 | 2026-11-05 | Step 3 done, with results. Saving held back until step 5. Ray Optics settles 13% on dev against an estimate of 37% |
| 7 | 2026-11-05 | Step 4 done, with results. Stored replies are tied to the cycle. Decisions 5 and 8 implemented as proposed, awaiting the owner. Prompt caching unconfirmed |
| 8 | 2026-11-05 | Decision 5 and the image part of decision 8 settled by the owner, as proposed. The pilot part stays with step 6 |
| 9 | 2026-11-05 | Decision 3 settled: layers 5a and 5b dropped, with their checks |
| 10 | 2026-11-05 | Step 5 built and checked against a stub. Decision 4 changed: no flag, existing tags start in the chosen set. Hold-outs live in `pending-tagging.json`, so no new file. Saving enabled |
| 11 | 2026-11-05 | Real save verified on dev by the owner, on two regression-settled questions |
| 12 | 2026-11-05 | Owner's changes before the pilot: undecided tags by hand, the LLM on request |
| 13 | 2026-11-05 | Owner's decision 11: new routing and acceptance, the From column, automatic LLM calls with a setting, Send to model |
| 14 | 2026-11-05 | Owner's change: the pilot step removed. What production needs stays, as *Going to production* |
