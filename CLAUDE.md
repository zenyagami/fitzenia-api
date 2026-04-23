> Cross-reference `docs/food-api-backend.md` for the full API specification, `docs/API_SECURITY_BACKEND.md` for the auth/security design, and `docs/DATABASE_SCHEMA.md` for the Supabase schema — all files travel together.

---

# CLAUDE.md — Food API Backend

> Ktor-based food API proxy that aggregates Open Food Facts, FatSecret, and USDA FoodData Central.
> Also provides AI-powered image analysis via Gemini (primary) or OpenAI (fallback).
> Deployed as a containerized JVM service on Google Cloud Run.

## Project overview

This service is a thin aggregation proxy that the Fitzenio mobile app calls for food search and barcode
lookup. It does not store data — it fans out to up to three upstream food APIs, merges the results, and
returns a normalized response. It also exposes image analysis endpoints backed by Gemini or OpenAI.
See `food-api-backend.md` for the full endpoint spec, response schemas, and upstream API details.

**Deployment target:** Google Cloud Run (containerized, stateless, scales to zero)

---

## Tech stack

| Concern | Library | Notes |
|---|---|---|
| **Server** | Ktor 3.2.3 (Netty engine) | `ktor-server-netty` |
| **HTTP client** | Ktor 3.2.3 (CIO engine) | `ktor-client-cio` |
| **Auth** | Ktor `ktor-server-auth` + `ktor-server-auth-jwt` | Supabase JWT via JWKS (default) or remote `/auth/v1/user` fallback |
| **Rate limiting** | Ktor `ktor-server-rate-limit` | Per-user limits on search + image analysis |
| **Database / Auth provider** | Supabase | `SupabaseClient` for REST + Auth; `CanonicalCatalogClient` (service-role) for shared catalog |
| **AI search** | Gemini 2.5 Flash Lite (classify/rank) + Flash (generate) | `GeminiAiSearchClient`, used by `SmartSearchOrchestrator` |
| **Serialization** | kotlinx.serialization 1.7.3 | JSON only |
| **Logging** | Logback 1.5.13 | Console output only (single `logback.xml`) |
| **Dev env loading** | dotenv-kotlin 6.4.1 | Always loaded, `ignoreIfMissing = true` |
| **Image analysis** | Gemini Flash (primary) / GPT-5-mini (fallback) | Controlled by `config.useGeminiForAiImage` |
| **JDK** | 21 | Eclipse Temurin in container |
| **Build** | Gradle 8.x Kotlin DSL + version catalog | `gradle/libs.versions.toml` |
| **Container build** | Jib plugin | `./gradlew jib` pushes to GCR; no Dockerfile |

---

## Project structure

```
fitzenio-api/
├── src/
│   └── main/
│       ├── kotlin/
│       │   ├── Application.kt                  # EngineMain entry point (package com.zenthek.fitzenio.rest)
│       │   └── com/zenthek/
│       │       ├── config/
│       │       │   └── Environment.kt          # AppConfig, ApiKeys, SupabaseConfig, SmartSearchConfig, ConfigLoader
│       │       ├── auth/
│       │       │   └── SupabaseAuthentication.kt  # Ktor Authentication plugin (jwt / bearer);
│       │       │                                  # AuthenticatedUserContext principal;
│       │       │                                  # requireAuthenticatedUser / requireBearerAccessToken
│       │       ├── model/
│       │       │   ├── FoodItem.kt             # FoodItem, NutritionInfo, ServingSize, SearchResponse,
│       │       │   │                           # SmartSearchResponse, SearchStreamBestMatch, ErrorResponse,
│       │       │   │                           # ImageAnalysisItem, ImageAnalysisResponse, AnalyzeImageRequest
│       │       │   ├── ImageAnalyzer.kt        # ImageAnalyzer functional interface + system prompt
│       │       │   ├── CanonicalCatalogEntities.kt # canonical food / serving entities for Supabase catalog
│       │       │   └── UserProfile.kt          # RegisterUserRequest, registration status DTOs
│       │       ├── routes/
│       │       │   └── Routing.kt              # health (public); /api/food/* and /api/user/* under
│       │       │                               # authenticate(SUPABASE_AUTH_PROVIDER); rateLimit groups
│       │       ├── service/
│       │       │   ├── FoodService.kt          # OFF + USDA fan-out, merge, deduplicate, autocomplete
│       │       │   ├── SmartSearchOrchestrator.kt # canonical-catalog-first search + AI rank + write-behind
│       │       │   ├── QueryNormalizer.kt      # locale-aware query canonicalization
│       │       │   ├── NutritionValidator.kt   # sanity-checks AI-generated nutrition payloads
│       │       │   ├── UserProfileService.kt   # register / registration-status business logic
│       │       │   ├── UnauthorizedException.kt
│       │       │   └── UpstreamFailureException.kt
│       │       ├── network/
│       │       │   └── HttpClientProvider.kt
│       │       ├── ai/
│       │       │   ├── AiSearchClient.kt       # interface: classifyQuery + generateBestMatch
│       │       │   └── GeminiAiSearchClient.kt # Gemini Flash Lite (rank) + Flash (grounded generation)
│       │       ├── upstream/
│       │       │   ├── openfoodfacts/
│       │       │   │   ├── OpenFoodFactsClient.kt   # v3 product + search-a-licious search + autocomplete
│       │       │   │   └── dto/OpenFoodFactsDto.kt
│       │       │   ├── fatsecret/                   # legacy; retained but NOT wired into FoodService
│       │       │   │   ├── FatSecretClient.kt
│       │       │   │   ├── FatSecretTokenManager.kt
│       │       │   │   └── dto/FatSecretDto.kt
│       │       │   ├── usda/
│       │       │   │   ├── UsdaClient.kt
│       │       │   │   └── dto/UsdaDto.kt
│       │       │   ├── supabase/
│       │       │   │   ├── SupabaseClient.kt        # SupabaseGateway: auth user lookup + user_profiles REST
│       │       │   │   └── CanonicalCatalogGateway.kt  # service-role catalog reads/writes
│       │       │   ├── gemini/
│       │       │   │   └── GeminiApiService.kt      # Gemini Flash image analysis (primary)
│       │       │   └── openai/
│       │       │       └── OpenAiApiService.kt      # GPT-5-mini image analysis (fallback)
│       │       └── mapper/
│       │           ├── OpenFoodFactsMapper.kt  # map() for barcode, mapV3Search() for search
│       │           ├── FatSecretMapper.kt      # (legacy, unused — kept for reference)
│       │           └── UsdaMapper.kt
│       └── resources/
│           ├── application.conf                # HOCON config: port, module ref, PROJECT_ID
│           └── logback.xml                     # console output, DEBUG for com.zenthek
├── src/test/kotlin/com/zenthek/
│   ├── mapper/                                 # mapper unit tests with fixture DTOs
│   ├── routes/                                 # testApplication { } integration tests
│   └── upstream/                               # MockEngine client tests
├── gradle/
│   └── libs.versions.toml                      # centralized version catalog
├── docs/
│   ├── food-api-backend.md                     # full endpoint spec
│   ├── API_SECURITY_BACKEND.md                 # auth/security design (JWT, RLS, rate limits)
│   ├── DATABASE_SCHEMA.md                      # Supabase schema reference
│   ├── AI_PROMPT.md                            # AI search / image prompt design
│   └── migrations/                             # Supabase SQL migrations
├── .env.example
├── .gitignore
├── cloud-run-config.yaml                       # production Cloud Run service config
├── cloud-run-config.dev.yaml                   # staging Cloud Run service config
├── deploy.sh                                   # production deploy (fitzenio GCP project)
├── deploy-dev.sh                               # staging deploy (fitzenio-debug GCP project)
├── grant-secrets.sh                            # Secret Manager IAM bindings
├── sync-secrets.sh                             # sync local .env → Secret Manager
├── check-cloud-run-env.sh                      # diff deployed env vars vs local config
├── DEPLOY.md                                   # deployment guide
├── build.gradle.kts
└── settings.gradle.kts
```

---

## Coding conventions

### Kotlin style

- Follow official Kotlin coding conventions
- `data class` for all DTOs and domain models
- `sealed class` / `sealed interface` for typed errors and discriminated unions
- Use `Result<T>` for upstream client return types — never throw from client layer
- Upstream clients return `Result<T>`; `FoodService` calls `getOrElse {}` and decides fallback behavior
- Name upstream clients as `NounClient` (e.g., `FatSecretClient`)
- Name mappers as `NounMapper` with a single `fun map(dto: SomeDto): FoodItem` method

### Coroutines

- Every I/O operation is `suspend`
- Concurrent upstream calls use `coroutineScope { async { } }` — not `GlobalScope`, not `launch`
- FatSecret token refresh uses a `Mutex` — mandatory to prevent concurrent refresh races
- Gemini context cache management also uses a `Mutex` to prevent concurrent refresh races

### Serialization

- `@Serializable` on every DTO class
- Single `Json` instance configured with `ignoreUnknownKeys = true`, `isLenient = false`
- Install it via `ContentNegotiation` plugin and reuse the same instance for upstream client configuration
- FatSecret `serving` and `food` fields can be a JSON object **or** an array — use `JsonTransformingSerializer` to normalize to array before deserialization. This is the most fragile part — test it explicitly.

### Dependency injection

No DI framework. Use plain constructor injection via `ConfigLoader`:

```kotlin
// Application.kt
fun Application.module() {
    val config = ConfigLoader.loadConfig()          // AppConfig: ApiKeys + Supabase + SmartSearch + image config
    val httpClient = buildHttpClient()

    val offClient = OpenFoodFactsClient(httpClient)
    val usdaClient = UsdaClient(httpClient, config.apiKeys.usdaApiKey)
    val imageAnalyzer: ImageAnalyzer = if (config.useGeminiForAiImage) {
        GeminiApiService(httpClient, config.geminiApiKey)
    } else {
        OpenAiApiService(httpClient, config.apiKeys.openAiApiKey)
    }
    val foodService = FoodService(offClient, usdaClient)

    val supabaseClient = SupabaseClient(httpClient, config.supabase)
    val userProfileService = UserProfileService(supabaseClient)

    val canonicalCatalog = CanonicalCatalogClient(httpClient, config.supabase,
        serviceRoleKey = config.apiKeys.supabaseServiceRoleKey ?: "DISABLED")
    val aiSearchClient = GeminiAiSearchClient(httpClient, config.geminiApiKey,
        rankModel = config.smartSearch.aiRankModel,
        generateModel = config.smartSearch.aiGenerateModel)

    // SupervisorJob + Dispatchers.IO for async write-behind AI generation; cancelled on ApplicationStopping.
    val smartSearchBackgroundScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("smart-search-bg")
    )
    monitor.subscribe(ApplicationStopping) { smartSearchBackgroundScope.cancel() }

    val smartSearch = SmartSearchOrchestrator(
        offSearch = offClient::searchPaged,
        usdaSearch = usdaClient::searchPaged,
        catalog = canonicalCatalog,
        ai = aiSearchClient,
        config = config.smartSearch,
        backgroundScope = smartSearchBackgroundScope,
    )

    configureSerialization()
    configureStatusPages()
    configureRateLimit()
    configureAuthentication(config.supabase, supabaseClient)          // installs Ktor Authentication
    configureRouting(foodService, smartSearch, imageAnalyzer, userProfileService)
}
```

### Error handling

- Throw domain exceptions in the service layer (e.g., `NotFoundException`, `UpstreamException`)
- Catch all `Throwable` in `StatusPages` — map to HTTP 500 + `mapOf("error" to ..., "message" to ...)`
- **Never return internal error details or stack traces to clients**
- Log the real cause server-side with `log.error("...", cause)`

### Route handlers

- Route handlers only validate input and delegate to `FoodService`, `SmartSearchOrchestrator`, `ImageAnalyzer`, or `UserProfileService`
- **Never call upstream APIs from route handlers directly**
- Keep route files thin — extract complex query param parsing to separate functions if needed
- **Never use `mapOf(...)` with mixed value types for responses** — kotlinx.serialization cannot serialize `Map<String, Any>`. Always use a typed `@Serializable` data class instead. (Exception: homogeneous `Map<String, String>` is fine.)
- All `/api/**` routes are wrapped in `authenticate(SUPABASE_AUTH_PROVIDER) { ... }`. Only `GET /health` is public. Handlers retrieve the caller via `call.requireAuthenticatedUser()` and, if they need to forward the token to Supabase REST, `call.requireBearerAccessToken()`.

---

## Authentication (Supabase JWT)

All endpoints except `GET /health` require a Supabase-issued JWT in `Authorization: Bearer <token>`. `configureAuthentication()` in `auth/SupabaseAuthentication.kt` installs Ktor's `Authentication` plugin under the provider name `SUPABASE_AUTH_PROVIDER` (`"supabase-jwt"`).

### Verification modes

Controlled by `SUPABASE_JWT_VERIFICATION_MODE` (default `JWKS`):

| Mode | Behavior | When to use |
|---|---|---|
| `JWKS` | Verifies signature locally against Supabase's JWKS (cached 10 min, rate-limited 10/min). Validates `iss = ${SUPABASE_URL}/auth/v1`, `aud = authenticated`, 30s clock leeway. | **Default.** Zero per-request network cost, scales cleanly. |
| `REMOTE` | On every request, calls `GET ${SUPABASE_URL}/auth/v1/user` with the bearer token. | Temporary fallback only — logs a warning at startup. Every request adds an upstream hop. |

On startup the app probes the JWKS endpoint (`JWKS` mode only) and logs reachability — a failed probe is a warning, not a fatal error.

### Principal

A successful validation produces an `AuthenticatedUserContext`:

```kotlin
data class AuthenticatedUserContext(
    val userId: String,     // JWT `sub`
    val email: String?,
    val name: String?,      // JWT user_metadata.name / full_name
    val avatarUrl: String?, // JWT user_metadata.avatar_url / picture
    val role: String,       // must equal "authenticated"
)
```

Rejection rules (validator returns `null` → 401):
- `sub` is blank
- `role` claim is not `"authenticated"`
- `aud` does not contain `"authenticated"`

The raw bearer token is stashed on the call under `SupabaseAccessTokenKey` so downstream services (e.g., `UserProfileService`) can forward it to Supabase REST for RLS-scoped queries.

### Helpers

- `call.requireAuthenticatedUser(): AuthenticatedUserContext` — throws `UnauthorizedException` if the principal is missing.
- `call.requireBearerAccessToken(): String` — returns the token cached during validation, else re-extracts from the `Authorization` header; throws `UnauthorizedException` if missing.

### Important constraints

- **Never log JWTs or access tokens** — they are bearer credentials.
- **Never hit Supabase auth from route handlers** — route handlers call `requireAuthenticatedUser()` / `requireBearerAccessToken()` only; services talk to Supabase.
- **Do not add a separate `bearer { }` provider** — the `jwt { }` / `bearer { }` branch is selected inside `configureAuthentication` based on `SupabaseJwtVerificationMode`.
- **`UnauthorizedException`** is caught by `StatusPages` and mapped to HTTP 401 with an `ErrorResponse` body. Throw it from services when an upstream auth call (e.g., Supabase REST) returns 401.
- The token stashed in `SupabaseAccessTokenKey` is per-request — do not cache it anywhere else.

---

## Rate limiting

`configureRateLimit()` installs Ktor's `RateLimit` plugin with per-user buckets keyed off `AuthenticatedUserContext.userId` (pulled from `call.principal<AuthenticatedUserContext>()`). Requests that somehow reach the rate limiter without a principal throw `UnauthorizedException` (defense-in-depth — the `authenticate { }` wrapper should have already rejected them).

| Bucket | Name constant | Limit |
|---|---|---|
| Food search / autocomplete / barcode / smart search stream | `RateLimitNames.FOOD_SEARCH` (`"food-search"`) | 200 req/min/user |
| Image analysis (sync + SSE) | `RateLimitNames.IMAGE_ANALYSIS` (`"image-analysis"`) | 20 req/min/user |

Route groups are wrapped with `rateLimit(RateLimitName(...)) { ... }` inside `Routing.kt`. Adding a new endpoint to an existing bucket just means placing it inside the right block; creating a new bucket requires registering it in both `Application.configureRateLimit()` and `RateLimitNames`.

---

## Smart Food Search

`SmartSearchOrchestrator` sits in front of OFF + USDA for the `/api/food/search` and `/api/food/search/stream` endpoints. It layers a shared canonical catalog (Supabase) and Gemini-driven classification/generation on top of upstream results to deliver a consistent `bestMatch` per query.

**Flow (simplified):**
1. Normalize the query (`QueryNormalizer`) with locale/country context.
2. Look up canonical catalog hit via `CanonicalCatalogGateway` (service-role key).
3. Run upstream OFF (+ USDA if `SMART_SEARCH_USDA_ENABLED`) in parallel.
4. Use Gemini Flash Lite to classify/rank; Gemini Flash to generate a grounded `bestMatch` when the catalog misses.
5. Validate AI output with `NutritionValidator`; if `confidence >= CATALOG_WRITE_CONFIDENCE_THRESHOLD`, persist to the canonical catalog (sync on miss, or async write-behind, per config).

**Kill-switch / flags** (see env table below):
- `SMART_FOOD_SEARCH_ENABLED=false` — orchestrator short-circuits to upstream-only; catalog + AI clients are still constructed but never called.
- `SMART_SEARCH_USDA_ENABLED` — disables USDA fan-out without recompiling.
- `SMART_SEARCH_AI_SYNC_ON_MISS=false` (default) — first user gets upstream-only while AI warms the catalog in the background; subsequent users hit the warm catalog. Set `true` for a higher-latency but immediately-canonical response.

**Important constraints:**
- Service role key handling: `SUPABASE_SERVICE_ROLE_KEY` is **backend-only**; never surface it to clients, never log it. Required when `SMART_FOOD_SEARCH_ENABLED=true` — `ConfigLoader` fails at startup if absent.
- The background scope uses `SupervisorJob` + `Dispatchers.IO` and is cancelled on `ApplicationStopping` so in-flight writes finish cleanly.
- Cold-start friendly: the catalog + Gemini clients rebuild their state on boot; no disk persistence in the service itself.

---

## Image analysis

Two endpoints are exposed under `/api/food`:

- `POST /api/food/analyze-image` — synchronous, returns `ImageAnalysisResponse` JSON
- `POST /api/food/analyze-image-stream` — streaming SSE; sends `status` events during analysis, then a final `result` or `error` event

Both accept `AnalyzeImageRequest` (base64-encoded image bytes + optional `mealTitle`, `additionalContext`, `locale`, `mimeType`).

The `ImageAnalyzer` functional interface decouples route handlers from the backend:

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

**Backends:**

| Backend | Class | Notes |
|---|---|---|
| **Gemini** (primary) | `GeminiApiService` | Flash model; context cache with 1h TTL, Mutex-protected; 90s timeout |
| **OpenAI** (fallback) | `OpenAiApiService` | GPT-5-mini via Responses API; reasoning effort "low"; 120s timeout |

Selection is controlled by `config.useGeminiForAiImage` (env `USE_GEMINI`, defaults to `true`).
The system prompt lives in `ImageAnalyzerFactory.IMAGE_ANALYZE_SYSTEM_PROMPT` inside `ImageAnalyzer.kt`.

---

## Environment & configuration

### `config/Environment.kt` — `ConfigLoader` pattern

```kotlin
data class AppConfig(
    val environment: AppEnvironment,
    val apiKeys: ApiKeys,
    val useGemini: Boolean,
    val geminiApiKey: String,
)

data class ApiKeys(
    val fatSecretClientId: String,
    val fatSecretClientSecret: String,
    val usdaApiKey: String,
    val openAiApiKey: String,
)

object ConfigLoader {
    fun loadConfig(): AppConfig { ... }  // reads via dotenv (ignoreIfMissing = true)
}
```

`ConfigLoader.loadConfig()` is called once at startup. Missing required vars cause an immediate startup
failure with a clear error — no silent null propagation.

### Debug (local development)

1. Copy `.env.example` to `.env` and fill in real keys
2. `dotenv-kotlin` is always loaded with `ignoreIfMissing = true` — falls back to system env vars
3. `application.conf` sets port `8080` (overridable via `PORT` env var)
4. `APP_ENVIRONMENT=development` (or absent) → `AppEnvironment.DEVELOPMENT`
5. Logback uses console output at `DEBUG` level for `com.zenthek`
6. Run with `./gradlew run` — Ktor development mode enables auto-reload

### Production (Cloud Run)

1. Secrets are injected via Cloud Run environment variables or Secret Manager — **no `.env` file in prod**
2. `application.conf` reads port from `${?PORT}` (Cloud Run injects `PORT` automatically)
3. `APP_ENVIRONMENT=production` — set in Cloud Run service config
4. Container is built and pushed with Jib: `./gradlew jib` (no Dockerfile)

---

## Environment variables reference

### Runtime (injected into the server process)

| Variable | Required | Description |
|---|---|---|
| `FATSECRET_CLIENT_ID` | Yes | FatSecret OAuth2 client ID (legacy — still validated at startup) |
| `FATSECRET_CLIENT_SECRET` | Yes | FatSecret OAuth2 client secret (legacy — still validated at startup) |
| `USDA_API_KEY` | Yes | USDA FoodData Central API key |
| `OPENAI_API_KEY` | Yes | OpenAI API key (fallback image analysis) |
| `GEMINI_API_KEY` | Yes | Google Gemini API key (primary image analysis + Smart Search AI) |
| `USE_GEMINI` | No (default `true`) | Choose image analysis backend: `true` = Gemini, `false` = OpenAI |
| `PORT` | No (default `8080`) | Injected by Cloud Run automatically |
| `APP_ENVIRONMENT` | No (default `development`) | Set to `production` on Cloud Run |
| **Supabase** | | |
| `SUPABASE_URL` | Yes | Base URL (e.g., `https://xxx.supabase.co`) — used for issuer, JWKS, REST |
| `SUPABASE_PUBLISHABLE_KEY` | Yes¹ | Modern Supabase publishable key (preferred) |
| `SUPABASE_ANON_KEY` / `SUPABASE_DEV_ANON_KEY` | Yes¹ | Legacy anon JWT fallback (`SUPABASE_DEV_ANON_KEY` is read in development, `SUPABASE_ANON_KEY` in production) |
| `SUPABASE_JWT_VERIFICATION_MODE` | No (default `JWKS`) | `JWKS` or `REMOTE` — see Authentication section |
| `SUPABASE_SERVICE_ROLE_KEY` | Conditional | **Required when `SMART_FOOD_SEARCH_ENABLED=true`**. Backend-only. Never expose to clients. |
| **Smart Food Search** | | |
| `SMART_FOOD_SEARCH_ENABLED` | No (default `false`) | Master switch for `SmartSearchOrchestrator` |
| `SMART_SEARCH_USDA_ENABLED` | No (default `true`) | Kill-switch for USDA fan-out inside Smart Search |
| `AI_SEARCH_RANK_MODEL` | No (default `gemini-2.5-flash-lite`) | Gemini model for classify/rank |
| `AI_SEARCH_GENERATE_MODEL` | No (default `gemini-2.5-flash`) | Gemini model for grounded best-match generation |
| `AI_SEARCH_CLASSIFY_TIMEOUT_MS` | No (default `3000`) | Timeout for Gemini classify call |
| `AI_SEARCH_GENERATE_TIMEOUT_MS` | No (default `8000`) | Timeout for Gemini generate call |
| `SMART_SEARCH_AI_SYNC_ON_MISS` | No (default `false`) | `false` = async write-behind; `true` = sync-on-miss (higher latency) |
| `CATALOG_WRITE_CONFIDENCE_THRESHOLD` | No (default `0.7`) | Minimum AI confidence required to persist to canonical catalog |

¹ At least one of `SUPABASE_PUBLISHABLE_KEY` or the legacy anon key must be present. Startup fails with a clear error otherwise.

---

## `.env.example` — commit this file verbatim

```
# Copy this file to .env (or .env.dev / .env.prod) and fill in real values. Never commit the filled copy.
USE_GEMINI=true
FATSECRET_CLIENT_ID=""
FATSECRET_CLIENT_SECRET=""
USDA_API_KEY=""
OPENAI_API_KEY=""
APP_ENVIRONMENT=development
PORT=8080
GEMINI_API_KEY=""

# Supabase (auth + canonical catalog)
SUPABASE_URL=""
SUPABASE_PUBLISHABLE_KEY=
SUPABASE_JWT_VERIFICATION_MODE=JWKS
# Required only when SMART_FOOD_SEARCH_ENABLED=true. Backend-only; never expose to clients.
SUPABASE_SERVICE_ROLE_KEY=""

# Smart Food Search (canonical catalog + AI-grounded generic best match)
SMART_FOOD_SEARCH_ENABLED=true
SMART_SEARCH_USDA_ENABLED=true
AI_SEARCH_RANK_MODEL=gemini-2.5-flash-lite
AI_SEARCH_GENERATE_MODEL=gemini-2.5-flash
AI_SEARCH_CLASSIFY_TIMEOUT_MS=3000
AI_SEARCH_GENERATE_TIMEOUT_MS=8000
# false = async write-behind (low latency); true = sync-on-miss (canonical immediately).
SMART_SEARCH_AI_SYNC_ON_MISS=false
CATALOG_WRITE_CONFIDENCE_THRESHOLD=0.7
```

---

## Logback configuration

### `logback.xml` (single config — console output for both dev and prod)

```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{YYYY-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    <root level="INFO">
        <appender-ref ref="STDOUT"/>
    </root>
    <logger name="com.zenthek" level="DEBUG"/>
    <logger name="org.eclipse.jetty" level="INFO"/>
    <logger name="io.netty" level="INFO"/>
</configuration>
```

There is no separate `logback-prod.xml`. Cloud Run captures stdout and forwards it to Google Cloud Logging.

---

## Container build (Jib)

There is **no Dockerfile** in this repository. Container images are built and pushed with the Jib Gradle plugin:

```bash
./gradlew jib                  # push to GCR (dev target: gcr.io/fitzenio-debug/fitzenio-api-dev)
./gradlew jib -Pprod           # push to GCR (prod target: gcr.io/fitzenio/fitzenio-api-prod)
./gradlew jibDockerBuild       # build to local Docker daemon (for local testing)
```

Jib config in `build.gradle.kts`: base image `eclipse-temurin:21-jre` (linux/amd64), tagged `latest` + timestamp.

---

## Deployment

See `DEPLOY.md` for the full deployment guide.

Two environments with separate GCP projects:

| Environment | Script | GCP Project | Cloud Run Service |
|---|---|---|---|
| **Staging/Dev** | `./deploy-dev.sh` | `fitzenio-debug` | `fitzenio-api-dev` |
| **Production** | `./deploy.sh` | `fitzenio` | `fitzenio-api-prod` |

Cloud Run service configs: `cloud-run-config.yaml` (prod), `cloud-run-config.dev.yaml` (staging).
Secrets are managed via Google Cloud Secret Manager (`grant-secrets.sh` sets IAM bindings).

---

## Common commands

```bash
./gradlew run                          # Start dev server (port 8080, auto-reload)
./gradlew run --continuous             # Rebuild + restart on code changes
./gradlew test                         # Run all tests
./gradlew jibDockerBuild               # Build container image to local Docker daemon
./gradlew jib                          # Build + push to GCR (dev)
./gradlew jib -Pprod                   # Build + push to GCR (prod)
./deploy-dev.sh                        # Deploy to Cloud Run staging
./deploy.sh                            # Deploy to Cloud Run production
```

---

## Security rules

- **Never commit `.env`** — add it to `.gitignore` on project creation, before any other commit
- **Never hardcode API keys** — always via `ConfigLoader` / dotenv; catch missing keys at startup
- **Never log secrets** — no `println(config)`, no logging full request bodies that may contain keys
- **Never return internal error details to clients** — `StatusPages` catches `Throwable` and returns
  a generic 500 body; log the real cause server-side only
- Open Food Facts requires no key — but always send a `User-Agent` header identifying the app
  (OFF's fair-use policy requires this): `User-Agent: FitzenioApp/1.0 (contact@zenthek.com)`
- FatSecret OAuth2 token is cached in memory — never written to disk, never logged
- Gemini context cache ID is cached in memory — never written to disk, never logged
- **Never log Supabase JWTs or bearer tokens** — they are user credentials; also applies to the service role key
- **`SUPABASE_SERVICE_ROLE_KEY` is backend-only** — injected via Secret Manager on Cloud Run, never surfaced in client responses, never echoed in logs
- **Only `GET /health` is public** — every other route must live inside `authenticate(SUPABASE_AUTH_PROVIDER) { ... }`. If you add a new route, default to placing it inside the authenticated block unless explicitly required otherwise.

---

## Testing conventions

> **Do not write new tests (unit, integration, route, or otherwise) unless the user explicitly asks.**
> The conventions below describe the STYLE to follow when tests are explicitly requested.
> Default workflow: skip the test-writing step. Validate changes via `./gradlew compileKotlin compileTestKotlin` and running the existing test suite (`./gradlew test`) to catch regressions — do not add new coverage proactively.
> If you believe a specific test is essential for correctness, mention it as a suggestion in chat; do not write it.

### Mapper unit tests

Test each mapper in isolation with hardcoded DTO fixture data. No network required.

```kotlin
class OpenFoodFactsMapperTest {
    @Test
    fun `maps product with all fields`() {
        val dto = OpenFoodFactsProductDto(
            code = "1234567890123",
            product = ProductDto(productName = "Test Food", ...)
        )
        val result = OpenFoodFactsMapper.map(dto)
        assertEquals("Test Food", result.name)
    }
}
```

### Upstream client tests with MockEngine

```kotlin
@Test
fun `search returns parsed results`() = runTest {
    val client = HttpClient(MockEngine { request ->
        respond(
            content = ByteReadChannel("""{"hits": [], "count": 0, "page": 1, "page_size": 25}"""),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json")
        )
    }) { install(ContentNegotiation) { json() } }

    val offClient = OpenFoodFactsClient(client)
    val result = offClient.search("banana", 0, 25)
    assertTrue(result.isEmpty())
}
```

### Route integration tests

```kotlin
@Test
fun `GET food search returns 200`() = testApplication {
    application { module() }
    val response = client.get("/api/food/search?q=banana")
    assertEquals(HttpStatusCode.OK, response.status)
}
```

### FatSecret JsonTransformingSerializer test

Test both the object-shaped and array-shaped responses explicitly — this is the most fragile serialization
in the project. Use literal JSON strings as fixtures. Affected fields:
- `servings.serving` — single object or array
- `foods_search.results.food` — single object or array
- `suggestions.suggestion` — single string or array

---

## Important constraints

- **Never expose raw upstream errors to clients** — normalize all upstream failures to a generic error
  response; log the real error with context
- **FatSecret is currently NOT wired into `FoodService`** — the client + token manager + mapper are retained for reference but not constructed in `Application.module()`. Do not re-wire without updating this doc. The `Mutex` contract still applies if it is re-enabled.
- **Gemini context cache mutex is mandatory** — without a `Mutex`, concurrent requests will race to refresh the cache ID, causing double-refresh and potential invalidation
- **USDA search uses GET** with query params (not POST)
- **OFF barcode uses `/api/v3/product/{code}`** — response `status` is now a string `"success"`, not integer `1`
- **OFF search uses `https://search.openfoodfacts.org/search`** (search-a-licious) — response has `hits` (not `products`), `brands` is `List<String>` (not a comma-separated string), page is 1-indexed
- **OFF autocomplete uses `https://search.openfoodfacts.org/search`** with small `page_size` — returns product name strings
- **FatSecret search uses `foods.search.v5`** via `GET https://platform.fatsecret.com/rest/foods/search/v5` — response is `foods_search.results.food[...]` with full servings inline; requires **premier scope**
- **FatSecret autocomplete uses `foods.autocomplete.v2`** via `GET https://platform.fatsecret.com/rest/food/autocomplete/v2` — parameter is `expression`, response is `suggestions.suggestion` (single string or array); requires **premier scope**
- **FatSecret `serving`, `results.food`, and `suggestions.suggestion` fields are polymorphic** — they can be a JSON object/string OR an array; always use `JsonTransformingSerializer` to normalize to list
- **FatSecret `mapDetail()` covers both barcode and search** — v5 search returns full servings per food item, so `mapSummary` (description-regex parsing) is gone
- **Never use `mapOf(...)` with mixed value types for Ktor responses** — use a typed `@Serializable` data class; `Map<String, Any>` causes a serialization runtime error
- **One Ktor client instance** shared across all upstream clients — configured with `requestTimeoutMillis = 10_000` and `connectTimeoutMillis = 5_000`; do not create a separate client per upstream service
- **No shared state between requests** — the service is stateless except for the in-memory FatSecret
  token cache and Gemini context cache ID (both intentional and protected by Mutexes)
- **Scale-to-zero friendly** — do not assume warm state; FatSecret token and Gemini context cache will need re-fetch on cold start

---

## Gradle dependencies reference

Dependencies are managed via the version catalog at `gradle/libs.versions.toml`.

Key versions: Kotlin `2.1.10`, Ktor `3.2.3`, kotlinx.serialization `1.7.3`, kotlinx.coroutines `1.9.0`, Logback `1.5.13`, dotenv-kotlin `6.4.1`.

Plugins in `build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
}
```

Key dependencies:

```kotlin
dependencies {
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)          // JWKS verification + `jwt { }` provider
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.rate.limit)        // per-user rate limiting
    implementation(libs.google.api.client)             // transitive helpers used by upstream clients
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.logback.classic)
    implementation(libs.dotenv.kotlin)
    implementation(libs.bundles.ktor.client)           // core, cio, content-negotiation, logging, auth

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlin.test.junit)
}
```

Note: `com.auth0:jwk` / `com.auth0:java-jwt` come in transitively via `ktor-server-auth-jwt` — do not add them explicitly.
