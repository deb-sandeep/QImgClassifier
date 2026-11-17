# Transcription gate calibration: results

**Status:** results of `calibration.md` rev 2, run 2026-11-17 by the question-bank agent with the user. Data and
scripts are in `calibration/` (§6 of the spec). Next: the driver agent updates `logprob-confidence.md` to rev 3.

**Headline.** The user spot-checked the run and judged Qwen's transcriptions good enough for the tagger to work at
very high precision, and accepted Qwen's results. Only 14 of 197 were marked, all `correct`. So the gate's thresholds
can't be calibrated from marks: with no material fault marked, *caught* and *missed* are undefined. What the run
does settle is what each setting costs, which failures the well-formed check (§4.1) catches, and several changes to
§4.1, §4.2 and the prompt. The recommendation (§5) follows from the user's decision: route on §4.1 only, record C
and use it to order spot checks.

## 1. The run

| Item | Value |
|---|---|
| Date | 2026-11-17 |
| Prompt | `qtranscriber.prompt` version 2, SHA-256 `526db3668560ba5b69c28ccd3f9f8f5867f743ac9e669e020a00ebe9ecc43278` (prompt-review.md P1–P8, P10, P11 applied, plus a table example and roman-numeral list labels; LCT headings naming question numbers dropped, user's decision) |
| Server | Ollama 0.40.16 on `mac-studio`, `qwen3.5:27b-q8_0` |
| Request | Exactly spec §2.1 (`/api/generate`, greedy, all penalties off, `logprobs`, `top_logprobs 5`, `num_ctx 8192`, `num_predict 4096`); part images stacked into one PNG |
| Sample | 198 requests (`calibration/sample.jsonl`, `select_sample.py`, seed 20261117) |
| Completed | 197. `14631` (PYQ-M-200108-S2 C_NVT_49) fails every time: Ollama aborts with HTTP 500 `prediction aborted, token repeat limit reached` (§4) |

Sample by cell (questions; LCT as passages + parts):

| Type | P | C | M |
|---|---|---|---|
| SCA | 16 | 23 | 17 |
| MCA | 8 | 11 | 7 |
| IVT | 8 | 8 | 9 |
| NVT | 5 | 5 | 5 |
| ART | 5 | 10 | 5 |
| MMT | 5 | 5 | 5 (all 15 in the bank) |
| LCT | 4 passages + 10 parts | 4 + 9 | 4 + 10 |

The cells include the 8 regression cases (§3.5). The sample spans 75 topics and 8 source families: PYQ 53,
AITS 46, Laksha 36, Rank Booker 22, AL 14, YG 13, GMP 12, Manual 2.

**Differences from the spec:**
- Only 35 transcriptions contain a `diagram` block, against the spec's "at least 40 with a figure". Figures weren't
  known before the run.
- The SMILES parse check (§4.1) isn't in the replay. The user chose not to install RDKit, because the Java gate
  checks CDK parsing itself. The review page parses each SMILES with SmilesDrawer and flags any that doesn't parse;
  no parse failures were reported. The SMILES seen in the regression cases all parsed, including the wrong ones.

## 2. Throughput

8 questions, run identically each time (`calibration/tp/`):

| In flight | Total output tok/s | s / question | vs 1 |
|---|---|---|---|
| 1 | 22.3 | 6.8 | 1.0× |
| 2 | 38.4 | 4.0 | 1.7× |
| 3 | 52.4 | 2.9 | 2.35× |
| 4 | 53.7 | 2.8 | 2.4× |
| 1, no logprobs | 31.6 | 4.8 | – |

- Outputs were byte-identical at every setting, and with and without logprobs.
- 4 in flight gains nothing over 3, which is consistent with `OLLAMA_NUM_PARALLEL` 3 (not read from the server).
- Logprobs cost about 40% at one in flight.
- The full run (197 requests at 3 in flight) took 824 s: 4.2 s a request, 38 output tokens a second in total.
- **Recommended `maxParallel`: 3.** At about 4 s a question, the 40k pending questions take roughly 2 days.

## 3. Error rate

**User's marks:** 14 spot-checked transcriptions, all `correct`: 7 Physics, 5 Chemistry, 2 Mathematics, including all 8
regression cases. The user's overall judgement is that the quality is sufficient for the tagger.

**Faults the question-bank agent found** while checking the regression cases and failures against their images. These
aren't user marks. Severity is as §3.4 defines it.

| Key | Question | Fault | Severity (§3.4) | Gate |
|---|---|---|---|---|
| 36404 | PYQ-M-240406-S2 M_SCA_10 | Source typo `n²` written `n^3` ("corrected") | minor | contested, `3` 0.56 vs `2` 0.44 (digit-only) |
| 9361 | LK-A-C-04 C_SCA_6 | `5f^4` for `5f^{14}`; `FTF F` for `FTFF`; `(I)`–`(IV)` not list items, so they render as one line | minor | `5f^4` C = 0 (F4); `FTF F` same reading by design |
| 37100 | PYQ-A-13-P1 C_MCA_34 | `ClC1CC1` (chlorocyclopropane) for the drawn chlorocyclopropene | material (wrong structure) | contested only at MARGIN ≥ 0.4 (rival `=` p 0.06) |
| 37100 | same | Tropone SMILES with a 6-membered ring, `O=C1C=CC=CC=1` | material | contested at MARGIN ≥ 0.4 (`=` 0.65 vs `=C` 0.34, margin 0.30) |
| 37100 | same | 2,5-hexanedione (open chain) described as "a six-membered ring" | material (figure content) | invisible: description lines are skipped |
| 34146 | PYQ-M-260121-S2 C_SCA_59 | Four structures in one block, no SMILES (descriptions correct) | not a fault: the prompt wasn't followed, nothing lost | – |
| 33998 | PYQ-M-250408-S2 C_SCA_61 | Structures drawn as ASCII art inside a bare code fence | format | **§4.1 catches** (fence info string) |
| 36958 | PYQ-A-08-P2 P_MMT_44 | Diagram blocks inside table cells | format | **§4.1 catches** |
| 14631 | PYQ-M-200108-S2 C_NVT_49 | The blank `____` written as escaped underscores without end until Ollama aborts; also `equilibriuim` | – | HTTP 500 (§4) |

The user marked 36404, 9361 and 37100 `correct`. For 36404 and 9361 that agrees with §3.4, because their faults are
minor. For 37100 it doesn't: wrong structures are material under §3.4's wording. This is recorded as the user's
judgement of what the tagger needs, not changed.

## 4. Settings

`replay.py gate` writes `gate.jsonl`, and `replay.py report` writes `report-spotcheck.md`. There are four rules:

| Rule | Meaning |
|---|---|
| spec | §4.2 as written |
| md | Also treats markdown structure as notation: list markers, table pipes and separator rows, `` ` `` `*` `#`, fence lines, `\dots` / `\ldots` / `\cdots` / `...` / `…`, `\cdot` / `.` |
| spec-minor, md-minor | As above, ignoring contests whose chosen and rival tokens differ only in digits, decimal point or sign (exponents included, since `^` and braces are ignored) |

Share of all 197 escalated, and false escalations among the 14 marked (all acceptable):

| Rule | MARGIN | C_MAX 0 | C_MAX 1 | 14 marked, C_MAX 0 |
|---|---|---|---|---|
| spec | 0.1 | 23 (12%) | 7 (4%) | 3 |
| spec | 0.2 | 39 (20%) | 14 (7%) | 4 |
| spec | 0.3 | 48 (24%) | 19 (10%) | 4 |
| spec | 0.4 | 63 (32%) | 28 (14%) | 5 |
| spec | 0.5 | 75 (38%) | 35 (18%) | 8 |
| md | 0.1 | 16 (8%) | 5 (3%) | 1 |
| md | 0.2 | 25 (13%) | 9 (5%) | 2 |
| md | 0.3 | 29 (15%) | 13 (7%) | 2 |
| md | 0.4 | 42 (21%) | 15 (8%) | 3 |
| md | 0.5 | 52 (26%) | 19 (10%) | 4 |
| md-minor | 0.1 | 16 (8%) | 5 (3%) | 1 |
| md-minor | 0.2 | 24 (12%) | 9 (5%) | 1 |
| md-minor | 0.3 | 28 (14%) | 13 (7%) | 1 |
| md-minor | 0.4 | 40 (20%) | 14 (7%) | 2 |
| md-minor | 0.5 | 47 (24%) | 17 (9%) | 3 |

(spec-minor is within 1–4 of spec at every margin; full tables are in `report-spotcheck.md`. The counts include the
2 §4.1 failures.)

At md 0.3 the escalations break down as follows: Chemistry 18/74, Mathematics 6/62, Physics 5/61; MMT 5/15,
LCT passages 3/12, MCA 4/26, SCA 8/56, ART 3/20, IVT 3/25, LCT parts 3/29, NVT 0/14.

**Against the known material faults (§3):**
- The two wrong SMILES in 37100 are caught only at MARGIN ≥ 0.4 (21% or more escalated).
- The hexanedione description is invisible to the gate.
- The two format failures are caught by §4.1 at any setting.

No margin at or below 0.3 catches anything material that §4.1 doesn't.

**Ollama's Nemotron budget at 40k questions:**
- §4.1 alone: about 1.5%, roughly 600 requests.
- md-minor at 0.2: 12%, about 4,900.
- md at 0.4: 21%, about 8,400.

The free quota is 1,000 a day.

### Extra reports (§3.6)

- **Material faults the gate never catches:** the hexanedione description (diagram, skipped by design). `5f^4` is the
  F4 kind but minor.
- **Same reading at a fault's place:** `FTF F` in 9361 (` F` vs ` FT`, margin 0.03), as the rev 2 note expects. Minor.
- **Notation contested in acceptable transcriptions,** with 115 contested positions at MARGIN < 0.3 under the spec
  rule:
  - **Split UTF-8 characters:** the most frequent pairs are `⁻` vs `�` (5) and `⁺` vs `�` (5). The rival is an
    *incomplete UTF-8 byte sequence* (the first byte of a multi-byte character), shown as `�` once decoded, and may
    well be the same character.
  - **Dashes:** `–` vs `-` (4).
  - **Markdown structure:** list markers, table pipes, closing fences against more description. These account for most of the
    difference between spec and md: md leaves 81.
- **Well-formed checks that fired:** 33998 (fence info string `''`) and 36958 (fence info strings that are table rows).
  Both are real format failures.
- **Digit/sign-only contests:** 15 positions at MARGIN < 0.5. They include `3` vs `2` at the 36404 typo (minor) and
  `.`/`,` choices. None sits on a known material fault.

## 5. Recommendation

1. **Routing (follows the user's decision): escalate on §4.1 failures and Ollama aborts only.** Run §4.2 and record
   C, but don't route on it in v1. That means `C_MAX` is unbounded, or the gate's routing is switched off by a
   property. The reasons:
   - The user accepts Qwen's quality.
   - No margin up to 0.3 catches a known material fault that §4.1 misses.
   - Catching the SMILES errors would need MARGIN ≥ 0.4, which escalates over 20%: about 8,400 Nemotron requests,
     with Nemotron itself unchecked.
   
   If routing on C is wanted later, use **md-minor, MARGIN 0.3, C_MAX 0** (14% escalated). It's the best ratio seen
   here, but it still isn't validated against material faults.
2. **Use C to order the user's spot checks**, largest first (md-minor rule, MARGIN 0.3). The Qwen–Nemotron diff can't
   do this when nothing escalates.
3. **§4.1 additions:**
   - **Treat Ollama HTTP 500 `prediction aborted, token repeat limit reached` as a per-question failure:** escalate
     it, and don't stop the app. D1's "Qwen down, the app stops" should apply only to connection failures and
     server-wide errors.
   - `done_reason: length` is already in §4.1 and belongs to the same family.
4. **§4.2 changes** (for C as recorded and used for ordering, and for any later routing):
   - **Adopt the md normalisation:** list markers, table pipes and separator rows, `` ` `` `*` `#`, `\dots`-family and
     `…` to `...`, `\cdot` to `.`, and fence lines not scored. It cuts escalations at 0.3 from 48 to 29 without losing
     the one known material contest (37100 is the same under both).
   - **Map `–` `—` `−` to `-`.**
   - **Compare rivals by bytes, not by decoded text:** a rival whose `bytes` are an incomplete UTF-8 sequence that
     is a prefix of the chosen token's bytes (or of the continuation's) is the same reading.
   - **Adopt the minor-only rule:** a contest whose chosen and rival tokens differ only in digits, decimal point or
     sign is ignored. It saves little (one escalation at 0.3), but no material fault depends on it, and it matches
     §3.4: those faults are minor by definition. Proposed text for §4.2: *"4. **Minor only?** If the chosen token
     and the rival, with whitespace and `$ { } ^ _ \` removed, both consist only of digits, `.`, `,`, `+`, `-`,
     `−` or `±`, the position is not contested: such a difference can only be a minor fault."*
5. **Prompt v3:**
   - Change the blank rule to *"A blank to fill in is written as exactly five escaped underscores `\_\_\_\_\_`,
     however long the printed blank is."* That stops the 14631 loop. 10 of the 13 blanks in the sample were already
     five.
   - Re-run the regression set (now 11 cases, §8). There's no need to re-run the sample, because routing doesn't
     depend on C.
6. **`maxParallel` 3** (§2).
7. **Gate worth running?** As a router, not on this evidence. As a recorded signal for ordering spot checks, yes:
   it's cheap, because the logprobs are needed for nothing else, and they cost about 40% of generation time. Turning
   logprobs off would save that 40%, but would lose ordering and any later routing.

## 6. Golden cases (§5 of the spec)

`calibration/golden-cases.json`: 20 requests with:
- the stored response's path
- the §4.1 result
- C for every margin under all four rules (`C`, `Cmd`, `Cx`, `Cmdx`)
- every candidate below 0.5 with its `contested`, `contestedMd` and `minorOnly` flags

They cover:
- no candidates
- notation judged the same reading (formatting commands, spacing, line breaks)
- `FTFF`
- the digit-only contest
- `smiles:` lines
- diagram blocks with description lines skipped
- closing-fence and table-pipe contests (spec vs md)
- both §4.1 failures
- an LCT passage and an LCT question part
- the minor rule changing C
- several spec vs md differences

The replay is `calibration/replay.py`; the Java gate must give the same numbers. SMILES parsing isn't in the
expected results (CDK is on the Java side).

## 7. Self-check experiment (S6)

Not run. The user is satisfied with Qwen's output, so the extra signal isn't needed.

## 8. New regression cases

| Question | Why |
|---|---|
| `PYQ-M-200108-S2 C_NVT_49` (14631) | A blank written as underscores without end under greedy decoding; Ollama aborts with HTTP 500 |
| `PYQ-M-250408-S2 C_SCA_61` (33998) | Structures drawn as ASCII art in a bare code fence; §4.1 |
| `PYQ-A-08-P2 P_MMT_44` (36958) | Diagram blocks inside table cells (figures as matrix-match entries); §4.1 |
