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
)

data class ApiKeys(
    val fatSecretClientId: String,
    val fatSecretClientSecret: String,
    val usdaApiKey: String,
    val openAiApiKey: String,
    val supabaseServiceRoleKey: String?     // Required when SmartSearchConfig.enabled = true; validated at startup
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

private fun loadSmartSearchConfig(): SmartSearchConfig {
    val enabled = parseBoolFlag(env("SMART_FOOD_SEARCH_ENABLED"), default = false)
    return SmartSearchConfig(
        enabled = enabled,
        usdaEnabled = parseBoolFlag(env("SMART_SEARCH_USDA_ENABLED"), default = true),
        aiRankModel = env("AI_SEARCH_RANK_MODEL")?.trim()?.ifBlank { null } ?: "gemini-2.5-flash-lite",
        aiGenerateModel = env("AI_SEARCH_GENERATE_MODEL")?.trim()?.ifBlank { null } ?: "gemini-2.5-flash",
        aiClassifyTimeoutMs = env("AI_SEARCH_CLASSIFY_TIMEOUT_MS")?.trim()?.toLongOrNull() ?: 3_000L,
        aiGenerateTimeoutMs = env("AI_SEARCH_GENERATE_TIMEOUT_MS")?.trim()?.toLongOrNull() ?: 8_000L,
        aiSyncOnMiss = parseBoolFlag(env("SMART_SEARCH_AI_SYNC_ON_MISS"), default = false),
        catalogWriteConfidenceThreshold = env("CATALOG_WRITE_CONFIDENCE_THRESHOLD")?.trim()?.toFloatOrNull() ?: 0.7f,
    )
}

private fun loadSupabaseServiceRoleKey(smartSearchEnabled: Boolean): String? {
    val key = env("SUPABASE_SERVICE_ROLE_KEY")?.trim()?.ifBlank { null }
    if (smartSearchEnabled && key == null) {
        error("Missing SUPABASE_SERVICE_ROLE_KEY (required when SMART_FOOD_SEARCH_ENABLED=true)")
    }
    return key
}

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
        return AppConfig(
            environment = AppEnvironment.DEVELOPMENT,
            apiKeys = ApiKeys(
                fatSecretClientId = env("FATSECRET_CLIENT_ID") ?: error("Missing FATSECRET_CLIENT_ID"),
                fatSecretClientSecret = env("FATSECRET_CLIENT_SECRET") ?: error("Missing FATSECRET_CLIENT_SECRET"),
                usdaApiKey = env("USDA_API_KEY") ?: error("Missing USDA_API_KEY"),
                openAiApiKey = env("OPENAI_API_KEY") ?: error("Missing OPENAI_API_KEY"),
                supabaseServiceRoleKey = loadSupabaseServiceRoleKey(smartSearch.enabled),
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
        )
    }

    private fun createProductionConfig(): AppConfig {
        val smartSearch = loadSmartSearchConfig()
        return AppConfig(
            environment = AppEnvironment.PRODUCTION,
            apiKeys = ApiKeys(
                fatSecretClientId = env("FATSECRET_CLIENT_ID") ?: error("Missing FATSECRET_CLIENT_ID"),
                fatSecretClientSecret = env("FATSECRET_CLIENT_SECRET") ?: error("Missing FATSECRET_CLIENT_SECRET"),
                usdaApiKey = env("USDA_API_KEY") ?: error("Missing USDA_API_KEY"),
                openAiApiKey = env("OPENAI_API_KEY") ?: error("Missing OPENAI_API_KEY"),
                supabaseServiceRoleKey = loadSupabaseServiceRoleKey(smartSearch.enabled),
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
        )
    }
}
