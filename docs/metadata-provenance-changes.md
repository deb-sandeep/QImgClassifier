# Topic provenance in the workspace metadata files

**Audience:** the agent maintaining **QImgExtractor**.
**Written by:** QImgClassifier, 2026-10-16.

QImgClassifier classifies question images with AI models and writes the results into each source's `.workspace` folder, the same folder QImgExtractor reads and writes. Both metadata files now record **who chose a topic**: an AI model (and which one) or a user. This document gives the new structure, what each value means, and what QImgExtractor has to change so the information is not lost and is recorded the same way when QImgExtractor itself sets a topic.

The changes are additive. Every existing key keeps its name, type and meaning, so the current QImgExtractor still reads both files correctly. One thing does go wrong today: QImgExtractor drops the new `topicAssociator` attribute whenever it saves `question-info.json` (see [Required changes](#required-changes-in-qimgextractor)).

---

## 1. `.workspace/ai-topic-map.json`

QImgClassifier owns this file. It holds the AI's ranked topic recommendations per question, keyed by question id. Ids use `_` as the separator, e.g. `C_SCA_74`; QImgExtractor's `AITopicSuggestionRepo` already maps `_` to `/`.

### New: `classifiedBy` on each question entry

```json
{
  "C_SCA_74" : {
    "classifiedBy" : {
      "actor" : "AI",
      "agent" : "openrouter",
      "model" : "google/gemini-2.5-flash"
    },
    "topicMappings" : [
      { "topicId" : 214, "topicName" : "Chemical Bonding",
        "syllabusName" : "IIT Chemistry", "confidenceLevel" : 94 },
      { "topicId" : 215, "topicName" : "Molecular Structure",
        "syllabusName" : "IIT Chemistry", "confidenceLevel" : 41 }
    ]
  },
  "P_SCA_2" : {
    "classifiedBy" : { "actor" : "User", "agent" : null, "model" : null },
    "topicMappings" : [
      { "topicId" : 102, "topicName" : "Units and Measurements",
        "syllabusName" : "IIT Physics", "confidenceLevel" : 100,
        "humanIntervention" : true }
    ]
  }
}
```

| Field | Type | Meaning |
|---|---|---|
| `classifiedBy.actor` | `"AI"` \| `"User"` | `AI`: the recommendations are a model's reply. `User`: a person picked the topic in QImgClassifier's manual-association dialog. |
| `classifiedBy.agent` | `"gemma"` \| `"openrouter"` \| `null` | Which backend answered: `gemma` is a local Ollama server, `openrouter` is the hosted OpenRouter API. `null` when `actor` is `User`. |
| `classifiedBy.model` | string \| `null` | The exact model that produced the stored recommendations, e.g. `gemma4:latest` or `google/gemini-2.5-flash`. `null` when `actor` is `User`. |

Notes:

- `topicMappings` is unchanged: confidence-descending, `confidenceLevel` 0–100, and `humanIntervention: true` on hand-picked entries as before.
- A `User` entry still has `humanIntervention: true` on its single mapping, so `classifiedBy.actor == "User"` and `humanIntervention` agree.
- **Entries written before this change have no `classifiedBy`.** Treat a missing `classifiedBy` as "source unknown". Never treat it as an error.
- QImgClassifier may ask a second model for a question. That happens when a Gemma reply would not pass the auto-association quality gate and the question is re-asked on OpenRouter. In that case the file holds only the reply that was finally stored, and `classifiedBy` names that reply's model.

## 2. `.workspace/question-info.json`

QImgExtractor owns this file. QImgClassifier writes into it only to associate a topic, and never overwrites an existing topic during auto association.

### New: `topicAssociator` inside each written `topic` object

```json
{
  "questions" : [
    {
      "qid" : "C/SCA/74",
      "topic" : {
        "id" : 214,
        "name" : "Chemical Bonding",
        "syllabusName" : "IIT Chemistry",
        "topicAssociator" : {
          "actor" : "AI",
          "model" : "google/gemini-2.5-flash",
          "confidence" : 94
        }
      }
    },
    {
      "qid" : "P/SCA/2",
      "topic" : {
        "id" : 102,
        "name" : "Units and Measurements",
        "syllabusName" : "IIT Physics",
        "topicAssociator" : { "actor" : "User", "model" : null, "confidence" : 100 }
      }
    }
  ]
}
```

| Field | Type | Meaning |
|---|---|---|
| `topicAssociator.actor` | `"AI"` \| `"User"` | `AI`: auto-associated because the AI's top recommendation passed the quality gate. `User`: chosen by a person. |
| `topicAssociator.model` | string \| `null` | For `AI`, the model that made the recommendation, copied from `ai-topic-map.json` → `classifiedBy.model`. It can be `null` for an `AI` association if that entry predates `classifiedBy`. Always `null` for `User`. |
| `topicAssociator.confidence` | integer 0–100 | For `AI`, the top recommendation's `confidenceLevel`. For `User`, always `100`. |

Notes:

- `id`, `name` and `syllabusName` are unchanged, and only `id` is needed to resolve the topic.
- `topicAssociator` describes how this question got its topic, not the topic itself. It belongs to the question's topic assignment, not to the topic master.
- **Topics assigned before this change, or by the current QImgExtractor, have no `topicAssociator`.** Treat that as "source unknown".
- `"topic" : null` (no topic) is unchanged and has no associator.

### Quality gate (for reference)

QImgClassifier auto-associates using the same gate as QImgExtractor's `AutoTopicAssociator`. Recommendations are confidence-descending; the second one's confidence counts as 0 when there is only one.

- **Any subject:** top ≥ 90 and it leads the second by ≥ 10.
- **Physics / Maths:** otherwise, top ≥ 95.
- **Chemistry:** otherwise, top ≥ 95 and it leads the second by more than 5.

---

## Required changes in QImgExtractor

All paths below are under `src/main/java/com/sandy/sconsole/qimgextractor/ui/project/`.

### R1. Keep `topicAssociator` through load and save (needed to avoid data loss)

Today `model/Question.java` reads only `topic.id` in `deserializeFrom()`. `getStateSerializedForm()` then writes the topic from `Topic.getSerializedForm()`, which holds only `id`, `name` and `syllabusName`. So the next save **drops every `topicAssociator`**.

- Give `Question` a field for the associator of its current topic: `actor`, `model` (nullable) and `confidence`. A small value class is enough.
- In `deserializeFrom()`, read `topic.topicAssociator` when it is present, and leave the field `null` when it is absent.
- In `getStateSerializedForm()`, write `topicAssociator` into the topic JSON when the field is set. Don't put it on `model/Topic.java`: `Topic` is the shared topic master, and the associator varies per question.
- Use the same key names and value types as above. QImgClassifier writes `actor` as exactly `"AI"` or `"User"`, and `model` as JSON `null` (not the string `"null"`) when there is none.

### R2. Change the associator whenever the topic changes

- **The user picks a topic** in `topicmapper/TopicMapperUI.java`, both for the single question (≈ line 92) and for a bulk assignment (≈ line 141). Set the associator to `{ actor: "User", model: null, confidence: 100 }`.
- **Auto association** in `topicmapper/AutoTopicAssociator.java` (≈ line 60, `question.setTopic( chosen )`). Set it to `{ actor: "AI", model: <classifiedBy.model of that question, or null>, confidence: <top suggestion's confidenceLevel> }`.
- **The topic is cleared** (e.g. `model/ProjectModel.java` ≈ line 553 writes `"topic" : null`). Clear the associator too.
- Setting the topic in any other way should also set or clear the associator, so the two never go stale. Making `setTopic` take the associator, or adding an overload, enforces this.

### R3. Read `classifiedBy` from `ai-topic-map.json`

`topicmapper/AITopicSuggestionRepo.java` reads only `topicMappings` today. Also read the optional `classifiedBy` object of each entry, at least `model` (`actor` and `agent` are useful for display), and expose it per question. `AutoTopicAssociator` needs it for R2, and the UI may want to show it.

### R4. Keep `classifiedBy` when moving entries (already OK)

`model/ProjectModel.java` → `updateAITopicMapKeys()` moves whole entry objects when questions are renamed, so `classifiedBy` moves with its entry. No change is needed. Keep it that way: any future code that rewrites `ai-topic-map.json` must copy unknown keys through, not rebuild entries from `topicMappings` alone.

### R5. Optional: show provenance in the UI

These would be useful but are not required:

- In the topic mapper / classifier panels, show the AI suggestion's model, e.g. "94% · google/gemini-2.5-flash".
- For an assigned topic, show whether a user or an AI chose it, and at what confidence.

### Compatibility checklist

- [ ] A `question-info.json` without any `topicAssociator` loads and saves as before.
- [ ] A `topicAssociator` written by QImgClassifier survives a load/save round trip in QImgExtractor.
- [ ] Assigning a topic by hand in QImgExtractor writes `{ "actor" : "User", "model" : null, "confidence" : 100 }`.
- [ ] Auto association in QImgExtractor writes `actor: "AI"`, the model from `classifiedBy` (or `null`), and the top confidence.
- [ ] Clearing a topic removes its `topicAssociator`.
- [ ] An `ai-topic-map.json` entry without `classifiedBy` still produces suggestions.

---

## Background: other QImgClassifier changes in the same batch

These don't change QImgExtractor's files but explain the values above:

- **Routing:** Chemistry questions go to OpenRouter, and Physics / Maths to the local Gemma. OpenRouter helps with Physics / Maths only when no Chemistry is waiting and Gemma has no free slot. So most Physics / Maths entries show `agent: "gemma"` and Chemistry entries `agent: "openrouter"`.
- **Second opinion:** a Gemma reply that fails the quality gate is re-asked on OpenRouter, and OpenRouter's reply is stored. If OpenRouter fails, Gemma's reply is kept and `classifiedBy` names Gemma's model.
- **Transcripts:** every AI request and reply is saved as text files under QImgClassifier's own `workspace.dir`, not in the source's `.workspace` folder. QImgExtractor does not need them.
- **Concurrent edits:** QImgClassifier writes `question-info.json` by read-modify-rename. If QImgExtractor has the source open while it is being classified, QImgExtractor's next save overwrites those writes, as before. Reopen the source in QImgExtractor after classification.
