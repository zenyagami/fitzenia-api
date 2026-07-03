package com.zenthek.config

import io.github.cdimascio.dotenv.dotenv

private val dotenv = dotenv { ignoreIfMissing = true }
private fun env(key: String): String? = dotenv[key]

data class AppConfig(
    val environment: AppEnvironment,
    val apiKeys: ApiKeys,
    val useGeminiForAiImage: Boolean,
    val geminiApiKey: String,
    val supabase: SupabaseConfig,
    val smartSearch: SmartSearchConfig,
    val aiProgressProjection: AiProgressProjectionConfig,
    val offMirror: OffMirrorConfig,
    val usdaMirror: UsdaMirrorConfig,
    val revenueCat: RevenueCatConfig,
)

/**
 * RevenueCat → Supabase entitlement sync. Drives `POST /webhooks/revenuecat`
 * and the stale-claim sweeper Job.
 *
 * **Optional at load on purpose.** Both secrets are absent in dev and not wired into the
 * running food API in every environment. Making them hard-required would crash the
 * `fitzenia-api` service before the secrets land. Instead the webhook route
 * checks [configured] at request time and returns 503 when unset — every other endpoint
 * keeps working.
 *
 * - [webhookAuth]: static value RevenueCat sends in the `Authorization` header
 *   (`REVENUECAT_WEBHOOK_AUTH`); constant-time compared. No HMAC body signing.
 * - [restApiKey]: server secret for `GET /v1/subscribers/{id}` (`REVENUECAT_REST_API_KEY`).
 * - [restBaseUrl]: RevenueCat REST host; overridable for tests via `REVENUECAT_REST_BASE_URL`.
 */
data class RevenueCatConfig(
    val webhookAuth: String?,
    val restApiKey: String?,
    val restBaseUrl: String,
) {
    /** True only when both secrets are present — the webhook + sweeper refuse to run otherwise. */
    val configured: Boolean
        get() = !webhookAuth.isNullOrBlank() && !restApiKey.isNullOrBlank()
}

/**
 * OFF mirror feature config. Both flags default from APP_ENVIRONMENT (true in
 * production, false in development) but are individually overridable via env.
 *
 * - [readEnabled]: whether the API consults `off_food` before falling back to
 *   live OFF/USDA. When false, behavior is byte-identical to the pre-mirror
 *   code path. Driven by `OFF_MIRROR_READ_ENABLED`.
 * - [writeEnabled]: whether the ingest Job actually persists rows. When false,
 *   runs are dry-runs (stream + parse + count, write nothing). Driven by
 *   `OFF_MIRROR_WRITE_ENABLED`.
 */
data class OffMirrorConfig(
    val readEnabled: Boolean,
    val writeEnabled: Boolean,
    val batchSize: Int,
)

/**
 * USDA mirror feature config. Same shape as [OffMirrorConfig] — both flags
 * default from APP_ENVIRONMENT (true in production, false in development) and
 * are individually overridable via env.
 *
 * - [readEnabled]: whether the API consults `usda_food` after the OFF mirror
 *   miss and before falling back to live OFF/USDA. Driven by
 *   `USDA_MIRROR_READ_ENABLED`.
 * - [writeEnabled]: whether the USDA ingest Job actually persists rows.
 *   `false` forces dry-run mode. Driven by `USDA_MIRROR_WRITE_ENABLED`.
 */
data class UsdaMirrorConfig(
    val readEnabled: Boolean,
    val writeEnabled: Boolean,
    val batchSize: Int,
)

data class ApiKeys(
    val fatSecretClientId: String,
    val fatSecretClientSecret: String,
    val usdaApiKey: String,
    val openAiApiKey: String,
    val supabaseServiceRoleKey: String      // Required at startup for Smart Search and /api/account admin ops
)

data class SmartSearchConfig(
    val enabled: Boolean,                        // SMART_FOOD_SEARCH_ENABLED
    val usdaEnabled: Boolean,                    // SMART_SEARCH_USDA_ENABLED (kill switch)
    val aiRankModel: String,                     // AI_SEARCH_RANK_MODEL (Gemini classify/rank)
    val aiGenerateModel: String,                 // AI_SEARCH_GENERATE_MODEL (Gemini grounded generation)
    val aiClassifyTimeoutMs: Long,               // AI_SEARCH_CLASSIFY_TIMEOUT_MS
    val aiGenerateTimeoutMs: Long,               // AI_SEARCH_GENERATE_TIMEOUT_MS
    val aiSyncOnMiss: Boolean,                   // SMART_SEARCH_AI_SYNC_ON_MISS (false = async write-behind)
    val catalogWriteConfidenceThreshold: Float   // CATALOG_WRITE_CONFIDENCE_THRESHOLD
)

/**
 * AI Progress Projections feature config. Controls the image-edit ladder generator (OpenAI
 * gpt-image-2 OR Gemini nano banana, picked via [provider]) and the Gemini gatekeeper that
 * screens uploaded photos. All values hardcoded in [loadAiProgressProjectionConfig] — change
 * there and redeploy. Sized for OpenAI Tier 1.
 */
data class AiProgressProjectionConfig(
    val enabled: Boolean,
    val provider: ImageGenerationProvider, // selects which image-edit upstream wires in
    val openAiImageModel: String,          // model name when provider=OPENAI
    val geminiImageModel: String,          // model name when provider=GEMINI
    val quality: String,
    val size: String,
    val outputFormat: String,              // jpeg | png | webp
    val outputCompression: Int,            // 0-100, applies to jpeg / webp
    val promptVersion: Int,                // bump invalidates the cache
    val numRungs: Int,                     // count of AI projection rungs (excludes the SOURCE rung)
    val stepBodyFatPercent: Double,        // informational; actual step is computed from current → target / numRungs
    val maxUploadBytes: Long,
    val allowedMimeTypes: Set<String>,
    val gatekeeperModel: String,
    val gatekeeperTimeoutMs: Long,
    val generateTimeoutMs: Long,
    val maxParallelRungs: Int,             // Semaphore bound on parallel image-edit calls
) {
    /** Model name for the active provider. Stamped into the cache key, the ladder/rung DB
     *  rows, and logs so flipping [provider] yields a fresh ladder (cache miss). */
    val activeImageModel: String
        get() = when (provider) {
            ImageGenerationProvider.OPENAI -> openAiImageModel
            ImageGenerationProvider.GEMINI -> geminiImageModel
        }
}

enum class ImageGenerationProvider { OPENAI, GEMINI }

data class SupabaseConfig(
    val url: String,
    val publishableKey: String?,
    val legacyAnonKey: String?,
    val jwtVerificationMode: SupabaseJwtVerificationMode,
) {
    val normalizedUrl: String = url.trimEnd('/')
    val issuer: String = "$normalizedUrl/auth/v1"
    val jwksUrl: String = "$issuer/.well-known/jwks.json"
    val publicApiKey: String = publishableKey?.trim().orEmpty()
        .ifBlank { legacyAnonKey?.trim().orEmpty() }
        .ifBlank { error("Missing SUPABASE_PUBLISHABLE_KEY (or temporary SUPABASE_ANON_KEY fallback)") }
}

enum class SupabaseJwtVerificationMode {
    JWKS,
    REMOTE;

    companion object {
        fun fromString(value: String?): SupabaseJwtVerificationMode {
            return when (value?.trim()?.uppercase()) {
                "REMOTE" -> REMOTE
                else -> JWKS
            }
        }
    }
}


enum class AppEnvironment {
    DEVELOPMENT,
    PRODUCTION;

    fun isDebug() = this == DEVELOPMENT

    companion object {
        fun fromString(env: String?): AppEnvironment {
            return when (env?.uppercase()) {
                "PRODUCTION", "PROD" -> PRODUCTION
                else -> DEVELOPMENT
            }
        }
    }
}

private fun parseUseGemini(value: String?): Boolean {
    return when (value?.trim()?.lowercase()) {
        null, "" -> true
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> true
    }
}

private fun parseBoolFlag(value: String?, default: Boolean): Boolean {
    return when (value?.trim()?.lowercase()) {
        null, "" -> default
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> default
    }
}

internal fun parseOffMirrorBatchSize(value: String?): Int {
    val parsed = value?.trim()?.toIntOrNull() ?: OFF_MIRROR_DEFAULT_BATCH_SIZE
    return parsed.coerceIn(OFF_MIRROR_MIN_BATCH_SIZE, OFF_MIRROR_MAX_BATCH_SIZE)
}

internal fun parseUsdaMirrorBatchSize(value: String?): Int {
    val parsed = value?.trim()?.toIntOrNull() ?: USDA_MIRROR_DEFAULT_BATCH_SIZE
    return parsed.coerceIn(USDA_MIRROR_MIN_BATCH_SIZE, USDA_MIRROR_MAX_BATCH_SIZE)
}

private fun loadSmartSearchConfig(): SmartSearchConfig {
    val enabled = parseBoolFlag(env("SMART_FOOD_SEARCH_ENABLED"), default = true)
    return SmartSearchConfig(
        enabled = enabled,
        usdaEnabled = parseBoolFlag(env("SMART_SEARCH_USDA_ENABLED"), default = true),
        aiRankModel = env("AI_SEARCH_RANK_MODEL")?.trim()?.ifBlank { null } ?: "gemini-3.1-flash-lite",
        aiGenerateModel = env("AI_SEARCH_GENERATE_MODEL")?.trim()?.ifBlank { null } ?: "gemini-3.1-flash-lite",
        aiClassifyTimeoutMs = env("AI_SEARCH_CLASSIFY_TIMEOUT_MS")?.trim()?.toLongOrNull() ?: 3_000L,
        aiGenerateTimeoutMs = env("AI_SEARCH_GENERATE_TIMEOUT_MS")?.trim()?.toLongOrNull() ?: 8_000L,
        aiSyncOnMiss = parseBoolFlag(env("SMART_SEARCH_AI_SYNC_ON_MISS"), default = true),
        catalogWriteConfidenceThreshold = env("CATALOG_WRITE_CONFIDENCE_THRESHOLD")?.trim()?.toFloatOrNull() ?: 0.7f,
    )
}

/**
 * AI Progress Projections settings. Intentionally hardcoded (not env-driven) — these values
 * apply uniformly across environments and only change with a code review + deploy. Tweak here
 * and ship rather than juggling env vars in dev/prod configs.
 *
 * **Tier-1 note:** numRungs and maxParallelRungs are sized for OpenAI Tier 1 (5 IPM cap on
 * gpt-image-2). Bump both to 5 once the org is on Tier 2+ — single line edit, deploy, done.
 */
private fun loadAiProgressProjectionConfig(): AiProgressProjectionConfig = AiProgressProjectionConfig(
    enabled = true,
    provider = ImageGenerationProvider.GEMINI,           // flip to GEMINI to A/B-test nano banana
    openAiImageModel = "gpt-image-2",
    geminiImageModel = "gemini-3.1-flash-image",         // nano banana
    quality = "medium",
    size = "1024x1536",
    outputFormat = "jpeg",
    outputCompression = 75,
    promptVersion = 1,                                      // bump invalidates the cache
    numRungs = 3,                                           // 5 once on Tier 2+
    stepBodyFatPercent = 3.0,
    maxUploadBytes = 8L * 1024 * 1024,                      // 8 MB
    allowedMimeTypes = setOf("image/jpeg", "image/png"),
    gatekeeperModel = "gemini-3.1-flash-lite",
    gatekeeperTimeoutMs = 20_000L,                          // Flash Lite + image + JSON schema: typically 4–8s, but cold start can push past 10s
    generateTimeoutMs = 120_000L,                           // OpenAI's published "up to 2 minutes" ceiling
    maxParallelRungs = 3,                                   // 5 once on Tier 2+
)

private fun loadOffMirrorConfig(environment: AppEnvironment): OffMirrorConfig {
    val isProd = environment == AppEnvironment.PRODUCTION
    return OffMirrorConfig(
        readEnabled = parseBoolFlag(env("OFF_MIRROR_READ_ENABLED"), default = isProd),
        writeEnabled = parseBoolFlag(env("OFF_MIRROR_WRITE_ENABLED"), default = isProd),
        batchSize = parseOffMirrorBatchSize(env("OFF_MIRROR_BATCH_SIZE")),
    )
}

private fun loadUsdaMirrorConfig(environment: AppEnvironment): UsdaMirrorConfig {
    val isProd = environment == AppEnvironment.PRODUCTION
    return UsdaMirrorConfig(
        readEnabled = parseBoolFlag(env("USDA_MIRROR_READ_ENABLED"), default = isProd),
        writeEnabled = parseBoolFlag(env("USDA_MIRROR_WRITE_ENABLED"), default = isProd),
        batchSize = parseUsdaMirrorBatchSize(env("USDA_MIRROR_BATCH_SIZE")),
    )
}

private fun loadSupabaseServiceRoleKey(): String {
    return env("SUPABASE_SERVICE_ROLE_KEY")?.trim()?.ifBlank { null }
        ?: error("Missing SUPABASE_SERVICE_ROLE_KEY")
}

private fun loadRevenueCatConfig(): RevenueCatConfig = RevenueCatConfig(
    webhookAuth = env("REVENUECAT_WEBHOOK_AUTH")?.trim()?.ifBlank { null },
    restApiKey = env("REVENUECAT_REST_API_KEY")?.trim()?.ifBlank { null },
    restBaseUrl = (env("REVENUECAT_REST_BASE_URL")?.trim()?.ifBlank { null }
        ?: "https://api.revenuecat.com").trimEnd('/'),
)

object ConfigLoader {
    fun loadConfig(): AppConfig {
        val environment = AppEnvironment.fromString(env("APP_ENVIRONMENT"))

        return when (environment) {
            AppEnvironment.DEVELOPMENT -> createDevelopmentConfig()
            AppEnvironment.PRODUCTION -> createProductionConfig()
        }
    }

    private fun createDevelopmentConfig(): AppConfig {
        val smartSearch = loadSmartSearchConfig()
        val aiProgressProjection = loadAiProgressProjectionConfig()
        val offMirror = loadOffMirrorConfig(AppEnvironment.DEVELOPMENT)
        val usdaMirror = loadUsdaMirrorConfig(AppEnvironment.DEVELOPMENT)
        return AppConfig(
            environment = AppEnvironment.DEVELOPMENT,
            apiKeys = ApiKeys(
                fatSecretClientId = env("FATSECRET_CLIENT_ID") ?: error("Missing FATSECRET_CLIENT_ID"),
                fatSecretClientSecret = env("FATSECRET_CLIENT_SECRET") ?: error("Missing FATSECRET_CLIENT_SECRET"),
                usdaApiKey = env("USDA_API_KEY") ?: error("Missing USDA_API_KEY"),
                openAiApiKey = env("OPENAI_API_KEY") ?: error("Missing OPENAI_API_KEY"),
                supabaseServiceRoleKey = loadSupabaseServiceRoleKey(),
            ),
            useGeminiForAiImage = parseUseGemini(env("USE_GEMINI")),
            geminiApiKey = env("GEMINI_API_KEY") ?: error("Missing GEMINI_API_KEY"),
            supabase = SupabaseConfig(
                url = env("SUPABASE_URL") ?: error("Missing SUPABASE_DEV_URL"),
                publishableKey = env("SUPABASE_PUBLISHABLE_KEY"),
                legacyAnonKey = env("SUPABASE_DEV_ANON_KEY"),
                jwtVerificationMode = SupabaseJwtVerificationMode.fromString(env("SUPABASE_JWT_VERIFICATION_MODE")),
            ),
            smartSearch = smartSearch,
            aiProgressProjection = aiProgressProjection,
            offMirror = offMirror,
            usdaMirror = usdaMirror,
            revenueCat = loadRevenueCatConfig(),
        )
    }

    private fun createProductionConfig(): AppConfig {
        val smartSearch = loadSmartSearchConfig()
        val aiProgressProjection = loadAiProgressProjectionConfig()
        val offMirror = loadOffMirrorConfig(AppEnvironment.PRODUCTION)
        val usdaMirror = loadUsdaMirrorConfig(AppEnvironment.PRODUCTION)
        return AppConfig(
            environment = AppEnvironment.PRODUCTION,
            apiKeys = ApiKeys(
                fatSecretClientId = env("FATSECRET_CLIENT_ID") ?: error("Missing FATSECRET_CLIENT_ID"),
                fatSecretClientSecret = env("FATSECRET_CLIENT_SECRET") ?: error("Missing FATSECRET_CLIENT_SECRET"),
                usdaApiKey = env("USDA_API_KEY") ?: error("Missing USDA_API_KEY"),
                openAiApiKey = env("OPENAI_API_KEY") ?: error("Missing OPENAI_API_KEY"),
                supabaseServiceRoleKey = loadSupabaseServiceRoleKey(),
            ),
            useGeminiForAiImage = parseUseGemini(env("USE_GEMINI")),
            geminiApiKey = env("GEMINI_API_KEY") ?: error("Missing GEMINI_API_KEY"),
            supabase = SupabaseConfig(
                url = env("SUPABASE_URL") ?: error("Missing SUPABASE_URL"),
                publishableKey = env("SUPABASE_PUBLISHABLE_KEY"),
                legacyAnonKey = env("SUPABASE_ANON_KEY"),
                jwtVerificationMode = SupabaseJwtVerificationMode.fromString(env("SUPABASE_JWT_VERIFICATION_MODE")),
            ),
            smartSearch = smartSearch,
            aiProgressProjection = aiProgressProjection,
            offMirror = offMirror,
            usdaMirror = usdaMirror,
            revenueCat = loadRevenueCatConfig(),
        )
    }
}

private const val OFF_MIRROR_DEFAULT_BATCH_SIZE = 100
private const val OFF_MIRROR_MIN_BATCH_SIZE = 1
private const val OFF_MIRROR_MAX_BATCH_SIZE = 500

private const val USDA_MIRROR_DEFAULT_BATCH_SIZE = 100
private const val USDA_MIRROR_MIN_BATCH_SIZE = 1
private const val USDA_MIRROR_MAX_BATCH_SIZE = 500
