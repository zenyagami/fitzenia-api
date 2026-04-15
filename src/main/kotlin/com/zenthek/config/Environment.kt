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
)

data class ApiKeys(
    val fatSecretClientId: String,
    val fatSecretClientSecret: String,
    val usdaApiKey: String,
    val openAiApiKey: String
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

object ConfigLoader {
    fun loadConfig(): AppConfig {
        val environment = AppEnvironment.fromString(env("APP_ENVIRONMENT"))

        return when (environment) {
            AppEnvironment.DEVELOPMENT -> createDevelopmentConfig()
            AppEnvironment.PRODUCTION -> createProductionConfig()
        }
    }

    private fun createDevelopmentConfig(): AppConfig {
        return AppConfig(
            environment = AppEnvironment.DEVELOPMENT,
            apiKeys = ApiKeys(
                fatSecretClientId = env("FATSECRET_CLIENT_ID") ?: error("Missing FATSECRET_CLIENT_ID"),
                fatSecretClientSecret = env("FATSECRET_CLIENT_SECRET") ?: error("Missing FATSECRET_CLIENT_SECRET"),
                usdaApiKey = env("USDA_API_KEY") ?: error("Missing USDA_API_KEY"),
                openAiApiKey = env("OPENAI_API_KEY") ?: error("Missing OPENAI_API_KEY")
            ),
            useGeminiForAiImage = parseUseGemini(env("USE_GEMINI")),
            geminiApiKey = env("GEMINI_API_KEY") ?: error("Missing GEMINI_API_KEY"),
            supabase = SupabaseConfig(
                url = env("SUPABASE_URL") ?: error("Missing SUPABASE_DEV_URL"),
                publishableKey = env("SUPABASE_PUBLISHABLE_KEY"),
                legacyAnonKey = env("SUPABASE_DEV_ANON_KEY"),
                jwtVerificationMode = SupabaseJwtVerificationMode.fromString(env("SUPABASE_JWT_VERIFICATION_MODE")),
            ),
        )
    }

    private fun createProductionConfig(): AppConfig {
        return AppConfig(
            environment = AppEnvironment.PRODUCTION,
            apiKeys = ApiKeys(
                fatSecretClientId = env("FATSECRET_CLIENT_ID") ?: error("Missing FATSECRET_CLIENT_ID"),
                fatSecretClientSecret = env("FATSECRET_CLIENT_SECRET") ?: error("Missing FATSECRET_CLIENT_SECRET"),
                usdaApiKey = env("USDA_API_KEY") ?: error("Missing USDA_API_KEY"),
                openAiApiKey = env("OPENAI_API_KEY") ?: error("Missing OPENAI_API_KEY")
            ),
            useGeminiForAiImage = parseUseGemini(env("USE_GEMINI")),
            geminiApiKey = env("GEMINI_API_KEY") ?: error("Missing GEMINI_API_KEY"),
            supabase = SupabaseConfig(
                url = env("SUPABASE_URL") ?: error("Missing SUPABASE_URL"),
                publishableKey = env("SUPABASE_PUBLISHABLE_KEY"),
                legacyAnonKey = env("SUPABASE_ANON_KEY"),
                jwtVerificationMode = SupabaseJwtVerificationMode.fromString(env("SUPABASE_JWT_VERIFICATION_MODE")),
            ),
        )
    }
}
