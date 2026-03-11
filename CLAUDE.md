> **Copy this file to the root of your Ktor project as `CLAUDE.md`.**
> Cross-reference `food-api-backend.md` for the full API specification — both files travel together.

---

# CLAUDE.md — Food API Backend

> Ktor-based food API proxy that aggregates Open Food Facts, FatSecret, and USDA FoodData Central.
> Deployed as a containerized JVM service on Google Cloud Run.

## Project overview

This service is a thin aggregation proxy that the Fitzenio mobile app calls for food search and barcode
lookup. It does not store data — it fans out to up to three upstream food APIs, merges the results, and
returns a normalized response. See `food-api-backend.md` for the full endpoint spec, response schemas,
and upstream API details.

**Deployment target:** Google Cloud Run (containerized, stateless, scales to zero)

---

## Tech stack

| Concern | Library | Notes |
|---|---|---|
| **Server** | Ktor 3.1.x (Netty engine) | `ktor-server-netty` |
| **HTTP client** | Ktor 3.1.x (CIO engine) | `ktor-client-cio` |
| **Serialization** | kotlinx.serialization 1.7.x | JSON only |
| **Logging** | Logback 1.5.x | Console (dev) / JSON (prod) |
| **JSON logging** | logstash-logback-encoder 8.x | Production structured logs |
| **Dev env loading** | dotenv-kotlin 6.x | Load `.env` in development only |
| **JDK** | 21 | Eclipse Temurin in Docker |
| **Build** | Gradle 8.x Kotlin DSL | Version catalog preferred |
| **Fat JAR** | Shadow JAR plugin | `gradle shadowJar` → single runnable jar |

---

## Project structure

```
fitzenio-api/
├── src/
│   └── main/
│       ├── kotlin/
│       │   └── com/zenthek/
│       │       ├── Application.kt              # embeddedServer entry point
│       │       ├── config/
│       │       │   └── Environment.kt          # env var loading
│       │       ├── model/
│       │       │   └── FoodItem.kt             # FoodItem, NutritionInfo, ServingSize,
│       │       │                               # SearchResponse, ApiError
│       │       ├── routes/
│       │       │   └── Routing.kt              # all routes: health, search, barcode,
│       │       │                               # autocomplete, analyze-image
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
│       │       │   └── usda/
│       │       │       ├── UsdaClient.kt
│       │       │       └── dto/UsdaDto.kt
│       │       ├── mapper/
│       │       │   ├── OpenFoodFactsMapper.kt  # map() for barcode, mapV3Search() for search
│       │       │   ├── FatSecretMapper.kt      # mapDetail() for both barcode and search
│       │       │   └── UsdaMapper.kt
│       │       └── upstream/openai/
│       │           └── OpenAiApiService.kt     # image analysis
│       └── resources/
│           ├── application.yaml
│           └── logback.xml                     # dev: DEBUG for com.zenthek
├── src/test/kotlin/com/zenthek/
│   ├── mapper/                                 # mapper unit tests with fixture DTOs
│   ├── routes/                                 # testApplication { } integration tests
│   └── upstream/                              # MockEngine client tests
├── .env.example
├── .gitignore
├── Dockerfile
├── deploy.sh
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

### Serialization

- `@Serializable` on every DTO class
- Single `Json` instance configured with `ignoreUnknownKeys = true`, `isLenient = false`
- Install it in `Serialization.kt` plugin and reuse the same instance for upstream client configuration
- FatSecret `serving` and `food` fields can be a JSON object **or** an array — use `JsonTransformingSerializer` to normalize to array before deserialization. This is the most fragile part — test it explicitly.

### Dependency injection

No DI framework. Use plain constructor injection:

```kotlin
// Application.kt
fun Application.module() {
    val apiKeys = ApiKeys.fromEnv()
    val httpClient = buildHttpClient()
    val offClient = OpenFoodFactsClient(httpClient)
    val fatSecretClient = FatSecretClient(httpClient, apiKeys)
    val usdaClient = UsdaClient(httpClient, apiKeys)
    val foodService = FoodService(offClient, fatSecretClient, usdaClient)

    configureSerialization()
    configureStatusPages()
    configureRequestLogging()
    configureRouting(foodService)
}
```

### Error handling

- Throw domain exceptions in the service layer (e.g., `NotFoundException`, `UpstreamException`)
- Catch all `Throwable` in `StatusPages` — map to HTTP status + `ApiError` body
- **Never return internal error details or stack traces to clients**
- Log the real cause server-side with `call.application.log.error("...", cause)`

### Route handlers

- Route handlers only validate input and delegate to `FoodService`
- **Never call upstream APIs from route handlers directly**
- Keep route files thin — extract complex query param parsing to separate functions if needed
- **Never use `mapOf(...)` with mixed value types for responses** — kotlinx.serialization cannot serialize `Map<String, Any>`. Always use a typed `@Serializable` data class instead.

---

## Environment & configuration

### Debug (local development)

1. Copy `.env.example` to `.env` and fill in real keys
2. `dotenv-kotlin` loads `.env` automatically when `APP_ENV` is not `production`
3. `application.yaml` sets `development: true` and port `8080`
4. Logback uses colored console output at `DEBUG` level for `com.zenthek`
5. Run with `./gradlew run` — Ktor development mode enables auto-reload

### Production (Cloud Run)

1. Secrets are injected as Cloud Run environment variables — **no `.env` file in prod**
2. `application.yaml` reads port from `${PORT:8080}` (Cloud Run injects `PORT` automatically)
3. `APP_ENV=production` — set this in the Cloud Run deploy command
4. Logback uses JSON structured output (`logback-prod.xml`) for Google Cloud Logging integration
5. The Dockerfile sets `-Dlogback.configurationFile=logback-prod.xml`

### `config/ApiKeys.kt` pattern

```kotlin
data class ApiKeys(
    val fatSecretClientId: String,
    val fatSecretClientSecret: String,
    val usdaApiKey: String,
) {
    companion object {
        fun fromEnv() = ApiKeys(
            fatSecretClientId = requireEnv("FATSECRET_CLIENT_ID"),
            fatSecretClientSecret = requireEnv("FATSECRET_CLIENT_SECRET"),
            usdaApiKey = requireEnv("USDA_API_KEY"),
        )
    }
}

private fun requireEnv(name: String) =
    System.getenv(name) ?: error("Missing required env var: $name")
```

Call `ApiKeys.fromEnv()` once in `Application.module()` at startup. If a required var is missing the
server fails immediately with a clear error — no silent null propagation.

### dotenv-kotlin integration

Load `.env` before `ApiKeys.fromEnv()` in development:

```kotlin
fun Application.module() {
    if (System.getenv("APP_ENV") != "production") {
        // dotenv-kotlin: loads .env from working directory if it exists
        val dotenv = dotenv { ignoreIfMissing = true }
        dotenv.entries().forEach { (key, value) ->
            if (System.getenv(key) == null) {
                // dotenv-kotlin does NOT set system env vars — use dotenv["KEY"] directly
                // or configure ApiKeys to accept a map
            }
        }
    }
    // ...
}
```

Simpler approach: configure `ApiKeys` to accept `dotenv["KEY"] ?: System.getenv("KEY")`:

```kotlin
// In Application.module(), always load dotenv (ignoreIfMissing = true in prod)
val dotenv = dotenv { ignoreIfMissing = true }
val apiKeys = ApiKeys(
    fatSecretClientId = dotenv["FATSECRET_CLIENT_ID"],
    fatSecretClientSecret = dotenv["FATSECRET_CLIENT_SECRET"],
    usdaApiKey = dotenv["USDA_API_KEY"],
)
```

`dotenv-kotlin` falls back to system env vars automatically when a key is not in `.env`.

---

## Environment variables reference

### Runtime (injected into the server process)

| Variable | Required | Description |
|---|---|---|
| `FATSECRET_CLIENT_ID` | Yes | FatSecret OAuth2 client ID |
| `FATSECRET_CLIENT_SECRET` | Yes | FatSecret OAuth2 client secret |
| `USDA_API_KEY` | Yes | USDA FoodData Central API key |
| `PORT` | No (default `8080`) | Injected by Cloud Run automatically |
| `APP_ENV` | No (default `development`) | Set to `production` on Cloud Run |

### Shell-only (used by `deploy.sh`, never injected into the server)

| Variable | Description |
|---|---|
| `GCP_PROJECT_ID` | Your Google Cloud project ID |
| `CLOUD_RUN_REGION` | Cloud Run region (default `us-central1`) |
| `STAGING_FATSECRET_CLIENT_ID` | Staging-specific FatSecret client ID (separate quota) |
| `STAGING_FATSECRET_CLIENT_SECRET` | Staging-specific FatSecret client secret |
| `STAGING_USDA_API_KEY` | Staging-specific USDA key |

---

## `.env.example` — commit this file verbatim

```
# Runtime — copy to .env and fill in real values (never commit .env)
FATSECRET_CLIENT_ID=your_client_id_here
FATSECRET_CLIENT_SECRET=your_client_secret_here
USDA_API_KEY=your_usda_key_here
APP_ENV=development
PORT=8080

# Deploy script (shell only — not injected into the server)
GCP_PROJECT_ID=your-gcp-project-id
CLOUD_RUN_REGION=us-central1
STAGING_FATSECRET_CLIENT_ID=your_staging_client_id
STAGING_FATSECRET_CLIENT_SECRET=your_staging_client_secret
STAGING_USDA_API_KEY=your_staging_usda_key
```

---

## Logback configuration

### `logback.xml` (development — colored console)

```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} %highlight(%-5level) %cyan(%logger{36}) - %msg%n</pattern>
        </encoder>
    </appender>

    <logger name="com.zenthek" level="DEBUG"/>
    <logger name="io.ktor" level="INFO"/>

    <root level="INFO">
        <appender-ref ref="STDOUT"/>
    </root>
</configuration>
```

### `logback-prod.xml` (production — JSON for Google Cloud Logging)

```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
    </appender>

    <logger name="com.zenthek" level="INFO"/>
    <logger name="io.ktor" level="WARN"/>

    <root level="WARN">
        <appender-ref ref="STDOUT"/>
    </root>
</configuration>
```

The Dockerfile activates the production config via `-Dlogback.configurationFile=logback-prod.xml`.

---

## Dockerfile

```dockerfile
FROM gradle:8.7-jdk21 AS builder
WORKDIR /app
COPY . .
RUN gradle shadowJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/build/libs/*-all.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Dlogback.configurationFile=logback-prod.xml", "-jar", "app.jar"]
```

The production image is Alpine-based JRE only — no Gradle, no source. Keep it small.

---

## `deploy.sh` — Cloud Run deployment script

Supports two environments: `staging` and `production`. Each deploys to a separate Cloud Run service.
Secrets are passed as env vars in the deploy command — no Secret Manager setup required.

```bash
#!/usr/bin/env bash
set -euo pipefail

ENV="${1:-production}"   # staging | production

PROJECT_ID="${GCP_PROJECT_ID:?Set GCP_PROJECT_ID env var}"
REGION="${CLOUD_RUN_REGION:-us-central1}"
IMAGE="gcr.io/$PROJECT_ID/food-api"

case "$ENV" in
  staging)
    SERVICE_NAME="food-api-staging"
    FATSECRET_ID="${STAGING_FATSECRET_CLIENT_ID:?Set STAGING_FATSECRET_CLIENT_ID}"
    FATSECRET_SECRET="${STAGING_FATSECRET_CLIENT_SECRET:?Set STAGING_FATSECRET_CLIENT_SECRET}"
    USDA_KEY="${STAGING_USDA_API_KEY:?Set STAGING_USDA_API_KEY}"
    ;;
  production)
    SERVICE_NAME="food-api"
    FATSECRET_ID="${FATSECRET_CLIENT_ID:?Set FATSECRET_CLIENT_ID}"
    FATSECRET_SECRET="${FATSECRET_CLIENT_SECRET:?Set FATSECRET_CLIENT_SECRET}"
    USDA_KEY="${USDA_API_KEY:?Set USDA_API_KEY}"
    ;;
  *)
    echo "Usage: $0 [staging|production]"
    exit 1
    ;;
esac

echo "Deploying to Cloud Run [$ENV] → $SERVICE_NAME ($REGION)..."

./gradlew shadowJar --no-daemon
docker build -t "$IMAGE" .
docker push "$IMAGE"

gcloud run deploy "$SERVICE_NAME" \
  --image "$IMAGE" \
  --platform managed \
  --region "$REGION" \
  --memory 512Mi \
  --cpu 1 \
  --min-instances 0 \
  --max-instances 10 \
  --set-env-vars "APP_ENV=production,FATSECRET_CLIENT_ID=$FATSECRET_ID,FATSECRET_CLIENT_SECRET=$FATSECRET_SECRET,USDA_API_KEY=$USDA_KEY" \
  --allow-unauthenticated \
  --project "$PROJECT_ID"

echo "Done. URL:"
gcloud run services describe "$SERVICE_NAME" \
  --region "$REGION" \
  --project "$PROJECT_ID" \
  --format "value(status.url)"
```

### Deploy usage

```bash
# Prerequisites
gcloud auth login
gcloud auth configure-docker
# Docker Desktop must be running

export GCP_PROJECT_ID=my-gcp-project

# Deploy to staging
export STAGING_FATSECRET_CLIENT_ID=...
export STAGING_FATSECRET_CLIENT_SECRET=...
export STAGING_USDA_API_KEY=...
./deploy.sh staging

# Deploy to production
export FATSECRET_CLIENT_ID=...
export FATSECRET_CLIENT_SECRET=...
export USDA_API_KEY=...
./deploy.sh production   # or just: ./deploy.sh
```

Staging (`food-api-staging`) and production (`food-api`) are **separate Cloud Run services** with
separate API keys — useful for preserving your daily FatSecret free-tier quota during development.

---

## Common commands

```bash
./gradlew run                          # Start dev server (port 8080, auto-reload)
./gradlew run --continuous             # Rebuild + restart on code changes
./gradlew test                         # Run all tests
./gradlew shadowJar                    # Build fat JAR → build/libs/*-all.jar
docker build -t food-api .             # Build container locally
docker run -p 8080:8080 \
  --env-file .env food-api             # Run container with local .env
./deploy.sh staging                    # Deploy to Cloud Run staging
./deploy.sh production                 # Deploy to Cloud Run production (default)
```

---

## Security rules

- **Never commit `.env`** — add it to `.gitignore` on project creation, before any other commit
- **Never hardcode API keys** — always `System.getenv()` or dotenv; catch missing keys at startup
- **Never log secrets** — no `println(apiKeys)`, no logging full request bodies that may contain keys
- **Never return internal error details to clients** — `StatusPages` catches `Throwable` and returns
  a generic `500` body (`ApiError`); log the real cause server-side only
- Open Food Facts requires no key — but always send a `User-Agent` header identifying the app
  (OFF's fair-use policy requires this): `User-Agent: FitzenioApp/1.0 (contact@zenthek.com)`
- FatSecret OAuth2 token is cached in memory — never written to disk, never logged

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
    val response = client.get("/food/search?q=banana")
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

- **Never expose raw upstream errors to clients** — normalize all upstream failures to `ApiError` with
  a generic message; log the real error with context
- **FatSecret token mutex is mandatory** — without a `Mutex`, concurrent requests will race to refresh
  the OAuth2 token, causing double-refresh and potential token invalidation
- **USDA search uses GET** with query params (not POST)
- **OFF barcode uses `/api/v3/product/{code}`** — response `status` is now a string `"success"`, not integer `1`
- **OFF search uses `https://search.openfoodfacts.org/search`** (search-a-licious) — response has `hits` (not `products`), `brands` is `List<String>` (not a comma-separated string), page is 1-indexed
- **OFF autocomplete uses `https://search.openfoodfacts.org/search`** with small `page_size` — returns product name strings
- **FatSecret search uses `foods.search.v5`** via `GET https://platform.fatsecret.com/rest/foods/search/v5` — response is `foods_search.results.food[...]` with full servings inline; requires **premier scope**
- **FatSecret autocomplete uses `foods.autocomplete.v2`** via `GET https://platform.fatsecret.com/rest/food/autocomplete/v2` — parameter is `expression`, response is `suggestions.suggestion` (single string or array); requires **premier scope**
- **FatSecret `serving`, `results.food`, and `suggestions.suggestion` fields are polymorphic** — they can be a JSON object/string OR an array; always use `JsonTransformingSerializer` to normalize to list
- **FatSecret `mapDetail()` covers both barcode and search** — v5 search returns full servings per food item, so `mapSummary` (description-regex parsing) is gone
- **Never use `mapOf(...)` with mixed value types for Ktor responses** — use a typed `@Serializable` data class; `Map<String, Any>` causes a serialization runtime error
- **One Ktor client instance** shared across all upstream clients is fine — configure per-call timeouts
  if needed via `.config {}`. Do not create a separate client per upstream service.
- **No shared state between requests** — the service is stateless except for the in-memory FatSecret
  token cache (which is intentional and protected by a Mutex)
- **Scale-to-zero friendly** — do not assume warm state; FatSecret token will need re-fetch on cold start

---

## Gradle dependencies reference

```kotlin
// build.gradle.kts
val ktorVersion = "3.1.0"
val kotlinxSerializationVersion = "1.7.3"
val logbackVersion = "1.5.12"

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-default-headers:$ktorVersion")

    // Ktor client
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")

    // Serialization
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")

    // Logging
    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")

    // Dev env loading
    implementation("io.github.cdimascio:dotenv-kotlin:6.4.1")

    // Testing
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}
```

Apply the Shadow plugin in `plugins {}`:

```kotlin
plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    id("io.ktor.plugin") version "3.1.0"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}
```
