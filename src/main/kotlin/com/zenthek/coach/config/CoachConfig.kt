package com.zenthek.coach.config

import com.zenthek.config.AppEnvironment
import com.zenthek.config.SupabaseConfig
import com.zenthek.config.SupabaseJwtVerificationMode
import io.github.cdimascio.dotenv.dotenv

data class CoachConfig(
    val environment: AppEnvironment,
    val supabase: SupabaseConfig,
    val serviceRoleKey: String,
    val geminiApiKey: String,
    // RevenueCat REST for lazy sync-on-miss. Nullable: when unset the premium gate reads
    // user_entitlement only (no on-demand reconcile). The coach does NOT need REVENUECAT_WEBHOOK_AUTH
    // — the webhook lives in the main fitzenia-api service, not here.
    val revenueCatRestApiKey: String?,
    val revenueCatRestBaseUrl: String,
)

object CoachConfigLoader {
    private val dotenv = dotenv { ignoreIfMissing = true }
    private fun env(key: String): String? = dotenv[key]

    fun load(): CoachConfig {
        val environment = AppEnvironment.fromString(env("APP_ENVIRONMENT"))
        val serviceRoleKey = env("SUPABASE_SERVICE_ROLE_KEY")?.trim()?.ifBlank { null }
            ?: error("Missing SUPABASE_SERVICE_ROLE_KEY")
        val supabaseUrl = env("SUPABASE_URL")?.trim()?.ifBlank { null }
            ?: error("Missing SUPABASE_URL")
        val publishableKey = env("SUPABASE_PUBLISHABLE_KEY")?.trim()?.ifBlank { null }
        val legacyAnonKey = when (environment) {
            AppEnvironment.DEVELOPMENT -> env("SUPABASE_DEV_ANON_KEY")?.trim()?.ifBlank { null }
            AppEnvironment.PRODUCTION -> env("SUPABASE_ANON_KEY")?.trim()?.ifBlank { null }
        }
        val supabase = SupabaseConfig(
            url = supabaseUrl,
            publishableKey = publishableKey,
            legacyAnonKey = legacyAnonKey,
            jwtVerificationMode = SupabaseJwtVerificationMode.fromString(env("SUPABASE_JWT_VERIFICATION_MODE")),
        )
        val geminiApiKey = env("GEMINI_API_KEY")?.trim()?.ifBlank { null }
            ?: error("Missing GEMINI_API_KEY")
        val revenueCatRestApiKey = env("REVENUECAT_REST_API_KEY")?.trim()?.ifBlank { null }
        val revenueCatRestBaseUrl = env("REVENUECAT_REST_BASE_URL")?.trim()?.ifBlank { null }
            ?: "https://api.revenuecat.com"
        return CoachConfig(
            environment = environment,
            supabase = supabase,
            serviceRoleKey = serviceRoleKey,
            geminiApiKey = geminiApiKey,
            revenueCatRestApiKey = revenueCatRestApiKey,
            revenueCatRestBaseUrl = revenueCatRestBaseUrl,
        )
    }
}
