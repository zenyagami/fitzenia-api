package com.zenthek.coach.auth

import com.zenthek.auth.requireAuthenticatedUser
import com.zenthek.coach.config.CoachConfig
import com.zenthek.revenuecat.RevenueCatSyncService
import com.zenthek.service.ForbiddenException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class PremiumGate(
    private val httpClient: HttpClient,
    private val config: CoachConfig,
    // Present (non-null) only when REVENUECAT_REST_API_KEY is configured → enables lazy sync-on-miss.
    private val revenueCatSync: RevenueCatSyncService? = null,
) {
    private val log = LoggerFactory.getLogger(PremiumGate::class.java)
    private val supabaseUrl = config.supabase.normalizedUrl
    private val serviceRoleKey = config.serviceRoleKey

    // Per-user negative cache: userId → epochMillis until which we skip a fresh RevenueCat lookup.
    // Stops non-premium users (and transient RC blips) from re-hitting GET /v1/subscribers on every
    // gate miss. In-memory + per-instance — rebuilt on cold start (stateless-friendly).
    private val negativeUntil = ConcurrentHashMap<String, Long>()

    suspend fun requirePremium(call: ApplicationCall, entitlementId: String = "premium") {
        val userId = call.requireAuthenticatedUser().userId

        // Entitlements are driven by RevenueCat → user_entitlement. Fast path: a live active row.
        if (fetchActive(userId, entitlementId)) return

        // Lazy sync-on-miss: existing RevenueCat subscribers never fired a fresh webhook, so
        // they may have no user_entitlement row yet. Reconcile once against the live RC subscriber
        // snapshot (source='revenuecat'), then re-check. Guarded by the negative cache so this costs
        // at most one RC round-trip per user per TTL window.
        val rcSync = revenueCatSync
        if (rcSync != null && !isNegativeCached(userId)) {
            val synced = runCatching { rcSync.syncUserById(userId) }
                .onFailure { e ->
                    // Transient RC/Supabase error: short TTL so a real premium user isn't locked out
                    // for long once RC recovers.
                    log.warn("[COACH-GATE] lazy RC sync failed userId={} error={}", userId, e.message)
                    negativeUntil[userId] = System.currentTimeMillis() + ERROR_TTL_MS
                }
                .isSuccess
            if (synced) {
                if (fetchActive(userId, entitlementId)) return
                // Sync succeeded but no active entitlement → genuinely not premium; cache the miss.
                negativeUntil[userId] = System.currentTimeMillis() + MISS_TTL_MS
            }
        }

        throw ForbiddenException("PREMIUM_REQUIRED")
    }

    private fun isNegativeCached(userId: String): Boolean {
        val until = negativeUntil[userId] ?: return false
        if (System.currentTimeMillis() < until) return true
        negativeUntil.remove(userId)
        return false
    }

    private suspend fun fetchActive(userId: String, entitlementId: String): Boolean =
        runCatching {
            val response = httpClient.get("$supabaseUrl/rest/v1/user_entitlement") {
                header("apikey", serviceRoleKey)
                header("Authorization", "Bearer $serviceRoleKey")
                parameter("user_id", "eq.$userId")
                parameter("entitlement_id", "eq.$entitlementId")
                parameter("active", "eq.true")
                parameter("select", "user_id")
                parameter("limit", "1")
            }
            if (!response.status.isSuccess()) return@runCatching false
            val body: String = response.body()
            Json.parseToJsonElement(body).jsonArray.isNotEmpty()
        }.getOrElse { e ->
            log.error("[COACH-GATE] entitlement check failed userId={} error={}", userId, e.message)
            false
        }

    private companion object {
        const val MISS_TTL_MS = 10 * 60 * 1000L // confirmed non-premium: skip re-syncing for 10 min
        const val ERROR_TTL_MS = 60 * 1000L     // transient RC/Supabase error: retry after 1 min
    }
}
