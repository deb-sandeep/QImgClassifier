# Auto-Tagging Driver — Design Outline

| Field | Value |
|---|---|
| System | SConsoleNxt — question auto-tagging driver |
| Status | Design outline for implementation; decisions marked **Decided** or **Proposed default** |
| Updated | 2026-10-22 |
| Base | Existing topic-association driver (QTopicReviewer): cascade scaffolding, gateways, rate-limit handling, job queue, decision panel |
| Server side | Done: `tag_master` carries `unabbreviated_tag_text`, `description`, `ai_association_hint`; bulk tag import (format v2) is live |

---

## 1. Purpose

Associate diagnostic tags with exam-bank questions (~40k) at scale, one topic at a time, using an LLM cascade, with a human deciding only the cases the automation cannot settle.

Each question gets tags on three axes, all identified by the root segment of the tag name:

| Axis | Prefix | Basis | Per question | Source topic |
|---|---|---|---|---|
| Setup | `s.` | Presence of a component in the figure or stem | 0..n | The scoped topic |
| Concept | `c.` | Dominant on the intended solution path | 1–2 | The scoped topic |
| Math technique | `m.` | The technique is the crux | 0–2 | Foundation Mathematics (topic 124) |

Axes are a design convention only. The server knows tags, not axes.

---

## 2. Scope and inputs

1. **Topic scope (required).** The user selects one topic. The driver does nothing without it.
2. **On scope selection the driver fetches:**
   - the tag catalogue of the scoped topic (`s.` and `c.` tags), with `tag_text` and `ai_association_hint`;
   - the math catalogue from topic 124 (`m.` tags);
   - the first batch of questions **awaiting auto-tag** (paged, as in the topic reviewer).
3. **Awaiting auto-tag (proposed default):** a question in the scoped topic with no `s.` or `c.` tag and no recorded human decision. Re-tagging after a tag-set version change is a separate, explicit run, never automatic.
4. Only tags with a non-null `ai_association_hint` are offered to the LLM (legacy tags have none).

---

## 3. The core gate: blind solve, local answer check — **Decided**

- The LLM receives the question image, the question type and the tag catalogue. **It never receives the answer key.**
- The LLM returns its own answer plus tags.
- The driver compares the LLM's answer with the stored key **locally**, using the existing `app-jee-exam` evaluator:
  - SCA, ART: letter equality;
  - MCA: exact set equality (a subset is a mismatch);
  - NVT, IVT: numeric value-equality (parse both sides as numbers; never string comparison), honouring any stored tolerance or range.
- **Why:** a correct blind answer is strong evidence that the model took a valid solution path, which is what concept tags depend on. Giving the key would let the model reason backwards to it and make the check meaningless.
- **Known weakness:** for SCA a guess passes 25% of the time. Mitigated by the audit sample (§5).
- **Setup tags follow the same gate.** A wrong answer often comes from misreading the figure, so setup tags are accepted only together with a correct answer.

---

## 4. Cascade — **Decided**

Tiers are configured by role, not hard-wired to models. Each tier is one config entry: model ID, gateway, reasoning budget, max output tokens, concurrency.

| Tier | Role | Current model | Planned |
|---|---|---|---|
| 1 | First-pass blind solve | Nemotron 3.5 (OpenRouter) | Gemma 4 31B unquantised (local), once admitted by the pilot (§9) |
| 2 | Escalation blind solve | GLM 5.3 Flash (reasoning) | unchanged |

**Flow per question**

1. Tier 1 solves blind.
   - Answer matches the key → **accept tier 1's tags** (setup, concept, math). Done.
   - Mismatch → step 2.
2. Tier 2 solves blind (also without the key).
   - Tier 2 matches the key → **accept tier 2's tags**; tier 1's are discarded. Done.
   - Tier 2 mismatches the key and **agrees with tier 1** → human, `KEY_SUSPECT`.
   - Tier 2 mismatches the key and **disagrees with tier 1** → human, `UNSOLVED`.

The key acts as the arbiter; there is no separate voting tie-break.

**Operational:** the OpenRouter free tier for Nemotron is capped at 1000 requests/day. The driver must stop cleanly at the cap and resume (existing "held for the day" state), or tier 1 moves to the paid endpoint.

---

## 5. Human hand-off — Proposed default

The driver relinquishes control and queues the question for a human when:

| Reason | Trigger |
|---|---|
| `KEY_SUSPECT` | Both tiers give the same answer, different from the key. |
| `UNSOLVED` | Both tiers are wrong and disagree with each other. |
| `TAG_GAP` | The model reports that no catalogue tag fits a dominant move. Feeds taxonomy revision; never forced into a tag. |
| `CROSS_TOPIC` | The model reports that the solution needs another topic's move. |
| `INVALID_OUTPUT` | Still malformed after one automatic repair attempt: tag not in the catalogue, more than 2 concept tags, zero concept tags, more than 2 math tags, truncated reply. |
| `UNSUPPORTED` | Key missing, bonus, or "all correct"; question type out of scope (e.g. SUB, matrix match). |
| `AUDIT` | Random ~5% sample of auto-accepted items, for measuring how often accepted tags are wrong. |

Self-reported model confidence is **not** used as a gate (poorly calibrated). Every gate is an objective check.

---

## 6. Human decision UI — Proposed default

Extends the existing decision panel.

**Shows:** question image; key; tier 1 answer and tags; tier 2 answer and tags (if run); each concept tag's one-line reason; hand-off reason.

**Actions:**
- accept tier 1's tags;
- accept tier 2's tags;
- edit tags (picker limited to the scoped catalogue plus math tags);
- **correct the answer key**;
- mark `TAG_GAP` / `CROSS_TOPIC` for later;
- skip.

**Correcting the key:**
- an audited write to the question bank: old key, new key, timestamp, the model answers that supported the change;
- no tag is written until the human decides;
- the driver then **re-checks the stored model responses against the new key, without new LLM calls**. If a stored response now matches, its tags become the proposal automatically.

This requires every raw model response to be persisted (§8).

---

## 7. Prompt design and token economy — Proposed default

**Where the cost is:** per call, the catalogue is ~2–3k input tokens, the image 0.5–1.5k, the structured reply ~80 output tokens, but the model's hidden reasoning while solving is **2–10k output tokens**. Reasoning dominates. Levers in order of impact:

1. **Resolve as many items as possible at the cheapest tier.** Tier-1 accuracy drives total cost (measured in the pilot).
2. **Reasoning budget cap per tier** (OpenRouter reasoning effort / max reasoning tokens). A reply truncated by the cap is retried once, then `INVALID_OUTPUT`.
3. **Cached fixed prefix.** Topic-at-a-time keeps the catalogue identical for the whole run. Order: system prompt → catalogue → (variable) question. Use provider prompt caching where available.
4. **Compact catalogue.** Per tag: `tag_text` + `ai_association_hint` only (no description, no unabbreviated name). Each axis rule is stated **once per axis section**, not per tag.
   - **Change required:** the format v2 export currently prepends the axis rule to every hint. Revert this: store bare hints; the driver adds the axis rule once per section. (Affects the tag-set export skill and a re-import of LoM and Foundation Mathematics.)
5. **Minimal reply schema** (no worked solution, no prose):
   ```json
   {"ans":"C",
    "setup":["s.connector.pulley"],
    "concept":[{"t":"c.dyn.sys-choice","why":"equivalent mass of pulley subsystem"}],
    "math":[],
    "gap":null,
    "xtopic":null}
   ```
   `why` ≤ 12 words. `gap` / `xtopic`: null, or a ≤ 15-word note.
6. **Image preparation once per question:** crop to the question, downscale to the smallest legible size, greyscale.

**Prompt structure:** shared template + per-topic section. The topic section = catalogue + a short list of topic-specific notes, versioned with the tag design document. Every prompt carries a `promptVersion`.

**Prompt skeleton**

```
[SYSTEM — cached]
You are tagging a JEE question for diagnostic analysis.
1. Solve the question yourself. Do not assume any answer is given.
2. Choose tags only from the catalogue below, using each tag's rule.
3. Reply with the JSON schema only.

SETUP TAGS — <setup axis rule, once>
- s.connector.pulley: <hint>
...
CONCEPT TAGS — <concept axis rule, once>
- c.dyn.sys-choice: <hint>
...
MATH TAGS — <math axis rule, once>
- m.integration: <hint>
...
TOPIC NOTES: <per-topic nuances>
REPLY SCHEMA: <schema>

[USER — per question]
Question type: SCA
<image>
```

---

## 8. Persistence and provenance — Proposed default

**Per model call (driver log):** question id, tier, model, gateway, prompt version, tag-set version, raw response, parsed answer, answer-check result, input/output/reasoning tokens, latency, error.

**Per accepted association:** tag-set version, model, prompt version, tier, `humanVerified`. If `tag_question_map` holds only `explicit`, keep this in a driver-side table keyed by `(question_id, tag_id)`.

**Per question:** driver state and hand-off reason.

| State | Meaning |
|---|---|
| `PENDING` | Awaiting tier 1 |
| `T1_ACCEPTED` / `T2_ACCEPTED` | Tags written automatically |
| `NEEDS_HUMAN(reason)` | Queued per §5 |
| `HUMAN_DECIDED` | Human accepted, edited or corrected |
| `SKIPPED` | Human skipped |
| `ERROR` | Infrastructure failure (retryable) |

---

## 9. Pilot before scale — Proposed default

Before running the bank:
1. **Golden set:** the validated exemplars in the topic's design document (validation log), plus human-tagged items as they accumulate.
2. Run ~50 items through each tier. Measure:
   - answer accuracy against the key;
   - tag precision and recall against the golden set, per axis;
   - tokens and cost per item, per tier.
3. Use the results to set reasoning budgets and confirm the tier order.
4. **Gemma 4 31B admission:** the same pilot; admitted to tier 1 if its answer and tag accuracy are close to Nemotron's. Its tokens are free, so its reasoning budget can be generous; throughput and per-gateway concurrency become the constraint.
5. Re-run the golden set whenever the prompt, a hint or the tag set changes (regression).

---

## 10. Out of scope (for now)

- Re-classifying a question's topic (the topic reviewer's job).
- Automatic retirement or renaming of tags.
- Book exercise questions without digital copies (manual tagging).

---

## 11. Decision status

| # | Item | Status |
|---|---|---|
| 1 | Blind solve; local answer check with the existing evaluator | Decided |
| 2 | Two-tier cascade by role; key as arbiter; tier configuration by role | Decided |
| 3 | Human hand-off reasons (§5) | Proposed default |
| 4 | Key-correction UI with audit and stored-response re-check (§6) | Proposed default |
| 5 | Pilot and golden-set regression (§9) | Proposed default |
| 6 | Token levers, including reverting the per-hint axis-rule prefix (§7) | Proposed default |
| 7 | Provenance and "awaiting auto-tag" definition (§2, §8) | Proposed default |
| 8 | Math tags in the same call, from topic 124 | Proposed default |
| 9 | Draft AI hints for LoM and Foundation Mathematics | Pending review |
