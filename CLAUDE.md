> Cross-reference `docs/food-api-backend.md` (endpoint spec), `docs/API_SECURITY_BACKEND.md` (auth/security), `docs/DATABASE_SCHEMA.md` (Supabase schema), `docs/AI_PROMPT.md` (AI prompts).

---

# CLAUDE.md — Fitzenio API

Ktor-based Kotlin/JVM service for the Fitzenio mobile app:
- **Food search & barcode lookup** — fans out to Open Food Facts + USDA, layers a shared canonical catalog (Supabase) plus Gemini-backed AI ranking/generation on top (Smart Food Search). In production, an additional local **OFF mirror** is consulted first (see "OFF mirror" below).
- **AI image analysis** — meal photo → structured nutrition payload, Gemini (primary) or OpenAI (fallback).
- **User onboarding** — registers `user_profile` / `user_goal` / `calorie_target` rows in Supabase (RLS-scoped via the caller's JWT).
- **Account deletion** — three-stage hard wipe (Storage → Postgres → Auth), service-role only.

Stateless, scales-to-zero on Google Cloud Run. Containerized with Jib (no Dockerfile).

The same Gradle module also produces two separate **Cloud Run Job** images:
- `com.zenthek.ingest.IngestMain` → `gcr.io/fitzenio/fitzenio-off-ingest` — daily/weekly OFF mirror sync. Build: `./gradlew jib -Pprod -PtargetService=ingest`.
- `com.zenthek.ingest.UsdaIngestMain` → `gcr.io/fitzenio/fitzenio-usda-ingest` — monthly USDA mirror sync (FDC bi-annual snapshots). Build: `./gradlew jib -Pprod -PtargetService=usda-ingest`.

---

## Tech stack

| Concern | Library | Notes |
|---|---|---|
| Server | Ktor 3.2.3 (Netty) | `EngineMain`, `application.conf` references `com.zenthek.fitzenio.rest.ApplicationKt.module` |
| HTTP client | Ktor 3.2.3 (CIO) | One shared instance; 15s request / 10s connect timeouts |
| Auth | `ktor-server-auth` + `ktor-server-auth-jwt` | Supabase JWT via JWKS (default) or remote `/auth/v1/user` fallback |
| Rate limit | `ktor-server-rate-limit` | Per-user buckets keyed off `AuthenticatedUserContext.userId` |
| Database | Supabase | User-scoped REST via `SupabaseClient`; service-role admin via `SupabaseAdminGateway` + `CanonicalCatalogClient` |
| AI search | Gemini 2.5 Flash Lite (rank) + Gemini 2.5 Flash (generate) | Models pinned via env, see `AI_SEARCH_*_MODEL` |
| AI image | Gemini 3.1 Flash Lite Preview (primary) / GPT-5-mini (fallback) | Selected by `USE_GEMINI` |
| Serialization | kotlinx.serialization 1.7.3 | JSON only; single shared `Json` |
| Logging | Logback 1.5.13 | Console only; `com.zenthek` at DEBUG |
| Env loading | dotenv-kotlin 6.4.1 | Always loaded with `ignoreIfMissing = true` |
| JDK / Build | 21 / Gradle Kotlin DSL + version catalog | `gradle/libs.versions.toml` |
| Container | Jib | `./gradlew jib` (dev), `./gradlew jib -Pprod` (prod) |

---

## Project structure

```
src/main/kotlin/
├── Application.kt                           # com.zenthek.fitzenio.rest — module() wiring
└── com/zenthek/
    ├── config/Environment.kt                # AppConfig, ApiKeys, SupabaseConfig, SmartSearchConfig, ConfigLoader
    ├── auth/SupabaseAuthentication.kt       # configureAuthentication + AuthenticatedUserContext + helpers
    ├── routes/Routing.kt                    # /health (public); /api/{food,user,account} authenticated + rate-limited
    ├── service/
    │   ├── FoodService.kt                   # barcode (OFF→USDA fallback) + autocomplete (OFF only)
    │   ├── SmartSearchOrchestrator.kt       # canonical-catalog-first search + AI rank/generate + write-behind
    │   ├── UserProfileService.kt            # /api/user/register + /registration-status
    │   ├── AccountService.kt                # /api/account DELETE — three-stage wipe orchestrator
    │   ├── AiProgressProjectionService.kt   # /api/progress/ladders/* — ladder generator + delete
    │   ├── QueryNormalizer.kt               # locale-aware query canonicalization
    │   ├── NutritionValidator.kt            # sanity-checks AI nutrition payloads
    │   ├── UnauthorizedException.kt         # → HTTP 401
    │   └── UpstreamFailureException.kt      # → HTTP 502
    ├── model/
    │   ├── FoodItem.kt                      # FoodItem, FoodSource (incl. CANONICAL), Smart Search responses, image DTOs
    │   ├── ImageAnalyzer.kt                 # ImageAnalyzer fun interface + ImageAnalyzerFactory (system prompt + JSON schema)
    │   ├── CanonicalCatalogEntities.kt      # canonical_food / serving / term + insert_canonical_foods RPC payload
    │   ├── UserProfile.kt                   # UserProfileEntity, UserGoalEntity, CalorieTargetEntity, register DTOs, ErrorResponse
    │   ├── AiProgressLadderEntities.kt      # ladder + rung row/insert/dto types, BodyFatSource, ResolvedLadderInputs, SSE event DTOs
    │   └── AiProgressPrompts.kt             # versioned prompt template for gpt-image-2 edits
    ├── ai/
    │   ├── AiSearchClient.kt                # interface: classify + generate
    │   ├── GeminiAiSearchClient.kt          # Gemini generateContent with strict JSON schema
    │   └── ProgressGatekeeperClient.kt      # interface for the photo gatekeeper + GatekeeperVerdict DTO
    ├── network/HttpClientProvider.kt
    ├── upstream/
    │   ├── openfoodfacts/                   # v3 product + search-a-licious search + autocomplete (country-aware fan-out)
    │   ├── usda/                            # FoodData Central search + barcode
    │   ├── fatsecret/                       # legacy — NOT wired into FoodService or SmartSearch (kept for reference)
    │   ├── supabase/
    │   │   ├── SupabaseClient.kt            # SupabaseGateway: auth user lookup + user_profile/user_goal/calorie_target REST
    │   │   ├── CanonicalCatalogGateway.kt   # service-role canonical catalog reads + insert_canonical_foods RPC
    │   │   ├── SupabaseAdminGateway.kt      # service-role storage/postgres/auth admin (uploadObject, removeStorageObjects, etc.)
    │   │   └── AiProgressLadderGateway.kt   # service-role reads/writes for ai_progress_ladder + rung tables (cache lookup, INSERT-on-conflict, ownership-checked delete)
    │   ├── gemini/
    │   │   ├── GeminiApiService.kt           # image analysis primary backend (context cache, Mutex-protected)
    │   │   └── GeminiProgressGatekeeperClient.kt  # gatekeeper for AI progress projections (gate verdict + body-fat estimate in one call)
    │   └── openai/
    │       ├── OpenAiApiService.kt           # image analysis fallback (Responses API)
    │       └── OpenAiImageEditClient.kt      # gpt-image-2 /v1/images/edits (multipart, b64_json), used by progress projections
    └── mapper/
        ├── OpenFoodFactsMapper.kt           # map() barcode + mapV3Search() with ResultKind
        ├── UsdaMapper.kt                    # mapSearchItem* with GENERIC/BRANDED kind
        └── FatSecretMapper.kt               # legacy, unused

src/main/resources/
├── application.conf                         # port 8080 (overridable via PORT)
└── logback.xml                              # console output

src/test/kotlin/com/zenthek/                 # auth, mapper, service, routes, upstream — see "Testing"
docs/                                        # food-api-backend.md, API_SECURITY_BACKEND.md, DATABASE_SCHEMA.md,
                                             # AI_PROMPT.md, migrations/
.env.example, build.gradle.kts, settings.gradle.kts
cloud-run-config.yaml, cloud-run-config.dev.yaml
deploy.sh, deploy-dev.sh, grant-secrets.sh, sync-secrets.sh, check-cloud-run-env.sh, DEPLOY.md
```

---

## Endpoints

| Method | Path | Auth | Rate-limit bucket | Purpose |
|---|---|---|---|---|
| GET | `/health` | public | — | Liveness probe |
| GET | `/credits` | public | — | Data-source attribution (ODbL for OFF, public domain for USDA) |
| GET | `/api/food/autocomplete?q=&limit=` | JWT | `food-search` | Suggestions (OFF only) |
| GET | `/api/food/search?q=&locale=&country=&page=&pageSize=` | JWT | `food-search` | Smart search (canonical + upstream + AI bestMatch) |
| GET | `/api/food/search/stream?...` | JWT | `food-search` | SSE: emits `upstream`, `bestMatch`, `done` (or `error`) |
| GET | `/api/food/barcode/{barcode}` | JWT | `food-search` | OFF → USDA barcode fallback |
| POST | `/api/food/analyze-image` | JWT | `image-analysis` | Synchronous image analysis |
| POST | `/api/food/analyze-image-stream` | JWT | `image-analysis` | SSE: `status` → `result` (or `error`) |
| POST | `/api/user/register` | JWT | — | Idempotent insert of `user_profile`/`user_goal`/`calorie_target` |
| GET | `/api/user/registration-status` | JWT | — | Returns `{isSignedIn, isRegistered}` |
| DELETE | `/api/account` | JWT | `account` | Three-stage hard wipe (storage → postgres → auth) |
| POST | `/api/progress/ladders/generate-stream` | JWT | `progress-projection` | Multipart bytes in (image + body comp). SSE: `status` → `rung` (source) → `rung` × N (projections) → `done` (or `error`) |
| DELETE | `/api/progress/ladders/{id}` | JWT | `progress-projection` | Sync row + blob cleanup (storage delete then DB delete). 204 / 404 |

---

## Authentication (Supabase JWT)

Every route except `GET /health` is wrapped in `authenticate(SUPABASE_AUTH_PROVIDER) { ... }`. `SUPABASE_AUTH_PROVIDER = "supabase-jwt"`.

`SUPABASE_JWT_VERIFICATION_MODE` (default `JWKS`):

| Mode | Behavior |
|---|---|
| `JWKS` | Local signature verification against `${SUPABASE_URL}/auth/v1/.well-known/jwks.json` (cached 10 min, rate-limited 10/min). Validates `iss = ${SUPABASE_URL}/auth/v1`, audience contains `"authenticated"`, 30s clock leeway. |
| `REMOTE` | Calls `GET ${SUPABASE_URL}/auth/v1/user` per-request. Logs a startup warning. |

JWKS reachability is probed on startup (warning only on failure).

A successful validation produces `AuthenticatedUserContext(userId, email?, name?, avatarUrl?, role)`. Validator returns `null` (→ 401) when:
- `sub` is blank
- `role` is not `"authenticated"`
- `aud` does not contain `"authenticated"`

The raw bearer token is stashed under `SupabaseAccessTokenKey` so handlers can forward it to Supabase REST for RLS-scoped queries.

**Helpers** (in `auth/SupabaseAuthentication.kt`):
- `call.requireAuthenticatedUser(): AuthenticatedUserContext` — throws `UnauthorizedException` if the principal is missing.
- `call.requireBearerAccessToken(): String` — returns the cached token or re-extracts from the header; throws `UnauthorizedException` otherwise.

**Constraints:**
- Never log JWTs / bearer tokens / service-role keys.
- Route handlers never hit Supabase auth directly — services do.
- `UnauthorizedException` → HTTP 401 via `StatusPages`. Throw it from services on upstream 401 (e.g. `SupabaseClient.fetchAuthenticatedUser`, FK-violation on stale-after-delete tokens).
- The token in `SupabaseAccessTokenKey` is per-request — never cache it.

---

## Rate limiting

`Application.configureRateLimit()` registers four buckets, all keyed on the authenticated user's `userId`. A request reaching the rate limiter without a principal throws `UnauthorizedException` (defense-in-depth — the auth wrapper should already have rejected it).

| Bucket | Constant | Limit |
|---|---|---|
| Food search / autocomplete / barcode / smart search stream | `RateLimitNames.FOOD_SEARCH` (`"food-search"`) | 200 req/min/user |
| Image analysis (sync + SSE) | `RateLimitNames.IMAGE_ANALYSIS` (`"image-analysis"`) | 20 req/min/user |
| Account deletion | `RateLimitNames.ACCOUNT` (`"account"`) | 3 req/min/user |
| AI progress projections (generate + delete) | `RateLimitNames.PROGRESS_PROJECTION` (`"progress-projection"`) | 3 req/min/user |

Adding a route to an existing bucket: drop it into the matching `rateLimit(...) { ... }` block in `routes/Routing.kt`. New bucket → register in both `configureRateLimit` and `RateLimitNames`.

---

## Smart Food Search

`SmartSearchOrchestrator` powers `/api/food/search` and `/api/food/search/stream`. Layers a shared canonical catalog (Supabase, service-role) and Gemini-driven classify/generate on top of OFF + USDA upstream fan-out. See in-source design notes at the top of the file.

**Page 0 flow (simplified):**
1. Normalize query (`QueryNormalizer`) using locale + country (locale `country` segment > `country` query param > IP geo header).
2. Parallel: catalog `lookupQueryMappings` AND upstream OFF (+ USDA if enabled).
3. Catalog hit → `readCanonicals(ids)`, assemble, return.
4. Catalog miss → split upstream by `ResultKind` (GENERIC vs BRANDED). One whole-token GENERIC hit → accept without AI.
5. AI classify (`gemini-2.5-flash-lite`, ≤`AI_SEARCH_CLASSIFY_TIMEOUT_MS`) → `MATCH_EXISTING` / `CREATE_SPECIFIC` / `CREATE_BROAD`.
6. AI generate (`gemini-2.5-flash`, ≤`AI_SEARCH_GENERATE_TIMEOUT_MS`), grounded on upstream + `findEquivalentCanonicalCandidates` for cross-locale linking.
7. `NutritionValidator` sanity-check → if `confidence ≥ CATALOG_WRITE_CONFIDENCE_THRESHOLD`, persist via `insert_canonical_foods` RPC. `SMART_SEARCH_AI_SYNC_ON_MISS` decides sync vs async write-behind.
8. Assemble `SmartSearchResponse` (`bestMatch`, `bestMatchCandidates`, `genericMatches`, `brandedMatches`) with bestMatch items removed from the generic/branded pools.

**Streaming variant** emits an `upstream` SSE event with the upstream-only response immediately, then a `bestMatch` SSE event once AI generation resolves (`null` = timed out / low-confidence / no canonical), then `done`.

**Page > 0**: upstream-only pagination of generic+branded; no bestMatch, no AI.
**Flag-off (`SMART_FOOD_SEARCH_ENABLED=false`)**: orchestrator short-circuits to upstream-only; catalog + AI clients stay constructed but unused.

**Constraints:**
- `SUPABASE_SERVICE_ROLE_KEY` is required at startup (`ConfigLoader` errors otherwise). Never surface to clients, never log. `CanonicalCatalogClient` and `SupabaseAdminGateway` use it; both bypass RLS so never invoke them on a user-scoped path.
- Background scope: `SupervisorJob` + `Dispatchers.IO`, cancelled on `ApplicationStopping`. One failed write-behind doesn't cancel siblings.
- Per-instance dedup set (`inFlightGenerations`) prevents the same query from launching twice on the same container; cross-instance dedup relies on the RPC's slot-idempotency + `ON CONFLICT DO NOTHING`.
- Cold-start friendly: no in-process state survives instance restart.

---

## Image analysis

| Backend | Class | Model | Timeout | Notes |
|---|---|---|---|---|
| Gemini (primary) | `GeminiApiService` | `gemini-3.1-flash-lite-preview` | 90s | Context cache (1h TTL, Mutex-protected, refreshed 1 min before expiry); `thinkingBudget = MEDIUM (8192)`; `responseJsonSchema` enforced |
| OpenAI (fallback) | `OpenAiApiService` | `gpt-5-mini` via Responses API | 120s | `reasoning.effort = "low"`; `store = false` |

Selection: `config.useGeminiForAiImage` (env `USE_GEMINI`, default `true`).

System prompt + response JSON schema live in `model/ImageAnalyzer.kt` (`ImageAnalyzerFactory.IMAGE_ANALYZE_SYSTEM_PROMPT`, `imageAnalysisResponseSchema()`, `buildImageAnalyzeUserPrompt()`).

The `ImageAnalyzer` `fun interface` decouples route handlers from the backend:

```kotlin
fun interface ImageAnalyzer {
    suspend fun analyzeImage(
        imageBytes: ByteArray,
        mealTitle: String?,
        additionalContext: String?,
        locale: String?,
        mimeType: String
    ): ImageAnalysisResponse
}
```

Both image endpoints accept `AnalyzeImageRequest(image: base64, mealTitle?, additionalContext?, locale?)`. The streaming variant emits a `status` event before delegating, then `result` (or `error`) once analysis resolves.

---

## AI Progress Projections (the "ladder")

`POST /api/progress/ladders/generate-stream` takes a multipart body (`image` bytes + optional `currentWeightKg` / `currentBodyFatPercent` / `targetWeightKg` / `targetBodyFatPercent`) and returns an SSE stream emitting `status` → `rung` (source, step_index=0) → `rung` × N (projections) → `done` (or `error`).

Decoupled from `progress_photo` and the `progress-photos` bucket — the client picks any photo (gallery / camera / library) and ships the bytes. The orchestrator (`AiProgressProjectionService`) hashes the bytes for the cache key, runs a single Gemini Flash Lite gatekeeper call (gate verdict + body-fat estimate), persists the source as **rung #0** with `kind='SOURCE'` in the `ai-progress-ladders` bucket at `{userId}/ladders/{ladderId}/0.jpg`, then fans out N parallel `gpt-image-2 /v1/images/edits` calls bounded by a `Semaphore`. Each rung row inserts into `ai_progress_ladder_rung` (in `supabase_realtime` publication) so a dropped SSE connection doesn't lose progress — clients subscribe to realtime as a backup truth source.

**Body comp resolution chain** (run before the cache key is computed, persisted on the ladder row):
- `currentWeightKg`: request → `weight_entry.weight_kg` (latest) → `400 missing_current_weight`
- `currentBodyFatPercent`: request → gatekeeper photo-AI estimate → `400 missing_current_body_fat` (no `weight_entry` or formula fallback)
- `targetWeightKg`: request → `user_goal.goal_weight_kg` → `400 missing_target_weight`
- `targetBodyFatPercent`: request → `user_goal.body_fat_percent` → `400 missing_target_body_fat`

Photo-AI estimate is **not** persisted to `weight_entry` — it lives only on the ladder row + the SSE `status.resolved` payload.

**Cache hits** are zero-cost: same `(user_id, sha256(bytes), resolved targets, prompt_version, model)` returns the cached rungs immediately. Same photo + tweaked targets → fresh ladder. The unique index on `(user_id, request_key)` handles cross-instance dedup; per-instance, an `INSERT ... ON CONFLICT DO NOTHING` handles concurrent calls.

**`DELETE /api/progress/ladders/{id}`** is the only delete path. Postgres can't call out to Supabase Storage, so the client cannot delete via Supabase REST directly (RLS has no client DELETE policy on the ladder table). The endpoint reads rung `storage_path`s, batch-deletes blobs via service-role (404 = success), then deletes the ladder row (cascade FK wipes the rung rows).

**Tier-1 sizing.** All feature config is hardcoded in `loadAiProgressProjectionConfig()` — no env vars, no Cloud Run config changes. `numRungs` and `maxParallelRungs` default to 3 to fit in the OpenAI Tier 1 5-IPM cap on `gpt-image-2`. Bump both to 5 once on Tier 2+ (single-line edit + redeploy).

**Image-edit provider switch.** `provider = ImageGenerationProvider.OPENAI | GEMINI` in `loadAiProgressProjectionConfig()` picks the upstream — OpenAI `gpt-image-2` (`OpenAiImageEditClient`) or Gemini nano banana (`GeminiImageEditClient`, `gemini-2.5-flash-image`). Service depends on the `ProgressImageEditClient` interface. Cache key includes `activeImageModel`, so flipping the provider produces a fresh ladder. Cost computation is gpt-image-2-only today — Gemini ladders write `null` cost.

`gpt-image-2` requires a verified OpenAI organization. Tier-1 alone isn't enough — verify org status before the first test call.

---

## OFF mirror

Production mirrors the entire Open Food Facts catalog into Supabase (`public.off_food`) so the API can resolve barcodes and search candidates without round-tripping to OFF on every request. Reads are gated by `OFF_MIRROR_READ_ENABLED` (default true in prod, false in dev); when disabled, behavior is byte-identical to the pre-mirror code path.

**Read path (prod only).** `OffMirrorGateway` (service-role REST) is constructed in `Application.module()` only when `readEnabled`. Two access methods:
- `findByBarcode(code)` — PK lookup; called by `FoodService.getByBarcode` BEFORE the USDA mirror, OFF live, and USDA live tiers (sequential mirror→mirror→live→live; first non-empty wins). A miss falls through to the existing path so brand-new barcodes still resolve.
- `candidatesFor(query, country)` — pg_trgm-ranked recall via the `public.off_search_candidates(p_query, p_country, p_limit)` RPC; called by `SmartSearchOrchestrator` in **phase 1** of a two-phase fan-out (parallel with the USDA mirror). When phase 1 returns ≥`MIN_MIRROR_RESULTS_FOR_LIVE_SKIP` (=5) hits, the live tier is skipped entirely (~500 ms+ saved). Otherwise phase 2 fans out live OFF + USDA; mirror hits dedup-merge with live hits by `FoodItem.id`. Page > 0 stays upstream-only.

**Write path (separate Cloud Run Job, prod only).** `com.zenthek.ingest.IngestMain` builds a separate Jib image (`gcr.io/fitzenio/fitzenio-off-ingest`). Two scheduled invocations:
- **Daily delta** (`--kind=delta`, 03:07 UTC). Reads `https://static.openfoodfacts.org/data/delta/index.txt`, fetches every `openfoodfacts_products_<from>_<to>.json.gz` newer than the last successful checkpoint, batches 500-row upserts via the `public.upsert_off_products(items JSONB)` RPC. Aborts with `status=FAILED` if the checkpoint is older than ~13 days (outside OFF's 14-day delta window — full reconcile required).
- **Weekly full reconcile** (`--kind=full`, Sun 04:13 UTC). Streams `openfoodfacts-products.jsonl.gz` (multi-GB, gzip-decoded line-by-line, no disk), batched upsert, then calls `public.soft_delete_off_unseen(p_before)` with the run-start cutoff to tombstone rows missing from the dump. Soft-delete correctness depends on the upsert RPC always refreshing `synced_at = now()` on conflict (matched or not).

**Concurrency.** Both jobs check `off_sync_state` for an active RUNNING row at the start; if one exists and isn't stale (>6h), the new run exits with `status=CANCELLED`. This serializes delta + full when the schedules drift.

**Operations.** Job deploy command, manual smoke tests, scheduler config, and the troubleshooting catalogue (NUL bytes, pg_trgm schema, statement timeout, stale RUNNING rows) live in `DEPLOY.md` under "OFF Mirror Ingest Job".

**Schema parity.** `db/migrations/002_off_mirror.sql` is applied to BOTH dev (`tpslgveyjldykkkhnifs`) and prod (`anqvtpesmddllplyhkrc`) Supabase projects. Dev tables stay empty (`OFF_MIRROR_WRITE_ENABLED=false` → all local ingest runs are dry-runs). The schema parity prevents drift bugs and lets dev exercise the read path against a single test row when needed.

**Hybrid storage.** Per-100g macros (`energy_kcal_100g`, `protein_100g`, `carbs_100g`, `sugars_100g`, `fat_100g`, `saturated_fat_100g`, `fiber_100g`, `sodium_100g`) live in typed columns; long-tail nutrients live in `nutriments JSONB`. Indexes: `gin (product_name gin_trgm_ops) WHERE deleted_at IS NULL`, `gin (countries_tags) WHERE deleted_at IS NULL`, `(last_modified_t)`, `(primary_brand) WHERE deleted_at IS NULL`. RLS enabled with no policies — service-role bypasses, anon/authenticated denied.

**ODbL.** OFF data is licensed under the Open Database License + Database Contents License. Attribution is served by the public `GET /credits` endpoint at launch (not a follow-up). Mirror rows live in `off_food` and are NEVER merged into `canonical_food_item` — the AI catalog stays separate from OFF data.

---

## USDA mirror

Production also mirrors the USDA FoodData Central (FDC) **Branded** + **Foundation** datasets into Supabase (`public.usda_food`) so US-grocery barcodes and search candidates resolve from local Postgres in ~30 ms most of the time. Reads are gated by `USDA_MIRROR_READ_ENABLED` (default true in prod, false in dev).

**Read path (prod only).** `UsdaMirrorGateway` (service-role REST) is constructed in `Application.module()` only when `readEnabled`. Two access methods:
- `findByBarcode(code)` — `gtin_upc` lookup; called by `FoodService.getByBarcode` after the OFF mirror miss and before the live OFF/USDA fallback.
- `candidatesFor(query, limit)` — pg_trgm-ranked recall via `public.usda_search_candidates(p_query, p_limit)`. **No country parameter** — FDC is US-only data. Called by `SmartSearchOrchestrator` in phase 1 alongside the OFF mirror.

**Write path (separate Cloud Run Job, prod only).** `com.zenthek.ingest.UsdaIngestMain` builds a separate Jib image (`gcr.io/fitzenio/fitzenio-usda-ingest`). One scheduled invocation:
- **Monthly full reconcile** (`--kind=full`, 1st of month 04:37 UTC). FDC ships **bi-annual full snapshots only** (April + December) — no deltas. The job scrapes `https://fdc.nal.usda.gov/download-datasets/` for the latest dated zip URLs, compares against the last `OK` `release_date` in `usda_sync_state`, and short-circuits with `status=NO_NEW_RELEASE` when unchanged. On a real release, streams Branded (3.1 GB unzipped) then Foundation (6.5 MB) via `ZipInputStream` + Jackson token-streaming, batched 500-row upserts to `public.upsert_usda_foods(items JSONB)`, then `public.soft_delete_usda_unseen(p_before)`.

**Concurrency.** `usda_sync_state.RUNNING` row guard with an **8 h** staleness window (vs OFF's 6 h — Branded reconcile is bigger). New invocations exit with `status=CANCELLED` if a non-stale RUNNING row exists.

**Operations.** Job deploy (`./deploy-usda-ingest.sh`), smoke tests, scheduler config, and troubleshooting in `DEPLOY.md` under "USDA Mirror Ingest Job".

**Schema parity.** `db/migrations/004_usda_mirror.sql` (numbered 004 because 003 is taken by `off_mirror_timeout_hardening` + `off_mirror_trim`) applies to BOTH dev and prod Supabase projects. Dev tables stay empty (`USDA_MIRROR_WRITE_ENABLED=false` in dev → all local ingest runs are dry-runs).

**Hybrid storage.** Same shape as OFF: per-100g macros in typed columns + long-tail nutrients in `nutriments JSONB` keyed by FDC nutrientId. **Unit divergence from OFF**: `sodium_100g` is **milligrams** per 100g (FDC nutrient 1093 reports in mg natively); the read-path mapper passes through unchanged. Other macros are grams per 100g. Indexes: `gin (description gin_trgm_ops) WHERE deleted_at IS NULL`, `(gtin_upc) WHERE deleted_at IS NULL AND gtin_upc IS NOT NULL`, `(data_type) WHERE deleted_at IS NULL`, `gin (brand_owner gin_trgm_ops) WHERE deleted_at IS NULL`. RLS enabled with no policies.

**Macro normalization.** Bulk `foodNutrients[]` reports per-100g for both Branded and Foundation (different from the live API's per-serving Branded shape). The mapper prefers per-100g and falls back to label-nutrients-divided-by-`servingSize` when a per-100g value is missing.

**FoodItem id format.** Mirror rows produce `id="USDA_${fdcId}"` — identical to the live USDA mapper — so dedup-by-`FoodItem.id` against live USDA results is correct.

**Public domain.** FDC data is US public domain. No `/credits` attribution required.

---

## Account deletion

`DELETE /api/account` (`account` bucket, 3 req/min/user) hard-wipes the caller. Implemented by `AccountService` over `SupabaseAdminGateway` (service-role). **Idempotent** — safe to retry on partial failure. Order is intentional:

1. **Storage** — recursive wipe of `progress-photos/<userId>/` AND `ai-progress-ladders/<userId>/`. List/delete in 1000-row batches until empty. 404 = empty bucket prefix, success.
2. **Postgres** — single transactional `public.delete_user_data(p_user_id)` RPC (see `docs/migrations/20260423000000_add_delete_user_data_function.sql`). Wipes `ai_progress_ladder_rung` and `ai_progress_ladder` in dependency order alongside the existing rows.
3. **Auth** — `DELETE /auth/v1/admin/users/{id}`. 404 = already deleted, success.

Auth is last so a Postgres failure leaves the user still authenticated and able to retry. Each stage logs `[ACCOUNT-ADMIN] stage=...`. Any non-tolerated exception in a stage becomes `UpstreamFailureException` → HTTP 502 with stage name preserved server-side.

---

## User registration

`POST /api/user/register` performs an idempotent three-table insert under the caller's RLS context:

- `user_profile` — `id = userId`, identity fields backfilled from JWT or Supabase user lookup if missing.
- `user_goal` — generated `id` if input doesn't supply one.
- `calorie_target` — same.

`UserProfileService.registerIfAbsent()` checks each table independently and inserts only the missing rows; returns `{ok, status}` where `status ∈ {"created", "already_registered"}`. If the profile already exists but identity fields (`name`/`email`/`avatar_url`) are blank, they are PATCH-backfilled from the JWT.

A 409 with Postgres FK code `23503` against `*_user_id_fkey` means the JWT's `sub` is stale (user deleted but token reused) → mapped to `UnauthorizedException` (HTTP 401) so the client re-authenticates.

`GET /api/user/registration-status` returns `{isSignedIn: true, isRegistered: <profile-row-exists>}`.

---

## Configuration

`config/Environment.kt`:

```kotlin
data class AppConfig(
    val environment: AppEnvironment,
    val apiKeys: ApiKeys,                    // fatSecret*, usda, openAi, supabaseServiceRoleKey
    val useGeminiForAiImage: Boolean,
    val geminiApiKey: String,
    val supabase: SupabaseConfig,            // url, publishableKey?, legacyAnonKey?, jwtVerificationMode
    val smartSearch: SmartSearchConfig,
    val aiProgressProjection: AiProgressProjectionConfig,  // hardcoded; see loadAiProgressProjectionConfig()
)
```

`ConfigLoader.loadConfig()` runs once at startup. Missing required vars throw with a clear message — no silent null. Branches on `APP_ENVIRONMENT` (development reads `SUPABASE_DEV_ANON_KEY`; production reads `SUPABASE_ANON_KEY`).

**`AiProgressProjectionConfig` is hardcoded** in `loadAiProgressProjectionConfig()` — model name, quality, size, num rungs, mime allowlist, timeouts, etc. all live in code, not env. They apply uniformly across dev and prod and only change with a code review + deploy. The two secrets the feature uses (`OPENAI_API_KEY`, `GEMINI_API_KEY`) come from the existing env-driven config.

### Environment variables

| Variable | Required | Default | Notes |
|---|---|---|---|
| `APP_ENVIRONMENT` | no | `development` | `production`/`prod` switches anon-key var |
| `PORT` | no | `8080` | Cloud Run injects this |
| `USE_GEMINI` | no | `true` | image analysis backend |
| `FATSECRET_CLIENT_ID` / `FATSECRET_CLIENT_SECRET` | yes | — | legacy; still validated at startup even though unwired |
| `USDA_API_KEY` | yes | — | |
| `OPENAI_API_KEY` | yes | — | image analysis fallback |
| `GEMINI_API_KEY` | yes | — | image analysis primary + Smart Search AI |
| **Supabase** | | | |
| `SUPABASE_URL` | yes | — | also derives issuer + JWKS URL |
| `SUPABASE_PUBLISHABLE_KEY` | yes¹ | — | preferred modern key |
| `SUPABASE_ANON_KEY` / `SUPABASE_DEV_ANON_KEY` | yes¹ | — | legacy fallback (env-specific) |
| `SUPABASE_JWT_VERIFICATION_MODE` | no | `JWKS` | `JWKS` or `REMOTE` |
| `SUPABASE_SERVICE_ROLE_KEY` | yes | — | backend-only; required even when Smart Search disabled (account delete still uses it) |
| **Smart Food Search** | | | |
| `SMART_FOOD_SEARCH_ENABLED` | no | `true` | master switch |
| `SMART_SEARCH_USDA_ENABLED` | no | `true` | USDA fan-out kill switch |
| `AI_SEARCH_RANK_MODEL` | no | `gemini-2.5-flash-lite` | classify/rank |
| `AI_SEARCH_GENERATE_MODEL` | no | `gemini-2.5-flash` | grounded best-match generation |
| `AI_SEARCH_CLASSIFY_TIMEOUT_MS` | no | `3000` | |
| `AI_SEARCH_GENERATE_TIMEOUT_MS` | no | `8000` | |
| `SMART_SEARCH_AI_SYNC_ON_MISS` | no | `true` | `true` = canonical immediately (higher latency); `false` = async write-behind (lower latency, first user upstream-only) |
| `CATALOG_WRITE_CONFIDENCE_THRESHOLD` | no | `0.7` | min AI confidence to persist |
| **OFF Mirror** | | | |
| `OFF_MIRROR_READ_ENABLED` | no | `true` in prod, `false` in dev | API consults `off_food` before the live OFF/USDA fallback |
| `OFF_MIRROR_WRITE_ENABLED` | no | `true` in prod, `false` in dev | Ingest Job persists rows; `false` forces dry-run mode |
| **USDA Mirror** | | | |
| `USDA_MIRROR_READ_ENABLED` | no | `true` in prod, `false` in dev | API consults `usda_food` after the OFF mirror miss |
| `USDA_MIRROR_WRITE_ENABLED` | no | `true` in prod, `false` in dev | USDA ingest Job persists rows; `false` forces dry-run mode |

¹ At least one of `SUPABASE_PUBLISHABLE_KEY` or the env-appropriate legacy anon key must be set.

### Local dev

1. Copy `.env.example` to `.env`, fill in real values. dotenv-kotlin loads it with `ignoreIfMissing = true`.
2. `./gradlew run` (port 8080, Ktor dev mode auto-reload). `./gradlew run --continuous` rebuilds on change.

### Production (Cloud Run)

Secrets are injected via Secret Manager — no `.env` in prod. `application.conf` reads `${?PORT}`. `APP_ENVIRONMENT=production` is set in the service config.

---

## Coding conventions

**Kotlin style**
- Official Kotlin conventions; `data class` for DTOs/domain types; `sealed class` / `sealed interface` for typed unions.
- Upstream clients return `Result<T>` (use `runCatching`); services call `getOrElse {}` and decide fallback/throw.
- Naming: `NounClient` for upstream HTTP, `NounMapper` with `fun map(dto): FoodItem` (or `mapV3Search`, `mapSearchItemWithKind`, etc.).

**Coroutines**
- All I/O is `suspend`. Concurrent fan-out via `coroutineScope { async { } }` — never `GlobalScope` / bare `launch`.
- `GeminiApiService` cache refresh is `Mutex`-protected (mandatory — concurrent refresh races invalidate the cache ID).
- Smart Search write-behind runs on a `SupervisorJob + Dispatchers.IO` scope cancelled on `ApplicationStopping`.

**Serialization**
- `@Serializable` on every DTO. Single shared `Json { ignoreUnknownKeys = true }` (server `ContentNegotiation` adds `prettyPrint = true, isLenient = true`).
- Postgres-side field names use `snake_case` via `@SerialName`.

**DI**
No framework. `Application.module()` constructs everything from `ConfigLoader.loadConfig()` and passes via constructors.

**Error handling**
- Service layer throws domain exceptions: `UnauthorizedException` (401), `UpstreamFailureException` (502), `IllegalArgumentException` / `BadRequestException` / `ContentTransformationException` (400), anything else → 500.
- `StatusPages` maps each to an `ErrorResponse(error)` body. Real cause is logged server-side only — never surface stack traces or upstream payloads to the client.

**Route handlers**
- Validate input, delegate to a service. Never call upstream APIs from a handler.
- Never use `mapOf(...)` with mixed value types as a response — kotlinx.serialization can't handle `Map<String, Any>`. Use a `@Serializable data class`. Homogeneous `Map<String, String>` is fine.
- All `/api/**` routes live inside `authenticate(SUPABASE_AUTH_PROVIDER) { ... }`. Use `call.requireAuthenticatedUser()` and (when forwarding to Supabase REST) `call.requireBearerAccessToken()`.

---

## Testing

> **Do not write new tests unless the user explicitly asks.** Validate via `./gradlew compileKotlin compileTestKotlin` and `./gradlew test` (existing suite) instead. If you believe a specific test is essential, suggest it in chat — don't write it.

When tests are explicitly requested, follow the existing style:
- **Mappers** (`src/test/kotlin/com/zenthek/mapper/`) — hardcoded DTO fixtures, no network.
- **Upstream clients** (`upstream/`) — `HttpClient(MockEngine { ... })` + assert parsed result.
- **Routes** (`routes/`) — `testApplication { application { module() } }` + assert status/body.
- **Auth** (`auth/`) — uses `TestSupabaseJwtSupport` to mint signed tokens.

---

## Common commands

```bash
./gradlew run                    # dev server (port 8080)
./gradlew run --continuous       # auto-rebuild on change
./gradlew test                   # run tests
./gradlew jibDockerBuild         # build container to local Docker daemon
./gradlew jib                    # push to GCR (dev: gcr.io/fitzenio-debug/fitzenia-api-dev)
./gradlew jib -Pprod             # push to GCR (prod: gcr.io/fitzenio/fitzenia-api-prod)
./deploy-dev.sh                  # deploy staging  (project fitzenio-debug, service fitzenio-api-dev)
./deploy.sh                      # deploy prod     (project fitzenio,       service fitzenio-api-prod)
./gradlew jib -Pprod -PtargetService=ingest        # build OFF mirror ingest image (prod only)
./deploy-ingest.sh               # deploy off-ingest Cloud Run Job (prod only) — see DEPLOY.md
./gradlew jib -Pprod -PtargetService=usda-ingest   # build USDA mirror ingest image (prod only)
./deploy-usda-ingest.sh          # deploy usda-ingest Cloud Run Job (prod only) — see DEPLOY.md
```

See `DEPLOY.md` for the full deployment guide. Cloud Run configs: `cloud-run-config.yaml` (prod), `cloud-run-config.dev.yaml` (staging). IAM: `grant-secrets.sh`. Local→Secret Manager sync: `sync-secrets.sh`. Diff deployed env vs local: `check-cloud-run-env.sh`.

---

## Security

- Never commit `.env`. Never hardcode keys — go through `ConfigLoader` / dotenv.
- Never log secrets, JWTs, bearer tokens, or the service-role key. No `println(config)`. Don't log full request bodies that may contain images or keys.
- Never return internal error details to clients — `StatusPages` returns generic `ErrorResponse`; the cause is logged.
- Open Food Facts requires no key but mandates a `User-Agent` (current value: `Fitzenio/1.0 (Android/iOS app; contact@fitzenio.com)`).
- Gemini context cache ID and the FatSecret OAuth token (if ever re-enabled) live in memory only.
- `SUPABASE_SERVICE_ROLE_KEY` is backend-only — Cloud Run injects via Secret Manager; never echoed in responses.
- Only `GET /health` is public. New routes default into the `authenticate(SUPABASE_AUTH_PROVIDER) { ... }` block.

---

## Key constraints (production-correctness gotchas)

- **OFF barcode**: `/api/v3/product/{code}`, response `status` is the string `"success"` (not integer `1`).
- **OFF search**: `https://search.openfoodfacts.org/search` (search-a-licious) — response has `hits` (not `products`); `brands` is `List<String>` (not comma-separated); 1-indexed pages. The client fans out two parallel calls when `country` resolves to a `countries_tags` slug (filtered + unfiltered) and merges, dedup-by-code, filtered-first.
- **USDA search**: GET (not POST) with `pageNumber` 1-indexed. `dataType=Branded,Foundation,SR Legacy`. `ResultKind` is set from `dataType`.
- **FatSecret is unwired** — client/token-manager/mapper kept for reference only. The `Mutex` token-refresh contract still applies if it's ever re-enabled. Its env vars are still validated at startup.
- **Service-role gateways** (`CanonicalCatalogClient`, `SupabaseAdminGateway`, `AiProgressLadderGateway`) bypass RLS — never use them on a user-scoped path. They are only invoked from `SmartSearchOrchestrator`, `AccountService`, and `AiProgressProjectionService`.
- **`gpt-image-2` requires a verified OpenAI org + Tier 2+ for full parallelism.** On Tier 1 (5 IPM cap), the feature ships with `numRungs=3` so a single ladder fits the cap with headroom. Bump to 5 once on Tier 2.
- **`ai_progress_ladder` and `ai_progress_ladder_rung` have no client DELETE policy** — the client must call `DELETE /api/progress/ladders/{id}` so storage blob cleanup is guaranteed. A direct `supabase.from(...).delete()` will fail under RLS.
- **Stale-after-delete tokens**: a 409 with Postgres `23503` against `*_user_id_fkey` on insert is mapped to `UnauthorizedException` (401), not 500.
- **One Ktor client** is shared across all upstream services (`HttpTimeout: requestTimeoutMillis = 15_000, connectTimeoutMillis = 10_000`). Don't spin up extras per-service.
- **Stateless** — only in-memory caches are the Gemini context cache ID and (legacy) FatSecret token, both Mutex-protected and rebuilt on cold start.

---

## Plans / open work (summary)

These were live in earlier revisions of this doc and have been pruned from the body — recorded here for context only.

- **Smart Food Search v1 → v2** — current code is "v1" (Gemini classify+generate, ILIKE for cross-locale candidate lookup). v2 ideas: pg_trgm RPC for fuzzy candidate recall, a confidence-floor escalation path, multi-locale write-fanout. The full design is at `~/.claude/plans/scalable-mapping-crayon.md` (referenced from `SmartSearchOrchestrator.kt`).
- **FatSecret re-enablement** — the legacy v5 client + JsonTransformingSerializer plumbing is intentionally retained but unwired. Re-enabling needs: wire `FatSecretClient` into `FoodService` / orchestrator, restore the polymorphic-array tests, and update this doc.
- **Logback prod config** — currently a single `logback.xml` (console only). A separate prod profile may be added if Cloud Logging structured-payload formatting is needed.
- **Rate-limit storage** — current limiter is in-memory per instance. Cross-instance limiting (Redis-backed bucket) is unbuilt.
