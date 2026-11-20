# Plan — Claude as primary AI meal scanner, Gemini as fallback

Status: **implemented (rev 2) — pending dev rollout**. Scope: `POST /api/food/analyze-image` and `POST /api/food/analyze-image-stream` only. Smart Search, progress ladders, and the coach are untouched.

Rev 2 includes an independent review (Fable) that was checked against the code and the Claude API reference. The main changes: fallback catches everything except real cancellation; out-of-credit is a 402, not a 400 string match; timeouts open the cooldown; SSE keepalive is in scope; the Cloud Run timeout gets raised; image-token cost corrected; config surface reduced.

## Goal

Spend the ~€100 of Anthropic API credit on meal-photo analysis, while guaranteeing the scanner never gets worse than today:

- Claude answers first.
- **Any** Claude failure → the same request is retried on Gemini (the current backend) transparently.
- When the credit runs out, it keeps working on Gemini with no deploy needed.
- `AI_IMAGE_PRIMARY` unset or `gemini` → byte-identical to today.

## Current state (verified)

- `ImageAnalyzer` is a `fun interface` (`model/ImageAnalyzer.kt:13`). Call sites: `Application.kt:80` (Gemini vs. OpenAI by `USE_GEMINI`), `Routing.kt:282` and `:311`. One test fake: `FoodImageRoutesTest.kt:129`.
- `GeminiApiService`: `gemini-3.1-flash-lite`, cached system prompt, `responseJsonSchema`, thinking budget 8192, 90s client timeout, then `.applyAtwaterFallback()`.
- Routes call `guardImageAnalysisResult` → `ImageAnalysisNutritionGuard.sanitize()` (throws `UpstreamFailureException` on reject). `label()` / `modelLabel()` (`Routing.kt:394-402`) use `javaClass.simpleName`, so a wrapper would log `unknown`.
- System prompt ≈ 16 KB (~4k tokens), comfortably cacheable.
- **Prod timeout is ambiguous:** `cloud-run-config.yaml:25` has annotation `80s`, `:31` has `timeoutSeconds: 60`. Dev is 300s, so dev testing can't reveal a prod timeout squeeze.
- `application.conf` has no `responseWriteTimeoutSeconds`. The coach already hit Netty's 10s write timeout on silent SSE streams (`coach.conf`, `ChatRoutes.kt` ping loop).
- The schema has no `additionalProperties: false`, and nullables are `type: [x, "null"]` arrays. Claude structured outputs need `additionalProperties: false` on every object; `anyOf` is the documented way to express nullability.
- Secrets are `secretKeyRef` in the Cloud Run YAML, so a missing secret or IAM grant **fails the deploy**.
- Side finding, out of scope: `OpenAiApiService.kt:59-60` sends `strict: true` with a schema lacking `additionalProperties: false`. The `USE_GEMINI=false` path may already be broken. Don't rely on it as a fallback.

## Design

### 1. Provenance from the analyzer

```kotlin
data class ImageAnalysisOutcome(
    val response: ImageAnalysisResponse,
    val provider: String,   // "claude" | "gemini" | "openai"
    val model: String,      // e.g. "claude-opus-5-5"
)

fun interface ImageAnalyzer {
    suspend fun analyzeImage(imageBytes, mealTitle, additionalContext, locale, mimeType): ImageAnalysisOutcome
}
```

- Gemini / OpenAI wrap their existing return.
- Routes read `outcome.provider` / `outcome.model`, and `label()` / `modelLabel()` are deleted.
- `FoodImageRoutesTest.kt:129` fake gets a one-line update.
- The client wire format is unchanged.

### 2. `upstream/anthropic/ClaudeImageAnalyzer.kt` (new)

- **SDK:** `com.anthropic:anthropic-java` (latest 3.x, ≥ 3.3.0) in `libs.versions.toml`. One `AnthropicClient` built in `Application.module()`:
  - explicit API key, `maxRetries(0)`
  - SDK request timeout **25s**, a few seconds above the coroutine timeout below so the coroutine timeout always wins the race
  - closed on `ApplicationStopping`
- **Call:** `client.async().messages().create(params).await()` (`kotlinx.coroutines.future.await`, in coroutines 1.9.0 core). Confirm the `async()` shape with `javap` at implementation time.
- **Request:**
  - `model` = `CLAUDE_IMAGE_MODEL` (default `claude-opus-5-5`).
  - `maxTokens(16000)`. No `thinking` param (adaptive is on by default for Opus/Sonnet/Haiku 5.5).
  - `outputConfig(effort = MEDIUM hardcoded, format = JsonOutputFormat(strict schema))`.
  - System: `IMAGE_ANALYZE_SYSTEM_PROMPT` via `systemOfTextBlockParams` with `CacheControlEphemeral(TTL_1H)`.
  - User: image block first (`ImageBlockParam` + `Base64ImageSource`, media type from `mimeType`), then `buildImageAnalyzeUserPrompt(...)`.
- **Response:**
  - `stopReason != END_TURN` → throw `ClaudeAnalysisException("stop=<reason>[:<refusal category>]")`.
  - Otherwise concatenate the text blocks → decode `ImageAnalysisResponse` → `.applyAtwaterFallback()`.
- **Log** (no key, no image): `[IMAGE-AI] provider=claude model=… latencyMs=… in=… cacheRead=… cacheWrite=… out=… stop=…`.

### 3. Strict schema variant

`imageAnalysisResponseSchema(strict: Boolean = false)`:
- `false` → exactly today's schema (Gemini and `ImageAnalysisSugarTest` unchanged).
- `true`:
  - `additionalProperties: false` on the root and item objects
  - **every** nullable helper (`putNullableString`, `putNullableNumber`, `putNullableEnumString`, `ImageAnalyzer.kt:387-416`) emits `anyOf: [{type: X[, enum]}, {type: "null"}]`
  - optional fields (`sugarG`, `totalSugarG`, `notes`) stay non-required
- Kotlin `JsonObject` → string → SDK Jackson → `JsonValue`.
- **Smoke-test the schema in dev before anything else** (rollout step 1). A rejected schema is a 400 on every scan, which would mean silent permanent fallback plus a wasted round trip.

### 4. `service/FallbackImageAnalyzer.kt` (new, kept small)

```kotlin
class FallbackImageAnalyzer(primary: ImageAnalyzer, secondary: ImageAnalyzer)
// private val claudeDisabledUntil = AtomicLong(0)
```

Flow:
1. If `now < claudeDisabledUntil` → secondary.
2. `val r = withTimeoutOrNull(20_000) { primary.analyzeImage(...) }`. `null` = timeout → cooldown 60s → secondary.
3. On a result, call `ImageAnalysisNutritionGuard.sanitize(r.response)` directly as a dry run. If it rejects → secondary.
4. **`catch (e: Exception)`:** `if (e is CancellationException) throw e`, otherwise classify for cooldown → secondary. This catch-all covers SDK transport / invalid-data exceptions, which are not `AnthropicServiceException`.

Cooldown classification (one `AtomicLong`):

| Failure | Cooldown |
|---|---|
| 401 / 402 `billing_error` (credit gone) / 403 / 404 (e.g. model typo) | 30 min |
| 429 with `enforced_spend_limit_reached` (Console spend cap) | 30 min |
| other 429, 529 overloaded, timeout | 60 s |
| anything else (bad image, bad JSON, guard reject) | none |

Secondary call: `withTimeoutOrNull(remainingBudget) { secondary.analyzeImage(...) } ?: throw UpstreamFailureException("image analysis timed out")`. That maps to the existing 502 handler (`Application.kt:334`). A bare `withTimeout` would surface as `TimeoutCancellationException` → 500.

Log one line per fallback: `[IMAGE-AI] fallback=gemini reason=<class/status/guard message> claudeLatencyMs=…`. This carries the guard reason, since a Claude-side guard rejection no longer reaches the route's "nutrition rejected" log.

The route still runs `guardImageAnalysisResult` on whichever result comes back (unchanged behavior for Gemini results).

### 5. Timeout budget

- **Raise prod Cloud Run timeout to 120s** (make `timeoutSeconds` and the `run.googleapis.com/timeout` annotation agree). First check the mobile client's HTTP timeout for `analyze-image` and make sure it's ≥ 120s or at least > 60s.
- Budget: Claude 20s → Gemini up to `110s - elapsedSinceRequestStart` (≈ 90s, same as today's Gemini ceiling) → headroom for the guard and the write.
- Measure in **dev** first: get Opus 5.5 p50/p95 latency on real photos. If p95 is near 20s, frequent timeouts double both latency and spend → switch the model (Sonnet/Haiku 5.5) rather than raising the cap.

### 6. SSE keepalive (in scope)

`analyze-image-stream` (`Routing.kt:302-334`) will now have ≥ 20s silences after the `status` event on fallback.
- Add the coach's ping job (ping at t=0, then every 5s) around the analyze call.
- Add `responseWriteTimeoutSeconds = 120` under `ktor.deployment` in `application.conf`.

### 7. Config (`config/Environment.kt`)

One `loadAiImageConfig()` helper (mirrors `loadRevenueCatConfig()`, `Environment.kt:273`), used by both the dev and prod builders.

| Env var | Required | Default | Notes |
|---|---|---|---|
| `AI_IMAGE_PRIMARY` | no | `gemini` | `claude` enables Claude-first |
| `ANTHROPIC_API_KEY` | no | — | If `AI_IMAGE_PRIMARY=claude` and the key is missing → startup warning, Gemini-only |
| `CLAUDE_IMAGE_MODEL` | no | `claude-opus-5-5` | switch to `claude-sonnet-5-5` / `claude-haiku-5-5` without a code change |

Effort (`medium`) and the timeouts (20s/25s) are hardcoded, matching the `loadAiProgressProjectionConfig()` convention.

### 8. Wiring (`Application.kt`)

```kotlin
val legacy = if (config.useGeminiForAiImage) GeminiApiService(...) else OpenAiApiService(...)
val imageAnalyzer = if (config.aiImage.claudeEnabled)
    FallbackImageAnalyzer(ClaudeImageAnalyzer(anthropicClient, config.aiImage.model), legacy)
else legacy
```

### 9. Deploy plumbing (order matters, because `secretKeyRef` fails the deploy if the secret is missing)

1. Create the `ANTHROPIC_API_KEY` secret in `fitzenio-debug` and `fitzenio`, with a **separate Console key per env** so spend can be compared.
2. IAM grant (`grant-secrets.sh`).
3. Then update `cloud-run-config.yaml`, `cloud-run-config.dev.yaml` (secret + `AI_IMAGE_PRIMARY` + prod timeout), `sync-secrets.sh`, `check-cloud-run-env.sh`, `.env.example`, `AGENTS.md`.
4. Anthropic Console: set a monthly spend limit on the workspace as a second cap. Its 429 is handled by the 30-min cooldown.
5. Check `./gradlew dependencies` for Jackson (2.18.2 pinned) / OkHttp conflicts. The SDK lands in every Jib target (api, coach, ingest), so compile the `coach` target too.

### 10. Rollout

1. Dev, `AI_IMAGE_PRIMARY=claude`:
   - First scan → confirm the strict schema is accepted (no 400) and `cacheRead > 0` from the 2nd scan.
   - Scan ~20 real meals: mixed cuisines, restaurant, packaged, non-food, blurry.
   - Record latency p50/p95, tokens, € per scan, fallbacks. Compare with Gemini by flipping the flag.
2. Force fallbacks in dev:
   - bogus key → 401 → Gemini answer + 30-min cooldown
   - bogus model → 404
   - Confirm the SSE stream survives a slow path.
3. Prod: create the secret, grant it, deploy with the 120s timeout, flip the flag. Watch the `[IMAGE-AI]` logs for a day.
4. Credit runs out → 402 → cooldown makes it Gemini-only automatically (one probe per instance per 30 min). Later set `AI_IMAGE_PRIMARY=gemini`.

### 11. Validation

Per AGENTS.md, no new tests. Run `./gradlew compileKotlin compileTestKotlin` and the existing suite. The only expected test edit is the `FoodImageRoutesTest.kt:129` fake.

## Cost estimate (rough; re-baseline from dev usage logs)

Current Claude models accept up to 2576px on the long edge, and **one phone photo can cost up to ~4.8k image tokens** (vs. ~1.6k at 1568px). Per scan ≈ 4k cached system tokens + ~5k uncached (image + text) in, ~1–2k out including thinking.

| Model | ≈ $ / scan (full-res) | ≈ scans for €100 | with client resize to ≤1568px |
|---|---|---|---|
| `claude-opus-5-5` ($4/$20) | ~0.05 | ~2,000 | ~3,000 |
| `claude-sonnet-5-5` ($2/$10) | ~0.025 | ~4,000 | ~6,000 |
| `claude-haiku-5-5` ($0.10/$0.50) | ~0.0013 | ~80,000 | ~100,000+ |

Open decision: client-side resize. It cuts cost ~30–40% and upload time, with some loss of fidelity on small text/labels.

## Risks / open points

- **Privacy:** meal photos go to a new subprocessor (Anthropic). Update the privacy policy / subprocessor list before the prod flip.
- **Mobile client timeout** must exceed the new server budget (see §5).
- **MIME:** routes hardcode `image/jpeg` (`Routing.kt:287`, `:316`). If clients ever send PNG/HEIC, Claude may reject what Gemini tolerates. A rejected image falls back, but verify in dev.
- **Oversized images:** the API may reject very large payloads (413). This falls back to Gemini; the resize decision above mitigates it.
- **Rate limits:** a credits-only org starts on a low usage tier. 429 → 60s cooldown.
- **Behavior drift:** Claude may estimate portions differently from Gemini. The nutrition guard catches the extremes; the dev comparison is the real check.
