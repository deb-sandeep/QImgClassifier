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

- `GET /Master/Question/TopicReview/Pending?syllabusNames=…|topicIds=…&limit=1..100`
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
