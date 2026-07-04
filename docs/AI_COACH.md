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
**cost-weighted credit budget**, and hard-blocks unsafe topics (eating-disorder / self-harm / medical /
drugs) before the LLM. Premium is enforced server-side via `user_entitlement` (RevenueCat-driven).

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
| POST | `/api/coach/messages` (new chat) · `/api/coach/chats/{id}/messages` (existing) — body takes optional `mode` (auto/fast/pro) | `coach-message` — 6/min |
| GET | `/api/coach/chats` · `/api/coach/chats/{id}/messages` | `coach-management` — 30/min |
| DELETE | `/api/coach/chats/{id}` · `/api/coach/notes/{id}` | `coach-management` — 30/min |
| GET | `/api/coach/notes` | `coach-management` — 30/min |
| GET | `/api/coach/usage` — monthly credit-usage snapshot (usage bars) | `coach-management` — 30/min |
| POST | `/api/coach/purchases/sync` — force-sync RevenueCat subscriber (restore purchases; grants missed top-ups), returns fresh usage | `coach-management` — 30/min |
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
3. **Budget reserve** (`coach_budget_reserve`, keyed on the per-turn `requestId`) — reserves a
   cost-weighted credit estimate (plan-dependent cap; `mode=pro` reserves Pro-weighted). `BUDGET_EXCEEDED`
   `error` event with `resetAt` + `plan` if the cap is hit. Runs *after* safety so blocks aren't charged.
4. **Pre-fetch context** every turn: `getUserProfile`, `getUserGoal`, `getCurrentTargets`,
   `getTodayMacros`, `getWeightTrend`; notes injected first turn only. RAG: `HybridRetriever` →
   `<kb_context>` block.
5. **LLM** — `CoachAgentFactory` calls `PromptExecutor.executeStreaming()` against the Gemini
   OpenAI-compat endpoint (Flash Lite). Tool loop ≤ 5 iterations; read tools forward the caller's
   bearer token so Supabase RLS enforces ownership. `tool_start`/`tool_done` SSE events stream live.
6. **Escalation** — retry on the Pro model when Flash Lite emits `<<NEEDS_ESCALATION>>`, on
   `finish_reason=length`, on > 3 tool calls, or `COMPLEX_REASONING`. Pro pass runs READ_ONLY tools.
   `mode=fast` suppresses this (Lite answers as-is); `mode=pro` skips the Lite pass and runs Pro directly.
7. **Output sanitize** (`OutputSanitizer`) — strips leaked prompt / dosage / diagnosis / URLs and the
   raw `(KB: …)` markers; one stricter retry then a generic fallback. The whole cleaned answer is
   emitted as **one buffered `token` event** (not incremental), then `citation` events.
8. **Persist + reconcile** — assistant `coach_message` (Lite+Pro token segments, model, escalated,
   citations) + `coach_trace`; `coach_budget_reconcile` with per-segment actuals → weighted credits
   (`release`/`exempt` on cancel/block).
9. First turn only: fire-and-forget title generation → `title` event + `coach_chat.title`.

Long chats are auto-compacted (`ConversationCompactor`, 8k–16k window → `coach_summary`).
System prompt: `coach/agent/SystemPromptV1.kt` (`CoachPromptVersion` — currently **v5**, "Fitzy").

---

## Data model

Applied to **dev** (`tpslgveyjldykkkhnifs`) and **prod** (`anqvtpesmddllplyhkrc`). Baseline:
**`db/migrations/005_coach_baseline.sql`** (12 tables, RLS, realtime); the cost-weighted credit
budget extends it via **`006`–`011`** (see Token budget & economics).

- **`public`** (RLS: self-`select` only; all writes service-role): `coach_chat`, `coach_message`
  (+ `pro_input_tokens`/`pro_output_tokens` segment columns), `coach_user_note`, `coach_summary`,
  `coach_trace`, `coach_budget` (+ `credits_used`/`pro_credits_used`), `coach_credit_topup`
  (purchased packs, self-`select`), `coach_kb_doc`, `coach_kb_chunk`, and the backend-wide
  `user_entitlement` (+ `is_trial`).
- **`coach_internal`** (no client access; service-role only): `coach_turn` (per-request idempotency +
  turn lease), `processed_revenuecat_event` (webhook idempotency), `coach_budget_reservation`
  (+ credit-draw bookkeeping: `reserved_credits`, `monthly_credits`, `topup_draws`, `cap_credits`).
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

## Token budget & economics (cost-weighted "Coach Credits")

The monthly budget is **cost-weighted credits**, not a flat message count — a turn answered by the
Pro model costs proportionally more, so Lite-only users get many messages while Pro usage drains the
pool faster (like Claude's usage model). Migrated from the old message counter via
`db/migrations/006–011` (schema on top of `005_coach_baseline.sql`).

**Credit model.** 1 credit = 1 Lite input token ($0.25 / 1M). Gemini prices Pro
(`gemini-3.5-flash`) at exactly 6× Lite (`gemini-3.1-flash-lite`) on both sides, so the weights are
exact integers:

| Token kind | Price / 1M | Weight |
|---|---|---|
| Lite input | $0.25 | ×1 |
| Lite output | $1.50 | ×6 |
| Pro input | $1.50 | ×6 |
| Pro output | $9.00 | ×36 |
| Cached input | 25% of input | ×0.25 (Lite) — SQL-ready; Kotlin passes 0 (koog 1.0.0 exposes no cached count) |

An **escalated turn charges both segments** (Lite pass + Pro retry ≈ 7× a Lite turn — the real cost).
The authoritative weighting lives in **one SQL function** `coach_internal.budget_credits(lite_in,
lite_out, pro_in, pro_out, cached_in)`; Kotlin only mirrors the weights for the reserve-time estimate.

**Caps** (`CoachModels.budgetCapsFor(isTrial)`):

| Cap | Value | Worst-case COGS |
|---|---|---|
| Premium monthly | **2,200,000 credits** | ~$0.55 ≈ €0.51/user/mo (~25% of worst-case net revenue) |
| Trial | **275,000 credits** (12.5%) | ~€0.06/trial |
| Message backstop | 1,000 msgs/mo | abuse guard only — never binds in normal use |

**Reserve → reconcile.** `coach_budget_reserve` (8-arg overload) reserves a **Lite-weighted**
estimate at turn start (`mode=pro` reserves Pro-weighted); it draws the monthly pot first, then
purchased top-up packs (oldest non-expired first), recording the exact split on the reservation.
`coach_budget_reconcile` (6-arg, per-segment) at turn end **refunds the reserved draws and re-draws
the actual weighted cost** in one transaction — it **never rejects** (worst overshoot ≈ 267k credits
for one fully-escalated max turn — 20k-token input cap × (1 + 6) + 1024 Lite-out × 6 + 4096 Pro-out
× 36, minus the Lite-weighted reserve estimate; ~$0.067 one-time, accepted). Pro-segment credits
also feed the monotonic
`coach_budget.pro_credits_used` display counter. `release`/`exempt` (cancel/hard-block) refund the
recorded split exactly. All keyed on `request_id`, advisory-locked, `greatest(0,…)`-clamped.
`coach_release_stale_budget_reservations` settles orphaned reservations, deriving Lite/Pro segments
from `coach_message.pro_input_tokens/pro_output_tokens` (legacy escalated rows → charged all-Pro,
conservative). Retention: `coach-retention` Job hard-deletes archived chats older than 12 months.

**Model selector.** `SendMessageRequest.mode` = `auto` (default — Lite with automatic Pro
escalation) · `fast` (Lite only; escalation triggers ignored *and* the `<<NEEDS_ESCALATION>>`
self-signal removed from the prompt so Lite always answers) · `pro` (straight to Pro, ~6× the credit
burn). Bad value → `400 INVALID_MODE`; the mode is echoed on the `done` event.

**Top-ups.** RevenueCat consumables (product `coach_credits_5m` → 5,000,000 credits, ~€5). Granted
idempotently on `rc_transaction_id` via `coach_grant_credit_topups`, driven from the live subscriber
snapshot in `RevenueCatSyncService` (never the event body), env-gated by `is_sandbox`. **12-month
expiry**, enforced solely by the reserve/reconcile draw filter — consumed only after the monthly pot,
never touched by the monthly reset. Purchased credits are **not** money-denominated to the user; the
recommended display is estimated messages remaining, or a separate "purchased credits" bar.

**Economics — what a message costs** (typical turn ≈ 10k input incl. system prompt + RAG + history,
600 output):

| Turn type | Credits | Messages/mo at cap |
|---|---|---|
| Light Lite (6k/400) | 8,400 | ~261 |
| Typical Lite (10k/600) | 13,600 | ~161 |
| Heavy Lite (15k/800) | 19,800 | ~111 |
| Pure Pro (`mode=pro`) | 81,600 | ~26 |
| Escalated (Lite + Pro retry) | 95,200 | ~23 |

**Margin** (worst case = every subscriber maxes the cap; yearly €30 = €2.50/mo gross, monthly €4.50):

| Plan / store fee | Net €/mo | Worst-case margin | At ~40% avg utilization |
|---|---|---|---|
| Yearly €30 @ 15% | 2.13 | 1.62 (76%) | 1.92 |
| Yearly €30 @ 30% | 1.75 | 1.24 (71%) | 1.55 |
| Monthly €4.50 @ 15% | 3.83 | 3.32 (87%) | 3.62 |
| Monthly €4.50 @ 30% | 3.15 | 2.64 (84%) | 2.95 |

Even the doomsday cohort (30% fee, yearly, 100% of subscribers maxing out) keeps ~71% of net revenue
for the other AI features. Escalation just drains the pool faster — it can't blow the cap.

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
  coach service. `requirePremium` returns the caller's `CoachPlan` (PREMIUM | TRIAL) from the same
  query, selecting the credit cap.
- **Trial detection:** RevenueCat `period_type == "trial"` → `user_entitlement.is_trial` (via the
  reconcile RPC), driving the reduced trial credit cap.
- **Credit top-ups:** consumable purchases in the subscriber snapshot (`non_subscriptions`) map to
  `coach_credit_topup` grants — same sync path, idempotent on `rc_transaction_id`. Grants are
  env-gated (`is_sandbox` must match the sync environment) so free sandbox purchases can't mint real
  credits in prod.
- **⚠️ Top-up delivery gap + fix:** an already-premium user never triggers lazy-sync-on-miss (they
  don't miss the gate), so a top-up reaches them **only via the webhook**. If the webhook is dropped,
  there's no self-heal. The **`POST /api/coach/purchases/sync`** endpoint closes this: it force-syncs
  the subscriber (reconcile entitlements + grant missed top-ups) on demand. Client must call it after
  a purchase completes and on coach-screen open. The `rc-sweeper` only retries *received* events — it
  can't recover a *never-delivered* webhook, which is why the sync endpoint exists.

---

## Models & config

- **Model IDs are code constants** in `com.zenthek.coach.config.CoachModels` (not env): primary
  `gemini-3.1-flash-lite`, escalation `gemini-3.5-flash`, plus `USER_TZ_FALLBACK`. Embeddings
  `gemini-embedding-2`. Same object holds the **budget constants** (`CAP_CREDITS_PER_MONTH=2_200_000`,
  `CAP_CREDITS_TRIAL=275_000`, `CAP_MESSAGES_PER_MONTH=1_000` backstop, credit weights) and the
  **top-up product map** (`TOPUP_PRODUCT_CREDITS = {"coach_credits_5m": 5_000_000}`) — all code-review-
  gated, no env flips.
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

## Status (2026-07-04)

The coach feature is live in production. Current state:

- Base coach schema (`005_coach_baseline.sql`) applied to dev + prod; parity verified.
- **Cost-weighted credit budget** (migrations `006`–`011`) — **shipped 2026-07-04.** All migrations
  applied to dev + prod; both services redeployed (`fitzenia-coach-prod` rev 00004, `fitzenia-api-prod`
  rev 00034). `011` cleanup applied to prod — only the credit-weighted RPC signatures remain
  (`cents_estimated` and the legacy 7-arg reserve / 3-arg reconcile overloads dropped; the stale
  sweeper re-pointed onto the 6-arg reconcile). Prod DB verified.
- Lazy sync-on-miss + trial detection + credit top-ups implemented in `PremiumGate` / `RevenueCatSyncService`.
- Prod KB corpus ingest complete (51 docs / 409 chunks, 0 null embeddings).
- Coach service, jobs, schedulers, secrets, and the RevenueCat webhook are managed through the runbook above.
- Observability (Langfuse/BigQuery) remains a fast-follow enhancement, not a feature blocker.

---

## Key files

| Concern | Path |
|---|---|
| Coach module wiring | `src/main/kotlin/com/zenthek/coach/CoachApplication.kt` |
| Streaming routes + turn flow | `…/coach/routes/ChatRoutes.kt`; management/notes: `…/coach/routes/NotesRoutes.kt`; usage: `…/coach/routes/UsageRoutes.kt` |
| Model selector + period helpers | `…/coach/routes/CoachMode.kt`, `…/coach/routes/BudgetPeriod.kt` |
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
| Schema | `db/migrations/005_coach_baseline.sql` (baseline) + `006`–`011` (cost-weighted credits) |
| Manual-test surface | `tools/coach-tester.html`, `tools/rc-webhook-tester.sh` |
