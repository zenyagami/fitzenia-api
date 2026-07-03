# AI Coach — Architecture & Operations

> **Scope.** This is the single living reference for the **AI Coach** feature ("Fitzy") — how it's
> built, deployed, and operated *today*. It replaces the old frozen design spec (`AI_COACH_BACKEND.md`)
> and the build tracker (`AI_COACH_MILESTONES.md`), both removed 2026-07-03 (history is in git).
>
> **Companion docs:** [`AI_COACH_CLIENT.md`](AI_COACH_CLIENT.md) — the exact client wire contract
> (endpoints, SSE events, error model). [`ENTITLEMENTS.md`](ENTITLEMENTS.md) — the backend-wide premium
> entitlement system. [`DATABASE_SCHEMA.md`](DATABASE_SCHEMA.md) — full schema. Coach schema itself:
> [`db/migrations/005_coach_baseline.sql`](../db/migrations/005_coach_baseline.sql).

---

## What it is

A premium, chat-style AI fitness coach ("Fitzy"), running as a **separate Cloud Run service** from the
food API — same Kotlin/Ktor codebase, different Jib image + module. It answers nutrition/training
questions grounded in a curated knowledge base **and** the user's own logged data (weight, targets,
today's macros, diary), streams answers over SSE, remembers cross-chat preferences, enforces a monthly
token budget, and hard-blocks unsafe topics (eating-disorder / self-harm / medical / drugs) before the
LLM. Premium is enforced server-side via `user_entitlement` (RevenueCat-driven).

Boots via `coach.conf` → `com.zenthek.coach.CoachApplicationKt.module` (not the food-API module).

---

## Services & build targets

One Gradle module, selected by `-PtargetService=`; each builds a distinct Jib image.

| Target | Main | Prod image (`gcr.io/fitzenio/…`) | Kind | Deploy / config |
|---|---|---|---|---|
| `coach` | `EngineMain` + `coach.conf` | `fitzenia-coach-prod` | Service | `deploy-coach.sh` · `cloud-run-config.coach.yaml` |
| `coach-ingest` | `com.zenthek.coach.ingest.CoachIngestMain` | `fitzenia-coach-ingest` | Job | `docs/DEPLOY.md` · corpus embed → KB |
| `coach-retention` | `com.zenthek.coach.retention.CoachRetentionSweeperMain` | `fitzenia-coach-retention` | Job | `deploy-coach-retention.sh` · `cloud-run-job-coach-retention.yaml` |
| `coach-rc-sweeper` | `com.zenthek.revenuecat.RevenueCatSweeperMain` | `fitzenia-coach-rc-sweeper` | Job | `deploy-coach-rc-sweeper.sh` · `cloud-run-job-coach-rc-sweeper.yaml` |

Dev variants: `deploy-coach-dev.sh` + `cloud-run-config.coach.dev.yaml` (`fitzenia-coach-dev`, project
`fitzenio-debug`). Local run: **`./gradlew runCoach`** (a `JavaExec` forcing `config.resource=coach.conf`;
plain `./gradlew run` still boots the food API). Service sizing: `minScale:0`, `maxScale:20`, `cpu:2`,
`memory:1Gi`, `cpu-throttling:false`, `timeoutSeconds:300`, `containerConcurrency:20`.

**The RevenueCat webhook (`POST /webhooks/revenuecat`) lives on the main `fitzenia-api` service, not
the coach service** — see Entitlements below.

---

## Endpoints & rate limits

All under `authenticate(SUPABASE_AUTH_PROVIDER)` (same Supabase JWT as the food API) except `/health`.
Full request/response + SSE shapes are in [`AI_COACH_CLIENT.md`](AI_COACH_CLIENT.md).

| Method | Path | Bucket (per user) |
|---|---|---|
| POST | `/api/coach/messages` (new chat) · `/api/coach/chats/{id}/messages` (existing) | `coach-message` — 6/min |
| GET | `/api/coach/chats` · `/api/coach/chats/{id}/messages` | `coach-management` — 30/min |
| DELETE | `/api/coach/chats/{id}` · `/api/coach/notes/{id}` | `coach-management` — 30/min |
| GET | `/api/coach/notes` | `coach-management` — 30/min |
| GET | `/health` | public |

`PremiumGate.requirePremium` gates every `/api/coach/**` route → `403 PREMIUM_REQUIRED` for
non-entitled users. Single-flight per `(userId, chatId)` → `409 IN_FLIGHT`.

---

## The turn (request flow)

`ChatRoutes.processMessage` per message:

1. **Auth + premium gate** (`PremiumGate`), then **input sanitize** (`InputSanitizer`).
2. **Safety hard-block** (`HardBlockClassifier`, regex/heuristic) — ED / self-harm / medical / drug →
   emit a `safety` SSE event with the locale-aware canned helpline text, persist `safety_action`,
   **skip the LLM, not budget-charged**, then `done`.
3. **Budget reserve** (`coach_budget_reserve`, keyed on the per-turn `requestId`) — `BUDGET_EXCEEDED`
   `error` event with `resetAt` if the monthly cap is hit. Runs *after* safety so blocks aren't charged.
4. **Pre-fetch context** every turn: `getUserProfile`, `getUserGoal`, `getCurrentTargets`,
   `getTodayMacros`, `getWeightTrend`; notes injected first turn only. RAG: `HybridRetriever` →
   `<kb_context>` block.
5. **LLM** — `CoachAgentFactory` calls `PromptExecutor.executeStreaming()` against the Gemini
   OpenAI-compat endpoint (Flash Lite). Tool loop ≤ 5 iterations; read tools forward the caller's
   bearer token so Supabase RLS enforces ownership. `tool_start`/`tool_done` SSE events stream live.
6. **Escalation** — retry on the Pro model when Flash Lite emits `<<NEEDS_ESCALATION>>`, on
   `finish_reason=length`, on > 3 tool calls, or `COMPLEX_REASONING`. Pro pass runs READ_ONLY tools.
7. **Output sanitize** (`OutputSanitizer`) — strips leaked prompt / dosage / diagnosis / URLs and the
   raw `(KB: …)` markers; one stricter retry then a generic fallback. The whole cleaned answer is
   emitted as **one buffered `token` event** (not incremental), then `citation` events.
8. **Persist + reconcile** — assistant `coach_message` (tokens, model, escalated, citations) +
   `coach_trace`; `coach_budget_reconcile` with actuals (`release`/`exempt` on cancel/block).
9. First turn only: fire-and-forget title generation → `title` event + `coach_chat.title`.

Long chats are auto-compacted (`ConversationCompactor`, 8k–16k window → `coach_summary`).
System prompt: `coach/agent/SystemPromptV1.kt` (`CoachPromptVersion` — currently **v5**, "Fitzy").

---

## Data model

Applied to **dev** (`tpslgveyjldykkkhnifs`) and **prod** (`anqvtpesmddllplyhkrc`, on 2026-07-03).
Canonical reference: **`db/migrations/005_coach_baseline.sql`** (12 tables, 39 functions, RLS, realtime).

- **`public`** (RLS: self-`select` only; all writes service-role): `coach_chat`, `coach_message`,
  `coach_user_note`, `coach_summary`, `coach_trace`, `coach_budget`, `coach_kb_doc`, `coach_kb_chunk`,
  and the backend-wide `user_entitlement`.
- **`coach_internal`** (no client access; service-role only): `coach_turn` (per-request idempotency +
  turn lease), `processed_revenuecat_event` (webhook idempotency), `coach_budget_reservation`.
- **Realtime:** `coach_chat` + `coach_message` are in the `supabase_realtime` publication (client
  fallback if SSE drops).
- **Idempotency key is `request_id`** (the per-turn UUID), not `message_id` — turn/budget/RC RPCs all
  key on it, advisory-locked.
- Service reaches `coach_internal` only through thin `public.coach_*` wrappers (PostgREST can't see
  `coach_internal`). Account deletion cascades via `public.delete_user_data`.

---

## Knowledge base (RAG)

- **Corpus** authored as JSON in `src/main/resources/coach/corpus/<section>/*.json` — sections `app`,
  `nutrition`, `training`, `general`, `recipes` (recipes = one chunk per recipe). Auto-discovered; no
  manifest.
- **Ingest** = the `coach-ingest` Job (`CoachIngestMain`): diff-by-content-hash, per-doc commit,
  embeddings via **`gemini-embedding-2` (768-dim, `v1`)**, `Semaphore(8)`. Writes to whichever Supabase
  `.env` points at. See `docs/DEPLOY.md` "Coach KB Ingest Job".
- **Retrieval** = `HybridRetriever` → the `search_coach_kb_hybrid` RPC: vector top-12 + pg_trgm top-12
  → RRF fuse to top-6 + section bias. Emits `citation` events; `citations` persisted on the message.

---

## Safety

- `InputSanitizer` (mirrors `AiPromptInputEscaper`) + system-prompt trust-boundary block.
- `HardBlockClassifier` → `PASS | ED_REDIRECT | MEDICAL_REDIRECT | DRUG_REDIRECT | SELF_HARM_REDIRECT |
  OFF_TOPIC | COMPLEX_REASONING`. Redirects load locale-aware canned text from
  `src/main/resources/redirects/{lang}.json` (region subtags stripped; `en,es,pt,de,fr,it,nl,pl,ja`).
- `OutputSanitizer` — dosage/drug/diagnosis/URL rejection + leaked-prompt strip, one stricter retry.
- **⚠️ Helpline numbers are machine-collected and awaiting a human verification pass before prod
  launch** (a real safety gate — see Status).

---

## Token budget & economics

Atomic monthly cap enforced **before** the LLM. `coach_budget_reserve` → `reconcile` (actuals) →
`release`/`exempt` (cancel/block), all `coach_internal` core + thin `public.coach_budget_*` wrappers,
keyed on `request_id`, `greatest(0,…)`-clamped. Caps from `CoachModels.budgetCapsFor()` =
`(400 messages, 8_409_600 tokens)` — the message cap is the binding gate, the token cap a backstop.
Stale reservations are reconciled/released by `coach_release_stale_budget_reservations` (called from the
retention path). Retention: `coach-retention` Job hard-deletes archived chats older than 12 months.

---

## Entitlements / RevenueCat

`user_entitlement` + the RC sync are **backend-wide infra**, not coach-scoped — full contract in
[`ENTITLEMENTS.md`](ENTITLEMENTS.md). Coach-relevant pieces:

- **Webhook** `POST /webhooks/revenuecat` on **`fitzenia-api`** (public route, static-header auth;
  `503` when `REVENUECAT_*` unset). Identity model: `app_user_id == auth.users.id` (app calls
  `Purchases.logIn(supabaseUserId)`). Always reconciles against `GET /v1/subscribers/{id}` (never the
  event body — webhooks arrive out of order) via `coach_reconcile_user_entitlements`.
- **`coach-rc-sweeper`** Job replays stuck/failed events (`coach_rc_claim_recoverable_events`).
- **`source` column** (`revenuecat` | `manual`) — RC reconcile only deactivates `source='revenuecat'`
  rows, so manual DB grants survive.
- **Lazy sync-on-miss:** the coach `PremiumGate`, on a no-active-row miss, does a one-time live
  `GET /v1/subscribers/{userId}` + reconcile then re-checks (negative-cached) — so existing subscribers
  who never fired a fresh webhook self-heal on first coach use. Needs `REVENUECAT_REST_API_KEY` on the
  coach service.

---

## Models & config

- **Model IDs are code constants** in `com.zenthek.coach.config.CoachModels` (not env): primary
  `gemini-3.1-flash-lite`, escalation `gemini-3.5-flash`, plus `USER_TZ_FALLBACK`. Embeddings
  `gemini-embedding-2`.
- **Config** = `coach/config/CoachConfig.kt`. Secrets only: `GEMINI_API_KEY`, `SUPABASE_URL`,
  `SUPABASE_PUBLISHABLE_KEY`, `SUPABASE_SERVICE_ROLE_KEY`, and optional `REVENUECAT_REST_API_KEY`
  (+ `REVENUECAT_REST_BASE_URL`) for lazy sync. No coach-specific model/tuning env vars.

---

## Operations / runbook

Prod operations checklist:

1. `ENV_FILE=.env.prod ./sync-secrets.sh prod` and `… ./sync-secrets.sh coach` (coach now needs
   `REVENUECAT_REST_API_KEY`). Sanity: `./check-cloud-run-env.sh prod .env.prod`.
2. `./deploy.sh` — redeploy `fitzenia-api` so the RevenueCat webhook route goes live.
3. `./deploy-coach.sh` — `fitzenia-coach-prod`.
4. `./deploy-coach-retention.sh` + `./deploy-coach-rc-sweeper.sh` + Cloud Scheduler crons
   (retention daily; rc-sweeper ~1-min).
5. Ingest the KB corpus into prod: `./gradlew run -PtargetService=coach-ingest --args="--section=<s>"`
   for all 5 sections (env pointed at prod). Verify `select section, count(*) from public.coach_kb_chunk`.
6. Register or verify the webhook in the RevenueCat dashboard (URL + `REVENUECAT_WEBHOOK_AUTH`
   header); `TEST` event → 200. Existing premium users backfill via lazy sync-on-miss.

Account deletion: the existing food-API `DELETE /api/account` cascades all `coach_*` + `user_entitlement`
via `delete_user_data` — no separate coach call.

---

## Deviations from the original plan (durable notes)

The old frozen spec assumed some things that changed during the build; recorded here so nobody
"corrects" the code back toward a stale spec:

| Area | What actually shipped |
|---|---|
| Agent framework | **Koog dropped** — direct `PromptExecutor.executeStreaming()` with `OpenAILLMClient` against the Gemini OpenAI-compat endpoint (Koog 1.0.0 had no Google client + conflicted with `request_id`-linked inserts). |
| Model IDs | Code constants in `CoachModels`, **not** env vars / the spec's model table. |
| Idempotency key | `request_id` (per-turn UUID), **not** `message_id`. |
| RC recovery | `list_stale_revenuecat_events` → two atomic `claim_stale` / `claim_recoverable` variants (`FOR UPDATE SKIP LOCKED`). |
| Safety redirects | `.json` (kotlinx.serialization), **not** `.yaml` (no YAML parser in the project). |
| SSE `token` | One **buffered whole-message** event after the output filter — **not** incremental tokens. |
| Migrations | Consolidated into `db/migrations/005_coach_baseline.sql` (the split `005–009` + a docs delete file drifted and were removed 2026-07-03). |
| pg_trgm schema | Prod installs pg_trgm in `public`, dev in `extensions` → the (unused) `coach_search_kb` uses `public.similarity` on prod; `search_coach_kb_hybrid` (the one the code calls) is schema-portable via `SET search_path = extensions, public, pg_temp`. |
| Observability | Langfuse + BigQuery cost lines **deferred** (fast-follow, not a launch blocker). |

---

## Status (2026-07-03)

The coach feature is complete and ready to operate. Current production state:

- Coach schema is applied to **prod** and dev/prod parity is verified (`005_coach_baseline.sql`).
- Lazy sync-on-miss is implemented in `PremiumGate`.
- Prod KB corpus ingest is complete (51 docs / 409 chunks across all 5 sections; matches the repo corpus, 0 null embeddings).
- Coach service, jobs, schedulers, secrets, and the RevenueCat webhook are managed through the runbook above.
- Observability (Langfuse/BigQuery) remains a fast-follow enhancement, not a feature blocker.

---

## Key files

| Concern | Path |
|---|---|
| Coach module wiring | `src/main/kotlin/com/zenthek/coach/CoachApplication.kt` |
| Streaming routes + turn flow | `…/coach/routes/ChatRoutes.kt`; management/notes: `…/coach/routes/NotesRoutes.kt` |
| SSE event DTOs | `…/coach/stream/SseProtocol.kt` |
| Premium gate (+ lazy sync-on-miss) | `…/coach/auth/PremiumGate.kt` |
| Agent + system prompt | `…/coach/agent/CoachAgentFactory.kt`, `…/coach/agent/SystemPromptV1.kt` |
| Safety | `…/coach/agent/safety/{InputSanitizer,HardBlockClassifier,OutputSanitizer}.kt`, `resources/redirects/*.json` |
| RAG | `…/coach/rag/{HybridRetriever,EmbeddingClient}.kt`; corpus `resources/coach/corpus/**` |
| Tools | `…/coach/agent/tools/{CoachToolDescriptors,CoachToolRunner}.kt` |
| Persistence / budget / compaction | `…/coach/persistence/{ChatGateway,NotesGateway,BudgetGateway}.kt`, `…/coach/compaction/ConversationCompactor.kt` |
| Models / budget caps | `…/coach/config/{CoachModels,CoachConfig}.kt` |
| RevenueCat sync | `…/revenuecat/{RevenueCatSyncService,RevenueCatRestClient,RevenueCatEntitlementGateway,RevenueCatSweeperMain}.kt`; webhook in `src/main/kotlin/Application.kt` + `routes/Routing.kt` |
| Jobs | `…/coach/ingest/CoachIngestMain.kt`, `…/coach/retention/CoachRetentionSweeperMain.kt` |
| Schema | `db/migrations/005_coach_baseline.sql` |
| Manual-test surface | `tools/coach-tester.html`, `tools/rc-webhook-tester.sh` |
