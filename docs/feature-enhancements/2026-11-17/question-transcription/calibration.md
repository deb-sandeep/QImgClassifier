# Transcription gate calibration

**Status:** spec, rev 2 (2026-11-17: faults graded by what they cost the
tagger, §1, §3.4, §3.6). To be run by the user with the
question-bank agent, outside QTranscriber; the results come back to this
folder (§7) and set the app's gate.

Read with `logprob-confidence.md` (the gate, rev 2), whose §4 defines the
checks and §6 the plan this spec details, and the prompt,
`src/main/resources/apps/transcriber/qtranscriber.prompt`.

## 1. Why

**What the transcription is for.** The transcription is not meant to be a
picture-perfect text copy of the question. It is there to make the tagger
(and search) more effective: they need to know what the question is about
and what it asks. So the accuracy tolerance is wide. A wrong digit, exponent
or sign (`5f^4` for `5f^{14}`) leaves the question about the same thing
and is harmless; a wrong compound, a missing statement or a garbled
reaction is not. The gate, and this calibration, aim at the faults that
matter to the tagger (*material* faults, §3.4), not at every fault.

QTranscriber accepts Qwen's transcription when Qwen was sure of it, and
otherwise sends the question to Nemotron, whose transcription is accepted
as it is. "Sure" is the gate of `logprob-confidence.md` §4: a reply must be
well formed (§4.1), and its count **C** of *contested positions* (§4.2:
places where Qwen nearly wrote a different reading, within `MARGIN` of the
token it chose) must be at most `C_MAX`.

`MARGIN = 0.3` and `C_MAX = 0` are guesses from a probe of four questions,
on which the gate escalated nothing and caught neither real error. Before
the app runs unattended we need to know, on a real sample:

1. how often Qwen's transcriptions have a material fault, and of what
   kind;
2. whether C is high on those and low on the rest, i.e. whether the gate
   works at all;
3. which `MARGIN` and `C_MAX` catch most material faults while sending an
   acceptable share to Nemotron;
4. how many material faults no setting catches (Qwen confidently wrong,
   F4), which only the user's spot checks can find;
5. whether the prompt needs changing first.

## 2. Setup

### 2.1 Qwen requests

Every request exactly as the app will send it (`logprob-confidence.md`
§4.4), so the numbers carry over:

```json
{
  "model"   : "qwen3.5:27b-q8_0",
  "prompt"  : "<the filled prompt>",
  "images"  : [ "<base64 PNG>" ],
  "stream"  : false,
  "think"   : false,
  "logprobs": true,
  "top_logprobs": 5,
  "options" : {
    "temperature"      : 0,
    "top_k"            : 1,
    "presence_penalty" : 0,
    "frequency_penalty": 0,
    "repeat_penalty"   : 1,
    "num_ctx"          : 8192,
    "num_predict"      : 4096
  }
}
```

to `POST http://mac-studio.local:11434/api/generate`. The Modelfile's own
`presence_penalty 1.5` and `temperature 1` must be overridden as above, or
Qwen doesn't write its most likely token (F1). The context is 8192 unless
a reply needs more; record any change.

### 2.2 The prompt

The `PROMPT` section of `qtranscriber.prompt`, as tweaked by the user and
the question-bank agent, with its placeholders filled as the file's header
says. Lines starting `#` are not sent. Freeze it before step 3.2: a change
to the prompt afterwards means running the sample again (the logprobs
change with it). Record the file's SHA-256 with the results.

### 2.3 Images

The question's own image files from the question bank, found as the apps
find them (`core.review.QuestionLocator`): the source folder named by
`sourceId` under the question bank, and in its `question-images/` the
files ending in `.<fileName>` of each server image, in `sequence` order.

- Part images (`X(1)`, `X(2)`) are stacked top to bottom into one PNG, as
  `core.util.ImageCollator` does (white background, left-aligned, no
  gap), and sent as one image.
- **LCT** questions are two kinds of request (the prompt's `TASK`
  sections):
  - *passage*: the group's `_Ctx` images only, `TASK LCT_PASSAGE`;
  - *question part*: the question's own images only (not the `_Ctx`
    ones), `TASK LCT_QUESTION`, with `{{PASSAGE}}` the passage as
    transcribed in this sample (Qwen's, or the corrected text if the
    user marked it faulty and corrected it; record which).
- No scaling or greyscale: the images go as stored.

## 3. Steps

### 3.1 Throughput

Measure on `mac-studio` with one question set run with 1, 2 and 3
requests in flight: tokens a second in total, and seconds a question.
Note `OLLAMA_NUM_PARALLEL` as set on the server. The app's Qwen
`maxParallel` is set from this (expect 1.5–2× with 3 in flight, not 3×;
single-stream was 32 tokens a second). Logprobs may slow generation;
compare a run with and without them once.

### 3.2 The sample

About 200 questions not yet transcribed (`question.xcription_needed` true
in the dev DB, or `GET /Master/Question/Transcription/Pending`), plus the
regression set (§3.5). Stratified, weighted towards what is hardest:

| Type | P | C | M | Total |
|---|---|---|---|---|
| SCA | 15 | 20 | 15 | 50 |
| MCA | 8 | 10 | 7 | 25 |
| IVT | 8 | 8 | 9 | 25 |
| NVT | 5 | 5 | 5 | 15 |
| ART | 5 | 10 | 5 | 20 |
| MMT | all 15 in the bank | | | 15 |
| LCT | 4 groups | 4 groups | 4 groups | 12 passages + their ~36 question parts |

- Pick at random within each cell, from several sources each (book,
  previous-year papers, test series), so no one printing dominates.
- Chemistry is weighted up: structures, SMILES and `\ce` are where Qwen
  was weakest.
- Note for each question whether it has a figure; at least 40 of the
  sample should.

### 3.3 The run

Run Qwen once over the sample with the settings of §2. For every request
keep (§6):

- the full Ollama response JSON (with `logprobs`, `top_logprobs`,
  `done_reason`, timings and token counts), so every metric can be
  recomputed later without running Qwen again;
- the exact prompt sent and the image sent.

### 3.4 Marking

The user marks each transcription, looking at it **rendered** (markdown +
MathJax 3 + mhchem, `diagram` blocks shown as boxes) beside the image. A
static HTML review sheet per batch is enough, built by the agent, with
the image, the rendered transcription, the raw markdown on demand, and
the marks written back to a file.

Each transcription gets one mark:

| Mark | Meaning |
|---|---|
| `correct` | Every printed character, symbol and figure is there and right |
| `faulty` | At least one fault of the kinds below |
| `unsure` | The user can't tell from the image; left out of the analysis |

and for a faulty one, every fault, each with a kind, a **severity** and
the text where it is.

| Severity | Meaning |
|---|---|
| `material` | Changes what the tagger or a search would take from the question: a wrong or missing concept word, compound, structure (`smiles:`), reaction, statement, option's meaning, or figure content the question depends on; text added that brings in another idea (a solution, a hint) |
| `minor` | The question still reads as the same problem: a wrong digit, exponent, sign, unit, subscript or symbol case, a stray or missing character, spacing, a layout slip that doesn't change the reading |

`5f^4` for `5f^{14}` and `FTF F` for `FTFF` are minor. A transcription with
only minor faults is marked `faulty` with them listed, but counts as
*acceptable* in the analysis (§3.6). When in doubt, ask: would a tag
chosen from this text differ from one chosen from the image?

The kinds:

| Kind | What it covers | Can the gate see it? |
|---|---|---|
| `misread` | A printed character, symbol, number or sign written as something else (`5f^4` for `5f^{14}`) | Yes, if Qwen hesitated |
| `missing` | Printed text left out (a word, line, option, table row) | Sometimes |
| `added` | Text not printed: a solution, a correction, a completed blank, a repeated passage | Sometimes |
| `smiles` | A `smiles:` line of the wrong structure | Yes (scored, E3) |
| `diagram` | A diagram description that is wrong or leaves out something the question needs | No (description lines are skipped) |
| `layout` | Content right but its structure wrong enough to mislead: options run together, a table broken, columns of a match list mixed | Rarely |
| `format` | Malformed markdown or LaTeX that doesn't render | Yes (§4.1) |

Not faults (mark `correct`): a notation choice that renders the same as
printed (`\mathrm` or not, spacing, `\dfrac` for `\frac`), and line breaks
that don't change the reading. A typo in the source copied faithfully is
correct; a typo "fixed" is `added` or `misread`.

The user may also note a **fix**: the corrected markdown. Corrected
transcriptions are not needed for the analysis, but become regression
cases and are what an LCT question part is run against (§2.3).

### 3.5 The regression set

Run with the sample, and marked the same way. These are known hard cases:

| Question | Why |
|---|---|
| `PYQ-M-240406-S2 M_SCA_10` | Source typo in a series (E1) |
| `PYQ-A-13-P1 C_MCA_34` | Reaction schemes; inferred names, wrong SMILES (E2, E3); two parts |
| `PYQ-M-260121-S2 C_SCA_59` | Four benzyl halide structures (E4) |
| `AITS-13-A-FT1P2 C_MMT_2` | Matrix match |
| `PYQ-M-260121-S2 C_SCA_52` | List-I / List-II |
| `PYQ-M-250404-S2 P_SCA_31` | Graph with statement options |
| `LK-A-C-04 C_SCA_6` | `5f¹⁴` misread and `FTFF` (probe) |
| `LK-A-M-28 M_SCA_36` | Notation choices (probe) |

### 3.6 Replay and choice of thresholds

Compute the gate of `logprob-confidence.md` §4.1–§4.2 offline from the
stored responses, for every question and every setting of:

- `MARGIN` in 0.1, 0.2, 0.3, 0.4, 0.5;
- `C_MAX` in 0, 1, 2, 3.

For each setting, report:

| Measure | Meaning |
|---|---|
| escalated | share of the sample sent to Nemotron (fails §4.1, or C > `C_MAX`) |
| caught | share of transcriptions with a material fault that are escalated |
| missed | transcriptions with a material fault that are accepted; the residual error rate of the gate |
| false escalations | acceptable transcriptions (correct, or minor faults only) escalated |

Minor faults are reported apart (how many, how many escalated) but don't
count against a setting.

with each also broken down by subject, type and fault kind. Then
recommend the setting, with the reasoning. Things to weigh:

- Nemotron's free quota is 1,000 requests a day (20 a minute), then the
  paid model; about 40,000 questions are pending. At 25% escalated that is
  ~10,000 Nemotron requests.
- Nemotron is not checked: an escalated question is only as good as
  Nemotron's reading. If time allows, run Nemotron on the faulty ones and
  some correct ones and mark those too, to know whether escalating helps.
- What a missed fault costs: only material faults cost the tagger
  anything. A setting that escalates for minor faults spends Nemotron
  requests for nothing.
- Whether some contests can be ignored outright because they can only be
  minor (e.g. a rival differing from the chosen token only in digits, or
  in a sign or exponent); if so, propose the rule for §4.2.

Also report:

- the transcriptions with a material fault and C = 0 at every setting, by
  kind (the share the gate can never catch, F4);
- every position that was a candidate but judged the *same reading* in a
  faulty transcription at the fault's place (a sign the §4.2 normalisation
  hides real errors, as it did `FTF F`);
- any notation that keeps coming up contested in correct transcriptions,
  which should go into the normalisation list or the prompt's
  conventions instead;
- the well-formed checks (§4.1) that fired, and on what.

### 3.7 Optional: the self-check experiment (S6)

Run the sample again with one line added to the prompt asking Qwen to end
with a fenced `uncertain` block listing anything it couldn't read
clearly, quoting the place. Compare with the marks: keep the block in the
design only if it flags faulty transcriptions that the gate misses,
without flagging most correct ones.

## 4. What changes in the app from the results

- `MARGIN` and `C_MAX`, as app properties.
- The normalisation list and skip rules of §4.2, if §3.6 shows a change is
  needed.
- The prompt (the calibrated version is the one the app ships).
- Qwen's `maxParallel`.
- Whether the gate runs at all: if it doesn't separate faulty from
  correct (caught about the same as false escalations at every setting),
  say so; then we decide between escalating by other signals, escalating
  by type or subject, or spot checks alone.

## 5. Golden cases

The app's gate is reimplemented in Java and must give the same C as the
agent's replay. So the agent's replay script is returned with the results,
and for about 20 questions (correct and faulty, with and without
candidates, including a `smiles:` line, a diagram block, a partial command
and a §4.1 failure) the results list the expected §4.1 result and C at the
recommended setting, computed from the stored responses. The app checks
these at build time, as the NXR classifier checks its golden cases.

## 6. Data kept

Under `docs/feature-enhancements/2026-11-17/question-transcription/calibration/`
(large files such as images may stay in the question bank's
`supporting-artefacts/` with a path given instead):

| File | Contents |
|---|---|
| `sample.jsonl` | One line per request: server `id`, `questionId`, subject, type, kind (`question` / `lct-passage` / `lct-question`), source, has figure, image file names |
| `responses/<id>[-ctx].json` | The full Ollama response, the prompt sent, and the prompt file's SHA-256 |
| `marks.jsonl` | One line per transcription: `id`, kind, mark, faults `[{kind, at, note}]`, optional `fix`, marked at |
| `replay.py` | The replay script |

## 7. Results to write back

`calibration-results.md` in this folder, with:

1. The run: dates, prompt SHA-256, Ollama and model version, settings,
   sample counts by cell, anything that differed from this spec.
2. Throughput (§3.1) and the recommended `maxParallel`.
3. Qwen's error rate by subject, type and fault kind.
4. The §3.6 table for every setting, the breakdowns and the extra reports.
5. **The recommendation**: `MARGIN`, `C_MAX`, any change to §4.1 / §4.2,
   any change to the prompt (the file itself updated in place), and
   whether the gate is worth running.
6. The golden cases (§5).
7. The self-check experiment's result, if run.
8. New regression cases, with why.

I then update `logprob-confidence.md` to rev 3 from it, and the app takes
its settings from there.

## Revision log

| Rev | Date | Change |
|---|---|---|
| 1 | 2026-11-17 | First version |
| 2 | 2026-11-17 | The transcription's purpose (the tagger, search): faults get a severity, material or minor; the gate is calibrated on material faults only |
