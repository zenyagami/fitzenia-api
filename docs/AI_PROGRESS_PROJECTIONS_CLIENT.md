# AI Progress Projections — Client Integration

> Cross-reference: `CLAUDE.md` (backend overview), `docs/DATABASE_SCHEMA.md` (full schema), `docs/API_SECURITY_BACKEND.md` (auth model).

This is the **complete wire contract** for the AI Progress Projections feature: every endpoint, every SSE event, every field, every error code, plus the storage layout and how to fetch images. Written so a client engineer can implement against it without reading backend code.

**Backend invariants the client must respect:**
- The backend trusts the client's premium-entitlement check. There is no server-side paywall — the client must gate UI access.
- All ladder writes happen through the backend (service-role). The client **cannot** insert/update ladder rows. Delete must go through the backend HTTP endpoint, not Supabase REST (RLS has no client DELETE policy).
- All ladder reads can happen client-side via Supabase REST + RLS + Realtime (or via the SSE stream during generation).

---

## Endpoint summary

| Method | Path | Auth | Rate-limit | Purpose |
|---|---|---|---|---|
| POST | `/api/progress/ladders/generate-stream` | Bearer (Supabase JWT) | 3 / min / user (`progress-projection`) | Multipart bytes in. SSE out: `status` → `rung` (source) → `rung` × N (projections) → `done` (or `error`). |
| DELETE | `/api/progress/ladders/{ladderId}` | Bearer (Supabase JWT) | 3 / min / user (`progress-projection`) | Sync row + blob cleanup. `204` on success, `404` if not owned / not found. |

`{ladderId}` is the UUID returned in the SSE `status` event (and stored on the rung row as `ladderId`).

A 429 response means the user hit the per-user rate bucket — show a "try again in a minute" toast.

---

## POST /api/progress/ladders/generate-stream

### Request

```
POST /api/progress/ladders/generate-stream
Authorization: Bearer <Supabase JWT>
Content-Type: multipart/form-data; boundary=...
Accept: text/event-stream
```

**Multipart fields (camelCase form names):**

| Field | Type | Required | Notes |
|---|---|---|---|
| `image` | file (JPEG or PNG) | yes | ≤ 8 MB. Use the `Content-Type: image/jpeg` or `image/png` part header. |
| `currentWeightKg` | string (Double) | no | If omitted, server uses latest `weight_entry.weight_kg`. |
| `currentBodyFatPercent` | string (Double) | no | If omitted, server uses the gatekeeper's photo-AI estimate (no `weight_entry` fallback). |
| `targetWeightKg` | string (Double) | no | If omitted, server uses `user_goal.goal_weight_kg`. |
| `targetBodyFatPercent` | string (Double) | no | If omitted, server uses `user_goal.body_fat_percent`. |

Send numbers as plain decimal strings (e.g., `"75.5"`).

### Response: Server-Sent Events

Standard SSE framing — lines `event: <name>` and `data: <json>`, each event terminated by a blank line:

```
event: status
data: {"phase":"validating","rungsTotal":4,"rungsReady":0,"ladderId":"...","resolved":null}

event: status
data: {"phase":"generating","rungsTotal":4,"rungsReady":1,"ladderId":"...","resolved":{...}}

event: rung
data: {"id":"...","ladderId":"...","stepIndex":0,"kind":"SOURCE",...}

event: rung
data: {...,"stepIndex":2,"kind":"PROJECTION",...}

event: done
data: {"ladderId":"...","rungsCount":4}
```

The connection closes after `done` or `error`. Keep-alives are not sent — if the client cares about dropped connections, subscribe to Supabase Realtime in parallel (see [Realtime fallback](#realtime-fallback)).

### Event reference

#### `status`

Emitted **at least twice**: once with `phase=validating` (right at the start, `resolved` is `null`), once with `phase=generating` (after the gatekeeper accepts and inputs are resolved). May be emitted again as rungs complete with updated `rungsReady`.

```ts
type StatusEvent = {
  phase: "validating" | "generating";
  rungsTotal: number;        // total rungs the ladder will have, including SOURCE (so 1 + numProjectionRungs)
  rungsReady: number;        // rungs already inserted (incremented as `rung` events fire)
  ladderId: string;          // UUID — store this; needed for DELETE and for Supabase queries
  resolved: ResolvedInputs | null;  // null on the first validating event; populated on the second
};

type ResolvedInputs = {
  currentWeightKg:       { value: number; source: ResolvedSource; confidence?: number | null };
  currentBodyFatPercent: { value: number; source: ResolvedSource; confidence?: number | null };
  targetWeightKg:        { value: number; source: ResolvedSource };
  targetBodyFatPercent:  { value: number; source: ResolvedSource };
};

type ResolvedSource = "request" | "weight_entry" | "user_goal" | "gatekeeper_estimate";
```

**What to do with `resolved`:** show the user a "we used these inputs" panel before the projections render. The `source` tells you whether the value came from their request, their stored measurements, their goal row, or the AI gatekeeper estimate. `confidence` is non-null only on the gatekeeper-estimated body-fat field (range 0–1).

#### `rung`

One event per inserted rung row. The **first** `rung` is always the source (step 0); the rest are projections (steps 1..N) and arrive in arbitrary completion order. Order them by `stepIndex` for display.

```ts
type RungEvent = {
  id: string;                       // rung row UUID
  ladderId: string;
  stepIndex: number;                // 0 = source/before, 1..N = projections in order from current → goal
  kind: "SOURCE" | "PROJECTION";
  projectedBodyFatPercent: number;  // for SOURCE: equals resolved currentBodyFatPercent
                                    // for PROJECTION: the body-fat % this rung represents (linearly stepped current → target)
  projectedWeightKg: number;        // same logic as above for weight
  storagePath: string | null;       // bucket-relative path; null only when status=FAILED
  status: "PENDING" | "SUCCEEDED" | "FAILED";
  failureCode: string | null;       // e.g. "moderation_block", "storage_upload" (per-rung failure, ladder may still succeed)
};
```

#### How to know which photo represents which body-fat %

**Every rung carries its own labels.** Read `projectedBodyFatPercent` and `projectedWeightKg` directly off each rung row — no math on the client. The linear interpolation between current and target is computed server-side at write time.

**Concrete example.** User has resolved current 28% / 85 kg → target 15% / 75 kg, with `numRungs = 3`. The backend writes 4 rungs (1 source + 3 projections):

| stepIndex | kind | projectedBodyFatPercent | projectedWeightKg | storagePath | What to show |
|---|---|---|---|---|---|
| 0 | `SOURCE` | 28.0 | 85.0 | `{userId}/ladders/{ladderId}/0.jpg` | "Now — 28% · 85 kg" (user's actual photo) |
| 1 | `PROJECTION` | 24.7 | 81.7 | `…/1.jpg` | "24.7% · 81.7 kg" |
| 2 | `PROJECTION` | 21.3 | 78.3 | `…/2.jpg` | "21.3% · 78.3 kg" |
| 3 | `PROJECTION` | 18.0 | 75.0 | `…/3.jpg` | "Goal — 18% · 75 kg" |

> Note: the final projection rung's `projectedBodyFatPercent` may sit slightly above the user's stated target if the linear step doesn't land exactly on it. The "goal" framing is a UX convention — the truth is on each rung. If you want the literal target, read `target_body_fat_percent` off the ladder row.

**For a list view** ("here are your past ladders"), you don't need to read rungs at all — the ladder row carries the endpoints:

| ladder column | meaning |
|---|---|
| `base_body_fat_percent`, `base_weight_kg` | the "from" point (resolved current values) |
| `target_body_fat_percent`, `target_weight_kg` | the "to" point |
| `body_fat_source` | `"request"` or `"gatekeeper_estimate"` — show "estimated from photo" badge if needed |
| `num_steps` | count of projection rungs (excludes source). Total rungs = `num_steps + 1`. |
| `status` | `"PENDING"` \| `"RUNNING"` \| `"SUCCEEDED"` \| `"FAILED"` |

So the list card can render `28% → 15%` (or `85kg → 75kg`) by reading just the ladder row.

**Per-rung failure.** A `rung` with `status="FAILED"` and `storagePath=null` is most commonly an OpenAI moderation block on that specific projection. The ladder as a whole may still succeed — show a placeholder for that step.

#### `done`

Emitted once when the ladder finishes successfully (≥ ⌈N/2⌉ projection rungs succeeded).

```ts
type DoneEvent = { ladderId: string; rungsCount: number };
```

#### `error`

Emitted once on terminal failure. Connection closes after.

```ts
type ErrorEvent = { message: string; code: ErrorCode };
```

`ErrorCode` (string enum — show a tailored UI per code):

| Code | Meaning | Suggested UX |
|---|---|---|
| `UPLOAD_TOO_LARGE` | Image > 8 MB. | "Photo is too large — please pick a smaller one." |
| `UNSUPPORTED_MIME_TYPE` | Image is not JPEG/PNG. | "Use a JPEG or PNG photo." |
| `MISSING_CURRENT_WEIGHT` | No `currentWeightKg` provided AND no `weight_entry` row found. | Prompt user to log weight. |
| `MISSING_CURRENT_BODY_FAT` | No `currentBodyFatPercent` provided AND gatekeeper couldn't estimate from the photo. | Prompt user to enter body-fat % manually. |
| `MISSING_TARGET_WEIGHT` | No `targetWeightKg` provided AND no `user_goal.goal_weight_kg`. | Prompt user to set a goal. |
| `MISSING_TARGET_BODY_FAT` | No `targetBodyFatPercent` provided AND no `user_goal.body_fat_percent`. | Prompt user to set a body-fat goal. |
| `INPUT_RESOLUTION_FAILED` | Internal error during input resolution. | Generic retry. |
| `GATEKEEPER_UNAVAILABLE` | Gatekeeper call failed (timeout / upstream error). | Retry. |
| `GATEKEEPER_REJECTED` | Photo not acceptable (not a body photo, multiple people, NSFW, low quality, face hidden, etc.). | "We can't use this photo — try a clear, front-facing full-body shot." |
| `SOURCE_UPLOAD_FAILED` | Could not write source bytes to storage. | Retry. |
| `MODERATION_BLOCKED` | OpenAI's safety classifier rejected the photo (typically minimal-clothing body shots flagged as `sexual`). All / most projection rungs failed with a moderation block. The ladder is persisted as `FAILED` with `failure_code="moderation_block"` — a retry of the same photo will return this same code from cache. | "This photo was flagged. Try one wearing athletic clothing (shorts + t-shirt, sports bra + shorts)." |
| `GENERATION_FAILED` | More than half of projection rungs failed for a non-moderation reason (OpenAI 5xx, timeout, etc.). | "Something went wrong — please try again." |
| `CACHE_RACE_LOST` | Edge case during concurrent insert. | Retry — should resolve. |
| `INTERNAL_ERROR` | Unhandled exception in the route. | Generic retry. |

### Cache hits

If the user re-issues the **same photo bytes + same resolved targets**, the backend returns the cached ladder immediately:
- `status` (with full `resolved` payload, `phase="generating"`)
- `rung` × N+1 (in `step_index` order, all `status="SUCCEEDED"`)
- `done`

No OpenAI calls, no waiting. The client's UX should be identical for cache-hit and fresh paths.

---

## DELETE /api/progress/ladders/{ladderId}

```
DELETE /api/progress/ladders/<uuid>
Authorization: Bearer <Supabase JWT>
```

| Status | Meaning |
|---|---|
| `204` | Deleted. Storage blobs and DB rows are gone. |
| `404` | Not owned by caller, or doesn't exist. (Same code on purpose — don't reveal existence.) |
| `401` | Missing / invalid JWT. |
| `429` | Rate-limited. |
| `502` | Storage delete failed mid-flight. **Safe to retry** — the endpoint is idempotent (already-gone blobs are tolerated). |

**Why this is the only delete path:** Postgres RLS on `ai_progress_ladder` and `ai_progress_ladder_rung` has no client `DELETE` policy. A direct `supabase.from('ai_progress_ladder').delete()` from the client will fail. This is intentional — going through the HTTP endpoint guarantees the storage blobs are wiped before the row.

---

## Listing & reading ladders (client-side via Supabase REST)

The backend does **not** expose list / get endpoints. The client uses Supabase REST + RLS directly.

### List ladders for the current user

```ts
const { data, error } = await supabase
  .from('ai_progress_ladder')
  .select('*')
  .order('created_at', { ascending: false });
```

RLS restricts SELECT to rows where `user_id = auth.uid()`.

**`ai_progress_ladder` columns the client cares about:**

| Column | Notes |
|---|---|
| `id` | UUID; use as `ladderId` for DELETE and for joining rungs. |
| `user_id` | UUID (always `auth.uid()`). |
| `base_weight_kg`, `base_body_fat_percent` | Resolved current values — the "from" point of the ladder. |
| `target_weight_kg`, `target_body_fat_percent` | Resolved target values — the "to" point of the ladder. |
| `body_fat_source` | `"request"` or `"gatekeeper_estimate"` — where the current BF came from. |
| `step_body_fat_percent` | Linear step between current and target (informational). |
| `num_steps` | Count of projection rungs (excludes source rung). Total rungs = `num_steps + 1`. |
| `model`, `quality`, `size`, `prompt_version` | Generation parameters. |
| `status` | `"PENDING"` \| `"RUNNING"` \| `"SUCCEEDED"` \| `"FAILED"`. |
| `failure_code` | Non-null only when `status="FAILED"`. |
| `created_at`, `updated_at` | Timestamps. |
| `gatekeeper_verdict` | JSON; **the AI body-fat estimate lives here**. See below. |

### Rungs in step order

```ts
const { data, error } = await supabase
  .from('ai_progress_ladder_rung')
  .select('*')
  .eq('ladder_id', ladderId)
  .order('step_index', { ascending: true });
```

`step_index = 0` is always the source/before; `1..num_steps` are projections, in order from current toward goal.

**Each row maps 1:1 to the `RungEvent` fields above** (snake_case in the DB, camelCase over SSE — pick whichever matches your client). `storage_path` is the bucket-relative path; `kind` is `'SOURCE'` or `'PROJECTION'`.

### Gatekeeper verdict (optional read)

Stored as JSONB in `ai_progress_ladder.gatekeeper_verdict`. Useful for debugging or showing the user why a photo was rejected. Shape:

```ts
type GatekeeperVerdict = {
  isAcceptable: boolean;
  rejectionReasons: GatekeeperRejectionReason[];
  confidence: number;                         // 0..1
  estimatedBodyFatPercent: number | null;     // the photo-AI BF estimate
  estimatedBodyFatConfidence: number | null;  // 0..1
  estimatedBodyFatNotes: string | null;
  model: string;                              // e.g. "gemini-2.5-flash-lite"
};

type GatekeeperRejectionReason =
  | "NOT_BODY_PHOTO"
  | "NOT_FRONT_FACING"
  | "MULTIPLE_PEOPLE"
  | "MINOR_DETECTED"
  | "NSFW"
  | "TOO_LOW_QUALITY"
  | "FACE_NOT_VISIBLE";
```

The verdict is persisted even on rejection (the ladder row exists with `status=FAILED`); a successful ladder also keeps it for debugging.

---

## Fetching the images

**Bucket:** `ai-progress-ladders` (private — `public=false`).

**Path layout:** `{userId}/ladders/{ladderId}/{stepIndex}.jpg` (the source rung is `0.jpg`, projections are `1.jpg`, `2.jpg`, …).

**RLS allows authenticated users to SELECT (read) blobs where `(storage.foldername(name))[1] = auth.uid()`.** That means the user can read their own files but not others'.

### Mint a signed URL (recommended)

```ts
const { data, error } = await supabase
  .storage
  .from('ai-progress-ladders')
  .createSignedUrl(rung.storage_path, 3600); // 1h expiry

const url = data?.signedUrl; // <img src={url} />
```

`storage_path` (DB) and `storagePath` (SSE) are the **bucket-relative path** — pass them directly. Do not prepend the bucket name.

### Or use a download with the user's session

```ts
const { data, error } = await supabase
  .storage
  .from('ai-progress-ladders')
  .download(rung.storage_path);
// data is a Blob
```

This works because the user's JWT is on the request and RLS lets them read their own files.

---

## Realtime fallback

Both `ai_progress_ladder` and `ai_progress_ladder_rung` are in the `supabase_realtime` publication. If the SSE connection drops mid-generation, the client can subscribe and pick up where it left off:

```ts
const channel = supabase
  .channel(`ladder:${ladderId}`)
  .on(
    'postgres_changes',
    { event: 'INSERT', schema: 'public', table: 'ai_progress_ladder_rung', filter: `ladder_id=eq.${ladderId}` },
    (payload) => { /* render the new rung */ },
  )
  .on(
    'postgres_changes',
    { event: 'UPDATE', schema: 'public', table: 'ai_progress_ladder', filter: `id=eq.${ladderId}` },
    (payload) => { /* watch status flip to SUCCEEDED / FAILED */ },
  )
  .subscribe();
```

For best resilience, **always subscribe to realtime in parallel with the SSE stream** — treat realtime as the authoritative truth source and SSE as the fast path for UI updates. Either feed renders the same DTO; dedupe by `id`.

---

## Client checklist (what to verify)

1. **Premium gate** — the user is on a premium plan before showing the "Generate projections" button. Backend trusts you.
2. **Image picker** — JPEG or PNG, ≤ 8 MB. Reject on the client before uploading.
3. **Body-comp form** — all four fields are optional from the client's POV. If left blank, the backend resolves them from the user's existing data. Surface the `resolved` payload on the result so the user knows what was used.
4. **SSE handling** — listen for `status` (twice), `rung` (×`rungsTotal`), then `done` or `error`. Tear down the connection on either terminal event.
5. **Render order** — sort rungs by `stepIndex`. Show the source first as "before" and the highest-step projection as "goal".
6. **Per-rung labels** — read `projectedBodyFatPercent` and `projectedWeightKg` directly off each rung; do not compute on the client.
7. **Image source** — `storagePath` from the rung, mint a signed URL via Supabase SDK, render as an `<img>` (or platform equivalent).
8. **Per-rung failure** — render a placeholder for any rung where `status="FAILED"` (`storagePath` will be null). The whole ladder may still be `SUCCEEDED` — that's fine.
9. **Listing past ladders** — `supabase.from('ai_progress_ladder').select(...).order('created_at')` + `eq('status', 'SUCCEEDED')` if you only want shippable ones. The list card can render `base_body_fat_percent → target_body_fat_percent` directly off the ladder row.
10. **Deleting** — fetch `DELETE /api/progress/ladders/{id}`. Do **not** use `supabase.from(...).delete()` — it will fail under RLS.
11. **Rate limit** — handle `429` on both POST and DELETE with a "wait a bit" toast.
12. **Cache hit UX** — same photo + same targets returns instantly. No special handling needed; rungs just arrive immediately.
13. **Account delete** — already covered by the existing `DELETE /api/account` endpoint; no changes needed on the client side. The backend wipes both the `progress-photos` and `ai-progress-ladders` buckets plus all DB rows.

---

## Reference: error code → user message cheat sheet

```ts
const ERROR_MESSAGES: Record<ErrorCode, string> = {
  UPLOAD_TOO_LARGE:        "That photo is too large. Please pick one under 8 MB.",
  UNSUPPORTED_MIME_TYPE:   "Please use a JPEG or PNG photo.",
  MISSING_CURRENT_WEIGHT:  "We need your current weight. Add a weight entry or include it in the request.",
  MISSING_CURRENT_BODY_FAT:"We couldn't read your body-fat % from the photo. Please enter it manually.",
  MISSING_TARGET_WEIGHT:   "Set a goal weight before generating projections.",
  MISSING_TARGET_BODY_FAT: "Set a goal body-fat % before generating projections.",
  GATEKEEPER_REJECTED:     "We can't use this photo. Please use a clear, front-facing full-body shot.",
  MODERATION_BLOCKED:      "This photo was flagged by our image partner. Try one wearing athletic clothing (shorts + t-shirt, sports bra + shorts).",
  GATEKEEPER_UNAVAILABLE:  "Couldn't validate the photo right now. Please try again.",
  SOURCE_UPLOAD_FAILED:    "Couldn't save the photo. Please try again.",
  GENERATION_FAILED:       "Something went wrong while generating. Please try again.",
  INPUT_RESOLUTION_FAILED: "Something went wrong reading your data. Please try again.",
  CACHE_RACE_LOST:         "Please try that again.",
  INTERNAL_ERROR:          "Unexpected error. Please try again.",
};
```

---

## Critical files referenced (for client engineers verifying a field)

| What | File |
|---|---|
| SSE event DTOs | `src/main/kotlin/com/zenthek/model/AiProgressLadderEntities.kt` |
| Route + multipart parsing + SSE emission | `src/main/kotlin/com/zenthek/routes/Routing.kt` (search for `/api/progress/ladders`) |
| Error code emission sites | `src/main/kotlin/com/zenthek/service/AiProgressProjectionService.kt` |
| Bucket policy | `docs/migrations/20260501000002_create_progress_storage_buckets.sql` |
| Ladder + rung tables + realtime publication | `docs/migrations/20260501000001_create_ai_progress_ladder.sql` |
| Gatekeeper verdict shape | `src/main/kotlin/com/zenthek/ai/ProgressGatekeeperClient.kt` |

---

## Verification (suggested smoke tests on the client side)

1. **Happy path** — pick a real front-pose photo, fill all four fields, hit generate. Expect: `status` (validating) → `status` (generating, with resolved) → `rung` (source, ~2–4s) → 3 more `rung` events (~60–90s total) → `done` with `rungsCount=4`. Source image displays as "before"; projections labeled with their `projectedBodyFatPercent`.
2. **Defaults path** — leave all four body-comp fields blank. Expect: `resolved` payload shows `source` values from `weight_entry` / `user_goal` / `gatekeeper_estimate`. Render the source labels accordingly.
3. **Reject path** — submit a non-body photo (pet, landscape). Expect: `error` with `code="GATEKEEPER_REJECTED"`. Show the appropriate message; no rungs render.
4. **Cache hit** — re-submit the exact same photo + same targets. Expect: SSE finishes in < 1s with all rungs already populated.
5. **Delete** — call `DELETE /api/progress/ladders/{id}` for a finished ladder. Expect 204 and the ladder/rungs disappear from list queries. Verify a follow-up `supabase.from('ai_progress_ladder').delete().eq('id', id)` from the client **fails** under RLS.
6. **Realtime parity** — during step 1, also subscribe to `ai_progress_ladder_rung` realtime INSERTs. Confirm every SSE `rung` is matched by a realtime row.
7. **Account delete** — `DELETE /api/account`. Confirm all the user's ladders + bucket prefix are gone.
