# Required SConsole server APIs

**Audience:** the agent maintaining **QImgClassifier**.
**Written by:** SConsoleNxt (the server), 2026-10-16.

This document describes the two existing SConsole server APIs that QImgClassifier needs:

1. [Get topic list](#1-get-topic-list): `GET /Master/Topic/All`
2. [Update a question's topic](#2-update-a-questions-topic): `POST /Master/Question/Topic/{questionId}/{newTopicId}`

Both are already implemented and deployed. The examples below come from the dev server.

---

## Common conventions

- **Base URL:** the SConsole server. Dev runs at `http://localhost:8080`. Neither API needs authentication.
- **Response envelope:** every response, whether it succeeds or fails, is wrapped like this:

  ```json
  {
    "data": <payload, or null>,
    "executionResult": {
      "status": "OK" | "ERROR",
      "message": "Processing success" | "<error message>",
      "exceptionTrace": "<stack trace on server errors, else empty string>"
    }
  }
  ```

- **Detecting failure:** check the HTTP status, and also check that `executionResult.status` is `OK`.
  - Validation errors return HTTP **400** with `status: ERROR` and a readable `message`.
  - Unexpected failures return HTTP **500** with `status: ERROR`, and `exceptionTrace` holds the stack trace.

---

## 1. Get topic list

```
GET /Master/Topic/All?syllabusName=<syllabus name>
```

Returns the topics of one syllabus, or of all syllabuses when `syllabusName` is left out.

| Query param    | Required | Notes |
|----------------|----------|-------|
| `syllabusName` | No       | Exact syllabus name, URL-encoded. Values on dev: `IIT Physics`, `IIT Chemistry`, `IIT Maths`, `Exam`, `Reasoning`. The three IIT syllabuses are the ones QImgClassifier uses. |

### Response

`data` is an array of topics.

```
GET /Master/Topic/All?syllabusName=IIT%20Physics
```

```json
{
  "data": [
    { "id": 102, "syllabusName": "IIT Physics", "sectionName": "General Physics", "topicName": "Units and Measurements" },
    { "id": 103, "syllabusName": "IIT Physics", "sectionName": "Mechanics",       "topicName": "Kinematics" },
    { "id": 104, "syllabusName": "IIT Physics", "sectionName": "Mechanics",       "topicName": "Laws of Motion" }
  ],
  "executionResult": { "status": "OK", "message": "Processing success", "exceptionTrace": "" }
}
```

| Field          | Type   | Meaning |
|----------------|--------|---------|
| `id`           | int    | Topic id. Use it as `newTopicId` in API 2. Same id space as `topicId` in `ai-topic-map.json` and `question-info.json`. |
| `syllabusName` | string | Syllabus the topic belongs to |
| `sectionName`  | string | Grouping within the syllabus (e.g. `Mechanics`) |
| `topicName`    | string | Display name |

Notes:

- With `syllabusName`, topics are ordered by `id`, ascending. Without it, the order isn't defined, so sort on the client if order matters.
- An unknown `syllabusName` returns an empty array with `status: OK`. It isn't an error.
- Topics change rarely. Fetching them once at startup and caching them is fine.

---

## 2. Update a question's topic

```
POST /Master/Question/Topic/{questionId}/{newTopicId}
Content-Type: application/json      (only when a body is sent)
```

Moves a question to another topic and records the provenance of the new association: who made it, which AI model, and with what confidence.

| Path param   | Type | Notes |
|--------------|------|-------|
| `questionId` | int  | The question's **numeric server id** (`id` in the server's question records). This is **not** the string id `C/SCA/74`. |
| `newTopicId` | int  | Topic `id` from API 1. It must belong to the question's syllabus. |

### Request body (optional)

```json
{
  "topicAssocActor":      "AI",
  "topicAssocAiModel":    "google/gemini-2.5-flash",
  "topicAssocConfidence": 94
}
```

| Field                  | Type          | Rules |
|------------------------|---------------|-------|
| `topicAssocActor`      | string        | `AI` or `User`, exact case. Required when a body is sent. |
| `topicAssocAiModel`    | string / null | At most 64 characters. Send `null` for `User`. |
| `topicAssocConfidence` | int / null    | Percentage, 0–100. `null` is treated as 100. |

- **No body** means a manual change by the user. The server stores `User` / `null` / `100`.
- The stored provenance is **replaced** on every call. It's never merged with the old values.
- `newTopicId` may be the question's current topic. That updates only the provenance.

These fields map directly onto QImgClassifier's `topicAssociator` in `question-info.json`:

| `topicAssociator` (`question-info.json`) | Request body field |
|------------------------------------------|--------------------|
| `actor`                                  | `topicAssocActor` |
| `model`                                  | `topicAssocAiModel` |
| `confidence`                             | `topicAssocConfidence` |

### Response

On success:

```json
{
  "data": "Success",
  "executionResult": { "status": "OK", "message": "Processing success", "exceptionTrace": "" }
}
```

### Errors

All errors below return HTTP 400 with `status: ERROR`:

| Condition | `message` |
|-----------|-----------|
| No question with that id | `Question <questionId> not found.` |
| No topic with that id | `Topic <newTopicId> not found.` |
| Topic belongs to another syllabus | `Topic <topicName> doesn't belong to syllabus <syllabusName>.` |
| Question already attempted in an exam, and the topic would change | `Question <qid> has been attempted in an exam. Its syllabus, topic and problem type can't be changed.` |
| `topicAssocActor` isn't `AI` or `User` | `Invalid topicAssocActor '<value>'. Expected AI or User.` |
| `topicAssocConfidence` is outside 0–100 | `topicAssocConfidence must be between 0 and 100.` |
| `topicAssocAiModel` is longer than 64 characters | `topicAssocAiModel must be at most 64 characters.` |

Example from dev:

```
POST /Master/Question/Topic/999999999/102
→ HTTP 400
{ "data": null,
  "executionResult": { "status": "ERROR", "message": "Question 999999999 not found.", "exceptionTrace": "" } }
```

### Topic review (implemented 2026-10-17)

The topic review is now live, and it is the reviewer's way of changing a
topic. The contract is in
`SConsoleNxt/doc/feature-enhancements/2026-10-16/question-topic-review/api-changes.md`:

- `GET /Master/Question/TopicReview/Pending?syllabusNames=…|topicIds=…&limit=1..500`
  returns the first flagged questions by id (`topicReviewNeeded = true`),
  with `totalResults`.
- `POST /Master/Question/TopicReview` `{reviews: [...]}` confirms up to 100
  reviews (topic + provenance), with a per-item `APPLIED` / `UNCHANGED` /
  `REJECTED` answer.

This endpoint keeps its contract. With no body it records `User`/null/100
and clears the review flag. A review client should use the review endpoint
instead, because only that one refuses to overwrite a user's decision.

Provenance in `QuestionVO` (`topicAssocActor`, `topicAssocAiModel`,
`topicAssocConfidence`) may now be `null`, which means unknown.

## 3. NXR pending fetch: an `afterId` cursor (requested 2026-10-30)

`GET /Master/Question/NXR/Pending?batchSize=1..100` (see
`SConsoleNxt/doc/feature-enhancements/2026-10-30/nxr-classification/api-changes.md`)
always returns the first unlabelled questions by id. The NXR classifier
leaves some questions unlabelled (no local or AI label, awaiting the user),
so once a batch's worth of them pile up every fetch returns the same ones.

Requested: an optional `afterId` parameter, as the tagger's pending fetches
have:

```
GET /Master/Question/NXR/Pending?batchSize=50&afterId=1234
```

- Returns the first `batchSize` questions with `nxrValue` null and
  `id > afterId`, by id. Without `afterId`, from the first, as now.
- `totalResults` stays the count of all unlabelled questions, whatever
  `afterId` is.
- `afterId` not a whole number → HTTP 400.

The client already sends it (`NXRServerClient.pending`): Load fetches after
the last question loaded, Load from start without the parameter. Until the
server reads it, the parameter is ignored and every fetch starts from the
first.

## 4. Transcription pending fetch: search criteria (implemented 2026-11-17)

`GET /Master/Question/Transcription/Pending?batchSize=1..100&afterId=` (see
`SConsoleNxt/doc/feature-enhancements/2026-11-17/question-transcription/api-changes.md`)
returns every pending question by id. The transcriber (QTranscriber, being
designed) works the queue the topic reviewer's way, by search: the user
picks syllabuses or topics, and the results come in server id order behind
a cursor that a new search resets. So the fetch needs the topic review
fetch's criteria.

Requested: two optional parameters, with the rules of
`TopicReview/Pending`:

```
GET /Master/Question/Transcription/Pending?syllabusNames=IIT Physics,IIT Chemistry&batchSize=50&afterId=6155
GET /Master/Question/Transcription/Pending?topicIds=12,15&batchSize=50
```

| Query param | Rules |
|---|---|
| `syllabusNames` | Optional. Comma-separated syllabus names |
| `topicIds` | Optional. Comma-separated topic ids. If both are given, `topicIds` is used and `syllabusNames` ignored |
| `batchSize`, `afterId` | As now |

- Neither given: all pending questions, as now (so existing callers keep
  working).
- The result is the first `batchSize` pending questions matching the
  criteria with `id > afterId`, by id.
- `totalResults` counts all pending questions matching the criteria,
  ignoring `afterId`.
- `data.criteria` echoes the filter as applied, the ignored one `null`, as
  in `TopicReview/Pending`.
- Items keep their shape (`question`, `lctCtxId`, `lctCtxTranscribed`).
- Errors (HTTP 400), as in `TopicReview/Pending`: `Invalid topicIds.`,
  `Syllabus 'X' not found.`, `Topic 15 not found.`.

Implemented on dev 2026-11-17 (`api-changes.md` rev 2, `feature-design.md`
decision 11); the Pi deploy is pending. As requested, with these details:

- Blank entries and duplicates are dropped; a blank parameter counts as
  absent.
- `data.criteria` is `{syllabusNames, topicIds, afterId}`; `afterId` is
  null when absent.
- `batchSize` and `afterId` are checked before the filter. An unknown
  syllabus is checked only when `topicIds` is absent; the first unknown
  topic or syllabus is named.
- The paging parameter stays `batchSize` (not `limit` as in
  `TopicReview/Pending`), and neither filter is required.

## 5. LCT questions: self-contained content and a passage read (implemented 2026-11-17)

Today an LCT question's transcription holds only its question part, and its
passage is stored once per group (`api-changes.md`, *LCT groups*). The user
wants each LCT question to stand on its own: its `content` holds the passage
as well, so the indexer (and any reader) needs no join to `lctCtx` to get
the whole question.

The transcriber works an LCT group like this: before a question of the
group is transcribed, it asks the server for the group's passage. If the
server has it, only the question part is transcribed, with the passage
given to the model as context. If not, the passage is transcribed first
and held in the transcriber's workspace (not saved) until the user saves:
the first question of the group saved then carries it as `lctCtx`, as now.
A passage the user marks faulty in a spot check is re-transcribed and the
group's staged questions rebuilt before anything reaches the server. So no
passage-only POST is needed.

### 5.1 The content of an LCT question

An LCT question's `content` is the passage, a marker line, then the
question part:

```
<passage>

<!-- question -->

<question part>
```

That is, `content = passage + "\n\n<!-- question -->\n\n" + questionPart`.
The marker is an HTML comment, so it doesn't show when the markdown is
rendered, and lets a client split the two parts again.

Requested, on `POST /Master/Question/Transcription`, for an LCT question:

- `content` must start with the group's passage followed by the marker:
  the `lctCtx.content` sent in the same item if any, else the stored
  passage. The question part after it must not be blank. Otherwise the
  item is REJECTED, e.g. `Question 6201: content does not start with the
  passage of LCT context LK-A-P-11://P/LCT/1.` This keeps an old copy of a
  passage from being saved.
- Non-LCT questions are unchanged (the marker is not required or checked).
- `GET /Master/Question/Transcription/{questionId}`: `content` is the whole
  question; `lctCtx` may stay in the reply, but rendering `content` alone
  is now the full question.

### 5.2 Read a group's passage (new)

```
GET /Master/Question/Transcription/LctCtx?lctCtxId=LK-A-P-11://P/LCT/1
```

The question part of a pending question can't be transcribed without the
passage, and today a passage can be read only through a question already
transcribed.

```json
{
  "lctCtxId"  : "LK-A-P-11://P/LCT/1",
  "aiModel"   : "qwen3.5:27b-q8_0",
  "content"   : "...",
  "questions" : [
    {
      "id"          : 6201,
      "questionId"  : "LK-A-P-11://P/LCT/1/49",
      "transcribed" : true,
      "current"     : true
    }
  ]
}
```

- `questions` lists every question of the group, by `id`: `transcribed`
  whether it has a transcription, `current` whether that transcription
  starts with the stored passage and the marker (null when not
  transcribed).
- When a passage is replaced (an item's `lctCtx` with new content), the
  group's other saved questions still hold the old copy. `current: false`
  tells the transcriber which to re-save: it splits off their question
  part at the marker, puts the new passage in front and saves them again,
  with no model call.
- An unknown or untranscribed group: HTTP 404, `LCT context X is not
  transcribed.` (or a 200 with `content: null` and the `questions` list,
  whichever fits the server's conventions better; the transcriber needs
  the list either way to know the group exists).
- A malformed `lctCtxId` (not `<questionId prefix>/<lctSequence>`): HTTP
  400.

### 5.3 Existing transcriptions

Any LCT transcription stored before this change holds the question part
alone; the server decides whether to migrate them (prepend the passage and
the marker) or delete them so they are pending again.

Implemented and verified on dev 2026-11-17 (`api-changes.md` rev 3,
`feature-design.md` decision 12); the Pi deploy is pending. As requested,
with these details:

- The passage + marker prefix is compared exactly. A blank question part
  has its own message: `Question 6156: the question part after the passage
  is empty.`
- The passage read returns 200 with `aiModel`, `content` and `lastUpdateAt`
  null when the group exists but its passage isn't stored; `questions` is
  filled either way. `current` is false for a question transcribed while
  no passage is stored.
- Errors (HTTP 400): `Invalid lctCtxId.`; `LCT context X not found.` when
  no LCT question is in the group (no 404).
- Replacing a passage leaves the group's other transcriptions as they are;
  the read reports them with `current: false`.
- Nothing migrated: no transcription had been stored on dev or the Pi.
- The prefix check runs after the existing checks. The passage compared is
  the item's `lctCtx.content`, else the stored one, including one stored by
  an earlier item of the same request. Comparison is exact, with no
  whitespace normalisation.
- A malformed `lctCtxId` is one not matching `<non-space prefix>/<digits>`;
  surrounding whitespace is trimmed. `lastUpdateAt` is an extra field.
