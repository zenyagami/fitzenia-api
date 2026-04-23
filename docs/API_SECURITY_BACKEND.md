# API Security — Backend Side

> Strategy document for hardening the Fitzenio Ktor backend against endpoint abuse.
> Implement in layers — lower layers are prerequisites for higher ones.
> Deployed on Google Cloud Run (europe-north1). Ktor 3.2.3 / Kotlin 2.1.10.

---

## Current state

| Concern | Status |
|---|---|
| Auth on `/api/food/*` and `/api/user/*` | ✅ Supabase JWT (JWKS, per-user) |
| Rate limiting | ✅ Per-user: 200 req/min food search, 20 req/min image analysis |
| Request size limits | ❌ None |
| Input validation HTTP codes | ✅ Fixed — 400/401/502/500 |
| `ktor-server-auth` | ✅ Installed and configured (JWKS + REMOTE fallback) |
| CORS | ❌ Not configured |
| Cloud Armor / WAF | ❌ Not configured |

---

## Layer 0 — Fix HTTP status codes ✅ DONE

**What it does:** Maps domain exceptions to appropriate HTTP status codes. Client errors return 4xx; upstream dependency failures return 502; unhandled server faults return 500. Stack traces never reach clients.

### Implementation

In `Application.kt`, `configureStatusPages()`:

```kotlin
fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "Bad request"))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Bad request"))
        }
        exception<ContentTransformationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
        }
        exception<UnauthorizedException> { call, cause ->
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse(cause.message ?: "Unauthorized"))
        }
        exception<UpstreamFailureException> { call, cause ->
            call.application.log.error("Upstream dependency failure", cause)
            call.respond(HttpStatusCode.BadGateway, ErrorResponse("Upstream dependency failure"))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error"))
        }
    }
}
```

`ErrorResponse` is defined in `src/main/kotlin/com/zenthek/model/UserProfile.kt`:
```kotlin
@Serializable
data class ErrorResponse(val error: String)
```

**File:** `src/main/kotlin/Application.kt`

---

## Layer 1 — Request size limits ❌ NOT YET

**What it does:** The `/analyze-image` and `/analyze-image-stream` endpoints accept a base64-encoded image with no size limit. A malicious client can send a 100MB payload, blocking a Coroutine thread and driving up Cloud Run memory. Cap incoming bodies.

### Limits

| Endpoint | Max body size |
|---|---|
| `POST /api/food/analyze-image` | 5 MB |
| `POST /api/food/analyze-image-stream` | 5 MB |
| All other routes | 64 KB |

A 3MP photo at JPEG 75% quality is ~600KB uncompressed → ~800KB base64-encoded. 5MB is a generous cap that covers all legitimate use cases.

### Implementation

Validate `Content-Length` at the route level before calling `receive<>()`:

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

## Layer 2 — Rate limiting ✅ DONE

**What it does:** Limits the number of requests a single authenticated user can make per time window. Protects upstream API quotas (Open Food Facts, USDA, Gemini, OpenAI) and controls Cloud Run costs.

### Rate limit values

| Endpoint group | Limit (per authenticated user) |
|---|---|
| `/api/food/search`, `/search/stream`, `/autocomplete`, `/barcode/*` | 200 req/min |
| `/api/food/analyze-image`, `/analyze-image-stream` | 20 req/min |
| `/health`, `/api/user/*` | Unlimited |

### Implementation

`ktor-server-rate-limit` is already in `gradle/libs.versions.toml`:
```toml
ktor-server-rate-limit = { module = "io.ktor:ktor-server-rate-limit", version.ref = "ktor" }
```

In `Application.kt`, `configureRateLimit()`:
```kotlin
fun Application.configureRateLimit() {
    install(RateLimit) {
        register(RateLimitName(RateLimitNames.FOOD_SEARCH)) {
            rateLimiter(limit = 200, refillPeriod = 1.minutes)
            requestKey { call -> call.authenticatedUserIdOrFail() }
        }
        register(RateLimitName(RateLimitNames.IMAGE_ANALYSIS)) {
            rateLimiter(limit = 20, refillPeriod = 1.minutes)
            requestKey { call -> call.authenticatedUserIdOrFail() }
        }
    }
}

private fun ApplicationCall.authenticatedUserIdOrFail(): String {
    return principal<AuthenticatedUserContext>()?.userId
        ?: throw UnauthorizedException("Authentication required")
}
```

Rate limit name constants live in `Routing.kt`:
```kotlin
object RateLimitNames {
    const val FOOD_SEARCH = "food-search"
    const val IMAGE_ANALYSIS = "image-analysis"
}
```

Applied in `Routing.kt` inside the `authenticate` block:
```kotlin
rateLimit(RateLimitName(RateLimitNames.FOOD_SEARCH)) {
    get("/autocomplete") { ... }
    get("/search") { ... }
    get("/search/stream") { ... }
    get("/barcode/{barcode}") { ... }
}
rateLimit(RateLimitName(RateLimitNames.IMAGE_ANALYSIS)) {
    post("/analyze-image") { ... }
    post("/analyze-image-stream") { ... }
}
```

### Cloud Run caveat
Rate limiting is per-instance. Cloud Run may run multiple instances concurrently. The per-instance limit is effectively `N * limit` globally. This is acceptable for MVP — Cloud Run scales slowly (min 0 replicas, scales out over seconds). For true global rate limiting at scale, use **Redis (Cloud Memorystore)** as a shared counter store.

**File:** `src/main/kotlin/Application.kt`
**File:** `src/main/kotlin/com/zenthek/routes/Routing.kt`

---

## Layer 3 — Shared API Key validation ❌ NOT YET

**What it does:** Every request to `/api/food/*` must include a valid `X-API-Key` header matching a secret stored in Cloud Secret Manager. Rejects requests from any client that is not the Fitzenio app (or a developer with the key).

**What it does NOT do:** Prevent a determined attacker who extracts the key from the APK. Combined with Layer 4 (JWT auth), this becomes much stronger.

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
        appApiKey = env("APP_API_KEY") ?: error("APP_API_KEY is required"),
    )
}
```

Create a custom Ktor plugin:
```kotlin
// src/main/kotlin/plugins/ApiKeyPlugin.kt
val ApiKeyPlugin = createApplicationPlugin("ApiKey") {
    val expectedKey = application.environment.config
        .property("ktor.security.apiKey").getString()

    onCall { call ->
        val key = call.request.headers["X-API-Key"]
        if (key == null || key != expectedKey) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing or invalid API key"))
            finish()
        }
    }
}
```

Apply to the `/api/food` route group only (not `/health` or `/api/user`).

### Key rotation grace period
When rotating keys, support two valid keys simultaneously for 30 days (old + new). Store as `APP_API_KEY` and `APP_API_KEY_PREV` in Secret Manager. Accept either value. After 30 days, remove `APP_API_KEY_PREV`.

**File:** `src/main/kotlin/Application.kt`
**File:** `src/main/kotlin/config/Environment.kt`
**File:** `src/main/kotlin/plugins/ApiKeyPlugin.kt` (new)

---

## Layer 4 — Supabase JWT validation ✅ DONE

**What it does:** Every request to `/api/food/*` and `/api/user/*` must include a valid Supabase access token in the `Authorization: Bearer <jwt>` header. In JWKS mode (default), the backend verifies the JWT signature locally against Supabase's cached public keys. In REMOTE mode (fallback), it calls Supabase's `/auth/v1/user` on every request.

### Verification modes

Controlled by `SUPABASE_JWT_VERIFICATION_MODE` (default `JWKS`):

| Mode | Behavior | When to use |
|---|---|---|
| `JWKS` | Verifies signature locally against Supabase's JWKS (cached 10 min, rate-limited 10/min). Validates `iss = ${SUPABASE_URL}/auth/v1`, `aud = authenticated`, 30s clock leeway. | **Default.** Zero per-request network cost, scales cleanly. |
| `REMOTE` | On every request, calls `GET ${SUPABASE_URL}/auth/v1/user` with the bearer token. Logs a startup warning. | Temporary fallback only — every request adds an upstream hop. |

On startup, the app probes the JWKS endpoint (JWKS mode only) and logs reachability — a failed probe is a warning, not a fatal error.

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

The raw bearer token is stashed on the call under `SupabaseAccessTokenKey` so downstream services can forward it to Supabase REST for RLS-scoped queries.

### How Supabase JWTs work

Supabase issues RS256-signed JWTs. The public keys are available at:
```
https://<project-ref>.supabase.co/auth/v1/.well-known/jwks.json
```

Standard JWT claims:
- `sub` — Supabase user UUID
- `iss` — `https://<project-ref>.supabase.co/auth/v1`
- `exp` — expiry timestamp
- `role` — `authenticated` for logged-in users

Issuer and JWKS URL are derived from `SUPABASE_URL` in `SupabaseConfig`:
```kotlin
val issuer: String = "$normalizedUrl/auth/v1"
val jwksUrl: String = "$issuer/.well-known/jwks.json"
```

### Implementation

In `auth/SupabaseAuthentication.kt`, `configureAuthentication()`:

```kotlin
fun Application.configureAuthentication(
    supabaseConfig: SupabaseConfig,
    supabaseGateway: SupabaseGateway,
    jwkProvider: JwkProvider = buildSupabaseJwkProvider(supabaseConfig),
) {
    install(Authentication) {
        when (supabaseConfig.jwtVerificationMode) {
            SupabaseJwtVerificationMode.JWKS -> {
                jwt(SUPABASE_AUTH_PROVIDER) {
                    realm = "fitzenio-api"
                    verifier(jwkProvider, supabaseConfig.issuer) {
                        withAudience("authenticated")
                        acceptLeeway(30)
                    }
                    validate { credential ->
                        if (!credential.payload.audience.contains("authenticated")) return@validate null
                        val userMetadata = credential.payload.getClaim("user_metadata").toUserMetadata()
                        val context = createAuthenticatedUserContext(
                            userId = credential.payload.subject,
                            email = credential.payload.getClaim("email").asString(),
                            name = userMetadata.name,
                            avatarUrl = userMetadata.avatarUrl,
                            role = credential.payload.getClaim("role").asString(),
                        ) ?: return@validate null
                        extractBearerToken(request.headers[HttpHeaders.Authorization])
                            ?.let { attributes.put(SupabaseAccessTokenKey, it) }
                        context
                    }
                    challenge { _, _ ->
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Authentication required"))
                    }
                }
            }
            SupabaseJwtVerificationMode.REMOTE -> {
                bearer(SUPABASE_AUTH_PROVIDER) {
                    realm = "fitzenio-api"
                    authenticate { credential ->
                        supabaseGateway.fetchAuthenticatedUser(credential.token).getOrNull()?.let { user ->
                            val context = createAuthenticatedUserContext(
                                userId = user.id, email = user.email,
                                name = user.name, avatarUrl = user.avatarUrl,
                                role = "authenticated",
                            ) ?: return@authenticate null
                            attributes.put(SupabaseAccessTokenKey, credential.token)
                            context
                        }
                    }
                }
            }
        }
    }
}

fun buildSupabaseJwkProvider(supabaseConfig: SupabaseConfig): JwkProvider =
    JwkProviderBuilder(URI(supabaseConfig.jwksUrl).toURL())
        .cached(10, 10, TimeUnit.MINUTES)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()
```

Applied to all `/api/food/*` and `/api/user/*` routes in `Routing.kt`:
```kotlin
authenticate(SUPABASE_AUTH_PROVIDER) {
    route("/api/food") { configureFoodRoutes(...) }
    route("/api/user") { configureUserRoutes(...) }
}
```

Extract userId for logging and rate limiting:
```kotlin
val user = call.requireAuthenticatedUser()  // throws UnauthorizedException if missing
val token = call.requireBearerAccessToken() // for forwarding to Supabase REST
```

### JWKS caching
The in-process JWKS cache is 10 minutes with rate-limiting (10 refreshes/min). This means key rotation is picked up within 10 minutes. The `rateLimited` builder prevents thundering-herd on cache miss.

**File:** `src/main/kotlin/com/zenthek/auth/SupabaseAuthentication.kt`
**File:** `src/main/kotlin/Application.kt`

---

## Layer 5 — Play Integrity / App Attest verification endpoint ❌ NOT YET (~5k DAU)

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

The food API routes can then accept EITHER a Supabase JWT (Layer 4) OR this integrity session token.

**Dry Run Mode:** Controlled by `INTEGRITY_DRY_RUN=true` env var. When enabled, the endpoint logs the full verdict (device model, OS version, verdict string) but always returns a valid session token. Run Dry Run Mode for 2–4 weeks post-rollout to collect real-world false-positive data. Only set `INTEGRITY_DRY_RUN=false` once false-positive rate is confirmed below 1%.

**File:** `src/main/kotlin/routes/Routing.kt` (new endpoint)
**File:** `src/main/kotlin/config/Environment.kt` (new env vars)

---

## Layer 6 — Google Cloud Armor ❌ NOT YET (after launch)

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

This complements the per-user Ktor rate limiting in Layer 2.

---

## Layer 7 — Structured logging and abuse alerts ❌ NOT YET (after launch)

**What it does:** Logs every request with structured fields that Cloud Logging can query. Sets up Cloud Monitoring alerts for abuse patterns (429 spikes, image analysis cost anomalies, 5xx bursts).

### Structured request log format

Install `CallLogging` plugin in `Application.kt`:
```kotlin
install(CallLogging) {
    level = Level.INFO
    format { call ->
        val status = call.response.status()
        val userId = call.principal<AuthenticatedUserContext>()?.userId ?: "anon"
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

## Environment variables reference

### Implemented

| Variable | Required | Layer | Description |
|---|---|---|---|
| `SUPABASE_URL` | Yes | Layer 4 | Supabase project base URL; derive issuer and JWKS URL from it |
| `SUPABASE_PUBLISHABLE_KEY` | Yes¹ | Layer 4 | Modern Supabase publishable key (preferred) |
| `SUPABASE_ANON_KEY` / `SUPABASE_DEV_ANON_KEY` | Yes¹ | Layer 4 | Legacy anon JWT fallback |
| `SUPABASE_JWT_VERIFICATION_MODE` | No (default `JWKS`) | Layer 4 | `JWKS` or `REMOTE` |

¹ At least one of `SUPABASE_PUBLISHABLE_KEY` or the legacy anon key must be present.

### Pending (not yet implemented)

| Variable | Required | Layer | Description |
|---|---|---|---|
| `APP_API_KEY` | Yes | Layer 3 | Shared app secret — validate `X-API-Key` header |
| `APP_API_KEY_PREV` | No | Layer 3 | Previous key during rotation grace period |
| `INTEGRITY_JWT_SECRET` | Yes | Layer 5 | HMAC secret for signing integrity session tokens |
| `GOOGLE_APPLICATION_CREDENTIALS` | Yes | Layer 5 | Path to service account key for Play Integrity API |
| `INTEGRITY_DRY_RUN` | No (default `true`) | Layer 5 | When `true`, log integrity failures but always grant access |

Add all to Cloud Secret Manager and reference in `cloud-run-config.yaml`.

---

## Implementation order summary

| Layer | Status | Priority | Effort | Impact |
|---|---|---|---|---|
| 0 — Fix HTTP status codes | ✅ Done | — | — | Correctness, better error handling |
| 2 — Rate limiting | ✅ Done | — | — | Protects upstream API quotas |
| 4 — Supabase JWT auth | ✅ Done | — | — | Per-user auth, real enforcement |
| 1 — Request size limits | ❌ Pending | Immediate | 1h | Prevents resource exhaustion |
| 3 — Shared API Key | ❌ Pending | Immediate | 2h | Stops casual scrapers |
| 6 — Cloud Armor | ❌ Pending | After launch | 2h (infra) | GCP-level DDoS + WAF |
| 7 — Logging + alerts | ❌ Pending | After launch | 2h | Visibility into abuse |
| 5 — Play Integrity endpoint | ❌ Pending | At scale | 1 day | Strongest device-level guarantee |

---

## What NOT to do

- **Do not use `ktor-server-auth` with HTTP Basic** — it does not fit the mobile app model
- **Do not validate JWTs with the Supabase service role key** — use the public JWKS endpoint, and keep `service_role` out of normal user-scoped request paths
- **Do not log full request bodies** — they may contain base64 image data or user credentials
- **Do not return stack traces to clients** — `StatusPages` already prevents this; do not add any `cause.stackTrace` to error responses
- **Do not block users on integrity check failure from day 1** — log first, enforce later after validating the false-positive rate
- **Do not share the Ktor `HttpClient` with supabase-kt** — they use separate client instances intentionally
- **Do not use `JWTPrincipal` directly in route handlers** — use `call.requireAuthenticatedUser()` which returns `AuthenticatedUserContext` and throws `UnauthorizedException` on missing principal
- **Never log JWTs or access tokens** — they are bearer credentials; also applies to the service role key
