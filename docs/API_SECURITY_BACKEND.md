# API Security — Backend Side

> Strategy document for hardening the Fitzenio Ktor backend against endpoint abuse.
> Implement in layers — lower layers are prerequisites for higher ones.
> Deployed on Google Cloud Run (europe-north1). Ktor 3.2.3 / Kotlin 2.1.10.

---

## Current state

| Concern | Status |
|---|---|
| Auth on `/api/food/*` | None — fully open |
| Rate limiting | None |
| Request size limits | None |
| Input validation HTTP codes | All errors return 500 |
| `ktor-server-auth` | Imported but not installed |
| CORS | Not configured |
| Cloud Armor / WAF | Not configured |

---

## Layer 0 — Fix HTTP status codes (quick win, no deps)

**What it does:** Currently all input errors (`IllegalArgumentException`, missing params, bad barcode format) return 500. This is wrong — 500 means server fault; client errors must return 4xx. Fixing this also makes rate-limit and auth error responses consistent.

### Implementation

In `Application.kt`, update the `StatusPages` block:

```kotlin
install(StatusPages) {
    exception<IllegalArgumentException> { call, cause ->
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "Bad request"))
    }
    exception<ContentTransformationException> { call, cause ->
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
    }
    exception<NotFoundException> { call, _ ->
        call.respond(HttpStatusCode.NotFound, ErrorResponse("Not found"))
    }
    exception<Throwable> { call, cause ->
        call.application.log.error("Unhandled error", cause)
        call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error"))
    }
}

@Serializable
data class ErrorResponse(val error: String)
```

**File:** `src/main/kotlin/Application.kt`

---

## Layer 1 — Request size limits (implement immediately)

**What it does:** The `/analyze-image` endpoint accepts a base64-encoded image with no size limit. A malicious client can send a 100MB payload, blocking a Coroutine thread and driving up Cloud Run memory. Cap incoming bodies.

### Limits

| Endpoint | Max body size |
|---|---|
| `POST /api/food/analyze-image` | 5 MB |
| `POST /api/food/analyze-image-stream` | 5 MB |
| All other routes | 64 KB |

A 3MP photo at JPEG 75% quality is ~600KB uncompressed → ~800KB base64-encoded. 5MB is a generous cap that covers all legitimate use cases.

### Implementation

Ktor does not have a single plugin for per-route body size limits in 3.x. Use `maxRequestBodySize` on the Netty engine config, or check `Content-Length` before receiving the body in the route:

```kotlin
// In Application.kt — Netty engine config
embeddedServer(Netty, configure = {
    maxInitialLineLength = 4096
    maxHeaderSize = 8192
    maxChunkSize = 5 * 1024 * 1024  // 5 MB
}) { ... }
```

For the default `EngineMain` setup (via `application.conf`), add to `application.conf`:
```hocon
ktor {
    deployment {
        port = ${?PORT}
    }
    application {
        modules = [ com.zenthek.fitzenio.rest.ApplicationKt.module ]
    }
}
```

Then validate `Content-Length` at the route level before calling `receive<>()`:
```kotlin
post("/analyze-image") {
    val contentLength = call.request.contentLength() ?: 0L
    if (contentLength > 5 * 1024 * 1024) {
        call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("Image too large (max 5 MB)"))
        return@post
    }
    val request = call.receive<AnalyzeImageRequest>()
    // ...
}
```

**File:** `src/main/kotlin/com/zenthek/routes/Routing.kt`

---

## Layer 2 — Rate limiting (implement immediately)

**What it does:** Limits the number of requests a single client can make per time window. Protects upstream API quotas (FatSecret, USDA, Gemini, OpenAI) and controls Cloud Run costs.

### Rate limit values

| Endpoint group | Limit (per IP) | Limit (per authenticated user) |
|---|---|---|
| `/api/food/search`, `/autocomplete`, `/barcode/*` | 60 req/min | 200 req/min |
| `/api/food/analyze-image*` | 10 req/min | 20 req/min |
| `/health` | Unlimited | Unlimited |

### Implementation

Ktor has a built-in `RateLimit` plugin since 2.3 (`ktor-server-rate-limit`).

Add to `gradle/libs.versions.toml`:
```toml
ktor-server-rate-limit = { module = "io.ktor:ktor-server-rate-limit", version.ref = "ktor" }
```

In `Application.kt`:
```kotlin
install(RateLimit) {
    // Food search/barcode/autocomplete — by IP
    register(RateLimitName("food-search")) {
        rateLimiter(limit = 60, refillPeriod = 1.minutes)
        requestKey { call -> call.request.origin.remoteHost }
    }
    // Image analysis — by IP (stricter)
    register(RateLimitName("image-analysis")) {
        rateLimiter(limit = 10, refillPeriod = 1.minutes)
        requestKey { call -> call.request.origin.remoteHost }
    }
}
```

Apply in routes:
```kotlin
rateLimit(RateLimitName("food-search")) {
    get("/search") { ... }
    get("/autocomplete") { ... }
    get("/barcode/{barcode}") { ... }
}
rateLimit(RateLimitName("image-analysis")) {
    post("/analyze-image") { ... }
    post("/analyze-image-stream") { ... }
}
```

### Cloud Run caveat
Rate limiting is per-instance. Cloud Run may run multiple instances concurrently. The per-instance limit is effectively `N * limit` globally. This is acceptable for MVP — Cloud Run scales slowly (min 0 replicas, scales out over seconds). For true global rate limiting at scale, use **Redis (Cloud Memorystore)** as a shared counter store.

### Real IP extraction
Cloud Run injects the client IP in the `X-Forwarded-For` header. Use it instead of `remoteHost` (which returns the Cloud Run load balancer IP):
```kotlin
requestKey { call ->
    call.request.headers["X-Forwarded-For"]?.split(",")?.firstOrNull()?.trim()
        ?: call.request.origin.remoteHost
}
```

**File:** `src/main/kotlin/Application.kt`
**File:** `src/main/kotlin/com/zenthek/routes/Routing.kt`
**File:** `gradle/libs.versions.toml`

---

## Layer 3 — Shared API Key validation (implement immediately)

**What it does:** Every request to `/api/food/*` must include a valid `X-API-Key` header matching a secret stored in Cloud Secret Manager. Rejects requests from any client that is not the Fitzenio app (or a developer with the key).

**What it does NOT do:** Prevent a determined attacker who extracts the key from the APK. Combined with Layer 5 (JWT auth), this becomes much stronger.

### Implementation

Add `APP_API_KEY` to Secret Manager and inject into Cloud Run:
```yaml
# cloud-run-config.yaml
env:
  - name: APP_API_KEY
    valueFrom:
      secretKeyRef:
        name: app-api-key
        key: latest
```

Add to `config/Environment.kt`:
```kotlin
data class AppConfig(
    // existing fields...
    val appApiKey: String,
)

object ConfigLoader {
    fun loadConfig(): AppConfig = AppConfig(
        // existing...
        appApiKey = env["APP_API_KEY"] ?: error("APP_API_KEY is required"),
    )
}
```

Create a custom Ktor plugin (simpler than the Authentication plugin for a static key check):
```kotlin
// src/main/kotlin/plugins/ApiKeyPlugin.kt
val ApiKeyPlugin = createApplicationPlugin("ApiKey") {
    val expectedKey = application.environment.config
        .property("ktor.security.apiKey").getString()
    // Or pass via constructor if using Application.module() pattern

    onCall { call ->
        val key = call.request.headers["X-API-Key"]
        if (key == null || key != expectedKey) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing or invalid API key"))
            finish()
        }
    }
}
```

Apply to the `/api/food` route group only (not `/health`).

### Key rotation grace period
When rotating keys, support two valid keys simultaneously for 30 days (old + new). Store as `APP_API_KEY` and `APP_API_KEY_PREV` in Secret Manager. Accept either value. After 30 days, remove `APP_API_KEY_PREV`.

**File:** `src/main/kotlin/Application.kt`
**File:** `src/main/kotlin/config/Environment.kt`
**File:** `src/main/kotlin/plugins/ApiKeyPlugin.kt` (new)

---

## Layer 4 — Supabase JWT validation (implement before public launch)

**What it does:** Requires every request to `/api/food/*` to include a valid Supabase access token in the `Authorization: Bearer <jwt>` header. The backend verifies the JWT against Supabase's public JWKS endpoint. Ties every API call to a real authenticated user — enables per-user rate limiting and user-level ban enforcement.

### How Supabase JWTs work

Supabase issues RS256-signed JWTs. The public keys are available at:
```
https://<project-ref>.supabase.co/auth/v1/jwks
```

Standard JWT claims:
- `sub` — Supabase user UUID
- `iss` — `https://<project-ref>.supabase.co/auth/v1`
- `exp` — expiry timestamp
- `role` — `authenticated` for logged-in users

### Implementation

Add `ktor-server-auth-jwt` to `gradle/libs.versions.toml`:
```toml
ktor-server-auth-jwt = { module = "io.ktor:ktor-server-auth-jwt", version.ref = "ktor" }
```

In `Application.kt`:
```kotlin
install(Authentication) {
    jwt("supabase-jwt") {
        realm = "fitzenio-api"
        verifier(
            jwkProvider = JwkProviderBuilder(URL("https://<project-ref>.supabase.co/auth/v1/jwks"))
                .cached(10, 24, TimeUnit.HOURS)
                .rateLimited(10, 1, TimeUnit.MINUTES)
                .build(),
            issuer = "https://<project-ref>.supabase.co/auth/v1"
        )
        validate { credential ->
            if (credential.payload.getClaim("role").asString() == "authenticated") {
                JWTPrincipal(credential.payload)
            } else null
        }
        challenge { _, _ ->
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Authentication required"))
        }
    }
}
```

Apply to all `/api/food/*` routes:
```kotlin
authenticate("supabase-jwt") {
    route("/api/food") {
        get("/search") { ... }
        get("/autocomplete") { ... }
        get("/barcode/{barcode}") { ... }
        post("/analyze-image") { ... }
        post("/analyze-image-stream") { ... }
    }
}
```

Extract userId for per-user rate limiting:
```kotlin
val userId = call.principal<JWTPrincipal>()?.payload?.subject
```

### JWKS caching
The JWKS endpoint is called at most once per JWK key rotation (cached for 24h). This is safe — Supabase rotates keys rarely. The `rateLimited` builder prevents thundering-herd on cache miss.

Add `SUPABASE_PROJECT_REF` to env vars:
```kotlin
val supabaseProjectRef = env["SUPABASE_PROJECT_REF"] ?: error("SUPABASE_PROJECT_REF is required")
```

**File:** `src/main/kotlin/Application.kt`
**File:** `src/main/kotlin/config/Environment.kt`
**File:** `gradle/libs.versions.toml`

---

## Layer 5 — Play Integrity / App Attest verification endpoint (implement at ~5k DAU)

**What it does:** Provides a backend endpoint that verifies Play Integrity tokens (Android) and App Attest assertions (iOS). Returns a short-lived session JWT. This decouples device integrity checks from every food API call — the app calls this endpoint once per session, caches the resulting token, and uses it for subsequent calls.

### Endpoint

```
POST /api/auth/verify-integrity
Content-Type: application/json

{
  "platform": "android" | "ios",
  "token": "<integrity token or App Attest assertion>",
  "nonce": "<sha256 of userId + timestamp>"
}

Response 200:
{
  "sessionToken": "<short-lived JWT, 15min TTL>",
  "expiresAt": 1234567890
}
```

### Android — Play Integrity

Backend calls Google Play Integrity API:
```
POST https://playintegrity.googleapis.com/v1/<packageName>:decodeIntegrityToken
Authorization: Bearer <service_account_token>
{ "integrity_token": "<token>" }
```

Validate the response:
- `requestDetails.nonce` must match the nonce you issued
- `appIntegrity.appRecognitionVerdict` must be `PLAY_RECOGNIZED`
- `deviceIntegrity.deviceRecognitionVerdict` should include `MEETS_DEVICE_INTEGRITY`
- `accountDetails.appLicensingVerdict` — optional for MVP

Library: `com.google.apis:google-api-services-playintegrity` or direct HTTP call with the service account JWT.

### iOS — App Attest

Backend verifies the App Attest assertion:
1. Decode the CBOR-encoded attestation object
2. Verify the certificate chain against Apple's Root CA
3. Check `aaguid` matches Apple's App Attest AAGUID
4. Verify the nonce hash in the authenticator data

Library: Apple does not provide a Java/Kotlin SDK. Use a community library (e.g., `app-attest-validator-java`) or implement the verification manually per Apple's documentation.

### Session JWT signing
Issue a short-lived JWT signed with a new secret `INTEGRITY_JWT_SECRET`:
```kotlin
val sessionToken = JWT.create()
    .withSubject(userId)
    .withClaim("integrity", "verified")
    .withExpiresAt(Date(System.currentTimeMillis() + 15 * 60 * 1000))
    .sign(Algorithm.HMAC256(integrityJwtSecret))
```

The food API routes can then accept EITHER a Supabase JWT (Layer 4) OR this integrity session token, giving flexibility.

**Dry Run Mode:** Controlled by `INTEGRITY_DRY_RUN=true` env var. When enabled, the endpoint logs the full verdict (device model, OS version, verdict string) but always returns a valid session token. Run Dry Run Mode for 2–4 weeks post-rollout to collect real-world false-positive data (custom ROM users, older devices, pre-release builds). Only set `INTEGRITY_DRY_RUN=false` once false-positive rate is confirmed below 1%.

**File:** `src/main/kotlin/routes/Routing.kt` (new endpoint)
**File:** `src/main/kotlin/config/Environment.kt` (new env vars)

---

## Layer 6 — Google Cloud Armor (implement after launch)

**What it does:** GCP-level WAF and DDoS protection that intercepts malicious traffic before it reaches the Cloud Run service. Zero app code changes required.

### Setup (Cloud Console or `gcloud`)

1. Create a security policy:
   ```bash
   gcloud compute security-policies create fitzenio-api-policy \
     --description "WAF for Fitzenio API"
   ```

2. Enable adaptive protection (auto-blocks DDoS patterns):
   ```bash
   gcloud compute security-policies update fitzenio-api-policy \
     --enable-layer7-ddos-defense
   ```

3. Add preconfigured WAF rules (OWASP top 10):
   ```bash
   gcloud compute security-policies rules create 1000 \
     --security-policy fitzenio-api-policy \
     --expression "evaluatePreconfiguredExpr('xss-stable')" \
     --action deny-403
   ```

4. Attach to Cloud Run via a backend service (requires Cloud Run behind a Global Load Balancer — see GCP docs for "Cloud Run with Cloud Armor").

### Rate limiting at Cloud Armor level
Cloud Armor can enforce global rate limits (across all Cloud Run instances):
```bash
gcloud compute security-policies rules create 2000 \
  --security-policy fitzenio-api-policy \
  --expression "true" \
  --action throttle \
  --rate-limit-threshold-count 300 \
  --rate-limit-threshold-interval-sec 60 \
  --conform-action allow \
  --exceed-action deny-429
```

This complements the per-instance Ktor rate limiting in Layer 2.

---

## Layer 7 — Structured logging and abuse alerts (implement after launch)

**What it does:** Logs every request with structured fields that Cloud Logging can query. Sets up Cloud Monitoring alerts for abuse patterns (429 spikes, image analysis cost anomalies, 5xx bursts).

### Structured request log format

Install `CallLogging` plugin in `Application.kt`:
```kotlin
install(CallLogging) {
    level = Level.INFO
    format { call ->
        val status = call.response.status()
        val userId = call.principal<JWTPrincipal>()?.payload?.subject ?: "anon"
        val ip = call.request.headers["X-Forwarded-For"]
            ?.split(",")?.firstOrNull()?.trim()
            ?: call.request.origin.remoteHost
        val duration = call.processingTimeMillis()
        """{"status":${status?.value},"method":"${call.request.httpMethod.value}","path":"${call.request.path()}","userId":"$userId","ip":"$ip","durationMs":$duration}"""
    }
}
```

Cloud Run forwards stdout to Cloud Logging automatically. The JSON format is parsed into structured fields.

### Cloud Monitoring alerts to create

| Alert | Condition | Threshold |
|---|---|---|
| 429 spike | Rate of 429 responses | >50/min sustained for 2 min |
| 5xx burst | Rate of 5xx responses | >20/min sustained for 1 min |
| Image analysis volume | Count of `/analyze-image` calls | >200/hour (unexpected cost) |
| Cold start spike | Cloud Run instance creation rate | >10 instances in 5 min |

---

## Environment variables reference (additions)

| Variable | Required | Layer | Description |
|---|---|---|---|
| `APP_API_KEY` | Yes | Layer 3 | Shared app secret — validate `X-API-Key` header |
| `APP_API_KEY_PREV` | No | Layer 3 | Previous key during rotation grace period |
| `SUPABASE_PROJECT_REF` | Yes | Layer 4 | Supabase project reference (for JWKS URL) |
| `INTEGRITY_JWT_SECRET` | Yes | Layer 5 | HMAC secret for signing integrity session tokens |
| `GOOGLE_APPLICATION_CREDENTIALS` | Yes (Layer 5) | Layer 5 | Path to service account key for Play Integrity API |
| `INTEGRITY_DRY_RUN` | No (default `true`) | Layer 5 | When `true`, log integrity failures but always grant access (Dry Run Mode) |

Add all to Cloud Secret Manager and reference in `cloud-run-config.yaml`.

---

## Implementation order summary

| Layer | Priority | Effort | Impact |
|---|---|---|---|
| 0 — Fix HTTP status codes | Immediate | 30m | Correctness, better error handling |
| 1 — Request size limits | Immediate | 1h | Prevents resource exhaustion |
| 2 — Rate limiting | Immediate | 2h | Protects upstream API quotas |
| 3 — Shared API Key | Immediate | 2h | Stops casual scrapers |
| 4 — Supabase JWT auth | Before launch | 3h | Per-user auth, real enforcement |
| 6 — Cloud Armor | After launch | 2h (infra) | GCP-level DDoS + WAF |
| 7 — Logging + alerts | After launch | 2h | Visibility into abuse |
| 5 — Play Integrity endpoint | At scale | 1 day | Strongest device-level guarantee |

---

## What NOT to do

- **Do not use `ktor-server-auth` with HTTP Basic** — it does not fit the mobile app model
- **Do not validate JWTs with the Supabase service role key** — use the public JWKS endpoint; never put the service role key on the backend
- **Do not log full request bodies** — they may contain base64 image data or user credentials
- **Do not return stack traces to clients** — `StatusPages` already prevents this; do not add any `cause.stackTrace` to error responses
- **Do not block users on integrity check failure from day 1** — log first, enforce later after validating the false-positive rate
- **Do not share the Ktor `HttpClient` with supabase-kt** — they use separate client instances intentionally
