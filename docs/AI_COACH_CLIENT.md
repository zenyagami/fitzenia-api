# AI Coach — Client Integration

> Cross-reference: `docs/AI_COACH.md` (architecture & operations), `docs/ENTITLEMENTS.md` (premium entitlements), `docs/API_SECURITY_BACKEND.md` (auth model), `docs/DATABASE_SCHEMA.md` (schema).

This is the **complete wire contract** for the AI Coach chat feature: every endpoint, request/response shape, every SSE event, every error code, and the full conversation lifecycle — written so a Kotlin client engineer can build a chat UI against it without reading backend code. All DTOs below are copied from the backend's actual `@Serializable` classes; you can paste them straight into the client.

---

## What's Live Today

- The AI Coach runs as a **separate Cloud Run service** from the food API (`fitzenia-coach-{dev,prod}`), with its **own base URL**. It is **not the same host** as `/api/food/*`.
- **The coach backend is complete.** The coach schema is applied to prod, the service is deployed separately, and the KB corpus is ingested. Use the relevant coach service host for the environment you are targeting.
- **The dev premium bypass has been retired.** Premium is now driven **only** by RevenueCat → `user_entitlement` (checked server-side). Access requires a real active `premium` entitlement row in **both** dev and prod; there is no env bypass. Existing subscribers backfill automatically on first coach use (lazy sync-on-miss). A non-entitled caller gets `403 PREMIUM_REQUIRED`.
- Base URLs are placeholders in this doc — substitute the real Cloud Run hosts:
  - dev: `https://api-dev.coach.fitzenia.com` · prod: `https://api-coach.fitzenia.com`

**Backend invariants the client must respect:**
- **Premium is enforced server-side**, but you should still gate the UI (don't show the coach to non-premium users). A non-entitled caller gets `403`.
- Auth is the **same Supabase JWT** you already use for the food API (same Supabase project). Send it as `Authorization: Bearer <jwt>`.
- All chat/message/notes **writes go through these endpoints** (backend uses the service role). The reads also have purpose-built endpoints (below) — prefer them over hitting Supabase REST directly.

---

## Endpoint summary

| Method | Path | Auth | Rate-limit (per user) | Purpose |
|---|---|---|---|---|
| POST | `/api/coach/messages` | Bearer | 6 / min (`coach-message`) | Send a message **starting a new chat** (`chatId` is created server-side). SSE out. |
| POST | `/api/coach/chats/{chatId}/messages` | Bearer | 6 / min (`coach-message`) | Send a message **in an existing chat**. SSE out. |
| GET | `/api/coach/chats` | Bearer | 30 / min (`coach-management`) | List the user's non-archived chats, newest-first. |
| GET | `/api/coach/chats/{chatId}/messages` | Bearer | 30 / min (`coach-management`) | Full message history for one chat, chronological. |
| DELETE | `/api/coach/chats/{chatId}` | Bearer | 30 / min (`coach-management`) | Archive the chat + delete its messages. `204` / `404`. |
| GET | `/api/coach/notes` | Bearer | 30 / min (`coach-management`) | List the user's cross-chat coach notes (max 50). |
| DELETE | `/api/coach/notes/{id}` | Bearer | 30 / min (`coach-management`) | Delete one coach note. `204` / `404`. |
| GET | `/health` | public | — | Liveness probe → `200 {"status":"ok"}`. |

A `429` on any endpoint means the per-user rate bucket is exhausted — show a "try again in a minute" toast. The two streaming endpoints share the `coach-message` bucket (6/min); everything else shares `coach-management` (30/min).

---

## Authentication

Every endpoint except `/health` requires a valid Supabase JWT:

```
Authorization: Bearer <supabase-access-token>
```

The user id is derived from the token's `sub` (server-side) — **never** send a user id in the body or path; it is ignored. An invalid/missing/expired token returns `401`. A valid token for a non-premium user returns `403` (see [Error model](#error-model)).

---

## The chat turn (streaming)

Both `POST /api/coach/messages` (new chat) and `POST /api/coach/chats/{chatId}/messages` (existing chat) take the **same JSON body** and return the **same SSE stream**. The only difference is whether the chat already exists.

### Request

```
POST /api/coach/messages          (new chat — server creates the chatId)
POST /api/coach/chats/{chatId}/messages   (existing chat)
Authorization: Bearer <jwt>
Content-Type: application/json
Accept: text/event-stream
```

```kotlin
@Serializable
data class SendMessageRequest(
    val content: String,        // required, non-blank, ≤ ~80 000 chars
    val locale: String,         // required, e.g. "en", "es", "de" (region subtags are stripped server-side)
    val userTz: String? = null, // optional IANA tz, e.g. "Europe/Madrid"; defaults to "UTC"
)
```

- `content` — the user's message. Blank → `400`. Over ~80 000 chars → `400 INPUT_TOO_LONG`.
- `locale` — drives the **reply language** and the language of any safety redirect. Send the user's app language (e.g. `"es"`).
- `userTz` — used so date-aware tools ("how many calories left **today**") resolve to the user's local day. Falls back to UTC if omitted/invalid.

### Response: Server-Sent Events

On success the response is `200` with `Content-Type: text/event-stream` and `Cache-Control: no-cache`. Standard SSE framing — `event: <name>` and `data: <json>` lines, each event terminated by a blank line:

```
event: chat_created
data: {"chatId":"5e2…","title":"New chat"}

event: tool_start
data: {"name":"checking_today"}

event: tool_done
data: {"name":"checking_today","ms":143}

event: token
data: {"delta":"On a cut, aim for about 2.0–2.4 g of protein per kg…"}

event: citation
data: {"chunkId":"…","source":"nutrition/protein-targets","score":0.83}

event: title
data: {"chatId":"5e2…","title":"Protein on a cut"}

event: done
data: {"chatId":"5e2…","messageId":"a17…","tokens":{"input":2960,"output":412,"cached":0},"model":"gemini-3.1-flash-lite","escalated":false}
```

The connection closes after the **terminal event**, which is **`done` OR `error`** — see [Stream termination](#stream-termination). Keep-alive comments (`: ping`) may appear; ignore any line starting with `:`.

> ⚠️ **`token` is a single buffered event with the *entire* answer**, despite the field name `delta`. The backend buffers the full LLM response (and runs the safety output filter) before emitting it, so you get exactly **one** `token` event containing the whole message — **not** incremental tokens. Do **not** build an append-as-you-go UI. The live "something is happening" signal is the `tool_start`/`tool_done` pairs that stream *before* the `token`; show a thinking/working indicator during those, then render the whole message when `token` arrives.

### Event reference

All payloads below are the backend's actual `@Serializable` DTOs (package `com.zenthek.coach.stream`).

#### `chat_created` — only on a **new** chat (POST `/api/coach/messages`)
```kotlin
@Serializable
data class ChatCreatedPayload(val chatId: String, val title: String)
```
First event when you start a new chat. **Capture `chatId`** — every follow-up message in this conversation goes to `POST /api/coach/chats/{chatId}/messages`. `title` is initially `"New chat"` (a generated title may arrive later via the `title` event). Not emitted when you POST to an existing chat.

#### `tool_start` / `tool_done` — zero or more, streamed live
```kotlin
@Serializable
data class ToolStartPayload(val name: String)
@Serializable
data class ToolDonePayload(val name: String, val ms: Long)
```
The coach is reading the user's live data (targets, today's macros, weight history, diary, notes, KB). Use these to drive a "working" indicator. `name` is **not** the raw tool id — it is a **stable status key** from the closed set below, meant to be mapped to your own **localized** display string. `ms` is the tool's duration.

| `name` key | Meaning | Suggested display (EN) |
|---|---|---|
| `reading_profile` | Reading the user's profile | "Reviewing your profile…" |
| `reading_goal` | Reading the user's goal | "Reviewing your goal…" |
| `checking_targets` | Reading current calorie/macro targets | "Checking your targets…" |
| `checking_today` | Reading today's logged food | "Checking today's food…" |
| `analyzing_weight` | Reading weight history / trend | "Analyzing your weight…" |
| `reading_plan` | Reading the active phase/plan | "Reviewing your plan…" |
| `reading_diary` | Reading diary entries for a date | "Analyzing your diary…" |
| `reading_notes` | Reading saved coach notes | "Reviewing your notes…" |
| `saving_note` | Saving a coach note | "Saving a note…" |
| `searching_knowledge` | Searching the knowledge base | "Searching nutrition knowledge…" |
| `thinking` | Generic fallback (unknown/future tool) | "Thinking…" |

Treat `thinking` as the catch-all: if a future tool ships a key you don't recognize, fall back to your generic indicator rather than showing the raw key.

#### `token` — exactly once on a normal turn (full message)
```kotlin
@Serializable
data class TokenPayload(val delta: String)
```
`delta` is the **complete** assistant message. Render it as the assistant bubble. (Not emitted on a hard-block turn — see below.)

#### `citation` — zero or more, after `token`
```kotlin
@Serializable
data class CitationPayload(val chunkId: String, val source: String, val score: Double)
```
Knowledge-base sources grounding the answer. `source` is a doc id like `nutrition/protein-targets` or `app/tdee-algorithm`. Optional to render (e.g. a "Sources" footer). These structured events are the **only** source attribution — the backend strips the model's raw inline `(KB: doc-id)` markers from the message text, so the `token` text is clean.

#### `safety` — only on a hard-blocked turn (instead of `token`)
```kotlin
@Serializable
data class SafetyPayload(val action: String, val message: String)
```
Emitted when the message is intercepted by the safety classifier (eating-disorder / self-harm / medical / drug content) **before** any LLM call. `action` is `"hard_block"`. **`message` is the canned, locale-aware response (with helplines) — render it as the assistant bubble.** It is followed by a `done` event with **no** `token`. ⚠️ A client that only paints the assistant bubble on `token` will show an empty bubble for every redirect — you **must** handle `safety.message`.

#### `title` — first turn only, best-effort
```kotlin
@Serializable
data class TitlePayload(val chatId: String, val title: String)
```
An auto-generated chat title (≤ ~6 words, locale-aware), emitted after the first user turn. **It is best-effort and may never arrive** (3 s timeout, fire-and-forget). Update your chat header/sidebar when it does; otherwise keep the `"New chat"` title from `chat_created` until a later `GET /api/coach/chats` returns the persisted title.

#### `done` — terminal (success)
```kotlin
@Serializable
data class TokenUsage(val input: Int, val output: Int, val cached: Int)
@Serializable
data class DonePayload(
    val chatId: String,
    val messageId: String,   // id of the persisted assistant coach_message row
    val tokens: TokenUsage,
    val model: String,       // e.g. "gemini-3.1-flash-lite" (or the escalation model)
    val escalated: Boolean,  // true if the turn was retried on the Pro model
)
```
Marks a successful turn. Tear down the SSE connection. `model`/`escalated` are informational — **display `model` as-is; do not hardcode model strings.** On a **hard-block** turn, `done` arrives with `model=""`, `tokens={0,0,0}`, `escalated=false` (the real content was in the preceding `safety` event).

#### `error` — terminal (two possible shapes — branch on `code`)
`event: error` is used for **two different payloads**. Disambiguate by `code` (or the presence of `resetAt`):

```kotlin
// Monthly budget cap hit (no `done` follows). Has resetAt.
@Serializable
data class BudgetExceededPayload(val code: String, val resetAt: String, val message: String)
//   code == "BUDGET_EXCEEDED", resetAt = ISO-8601 instant (start of next month, UTC)

// Generic in-stream failure (no `done` follows).
@Serializable
data class SseErrorPayload(val code: String, val message: String)
//   code == "INTERNAL_ERROR"
```
Suggested deserialization: parse the `data` JSON, read `code`; if `code == "BUDGET_EXCEEDED"` decode `BudgetExceededPayload`, else `SseErrorPayload`. Both are terminal — close the stream, no `done` will follow.

### Stream termination

The stream ends on **either** `done` **or** `error` — there is no guarantee of a `done`. Your "stream finished / re-enable the composer" handler must fire on **both**:

| How the turn ends | Events you see |
|---|---|
| Normal answer | … → `token` → `citation`× → (`title`) → **`done`** |
| Safety hard-block | `safety` → **`done`** (no `token`) |
| Monthly budget exhausted | **`error`** (`BUDGET_EXCEEDED`) — no `token`, no `done` |
| Internal failure | **`error`** (`INTERNAL_ERROR`) — no `done` |
| Client disconnects | (nothing; server tears down quietly) |

**Full event order for a normal new-chat turn:**
`chat_created` → (`tool_start`/`tool_done`)× → `token` (once) → `citation`× → `title` (first turn, maybe) → `done`.
For an existing chat, drop `chat_created`; for non-first turns, drop `title`.

---

## Conversation lifecycle & use-case scenarios

This is everything you need to implement a chat-style UI.

### 1. Start a brand-new conversation
The client does **not** create the chat. POST the first message with **no chatId**:
```
POST /api/coach/messages
{ "content": "What's a good protein target on a cut?", "locale": "en", "userTz": "Europe/Madrid" }
```
→ first SSE event is `chat_created` with the server-generated `chatId`. **Store it.** All subsequent messages in this conversation use that id.

### 2. Continue an existing conversation
```
POST /api/coach/chats/{chatId}/messages
{ "content": "and on a bulk?", "locale": "en" }
```
→ no `chat_created`; the stream goes straight to `tool_*` / `token` / `done`. The backend loads recent history server-side (last ~50 messages, auto-compacted) — you do **not** resend history.

### 3. "Does the conversation exist?" — you don't pre-check
There is no "create chat" endpoint and no "chat exists?" probe. The contract is: **only ever use a `chatId` you received from a `chat_created` event or from `GET /api/coach/chats`.** Posting to a `chatId` the user doesn't own / that doesn't exist is unsupported (messages silently won't persist). For a fresh conversation, always use `POST /api/coach/messages` (scenario 1).

### 4. Resume after app restart / open an old chat
- List chats: `GET /api/coach/chats` → pick one.
- Load its history: `GET /api/coach/chats/{chatId}/messages` (chronological).
- Send into it: scenario 2.

### 5. One turn at a time per chat (409 IN_FLIGHT)
A chat can have only **one** in-flight turn. If a second send for the same chat arrives while the first is still streaming, it returns `409` with `{"error":"IN_FLIGHT", ...}`. The lock key is per-`(user, chatId)`, and **new chats share a single `__new__` key** — so two simultaneous "start new chat" sends collide too. **Client rule: disable the send button until the current turn's `done`/`error`, and serialize sends per chat.**

### 6. Monthly budget exhausted
When the user hits the monthly cap, the turn opens the SSE stream and immediately emits `error` with `code="BUDGET_EXCEEDED"` and a `resetAt` (ISO instant). Show the `message` (e.g. "You've reached this month's coach limit. Resets July 1."), disable sending, and optionally use `resetAt` for a countdown. No tokens are charged for blocked turns.

### 7. Safety redirect (eating disorder / self-harm / medical / drugs)
The turn returns `safety` (with the localized helpline message) then `done`, **no LLM call, not charged**. Render `safety.message` as a distinct/cautionary assistant bubble. Detection is content-based; the `locale` you send picks the redirect language.

### 8. Escalation (transparent)
Hard questions are auto-retried on a stronger model. You see nothing special mid-stream — just `done.escalated=true` and a different `done.model`. Optional to surface ("answered with a more capable model").

---

## Chat management

### GET `/api/coach/chats`
Returns the user's **non-archived** chats, newest-first (`updated_at` desc).
```kotlin
@Serializable
data class ChatSummaryRow(
    val id: String,
    val title: String,
    @SerialName("message_count") val messageCount: Int,
    @SerialName("last_message_at") val lastMessageAt: String? = null, // ISO-8601, null if no messages yet
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)
```
Response: `200` with `List<ChatSummaryRow>` (JSON array). Use for the chat list/sidebar.

### GET `/api/coach/chats/{chatId}/messages`
Full history for one chat, **chronological** (`created_at` asc).
```kotlin
@Serializable
data class MessageDetailRow(
    val id: String,
    val role: String,    // "user" | "assistant"
    val content: String,
    @SerialName("created_at") val createdAt: String,
)
```
Response: `200` with `List<MessageDetailRow>`. Render `user` and `assistant` bubbles in order. (Safety-redirect turns are stored as normal `assistant` rows.)

### DELETE `/api/coach/chats/{chatId}`
Archives the chat and deletes its messages. → `204 No Content` on success; `404` if the chat isn't found or isn't owned by the caller. The chat disappears from `GET /api/coach/chats` afterward.

---

## Cross-chat notes

The coach remembers user preferences ("I'm vegetarian", "I hate oatmeal") across chats. These are surfaced so the user can view and delete them.

### GET `/api/coach/notes`
```kotlin
@Serializable
data class CoachUserNoteRow(
    val id: String,
    val note: String,
    val category: String,
    @SerialName("created_at") val createdAt: String,
)
```
Response: `200` with `List<CoachUserNoteRow>` (up to 50, newest-first). The coach **writes** notes itself during conversations — there is no client write endpoint.

### DELETE `/api/coach/notes/{id}`
→ `204` on success; `404` if not found / not owned.

---

## Error model

There are **two layers**. Handle both.

### Layer 1 — HTTP status (before the SSE stream opens)
These come back as a normal JSON body, **not** as SSE. Body shape is always `{"error": "<code-or-message>"}`, occasionally with an extra `"message"`:

| Status | When | Body example |
|---|---|---|
| `400` | Blank `content` | `{"error":"content must not be blank"}` |
| `400` | `content` too long | `{"error":"INPUT_TOO_LONG","message":"Message exceeds the maximum input length"}` |
| `400` | Malformed JSON body | `{"error":"Invalid request body"}` |
| `401` | Missing/invalid/expired JWT | `{"error":"Unauthorized"}` |
| `403` | Authenticated but **not premium** | `{"error":"PREMIUM_REQUIRED"}` |
| `409` | A turn is already streaming for this chat | `{"error":"IN_FLIGHT","message":"A message is already being processed for this chat"}` |
| `429` | Rate bucket exhausted (6/min stream, 30/min management) | (empty / plugin default) |
| `502` | Upstream dependency failure | `{"error":"Upstream dependency failure"}` |
| `500` | Unexpected server error | `{"error":"Internal server error"}` |

```kotlin
@Serializable
data class ErrorResponse(val error: String, val message: String? = null)
```

### Layer 2 — SSE `error` event (after the stream opened with 200)
Once you've received `200 text/event-stream`, failures arrive as an `event: error` (see [the two payload shapes](#error--terminal-two-possible-shapes--branch-on-code)): `BUDGET_EXCEEDED` (with `resetAt`) or `INTERNAL_ERROR`.

### Cheat sheet (code → user message)
```kotlin
fun coachErrorMessage(code: String): String = when (code) {
    "PREMIUM_REQUIRED" -> "The AI Coach is a premium feature."
    "IN_FLIGHT"        -> "Hang on — I'm still answering your last message."
    "INPUT_TOO_LONG"   -> "That message is too long. Please shorten it."
    "BUDGET_EXCEEDED"  -> "You've reached this month's coach limit."   // prefer the server `message` (has the reset date)
    "INTERNAL_ERROR"   -> "Something went wrong. Please try again."
    else               -> "Something went wrong. Please try again."
}
```

---

## Realtime fallback (optional)

`coach_chat` and `coach_message` are in the `supabase_realtime` publication and are RLS-scoped to the owner, so a client *can* subscribe with its Supabase session to receive the persisted assistant row if the SSE connection drops:

```kotlin
// pseudo — supabase-kotlin Realtime
supabase.realtime.channel("coach:$chatId")
    .postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
        table = "coach_message"; filter = "chat_id=eq.$chatId"
    } // render the row if you missed the SSE `token`
```
Value here is **limited**: the answer already arrives atomically in one `token`, and the assistant row is persisted just *before* `done`. Realtime only helps in the narrow window where the SSE drops between persistence and `done`. Treat it as belt-and-braces, not a primary channel; the SSE stream is the source of truth for a live turn, and `GET …/messages` is the source of truth on reload. Dedupe by message `id`.

---

## Client checklist

1. **Separate host** — point coach calls at the coach service URL, not the food-API host. (The prod coach service deploy is the last rollout step.)
2. **Premium UI gate** — only show the coach to premium users; backend returns `403 PREMIUM_REQUIRED` otherwise. Premium = a real RevenueCat `user_entitlement` (no dev bypass).
3. **New chat** — POST `/api/coach/messages` with no chatId; capture `chatId` from `chat_created`.
4. **Continue** — POST `/api/coach/chats/{chatId}/messages`; never resend history.
5. **SSE handling** — render the single `token` (or `safety.message`) as the assistant bubble; show a working indicator during `tool_*`; finish on `done` **or** `error`.
6. **Hard-block** — render `safety.message` (there is no `token` on those turns).
7. **Two error payloads on `event: error`** — branch on `code` (`BUDGET_EXCEEDED` vs `INTERNAL_ERROR`).
8. **Serialize sends per chat** — disable send until `done`/`error`; handle `409 IN_FLIGHT`.
9. **Budget** — on `BUDGET_EXCEEDED`, show the server `message` + use `resetAt`.
10. **List / open** — `GET /api/coach/chats` and `GET /api/coach/chats/{id}/messages`.
11. **Delete** — `DELETE /api/coach/chats/{id}` (204/404). Notes via `GET`/`DELETE /api/coach/notes`.
12. **Rate limits** — handle `429` on streaming (6/min) and management (30/min) with a wait toast.
13. **Don't hardcode model strings** — display `done.model` as-is.
14. **Account delete** — handled by the existing food-API `DELETE /api/account` (it cascades `coach_*` + `user_entitlement`); no separate coach call needed.

---

## Reference: backend files (for verifying a field)

| What | File |
|---|---|
| SSE event DTOs | `src/main/kotlin/com/zenthek/coach/stream/SseProtocol.kt` |
| Streaming routes + turn flow + error events | `src/main/kotlin/com/zenthek/coach/routes/ChatRoutes.kt` |
| Chat management + notes routes | `…/coach/routes/ChatRoutes.kt`, `…/coach/routes/NotesRoutes.kt` |
| Chat/message/notes row shapes | `…/coach/persistence/ChatGateway.kt`, `…/coach/persistence/NotesGateway.kt` |
| Premium gate (entitlement + lazy RC sync) | `…/coach/auth/PremiumGate.kt` |
| Rate-limit config + wiring | `…/coach/CoachApplication.kt` |
| HTTP error → status mapping | `src/main/kotlin/Application.kt` (`configureStatusPages`) |
| Model ids + budget caps | `…/coach/config/CoachModels.kt` |

---

## Verification (suggested client smoke tests)

1. **New chat happy path** — POST `/api/coach/messages` ("protein target on a cut?"). Expect `chat_created` → maybe `tool_*` → one `token` with a coherent answer → maybe `citation`/`title` → `done` with non-zero tokens and a model id.
2. **Continue** — POST to `/api/coach/chats/{chatId}/messages`; confirm context carries over and no `chat_created` fires.
3. **History reload** — `GET /api/coach/chats` then `GET …/messages`; confirm your user + assistant bubbles round-trip in order.
4. **Hard-block** — send an ED-trigger (e.g. contains "anorexia") with `locale="es"`; expect a `safety` event with a Spanish helpline message, then `done` (no `token`).
5. **In-flight** — fire two sends to the same chat rapidly; expect the second to `409 IN_FLIGHT`.
6. **Budget** — (dev) seed the user to the cap, send; expect `error` `BUDGET_EXCEEDED` with `resetAt`, no `done`.
7. **Delete** — `DELETE /api/coach/chats/{id}` → 204; confirm it leaves the list.
