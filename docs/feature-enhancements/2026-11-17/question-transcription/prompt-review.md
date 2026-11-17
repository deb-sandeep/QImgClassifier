# QTranscriber prompt v1: review

| Field | Value |
|---|---|
| Prompt | `src/main/resources/apps/transcriber/qtranscriber.prompt` (version 1, 2026-11-17) |
| Reviewer | question-bank agent, 2026-11-17 |
| Method | The prompt filled as the header describes (TASK QUESTION, matching TYPE and SUBJECT notes), sent to `qwen3.5:27b-q8_0` via `/api/chat` with the F1 sampler (`temperature 0`, `top_k 1`, all penalties off), on the hard cases from logprob-confidence.md S4 plus the Venturi question |

## 1. Results

| # | Question | Time | Result |
|---|---|---|---|
| R1 | Series with source typo, `PYQ-M-240406-S2 M_SCA_10` | 10.4 s | **Typo corrected again**: printed `… + n²` written as `n^3`. The whole question, including "is equal to", went into one `$$…$$` with `\text{}` |
| R2 | Aromaticity, two parts, `PYQ-A-13-P1 C_MCA_34` | 10.6 s | Parts joined correctly, and inferred names stayed inside diagram blocks. But **no SMILES**, a **misread in a description** (2,5-hexanedione, an open chain, described as "a six-membered ring with two ketone groups"), and LaTeX inside a block (`100-115^\circ C`) |
| R3 | Benzyl halides, `PYQ-M-260121-S2 C_SCA_59` | 11.0 s | Descriptions correct, but all four structures in **one** block, so **no SMILES**. Options wrote `$\text{a} > \text{b} …$` |
| R4 | Matrix match, `AITS-13-A-FT1P2 C_MMT_2` | 5.9 s | Correct: one table, `\ce{}` formulas, empty cell where the columns differ in length |
| R5 | List-I / List-II in an SCA, `PYQ-M-260121-S2 C_SCA_52` | 8.3 s | Text correct, but the table has invented empty columns and a bold "Pair of Compounds" data row. Labels sit in their own column for List-I but inside the text for List-II. **Options not a list** |
| R6 | Graph + statements, `PYQ-M-250404-S2 P_SCA_31` | 14.3 s | Graph description accurate and detailed. **Statements (A)–(E) and options (1)–(4) not a list** |
| R7 | Venturi, `PYQ-M-260121-S1 P_SCA_29` | 10.3 s | Text, units and blank (`\_\_\_\_\_`) correct, description accurate. **Options not a list** |

Input was 1,000–1,570 tokens per question (1.2–1.8 s to read), output 135–424 tokens.

## 2. Findings and suggestions, most important first

**P1. Labelled lines often aren't list items, and then they render as one paragraph.** R5, R6 and R7 wrote options (and R6 its statements) as plain consecutive lines. Markdown joins consecutive lines into one paragraph, so the rendered question shows `(1) … (2) … (3) …` run together. The rule is in the notation list and the TYPE notes, but Qwen followed it only for some layouts.
- In rule 2, make it explicit: "Every labelled line (options, statements, list entries) is a markdown list item starting with `- `, e.g. `- (A) …`. Never write labelled lines as consecutive plain lines."
- Also fix it deterministically in the driver, which is cheaper than escalating: prefix `- ` to any line matching `^\((?:[A-Za-z]|\d{1,2}|[ivx]{1,4})\)\s` that isn't already a list item and sits outside tables and code fences.

**P2. The typo correction is back, and the gate misses it.** R1 wrote `n^3` for the printed `n²`. With logprobs, the position was `3` at p = 0.846 and `2` at 0.154. That passes `P_FLOOR 0.6` and a 0.3 margin, so it isn't contested. The JSON prompt of 2026-11-16 kept `n²` with the same "reproduce typos" sentence. The difference here is that Qwen put the whole question in one display formula, and inside maths it completes the pattern.
- Suggest a separate, lower rival threshold for **digits and single letters in exponents, subscripts and numbers**: a rival that differs in a digit counts as contested at p ≥ 0.1. Digits are where a misread changes the meaning, and notation doubt (F2) rarely shows up as a digit. Calibrate it with the rest.
- P6 (prose stays out of maths) should also reduce the pull to complete the pattern.

**P3. Structures: one block per structure, each with SMILES.** R2 and R3 gave no SMILES at all, because rule 4 asks for one only for "a single chemical structure", and Qwen grouped the structures. Without SMILES, R2's description misread (an open chain described as a ring) goes uncaught, because the gate skips descriptions. Suggested wording:
- "Each drawn chemical structure gets its own diagram block, with its printed label (a, P, …) and a `smiles:` line."
- "Arrows, reagents and conditions printed between structures are printed text: write them outside the blocks with mhchem, e.g. `$\ce{->[AlCl3]}$ P`."

**P4. Figures inside table cells or options have no rule.** A fenced block can't sit inside a markdown table cell, or inside a list item unless it's indented. Matrix-match columns and options are often structures or graphs. Suggest: "If an option or table cell is a figure, write only its label there, and put the diagram block after the list or table, starting its description with that label (`Option (B): …`, `Column I (C): …`)."

**P5. Table shape.** R5 invented columns and turned a heading line into a data row. Suggested wording:
- "One table column per printed column."
- "Label and text in the same cell (`A. 2-Methylpropene …`)."
- "All printed title lines of a column go in its header cell (`List-I: Pair of Compounds`)."

Also:
- Match tables appear in SCA questions (JEE Main List-I/List-II), not only MMT. Move the table rules from TYPE MMT into the general notation list.
- In TYPE MMT, say "two or more columns" (some JEE Advanced papers have three) and "then the options as a list, if there are any" (AITS MMT questions have none).

**P6. Prose went into maths.** R1 wrapped the sentence in `$$…\text{ is equal to}$$`, and R3 wrote `\text{a}`. Suggest: "Use `$$…$$` only for an expression printed on its own line. Words printed on the same line as an expression stay outside the maths." For label letters in comparisons, pick one form, e.g. `$a > b > c > d$`.

**P7. Characters that break markdown.**
- `|` inside a table cell ends the cell, so modulus inside tables must be `\lvert x \rvert` (or `\vert`).
- Blanks as `\_\_\_\_` (R7 did this unprompted; make it a rule, because `____` renders as emphasis or a rule).
- A literal `*` or `#` at the start of a line, and `>` at the start of a line (a blockquote), are easiest avoided by keeping such content in maths.

**P8. Diagram blocks are plain text.** A code block doesn't render LaTeX, so `100-115^\circ C` shows raw. Suggest: "Inside a diagram block write plain text with Unicode symbols (°, θ, ², →), no `$`. Copy printed labels and values in the figure exactly."

**P9. Multi-part images.** R2 worked without a hint, but one sentence is cheap insurance: "The question may span several images, given in order; transcribe them as one." LCT passages need a decision from the user: keep or drop a printed heading such as "Paragraph for Questions 45 and 46". It carries source question numbers, which the prompt otherwise drops.

**P10. Units example.** `$9.8\,\mathrm{m\,s^{-2}}$` contradicts rule 1 when the page prints `m/s²`. R7 correctly kept `m/s^2`. Suggest: "Units as printed, set upright: `$10\,\mathrm{m/s^2}$` or `$\mathrm{m\,s^{-2}}$`, whichever is printed."

**P11. Minor.**
- `{{TYPE}}` says "name from the TYPE section's title", but the titles are codes. Take the name from the note's first sentence, or add a name line.
- TYPE LCT describes the group, but an LCT question is SCA, MCA or IVT in shape. Add "then its options as a list, if any".
- Prefix caching: reading the prompt took 1.2–1.8 s for 1,000–1,570 tokens including the image, so caching saves little. Don't bend the prompt's order for it.

## 3. What works

- Venturi, matrix match and the graph description are accurate.
- Two-part images were joined correctly.
- Inferred names stayed inside diagram blocks (rule 4 holds).
- There was no meta text, no answers and no placeholders.
- `\ce{}` was used where expected.
- Typical time is 6–14 s a question at about 32 tokens a second.

Re-run these seven after the P1–P8 changes. They are the S4 regression set of logprob-confidence.md plus the Venturi question.
