package com.zenthek.config

import io.github.cdimascio.dotenv.dotenv

private val dotenv = dotenv { ignoreIfMissing = true }
private fun env(key: String): String? = dotenv[key]

data class AppConfig(
    val environment: AppEnvironment,
    val apiKeys: ApiKeys,
    val useGemini: Boolean,
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
    val serviceRoleKey: String,
)


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
            useGemini = true,
            geminiApiKey = env("GEMINI_API_KEY") ?: error("Missing GEMINI_API_KEY"),
            supabase = SupabaseConfig(
                url = env("SUPABASE_DEV_URL") ?: error("Missing SUPABASE_URL"),
                serviceRoleKey = env("SUPABASE_DEV_SERVICE_ROLE_KEY") ?: error("Missing SUPABASE_DEV_SERVICE_ROLE_KEY"),
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
            useGemini = true,
            geminiApiKey = env("GEMINI_API_KEY") ?: error("Missing GEMINI_API_KEY"),
            supabase = SupabaseConfig(
                url = env("SUPABASE_URL") ?: error("Missing SUPABASE_URL"),
                serviceRoleKey = env("SUPABASE_SERVICE_ROLE_KEY") ?: error("Missing SUPABASE_SERVICE_ROLE_KEY"),
            ),
        )
    }
}
