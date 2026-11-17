# QTranscriber: when to trust Qwen's transcription (logprob confidence)

| Field | Value |
|---|---|
| Component | `QTranscriber` (planned qimgtools app), its confidence gate |
| Server | `SConsoleNxt/doc/feature-enhancements/2026-11-17/question-transcription/` (content format, pending / store APIs) |
| Status | Rev 3, 2026-11-17: calibrated (`calibration-results.md`). Routing on §4.1 only; C recorded and used to order spot checks (§0). Q7 parked |

QTranscriber copies each question image into markdown, in the server's
content format (feature design, *Content format*). Qwen 3.5 27B, local on
`mac-studio`, transcribes every question. When Qwen isn't confident, the
question is escalated to Nemotron (free, falling back to the paid one), and
Nemotron's transcription is accepted as it is. No agent checks another
agent's work.

This note defines "confident": a score computed from the probabilities
Qwen gave the tokens it wrote (their *logprobs*), and a threshold on it.
Rev 1 was reviewed by the question-bank agent (§9); rev 2 adopts the
review (§8).

---

## 0. Rev 3: what calibration changed

Calibration (`calibration.md` rev 2, `calibration-results.md`) ran Qwen
over 197 questions. The user spot-checked them and judged Qwen's quality
sufficient for the tagger, which is what transcription is for: 14 marked,
all correct. No setting of the margin rule up to 0.3 caught a known
material fault that §4.1 doesn't already catch; catching the two wrong
SMILES needed `MARGIN ≥ 0.4`, which escalates over 20% (about 8,400
Nemotron requests over the bank, with Nemotron itself unchecked).

So, in rev 3 (this section overrides the rest of the note where they
differ):

1. **Routing is on §4.1 only.** A Qwen reply goes to Nemotron if it fails
   a well-formed check, or if Ollama fails the request (including HTTP 500
   `prediction aborted, token repeat limit reached`: a per-question
   failure, not "Qwen down"). C is *not* used to route: property
   `gate.route.on.c` = false. If routing on C is ever wanted, the
   calibrated choice is the md-minor rule, `MARGIN` 0.3, `C_MAX` 0 (14%
   escalated), still unvalidated against material faults.
2. **C is recorded and orders spot checks**, largest first, by the
   md-minor rule at `MARGIN` 0.3 (§4.3).
3. **§4.2 changes:** the markdown-structure normalisation, dash mapping,
   byte-level comparison of split UTF-8 rivals, and the minor-only rule
   (§4.2, steps 3–4).
4. **Prompt v3:** a blank is written as exactly five escaped underscores
   (§5), which stops the endless-underscore loop of `C_NVT_49`.
5. **Throughput:** Qwen `maxParallel` 3 (2.35× one stream; 4 gains
   nothing); about 4 s a question, roughly 2 days for the 40k questions.
   Logprobs cost about 40% of generation time at one in flight.
6. **Golden cases:** `calibration/golden-cases.json` (20 requests), from
   `calibration/replay.py`. The Java gate must reproduce `Cmdx` (md-minor)
   and the §4.1 results. The replay doesn't yet implement the dash mapping
   and the byte comparison (rev 3 additions to §4.2); it is extended with
   them and the golden cases regenerated before the Java gate is checked
   against them.
7. **Regression set:** 11 cases (§6), the three new ones from calibration.

## 1. Decisions already made

| # | Decision |
|---|---|
| D1 | Qwen is the primary transcriber. If Qwen (its Ollama gateway) is down, questions fall back to the free Nemotron, by the AI config's fallback guards (revised 2026-11-17; it used to stop the app). Such a reply is Nemotron's, accepted as in D2 |
| D2 | A question whose Qwen transcription fails the gate (rev 3: §4.1 only) goes to Nemotron with the same prompt. Nemotron's reply is accepted with no gate (Nemotron on OpenRouter doesn't return logprobs) |
| D3 | Accepted transcriptions are saved the topic reviewer's way: they wait in the table until Save, or until the unattended run saves them |
| D4 | The user spot-checks rendered transcriptions (JavaFX WebView, MathJax + mhchem, SMILES drawn). A faulty one goes to the next model, Qwen → Nemotron → GLM, or is edited by hand (feature design §8) |
| D5 | `aiModel` stored on the server is the model whose transcription is accepted |

## 2. What Ollama gives

Verified on `mac-studio`, Ollama 0.40.16, `qwen3.5:27b-q8_0`, `/api/generate`:

- `"logprobs": true` adds, for each generated token, `{token, logprob,
  bytes}`. The probability is `p = exp(logprob)`.
- `"top_logprobs": k` adds the `k` most likely tokens at that position, with
  their logprobs. These are the alternatives Qwen weighed.
- The logprobs come from the model's raw distribution, before the sampler
  applies its settings (see finding F1).

## 3. Probe (2026-11-17)

Four images, the draft prompt (the content format's five rules, "reply
with the transcription only"), `think: false`. Each took 4–10 s.

| Image | Non-blank tokens | Geo-mean p | Min p | p < 0.5 | Errors in the reply |
|---|---|---|---|---|---|
| `LK-A-P-03 P_SCA_55` (text, one angle) | 68 | 0.984 | 0.62 | 0 | none |
| `LK-A-P-27 P_LCT_1_49` (μ, inequalities) | 99 | 0.994 | 0.72 | 0 | none |
| `LK-A-C-04 C_SCA_6` (electron configurations, T/F options) | 147 | 0.973 | 0.38 | 1 | `5f^4` for `5f¹⁴`; option (A) `FTF F` for `FTFF` |
| `LK-A-M-28 M_SCA_36` (integral, logs) | 118 | 0.953 | 0.41 | 1 | none after F1's fix (before it: `w.r.t $x$` dropped the printed `.`) |

(The last two rows are from the run with the sampler fixed, as in F1.)

### Findings

**F1. The sampler must be greedy and unpenalised.** Qwen's Modelfile sets
`temperature 1`, `top_k 20`, `top_p 0.95`, `presence_penalty 1.5`. With
`temperature 0` alone, the presence penalty still pushed the sampler off
the most likely token in four places in the Maths question. In one of them
the most likely token, `.` at p = 0.67, was the correct one (`w.r.t.`), and
the sampler wrote ` $` at 0.27 instead. A transcription repeats tokens by
nature, so a presence penalty works against copying, and the logprobs
then describe a choice the model didn't make. Every request sets
`temperature 0`, `top_k 1`, `presence_penalty 0`, `frequency_penalty 0`,
`repeat_penalty 1`.

**F2. Most low-probability tokens are choices of notation, not of
reading.** Of the 14 positions below p = 0.7 in the two harder
questions, 12 weigh alternatives that write the same printed thing in
different markdown or LaTeX:

| Chosen | Alternatives | What Qwen was deciding |
|---|---|---|
| ` [` 0.38 | ` $` 0.37, ` $\` 0.23 | Put `[Kr]` inside maths or not |
| `s` 0.61 | `\` 0.38 | `5s^1` or `\text{5s}^1` |
| `\n` 0.29 | ` (` 0.26 | Line break before `(II)` or not |
| ` +` 0.51 | `+\` 0.48 | Spacing in LaTeX |
| `text` 0.41 | `cos` 0.29, `operator` 0.20 | `\text{cosecx}`, `\cos ecx` or `\operatorname{cosec}` |

A plain probability threshold would escalate almost every Maths and
Chemistry question for reasons that don't matter.

**F3. Real reading doubt does show.** `FTFF` came out as ` F` 0.53 against
` FT` 0.47. The two alternatives carry different printed characters, and
Qwen was close to even between them. This is the signal the gate is for.

**F4. Confident misreads are invisible.** `5f¹⁴` → `5f^4` didn't appear
among the low tokens: Qwen was sure of the wrong reading. No logprob rule
catches this. Only the user's spot checks can, and their rate is the gate's
real error rate. This sets the expectation: the gate cuts misreads, it
doesn't remove them.

**F5. Speed.** 4–10 s a question, so the ~40k bank takes 1–2 days at
`maxParallel` 3, before any escalation.

## 4. The gate

A Qwen reply goes through three checks. It is accepted only if it passes
all three. Otherwise it is escalated to Nemotron, which sees the image
only, with no hints from Qwen's reply (§7 Q5). The reason is recorded.

### 4.1 Well-formed

The reply fails the gate if:

- it was cut off (`done_reason: length`), or it is empty;
- its `$` / `$$` delimiters are unbalanced, or its `{}` inside maths are
  unbalanced;
- it has an unclosed code fence, or a fence whose info string isn't
  `diagram` (a trailing ```` ```uncertain ```` block is allowed while the
  S6 experiment runs; it is stripped before storing);
- a `diagram` block has no description;
- it contains an image placeholder (`![`), meta text ("Here is the
  transcription"), or solution text: a line starting `Answer`, `Solution`,
  `Hence` or `∴`;
- a `smiles:` line doesn't parse with CDK (`cdk-smiles`, Maven Central,
  LGPL). This catches only broken SMILES. A valid SMILES of the wrong
  molecule, as in both of E3's errors, is left to §4.2.

### 4.2 Score: contested positions

Each generated token is examined with its `top_logprobs` (k = 5). Let
`text` be the whole reply, and `before` the text up to this token.

1. **Skip** tokens in the description lines of a ```` ```diagram ````
   block. The content format allows inferred content there, so doubt is
   expected. `smiles:` lines are **not** skipped: a SMILES transcribes the
   drawn structure, and E3 shows its errors come out at low probability
   (S1).
2. **Candidate**: an alternative token (a *rival*) with
   `p_chosen − p_rival < MARGIN`.
3. **Same reading?** Normalise `before + rival` (A) and `text` (B):
   - remove the formatting commands `\text`, `\mathrm`, `\operatorname`,
     `\rm`, `\mathbf`, `\left`, `\right`, `\displaystyle`, `\ce`, `\quad`,
     `\qquad`, `\,`, `\;`, `\!`;
   - drop the backslash of any other command (`\cos` → `cos`);
   - remove whitespace and `$ { } ^ _`;
   - if A ends in a partial command (a backslash and letters that are the
     start of a command removed above, e.g. `\operator`), drop it.

   If B starts with A (or A with B), the rival is the same reading, written
   differently or split into different tokens. Otherwise the position is
   **contested**.

The question's score **C** is the number of contested positions. The
count isn't divided by the reply's length (§7 Q1).

Replayed on the probe images (`MARGIN = 0.3`, sampler as in F1):

| Image | Candidates | Contested | C |
|---|---|---|---|
| P_SCA_55 | 1 (`^` vs `^\`) | none | 0 |
| P_LCT_1_49 | 0 | none | 0 |
| C_SCA_6 | 9: `[Kr]` in maths or not, `5s` vs `\text`, line breaks, and ` F` 0.53 vs ` FT` 0.47 | none | 0 |
| M_SCA_36 | 6: spacing, `\,`, `\text` vs `\cos`, and `\text` vs `\operator…` | none, after the partial-command rule (without it, `operator` was counted) | 0 |

So on the probe the rule escalates nothing for notation, and the earlier
false escalation of M_SCA_36 goes away. It also catches nothing. Both
errors in C_SCA_6 get through:

- `5f^4` for `5f¹⁴` is a confident misread (F4).
- `FTF F` for `FTFF`: the space is removed in normalisation, so ` FT`
  reads the same as what was written. Rev 1's token rule counted it. The
  error is a stray space, harmless to read, and is accepted as the cost of
  ignoring notation.

The probe has no evidence yet that the gate catches real errors. The
case for it rests on E3 (wrong SMILES at min p 0.30 and 0.56, against
0.84 and 0.92 for correct ones) and on calibration (§5).

### 4.3 Threshold

**Rev 3:** C doesn't route (§0). A reply passing §4.1 is accepted
whatever its C; C is recorded and orders spot checks. Routing on C stays
in the code behind `gate.route.on.c` (false), with `gate.margin` 0.3 and
`gate.c.max` 0: then a reply is accepted only if `C ≤ C_MAX`, LCT
passages included.

All three are app properties, not code.

### 4.2a Rev 3 additions to §4.2

Applied in this order, before the *same reading* comparison:

1. **Markdown structure is notation** (the calibration's *md* rule): list
   markers, table pipes and separator rows, `` ` ``, `*`, `#`, fence
   lines (not scored); `\dots`, `\ldots`, `\cdots`, `...` and `…` all
   become `...`; `\cdot` becomes `.`.
2. **Dashes:** `–`, `—` and `−` become `-`.
3. **Split UTF-8:** rivals are compared by their `bytes`. A rival whose
   bytes are an incomplete UTF-8 sequence that is a prefix of the chosen
   token's bytes (or of the continuation's) is the same reading.
4. **Minor only:** if the chosen token and the rival, with whitespace and
   `$ { } ^ _ \` removed, both consist only of digits, `.`, `,`, `+`,
   `-`, `−` or `±`, the position is not contested: such a difference can
   only be a minor fault.

### 4.1a Rev 3 additions to §4.1

- An Ollama failure on one question (HTTP 500 `prediction aborted, token
  repeat limit reached`, and `done_reason: length` as before) sends the
  question to Nemotron; it doesn't mark Qwen down.

### 4.4 Request settings

Every Qwen request sets `think: false`, `logprobs: true`,
`top_logprobs: 5`, and the sampler of F1: `temperature 0`, `top_k 1`,
`presence_penalty 0`, `frequency_penalty 0`, `repeat_penalty 1`.

## 5. The prompt

One prompt, with the subject and problem type filled in. It is the
content format's five rules, plus:

- **E1:** "Reproduce typos and inconsistencies exactly as printed, even if
  they look like mistakes."
- **E2:** "Only printed text goes outside `diagram` blocks. Names or
  identifications you infer go inside the block."
- **Notation conventions** (Q6), fixed so the gate isn't calibrated to
  noise that later goes away, and so the stored markdown is uniform:
  - element symbols, formulas, ions and electron configurations in
    `$\ce{…}$` (e.g. `$\ce{[Kr] 4d^{10} 5s^1}$`);
  - function names as `\sin`, `\cos`, `\ln`, `\log`, and
    `\operatorname{cosec}` for cosec;
  - printed words outside maths, with no `\text{}` for running prose;
  - units after numbers as `$5\,\mathrm{cm}$`;
  - degrees as `^\circ`;
  - no `\left` / `\right` unless the printed brackets are visibly sized.

The prompt is a stable prefix, with the image after it.

## 6. Calibration before the gate is trusted

The probe is four questions. Before the unattended run:

1. **Throughput** (S5). Check `OLLAMA_NUM_PARALLEL` on `mac-studio`, and
   measure total tokens a second with 1, 2 and 3 requests in flight
   (single-stream: 32 tokens a second). `maxParallel` is set from this.
   Expect 1.5–2× with 3 in flight, not 3×.
2. **Sample.** Run Qwen alone over about 200 questions, stratified by
   subject and problem type (SCA, MCA, IVT, NVT, LCT, ART, MMT), plus the
   regression set below. The gate is computed and recorded but nothing is
   escalated.
3. **Marking.** The user checks each transcription in the WebView beside
   its image, marking it correct or faulty. Faulty means a printed
   character or symbol wrong, missing or added. Notation differences don't
   count.
4. **Replay.** Every candidate position is recorded with its continuation
   (§7, S7), so the thresholds are tried offline over the sample, without
   running Qwen again. Choose `MARGIN` and `C_MAX` to catch most faulty
   transcriptions while escalating an acceptable share. The table of
   faulty against escalated, per setting, goes into rev 3 of this note.
5. **Self-check experiment** (S6). Run the sample a second time with a
   prompt asking for a trailing ```` ```uncertain ```` block. Keep the
   block only if it catches faulty transcriptions that the gate misses.

The share of faulty transcriptions with C = 0 measures F4: the error rate
that no threshold reduces.

**Regression set** (S4), re-run on any change to the prompt or the
thresholds:

| Question | Why |
|---|---|
| `PYQ-M-240406-S2 M_SCA_10` | Source typo in a series (E1) |
| `PYQ-A-13-P1 C_MCA_34` | Reaction schemes; inferred names, wrong SMILES (E2, E3); two parts |
| `PYQ-M-260121-S2 C_SCA_59` | Four benzyl halide structures (E4) |
| `AITS-13-A-FT1P2 C_MMT_2` | Matrix match |
| `PYQ-M-260121-S2 C_SCA_52` | List-I / List-II |
| `PYQ-M-250404-S2 P_SCA_31` | Graph with statement options |
| `LK-A-C-04 C_SCA_6` | `5f¹⁴` misread and `FTFF` (this probe) |
| `LK-A-M-28 M_SCA_36` | Notation choices (this probe) |
| `PYQ-M-200108-S2 C_NVT_49` | Blank written as endless underscores; Ollama aborts (calibration) |
| `PYQ-M-250408-S2 C_SCA_61` | Structures as ASCII art in a bare fence; §4.1 (calibration) |
| `PYQ-A-08-P2 P_MMT_44` | Diagram blocks inside table cells; §4.1 (calibration) |

## 7. What is recorded

- **Transcript** (per attempt, as in the other apps): the response file
  gets a `Confidence` section: the §4.1 result, C, geo-mean p, min p, and
  every candidate position with its rival, both probabilities, the
  continuation compared (S7), and whether it was contested.
- **Table**: a Confidence column (C, sortable), and the route (`Qwen`, or
  `Qwen → Nemotron` with the reason).
- **Qwen–Nemotron diff** (Q5): for an escalated question, the normalised
  Qwen and Nemotron texts are compared. Nemotron's reply is still accepted
  (D2), but the size of the difference outside the contested positions
  orders the user's spot checks, largest first: where they differ there,
  one of them misread with confidence.
- **Audit log** (one JSON line per saved transcription): the model, the
  route, C, the metrics, and why it escalated.
- **`token-usage.csv`**: as in the other apps.

## 8. Status of the open questions

| # | Settled in rev 2 |
|---|---|
| Q1 | Count contested positions, not length-normalised. A margin, compared against the continuation (§4.2) |
| Q2 | `MARGIN` 0.3, md-minor rule (rev 3); C doesn't route, it orders spot checks (§0) |
| Q3 | Strip a fixed list of formatting commands, plus the partial-command rule (§4.2) |
| Q4 | `C_MAX` 0 for passages too, if routing on C is switched on |
| Q5 | Nemotron sees the image only. The Qwen–Nemotron diff orders spot checks (§7) |
| Q6 | Conventions in the prompt before calibration (§5) |
| Q7 | **Open, parked.** The tesseract word and number overlap and the length check (§9.3) are to be decided later: escalate on them, or only use them to order spot checks |

## 9. Review by the question-bank agent (2026-11-17)

Kept as written; rev 2 adopts it as §8 records. Its section numbers
refer to rev 1.

### 9.1 Understanding

The gate is aimed at one failure: Qwen hesitating between two readings
that would print different characters. It deliberately ignores hesitation
over notation (F2), and it admits that confident misreads (F4) are out of
its reach. I agree with that framing, with F1 as a precondition and the
sampler fixed on every request. The shape is right. The suggestions below
reduce false escalations and add one signal the note currently drops.

### 9.2 Evidence from the question-bank probes (2026-11-16)

These were seven questions through `/api/chat` with a JSON-output prompt,
`temperature 0` only (so the presence penalty of F1 was still active). The
pages are no longer available, but the findings carry over:

| # | Case | What happened | What would have caught it |
|---|---|---|---|
| E1 | Series with a source typo (`1³ + 2³ + … + n²`) | First run silently wrote `n^3`. A prompt line "reproduce typos and inconsistencies exactly as printed, even if they look like mistakes" fixed it | Not a logprob signal (a confident correction, an F4 case). The prompt line must stay |
| E2 | Two-part aromaticity question (reaction schemes) | Wrote inferred compound names into the question text as if printed | Prompt rule 1 / 4 (inferences only inside a `diagram` block). The gate can't see it |
| E3 | Same question: SMILES | Two of four SMILES were wrong: `C1(Cl)CC1` (min p 0.30, mean 0.74) and the 6-ring tropone `O=C1C=CC=CC=1` (min 0.56, mean 0.94). The two correct ones had min p 0.84 and 0.92 | Logprobs, but only if `smiles:` lines are scored (see S1) |
| E4 | Benzyl halides, four labelled structures | All four SMILES correct | – |
| E5 | Self-reported `uncertain` list (JSON prompt) | Flagged E1's typo and E2's inferred names, missed E3. Self-reported 0–100 scores were 90–100 for everything, so useless | See S6 |

### 9.3 Answers to §7

**Q1. Counting contested positions is the right shape. Don't
length-normalise.** One misread spoils the transcription however long it
is, as the note says. Geometric mean is dominated by notation (F2) and
by length, so it doesn't fit. Two refinements:

- **Margin instead of two thresholds.** Call a position a candidate when
  `p_chosen − p_rival < MARGIN` (start around 0.3), rather than
  `p_chosen < P_FLOOR and p_rival ≥ P_RIVAL`. It's one parameter and
  measures what F3 describes (0.53 against 0.47). The two-threshold rule
  is a rough version of the same thing.
- **Compare the rival against what Qwen actually wrote next, not against
  the chosen token alone.** A token-level comparison treats tokenisation
  differences as content: ` F` + `T` versus ` FT` reads the same. Rule:
  normalise the rival, then normalise the text Qwen actually wrote from
  this position onward (as many characters as the rival has after
  normalisation). If one is a prefix of the other, it's the same reading
  and not contested. This also settles most of Q3 (see below) and the
  `s` / `\` row of F2.

**Q2. 0.6 / 0.2 is a reasonable start,** or MARGIN 0.3 if the margin
form is used. Either way, calibration (§5) sets the value. Record the
full candidate list per question during calibration so any threshold can
be replayed without re-running Qwen.

**Q3. Strip a fixed list of formatting commands in normalisation.** This
is deterministic and cheap: `\text`, `\mathrm`, `\operatorname`, `\rm`,
`\mathbf`, `\left`, `\right`, `\,`, `\;`, `\!`, `\quad`, `\qquad`,
`\displaystyle`, `\ce`. Function names (`\cos`, `\ln`, `\sin`) are kept
as letters by removing only the backslash, which the current rule
already does. With the continuation check from Q1, `text` versus `cos`
in M_SCA_36 compares `cosecx` (what was written, with `\text{}`
stripped) against `cos`. It's a prefix, so it isn't contested, and the
false escalation goes away.

**Q4. Keep `C_MAX = 0` for passages too.** There are 621 passages in all,
so even a high escalation rate costs little, and an error in a passage
affects every question in its group. That's a reason to be stricter,
not looser.

**Q5. Image only (an independent reading) is right.** Hints would anchor
Nemotron to Qwen's two candidates, and Nemotron's reading would then no
longer be independent. A cheap use of having both readings: diff the
normalised Qwen and Nemotron texts. Agreement outside the contested
spots is strong evidence for the rest of the transcription. Where they
disagree elsewhere, one of them misread with confidence (F4). Nemotron's
reply is still accepted (D2), but a large diff puts the question at the
front of the user's spot checks.

**Q6. Yes, fix the conventions in the prompt before calibration.**
Otherwise the thresholds get tuned to notation noise that later goes
away. Suggested conventions:

- element symbols, formulas, ions and electron configurations in
  `$\ce{…}$` (e.g. `$\ce{[Kr] 4d^{10} 5s^1}$`)
- function names as `\sin`, `\cos`, `\ln`, `\log`, with
  `\operatorname{cosec}` for cosec
- printed words stay outside maths, with no `\text{}` for running prose
- units after numbers as `$5\,\mathrm{cm}$`
- degrees as `^\circ`
- no `\left` / `\right` unless the printed brackets are visibly sized

The conventions also make the stored markdown uniform, which search and
tagging both benefit from.

**Q7. Cheap partial checks for confident misreads:**

- **Word and number overlap with tesseract.** Compare the alphabetic
  words and the multi-digit baseline numbers in the normalised
  transcription with tesseract's output, and flag dropped or added lines
  or numbers. Don't compare exponents, subscripts or Greek letters:
  tesseract garbles them (`x?`, `x°`, `x*` for x², x³, x⁴), so they would
  only add noise. This catches dropped options and lines (tesseract
  itself dropped options A and B on one question, so treat the check as
  a flag, not a verdict). It does not catch `5f¹⁴` → `5f^4`.
- **Length sanity.** A Qwen reply far shorter than tesseract's character
  count usually means a dropped part.
- Beyond that, accept F4 as the residual error rate and measure it in
  calibration, as §5 already plans.

### 9.4 Further suggestions

| # | Suggestion |
|---|---|
| S1 | **Score `smiles:` lines inside `diagram` blocks.** §4.2 rule 1 skips the whole block, but a SMILES line is a transcription of the drawn structure, not an inference. E3 shows its errors do show up as low probability. Skip only the description text. Also parse each SMILES (RDKit, or CDK in Java). One that fails to parse fails the §4.1 check |
| S2 | **Add to §4.1:** solution or answer text in the reply ("Answer", "Solution", "∴", "Hence"); a `diagram` block with no description; an empty table cell in a match list where the image clearly has one (optional) |
| S3 | **Keep the E1 and E2 prompt lines**: "reproduce typos and inconsistencies exactly as printed", and "only printed text outside `diagram` blocks; names or identifications you infer go inside the block". These are F4-class failures that no gate catches, and the prompt prevented E1 in testing |
| S4 | **Add a fixed regression set to calibration.** Include the known hard cases: the series typo (`PYQ-M-240406-S2 M_SCA_10`), the reaction-scheme aromaticity question (`PYQ-A-13-P1 C_MCA_34`, two parts), the benzyl halides (`PYQ-M-260121-S2 C_SCA_59`), a matrix match (`AITS-13-A-FT1P2 C_MMT_2`), a List-I/List-II SCA (`PYQ-M-260121-S2 C_SCA_52`), and a graph question with statement options (`PYQ-M-250404-S2 P_SCA_31`). Re-run them on any prompt or threshold change |
| S5 | **Check F5's parallel speed-up on the server.** Three requests in parallel only help if Ollama is set up for it (`OLLAMA_NUM_PARALLEL` ≥ 3), and on a single Mac Studio total throughput is limited by memory bandwidth. Expect a 1.5–2× total speed-up, not 3×. On 2026-11-16, single-stream generation was a steady 32 tokens a second. Measure total tokens a second with 3 in flight before planning the run |
| S6 | **Optional experiment in calibration: a trailing self-check block.** Ask Qwen to append a ```` ```uncertain ```` fenced block listing anything it inferred or found unclear, and strip it before storing. In E5 this caught the confident correction and the inferred names that logprobs can't see. Measure in calibration whether it adds catches beyond the gate. Drop it if it doesn't, because it costs output tokens and slightly loosens "reply with the transcription only" |
| S7 | **Recorded metrics (§6):** for each candidate position, also record the actual continuation text used in the Q1 comparison, so a contested decision can be audited in the transcript without reconstructing it |

## Revision log

| Rev | Date | Change |
|---|---|---|
| 1 | 2026-11-17 | Draft from a four-question probe |
| 1-review | 2026-11-17 | §8 added by the question-bank agent: earlier probe evidence (E1–E5), answers to Q1–Q7, suggestions S1–S7 |
| 2 | 2026-11-17 | The review adopted: margin rule against the continuation, formatting-command list, partial-command rule, SMILES scored and parsed (CDK), more §4.1 checks, prompt conventions, regression set, throughput and self-check in calibration, Qwen–Nemotron diff. Probe replayed under the new rule. Q7 parked |
| 3 | 2026-11-17 | Calibrated (§0): routing on §4.1 and Ollama failures only; C recorded (md-minor, 0.3) to order spot checks; §4.1a, §4.2a; prompt v3 blank rule; `maxParallel` 3; golden cases; regression set 11 |
