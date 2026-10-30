# NXRClassifier: local N/X/R labelling of question images

| Field | Value |
|---|---|
| Component | `NXRClassifier` (`com.sandy.sconsole.nxr`), standalone, used by a driver app |
| Builds on | The 2024–26 JEE Main question-type analysis (`question-bank/supporting-artefacts/analysis/question-type-2026/`, `question-type-2024-25/`) |
| Server | SConsoleNxt NXR endpoints (`/NXR/Pending`, `/NXR`, `/NXR/{questionId}/{nxrValue}`), implemented |
| Status | Rev 6, 2026-10-30: implemented; Physics/Maths never R (§4.5), Chemistry rules (§4.6) |

Every question in the bank (~40k images) gets an NXR label, so that mocks
can match the mix of the real exam and so be time-representative. Sending
every image to an LLM costs too much and takes too long. `NXRClassifier`
labels a question locally, from its image and the metadata the server already
has. When it can't label a question with enough confidence it returns
null, and a human labels it.

This document specifies the classifier, its model file and how that file is
trained. The driver (fetching batches, posting labels) is the caller's; §8
lists what the driver needs to know.

---

## 1. Concepts

| Term | Meaning |
|---|---|
| **N** (numeric) | Solving needs calculation, symbolic included. |
| **X** (reasoning) | Solving needs reasoning or concepts, but no real calculation. |
| **R** (recall) | The answer is a fact to remember. |
| **Label** | One of `N`, `X`, `R`; the server's `Question.nxrValue`. |
| **Consolidated image** | The question as one image: its parts stacked in order (`core.util.ImageCollator.collate`). |
| **Tier** | One step of the pipeline (§4). The first tier that decides gives the result. |

Conventions, as used for the hand labels:

| Case | Label |
|---|---|
| Physics: matching dimensions or units | R in the PYQ hand labels; the classifier gives N or X (§4.5) |
| Physics: deriving a single dimension | N |
| Statement, assertion–reason, logic-gate, graph-choice questions | X (R if only a fact is asked) |
| Maths: properties of relations, one-one / onto, function-property statements, counting points of discontinuity or non-differentiability | X |
| Maths: counting relations or elements, solving for continuity parameters | N |
| Chemistry: molar mass of an identified product | X |

The mix the hand labels show (share of questions, JEE Main 2024–26):

| Subject | N | X | R |
|---|---|---|---|
| Maths | ~96% | ~4% | 0 |
| Physics | 80–90% | 8–16% | 2–5% |
| Chemistry | 20–37% | 42–60% | 11–23% |

So most of the corpus is N, and most of the work is in Chemistry. The
user's review of the first bank labels settled that Physics and Maths are
never R (§4.5).

---

## 2. API

`NXRClassifier` stands alone, like `topicresolver.TopicResolver`. It uses
only the JDK and Jackson, with no Lombok, no SLF4J and no project imports,
so it can be copied into another app unchanged. It is thread-safe: one
instance serves every thread of the driver.

```java
public final class NXRClassifier {

    /** Loads the model file and checks that tesseract runs. */
    public static NXRClassifier load( Path modelFile, Path tesseractBin )
            throws NXRException ;

    /** Labels one question. Never throws for a bad image; see NXRResult. */
    public NXRResult classify( BufferedImage image, String subject,
                               String problemType, Integer topicId ) ;
}

public record NXRResult(
        String value,       // "N", "X", "R", or null: a human decides
        double confidence,  // 0-1; 1.0 for a rule
        Method method,      // RULE, GATE, MODEL, or NONE when value is null
        String reason,      // the rule or gate that fired, or why null
        String ocrText      // normalised OCR text (§4.2); null if not run
) {}
```

| Input | Values | Notes |
|---|---|---|
| `image` | consolidated image | Built by the caller with `ImageCollator.collate`. For an LCT question, pass the question's own images only, not the shared `Ctx` passage. |
| `subject` | `P`, `C`, `M` | The driver maps `ServerQuestion.syllabusName` with `core.model.Subject`. Anything else → null result, reason `unknown subject`. |
| `problemType` | `SCA`, `MCA`, `ART`, `CMT`, `LCT`, `MMT`, `NVT`, `IVT` | `ServerQuestion.problemType`. |
| `topicId` | server topic id, or null | A feature for the model (§4.4). Pass it whenever the question has one: on a 20-question non-PYQ sample, adding it cut nulls from 8 to 5 and fixed the one wrong label. Null is allowed but gives more nulls, since every training question had a topic. |

**Errors.** `NXRException` (checked) is thrown only by `load`, for faults
in the setup: the model file is missing, unreadable or of an unknown
`version`, or tesseract is missing or fails on a built-in test image. Every
fault on a single question gives a null result with a reason and doesn't stop
the batch. Examples: OCR timing out or failing, empty OCR text, an unknown
subject.

| `reason` (null results) | Cause |
|---|---|
| `unknown subject` | `subject` not P/C/M |
| `ocr failed: <detail>` | tesseract exit code ≠ 0, or timeout |
| `ocr empty` | fewer than `minOcrChars` characters after normalisation |
| `low confidence <p> < <threshold>` | the model's top probability is below its threshold, and no Chemistry pattern matches (Chemistry only, §4.5, §4.6) |

A Physics or Maths question that reaches the model always gets N or X, with
`reason` = `physicsMathsNoRecall` (§4.5).

---

## 3. Expected volumes

No accuracy figures are claimed here. §7 sets the targets and training
(§6) measures them. The tiers are expected to split the corpus roughly like
this, going by the hand labels:

| Tier | Share of corpus (rough) | Basis |
|---|---|---|
| Rules (no OCR) | ~10% | Physics numericals |
| Maths gate | ~30% | Maths questions with no flag keyword |
| Model, labelled | ~45% | the rest, above threshold |
| Null → human | ≤ 15% (target) | Chemistry below threshold (§4.5) |

The share is measured on the held-out set and reported by the trainer.

---

## 4. Pipeline

`classify` runs the tiers in order. The first one that decides gives the
result.

### 4.1 Rules (metadata only, no OCR)

| Rule | Label | Evidence (hand labels) |
|---|---|---|
| `subject = P` and `problemType ∈ {IVT, NVT}` | N | 385 of 385 Physics numericals are N (2024–26). |
| `problemType = ART` | X | By convention (assertion–reason is X). Not in the PYQ-M labels; the user may switch it off in the model file. |

Each rule can be switched on or off in the model file (`rules`). Maths
numericals are **not** a rule: 13 of 388 were X ("number of … relations",
"how many points of non-differentiability"). They go through the gate, §4.3.

### 4.2 OCR

- Command: `<tesseractBin> <png> stdout --psm 6 -l eng`. The image is first
  written to a temporary PNG, which is deleted afterwards.
- Timeout: `ocrTimeoutSeconds` from the model file (default 30). On a
  timeout the process is destroyed and the result is null.
- Normalisation, applied in this order to the OCR output. The trainer does
  exactly the same, and the golden vectors (§5.2) check that it does:
  1. Unicode NFKC, then lower-case.
  2. Replace each run of digits (with an optional decimal point and digits)
     with ` <num> `.
  3. Replace each character that isn't a letter, `<`, `>` or a space with a
     space. The exceptions are `(`, `)` and `.`, which are kept only inside
     option markers (`(a)`, `(1)`, `a.`). Those markers are read in step 5.
  4. Collapse whitespace.
  5. **Options**: find markers `(a)`–`(d)`, `(1)`–`(4)`, `a.`–`d.`. The text
     between one marker and the next is that option. With fewer than 2
     markers there are no options.
- `minOcrChars` (default 20): below it the result is null, reason
  `ocr empty`.

### 4.3 Maths gate

For `subject = M`, any type: if the OCR text matches none of the flag
keywords, the label is N (`method = GATE`, confidence 1.0). Otherwise the
question goes to the model.

Flag keywords (case-insensitive regex, kept in the model file as
`mathsFlagKeywords`):

```
statement|relation|one-one|onto|continu|differentiab|reflexive|symmetric|
transitive|true|false|negation|tautology|contrapositive|equivalence|
logical|converse|which of the following is (correct|incorrect)
```

Evidence: on the 2026 labels (471 Maths questions, from PDF text) it flags
50, including all 15 that aren't N. On 2024–25 it flags 101 of 1,073. OCR text
is noisier than PDF text, so the trainer re-measures the catch rate on OCR
text (§6). If the rate drops below 100% there, the trainer may add the
misspellings it finds to the list.

### 4.4 Model

A multinomial logistic regression over three classes (`N`, `X`, `R`).

| Feature group | Detail |
|---|---|
| Words | TF-IDF over word unigrams and bigrams of the normalised text; vocabulary and idf from the model file; L2-normalised vector; out-of-vocabulary words dropped |
| Subject | one-hot `P` / `C` / `M` |
| Type | one-hot over the types in the model file; others → `type:other` |
| Topic | one-hot over the topic ids in the model file; others or null → `topic:other` |
| Engineered | `numericOptionShare` (options that are mostly `<num>`), `optionCount`, `statementCount` (markers like `statement i`, `(i)`, `(ii)`), `hasMatch` (`match`, `list i`, `column`), `hasCorrectIncorrect`, `hasAssertionReason`, `log1p( tokenCount )`. All are defined exactly in the model file's `tokeniser` section |

Score: `p = softmax( W · x + b )`. The label is `argmax p` if `max p` is at
least the threshold for the question's `(subject, problemType)`. The
lookup order is `subject:type`, then `subject`, then `default`. Otherwise
the result is null, reason `low confidence`.

Types absent from training (in the PYQ-M labels only SCA and IVT appear)
fall to the `subject` threshold plus `unseenTypeMargin` (default 0.10). Their
labels are therefore emitted only when the model is very confident.

### 4.5 Physics and Maths: never R

Rule `physicsMathsNoRecall`, applied to the model's probabilities for a
Physics or Maths question (any type). Thresholds are not used:

| p(N) | Label | `confidence` |
|---|---|---|
| ≥ `physicsMathsNMin` (0.60) | N | p(N) |
| < `physicsMathsNMin` | X | 1 − p(N) |

`method` is `MODEL`, `reason` is `physicsMathsNoRecall`. Physics and Maths
questions are therefore never null once OCR succeeds. Chemistry keeps the
thresholds of §4.4.

Why: the user reviewed the first 220 bank labels (AITS-14-M-CT1–3, all SCA).
Physics and Maths are never recall, and a Physics or Maths question the
model isn't sure is N is reasoning. With v1's weights:

| Subject | Before (v1): labelled, accuracy, null | After |
|---|---|---|
| Maths (60) | 58, 100%, 3% | 60, 100%, 0% |
| Physics (90) | 73, 100%, 19% | 90, 93.3%, 0% |
| All (220) | 169, 100%, 23% | 188, 96.8%, 15% |

The 6 Physics misses: 3 N given X (p(N) 0.50–0.57), 2 the user labelled R,
1 X given N (p(N) 0.69). A floor of 0.5 or 0.7 scored 94.4% and 91.1%.

### 4.6 Chemistry rules

All of §4.6 is switched by rule `chemistryPatterns`, except the label
thresholds.

**Steps, in order, for a Chemistry question the model has scored:**

1. **Numeric stem boost.** If the stem (below) shows a measured value
   (`measuredOption` regex), the odds of N are multiplied by
   `chemistryNumericStemOdds` (3.0): `p(N) ×= 3`, then `p` is renormalised.
2. **Label thresholds.** The label is `argmax p` if it reaches
   `labelThresholds.C.<label>`: 0.60 for N, X and R. A label threshold
   replaces the §4.4 threshold and gets no unseen-type margin.
   **Comparison override:** if that label is R and the question asks for a
   comparison (the `chemCompareX` condition below), the label is X instead,
   with `method` = `RULE` and reason `chemCompareX`.
3. **Patterns** (the table below) if the question is still unlabelled.
4. **Scenario.** If still unlabelled, the stem sets up a situation
   (`scenarioStem`: if, when, what will happen, assuming, "is added /
   heated / passed …"), and p(N) and p(R) are both below
   `chemistryScenarioMax` (0.50): X, reason `chemScenarioX`.

The user's rules, from reviewing the bank labels:
- numbers in the question body raise N;
- a scenario question with low N confidence is X;
- 0.60 is enough for Chemistry N, X and R.

On the user's example, AITS-14-M-FT2 C18 (labelled N), p(N) goes from 0.38
to 0.65. A plain additive boost did worse than the odds multiplier, and ×3
scored best of ×2–×5. Requiring p(R) < 0.5 for a scenario keeps out the
recall questions with a scenario-like stem ("on heating … it gives"). Without
it, 3 of the 10 scenario labels were R.

Measured on 180 verified bank Chemistry questions (v1 weights, with topic).
The settings were chosen on these same questions, so expect a little less on
new ones:

| | Labelled | Accuracy | Null |
|---|---|---|---|
| Model, R/N/X at 0.80 | 107 of 172 | 100% | 38% |
| + patterns (Rev 3) | 127 of 180 | 100% | 29% |
| + boost, N/X at 0.60, scenario (Rev 4) | 154 of 180 | 94.2% | 14% |

The 9 wrong labels in Rev 4 all come from the model clearing 0.60:
- 5 R given X (p(X) 0.66–0.80; "which of the following is correct …");
- 4 X given N, two of them boosted (a scenario with quantities: "0.5 mole of
  He is introduced … what will happen").

Rev 5 lowers R to 0.60 too. On 210 verified Chemistry questions: 191
labelled, 92.1% accurate, 9% null. The 15 R labels between 0.60 and 0.80
are 9 right and 6 wrong (all X). Four of the six are comparisons ("increasing
order of thermal stabilities", "weakest interparticle forces"), which the
`chemCompareX` pattern would call X. Patterns only fill nulls, though, so it
doesn't get the chance.

Rev 6 therefore lets the comparison override an R label from the model. On
the verified bank Chemistry questions it changed 7 labels from R to X. 6 were
right; the wrong one was "most oxidizing agent is …", which the user labelled
R. One of the 6 right ones matched by accident ("in order to distinguish …"
reads as "order"). The golden cases carry a `compare` flag for this check.

#### Patterns

When the model is below its threshold for a Chemistry question, these
patterns are tried in order, on the cleaned text
(§4.2). The first that matches gives the label, with `method` = `RULE`,
`confidence` 1.0 and `reason` the pattern's name. A confident model label is
left alone. On the bank labels the confident labels were all right, and
running the patterns first would have added two wrong ones.

The **stem** is the text before the first option marker. The **options** are
the texts after each marker, up to the next (§4.2). An option is
**numeric** if at least half its tokens are numbers. The regexes are in the
model file, as `chemistryPatterns.<key>`.

| Pattern | Label | Matches when |
|---|---|---|
| `chemNumericOptionsN` | N | ≥ 3 options, ≥ ¾ of them numeric; the stem doesn't ask for a count or a state (`countStem`: "number of", "how many", oxidation state, n-factor, …); and at least half the options show a measured value (`measuredOption`: a decimal, %, a number of 2+ digits, ×10, a unit) |
| `chemOptionSetsX` | X | ≥ 2 options are each a whole set of items (`setOption`: "I and II", "(a), (b) and (c)", "II only"; OCR's `|`, `l`, `1` for I allowed) |
| `chemCompareX` | X | ≥ 3 options, ≤ ¼ numeric, and the stem asks for a comparison (`compareStem`: order, increasing, highest, strongest, more, less, arrange, …) |
| `chemChainX` | X | ≥ 3 options, ≤ ¼ numeric; the stem has ≥ 2 reaction arrows (`arrow`) and asks for a product or compound (`asksProduct`) |

These are the user's patterns, from reviewing the bank labels:
- numeric options are N;
- a comparison with non-numeric options is X;
- a chain of reactions A → B → C is X;
- options that are sets of items are X.

The count and measured-value checks keep out questions like "the number of
stereoisomers is (a) 2 (b) 4 …". Those were X in the user's labels.

Measured on the 172 verified bank Chemistry questions (v1 weights, with
topic):

| | Labelled | Accuracy | Null |
|---|---|---|---|
| Model only | 107 | 100% | 38% |
| Model, then patterns | 122 | 100% | 29% |

The remaining 50 nulls are 10 N, 20 R and 20 X. No pattern targets R.

The model file also carries `chemistryPatternGolden`: about 40 cleaned texts,
each with the pattern the trainer found (or null) and whether its stem is
numeric and a scenario. `load` runs them and
throws `NXRException` on any difference. The regexes are compiled with
`UNICODE_CHARACTER_CLASS`, which matches Python's `re`.

---

## 5. Model file

### 5.1 Format

One JSON file, written by the trainer and read by `load`.

```json
{
  "version"          : 1,
  "trainedAt"        : "2026-10-31T10:00:00Z",
  "trainingSummary"  : { "questions": 4616, "heldOut": "2026", "...": "..." },
  "ocr"              : { "psm": 6, "lang": "eng", "timeoutSeconds": 30,
                         "minChars": 20 },
  "tokeniser"        : { "ngrams": [1, 2], "engineered": [ "..." ] },
  "rules"            : { "physicsNumericN": true, "artX": true,
                         "physicsMathsNoRecall": true,
                         "chemistryPatterns": true },
  "physicsMathsNMin" : 0.60,
  "labelThresholds"  : { "C": { "N": 0.60, "X": 0.60, "R": 0.60 } },
  "chemistryNumericStemOdds": 3.0,
  "chemistryScenarioMax"    : 0.50,
  "chemistryPatterns": { "countStem": "...", "measuredOption": "...",
                         "compareStem": "...", "arrow": "...",
                         "asksProduct": "...", "setOption": "...",
                         "scenarioStem": "..." },
  "chemistryPatternGolden": [ { "text": "...", "rule": "chemCompareX",
                                "numericStem": false,
                                "scenarioStem": true } ],
  "mathsFlagKeywords": "statement|relation|...",
  "classes"          : [ "N", "X", "R" ],
  "vocab"            : { "<term>": [ index, idf ], "...": "..." },
  "featureNames"     : [ "w:...", "subj:C", "type:SCA", "topic:42", "..." ],
  "weights"          : [ [ "...per class, one per feature..." ] ],
  "bias"             : [ 0.0, 0.0, 0.0 ],
  "thresholds"       : { "default": 0.8, "C": 0.85, "P:SCA": 0.8 },
  "unseenTypeMargin" : 0.10,
  "golden"           : [ { "text": "...", "subject": "C",
                           "problemType": "SCA", "topicId": 42,
                           "p": [ 0.1, 0.7, 0.2 ] } ]
}
```

`load` rejects an unknown rule, a missing `physicsMathsNMin` when
`physicsMathsNoRecall` is on, a Chemistry pattern golden case it doesn't
reproduce, `classes` without `N`, an unknown `version`, a `weights` shape that doesn't match
`classes` × `featureNames`, or a `golden` case it doesn't reproduce (§5.2).

### 5.2 Golden vectors

The trainer writes about 50 cases of already-OCR'd text and metadata with the
probabilities it computed, mixing subjects, types and labels. `load` scores
each one through the Java normalisation and features. If any probability is
more than 1e-6 off, it throws `NXRException` naming the case. This catches
any drift between the Python and Java tokenisers before a single label is
written.

---

## 6. Training (offline, Python)

Lives in the question bank: `supporting-artefacts/analysis/nxr-model/train.py`,
with its own README.

| Step | Detail |
|---|---|
| Labels | 4,616 hand-labelled PYQ-M questions: `question-type-2026/2026-01/labelled.json`, `2026-04/labelled.json` (1,407) and `question-type-2024-25/labelled.json` (3,209). Keys: `paper`, `n`, `subj`, `type`, `topic`, `cat`. |
| Images | Each labelled question's images from `PYQ-Archive/PYQ-M/PYQ-M-<paper>/question-images/`, parts joined in order, as `ImageCollator` does. A 16-bit grayscale PNG (about 12% of the bank) is scaled to 8 bits, as Java's ImageIO reads it; PIL's own conversion clips it to the pure-black pixels. |
| Text | The same tesseract command and normalisation as §4.2, run on those images. PDF text is **not** used, so the model sees in training what it will see in production. |
| Gate check | Re-measure the §4.3 catch rate on OCR text; report the non-N Maths questions it misses. |
| Fit | scikit-learn `LogisticRegression` (multinomial, L2, `class_weight="balanced"`), C by 5-fold CV on the training years. |
| Evaluate | Train on 2024–25, test on 2026. Report, per subject and overall: accuracy of emitted labels, null rate, confusion matrix, per-class recall. Chemistry X/R confusion is reported separately. |
| Thresholds | Per subject (and `subject:type` where there's enough data), the lowest threshold at which emitted labels on the held-out set are ≥ 95% accurate. |
| Ship | Refit on all years with the chosen C. Keep the held-out thresholds. Write the model file with golden vectors and `metrics.md`. |

The topic ids in the labels are names (`topic`). The trainer maps them to
server topic ids through `scripts/topic_master.json`, and keeps unmapped
ones as `topic:other`.

---

## 7. Acceptance

The model file is accepted for a bulk run when:

1. On held-out 2026, emitted labels are ≥ 95% accurate overall and ≥ 90% for
   Chemistry, with a null rate ≤ 15% overall.
2. The Maths gate misses no more than 1 non-N Maths question of the 2026 set
   on OCR text.
3. **Book check.** The user labels ~200 random non-PYQ questions (books and
   test series: RB, LK, AITS and the old SConsole import, all three subjects)
   in a small sheet, and the classifier is run on them. Accuracy on emitted
   labels must be ≥ 90%. If it falls short, those questions are added to
   training and the thresholds tightened before going on.
4. Throughput: ≤ 2 s per OCR'd question on one core. The classifier is safe
   to call from several threads; tesseract runs as separate processes.

---

## 8. Driver notes (outside the classifier)

| Point | Detail |
|---|---|
| Fetch | `GET /NXR/Pending?batchSize=1..100` → `{ totalResults, questions: [QuestionVO] }`, unlabelled questions in id order. |
| Post | `POST /NXR` with `{ updates: [ { questionId, nxrValue } ] }`, ≤ 500 per request. One result per update: `APPLIED`, `UNCHANGED` or `REJECTED` (+ `message`). |
| Null results | Post nothing. **Server change needed:** `/NXR/Pending` has no cursor, so once there are `batchSize` or more questions waiting for a human, every fetch returns the same batch. Add an `afterId` parameter (as the topic-review query has), or keep a local skip list in the meantime. |
| Audit | The server keeps no provenance or confidence. Log every result locally (`questionId, value, confidence, method, reason, modelTrainedAt`), e.g. `nxr-results.csv` in `workspace.dir`. This lets a later model re-run only the `MODEL` labels, and gives the human queue its input. |
| Overwrites | A label can be changed but not cleared. A re-run must never overwrite a human's label. Only the local log tells them apart, until the server records provenance. |
| Images | Local files from the question bank (`<sourceId>.<page>.<fileName>`), as the other apps read them; `ServerQuestionImage` gives the order. |

---

## 9. Configuration

The driver's properties file gains two keys. As in `QClassifierConfig`,
there are no defaults in code, and a missing key is a startup error.

| Key | Example |
|---|---|
| `nxr.model.file` | `~/projects/workspace/nxr/nxr-model.json` |
| `nxr.tesseract.bin` | `/opt/homebrew/bin/tesseract` |

Prerequisite: `brew install tesseract` (the English data is included).

---

## 10. Code (sketch)

Package `com.sandy.sconsole.nxr`:

| Class | Role |
|---|---|
| `NXRClassifier` | `load`, `classify`; runs the tiers |
| `NXRResult` | the result record |
| `NXRResult.Method` | `RULE`, `GATE`, `MODEL`, `NONE` |
| `NXRException` | setup faults from `load` |
| `Ocr` (package-private) | temp PNG, tesseract process, timeout, raw text |
| `Normaliser` (package-private) | §4.2 normalisation and option parsing |
| `Features` (package-private) | the §4.4 feature vector |
| `LinearModel` (package-private) | weights, softmax, thresholds, golden check |
| `Rules` (package-private) | §4.1 rules, §4.3 gate, §4.5 Physics/Maths rule |
| `ChemistryPatterns` (package-private) | §4.6 boost, patterns, scenario and their golden check |

---

## Appendix A: decisions

| Decision | Why |
|---|---|
| Image plus metadata as input | Subject and type decide about 40% of the corpus with no reading. Subject and topic sharpen Chemistry, where the hard cases are. |
| Null rather than a best guess | A wrong label skews the mocks' timing without anyone noticing. A null is visible and a human fixes it. |
| tesseract CLI | Local, free, about 1 s per image, no new Java dependency, and the class stays standalone. Tess4J adds a native library; a local vision LLM would take days over 40k images, with accuracy unmeasured. |
| A linear model | It is explainable (weights per word), fits in one JSON file, trains in seconds, and Java can score it in a few dozen lines with no ML library. The features carry most of the signal. |
| Train on OCR text, not PDF text | The model must see in training what it sees in production. Most of the bank has no PDF text layer. |
| Golden vectors in the model file | The tokeniser exists twice, in Python and Java. The model file proves at `load` that the two agree. |
| Maths numericals not a rule | 13 of 388 are X. The gate catches them, and the rest still need no model. |
| Chemistry patterns only below threshold | The model's confident Chemistry labels were all right on the bank labels. The patterns fill nulls without overriding them. |
| Physics and Maths never R, low p(N) → X | The user's call on reviewing the first bank labels. A Physics or Maths question that isn't clearly numeric is reasoning, so a null there only costs a human's time. |

---

## Revision log

| Rev | Date | Change |
|---|---|---|
| 1 | 2026-10-30 | First version. |
| 2 | 2026-10-30 | §4.5: Physics and Maths never R; N if p(N) ≥ 0.60, else X; no nulls for them. Model file: rule `physicsMathsNoRecall`, `physicsMathsNMin`. Measured on the user's 220 verified bank labels. |
| 3 | 2026-10-30 | §4.6: the user's Chemistry patterns label nulls (numeric options → N; option sets, comparisons, reaction chains → X). Model file: rule `chemistryPatterns`, `chemistryPatterns`, `chemistryPatternGolden`. Chemistry null 38% → 29% on 172 bank labels, no wrong labels. |
| 4 | 2026-10-30 | §4.6: N boosted (odds ×3) when the stem shows a measured value; Chemistry N and X need 0.60 (`labelThresholds`); scenario stems with p(N), p(R) < 0.5 → X. Chemistry on 180 bank labels: 94.2% accurate, 14% null. |
| 5 | 2026-10-30 | Chemistry R needs 0.60 too. Chemistry on 210 bank labels: 92.1% accurate, 9% null. |
| 6 | 2026-10-30 | A comparison overrides a model R label → X (`chemCompareX`). Golden cases gain `compare`. |
