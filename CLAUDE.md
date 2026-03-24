> Cross-reference `food-api-backend.md` for the full API specification — both files travel together.

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
| **Serialization** | kotlinx.serialization 1.7.3 | JSON only |
| **Logging** | Logback 1.5.13 | Console output only (single `logback.xml`) |
| **Dev env loading** | dotenv-kotlin 6.4.1 | Always loaded, `ignoreIfMissing = true` |
| **Image analysis** | Gemini Flash Lite (primary) / GPT-4o mini (fallback) | Controlled by `config.useGemini` |
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
│       │       │   └── Environment.kt          # AppConfig, ApiKeys, AppEnvironment, ConfigLoader
│       │       ├── model/
│       │       │   ├── FoodItem.kt             # FoodItem, NutritionInfo, ServingSize,
│       │       │   │                           # SearchResponse, ApiError,
│       │       │   │                           # ImageAnalysisItem, ImageAnalysisResponse, AnalyzeImageRequest
│       │       │   └── ImageAnalyzer.kt        # ImageAnalyzer functional interface + ImageAnalyzerFactory (system prompt)
│       │       ├── routes/
│       │       │   └── Routing.kt              # all routes: health, search, barcode,
│       │       │                               # autocomplete, analyze-image, analyze-image-stream
│       │       ├── service/
│       │       │   ├── FoodService.kt          # fan-out, merge, deduplicate, autocomplete
│       │       │   └── UpstreamFailureException.kt
│       │       ├── network/
│       │       │   └── HttpClientProvider.kt
│       │       ├── upstream/
│       │       │   ├── openfoodfacts/
│       │       │   │   ├── OpenFoodFactsClient.kt   # v3 product + search-a-licious search + autocomplete
│       │       │   │   └── dto/OpenFoodFactsDto.kt
│       │       │   ├── fatsecret/
│       │       │   │   ├── FatSecretClient.kt       # foods.search.v5 + autocomplete.v2 + barcode
│       │       │   │   ├── FatSecretTokenManager.kt # OAuth2 with Mutex
│       │       │   │   └── dto/FatSecretDto.kt
│       │       │   ├── usda/
│       │       │   │   ├── UsdaClient.kt
│       │       │   │   └── dto/UsdaDto.kt
│       │       │   ├── gemini/
│       │       │   │   └── GeminiApiService.kt      # Gemini Flash Lite image analysis (primary)
│       │       │   └── openai/
│       │       │       └── OpenAiApiService.kt      # GPT-4o mini image analysis (fallback)
│       │       └── mapper/
│       │           ├── OpenFoodFactsMapper.kt  # map() for barcode, mapV3Search() for search
│       │           ├── FatSecretMapper.kt      # mapDetail() for both barcode and search
│       │           └── UsdaMapper.kt
│       └── resources/
│           ├── application.conf                # HOCON config: port, module ref, PROJECT_ID
│           └── logback.xml                     # console output, DEBUG for com.zenthek
├── src/test/kotlin/com/zenthek/
│   ├── mapper/                                 # mapper unit tests with fixture DTOs
│   ├── routes/                                 # testApplication { } integration tests
│   └── upstream/                              # MockEngine client tests
├── gradle/
│   └── libs.versions.toml                     # centralized version catalog
├── .env.example
├── .gitignore
├── cloud-run-config.yaml                       # production Cloud Run service config
├── cloud-run-config.dev.yaml                   # staging Cloud Run service config
├── deploy.sh                                   # production deploy (fitzenio GCP project)
├── deploy-dev.sh                               # staging deploy (fitzenio-debug GCP project)
├── grant-secrets.sh                            # Secret Manager IAM bindings
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
    val config = ConfigLoader.loadConfig()  // loads AppConfig with ApiKeys + image analysis config
    val httpClient = buildHttpClient()

    val offClient = OpenFoodFactsClient(httpClient)
    val fsTokenManager = FatSecretTokenManager(httpClient, config.apiKeys)
    val fsClient = FatSecretClient(httpClient, fsTokenManager)
    val usdaClient = UsdaClient(httpClient, config.apiKeys.usdaApiKey)
    val imageAnalyzer: ImageAnalyzer = if (config.useGemini) {
        GeminiApiService(httpClient, config.geminiApiKey)
    } else {
        OpenAiApiService(httpClient, config.apiKeys.openAiApiKey)
    }
    val foodService = FoodService(offClient, fsClient, usdaClient)

    install(ContentNegotiation) { json(appJson) }
    install(StatusPages) { ... }
    configureRouting(foodService, imageAnalyzer)
}
```

### Error handling

- Throw domain exceptions in the service layer (e.g., `NotFoundException`, `UpstreamException`)
- Catch all `Throwable` in `StatusPages` — map to HTTP 500 + `mapOf("error" to ..., "message" to ...)`
- **Never return internal error details or stack traces to clients**
- Log the real cause server-side with `log.error("...", cause)`

### Route handlers

- Route handlers only validate input and delegate to `FoodService` or `ImageAnalyzer`
- **Never call upstream APIs from route handlers directly**
- Keep route files thin — extract complex query param parsing to separate functions if needed
- **Never use `mapOf(...)` with mixed value types for responses** — kotlinx.serialization cannot serialize `Map<String, Any>`. Always use a typed `@Serializable` data class instead. (Exception: homogeneous `Map<String, String>` is fine.)

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
| **Gemini** (primary) | `GeminiApiService` | Flash Lite model; context cache with 1h TTL, Mutex-protected; 90s timeout |
| **OpenAI** (fallback) | `OpenAiApiService` | GPT-4o mini via Responses API; reasoning effort "low"; 120s timeout |

Selection is controlled by `config.useGemini` (currently hardcoded `true` in `ConfigLoader`).
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
| `FATSECRET_CLIENT_ID` | Yes | FatSecret OAuth2 client ID |
| `FATSECRET_CLIENT_SECRET` | Yes | FatSecret OAuth2 client secret |
| `USDA_API_KEY` | Yes | USDA FoodData Central API key |
| `OPENAI_API_KEY` | Yes | OpenAI API key (fallback image analysis) |
| `GEMINI_API_KEY` | Yes | Google Gemini API key (primary image analysis) |
| `PORT` | No (default `8080`) | Injected by Cloud Run automatically |
| `APP_ENVIRONMENT` | No (default `development`) | Set to `production` on Cloud Run |

---

## `.env.example` — commit this file verbatim

```
# Copy this file to .env and fill in real values. Never commit .env
FATSECRET_CLIENT_ID=your_fatsecret_client_id
FATSECRET_CLIENT_SECRET=your_fatsecret_client_secret
USDA_API_KEY=your_usda_api_key
OPENAI_API_KEY=your_openai_api_key
APP_ENVIRONMENT=development
PORT=8080
USE_GEMINI=false
GEMINI_API_KEY=your_gemini_api_key_here
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

---

## Testing conventions

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
- **FatSecret token mutex is mandatory** — without a `Mutex`, concurrent requests will race to refresh
  the OAuth2 token, causing double-refresh and potential token invalidation
- **Gemini context cache mutex is mandatory** — same race condition risk as FatSecret token
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
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.logback.classic)
    implementation(libs.dotenv.kotlin)
    implementation(libs.bundles.ktor.client)   // core, cio, content-negotiation, logging, auth

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
}
```
