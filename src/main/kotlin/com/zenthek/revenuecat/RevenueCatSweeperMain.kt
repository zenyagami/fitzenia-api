package com.zenthek.revenuecat

import com.zenthek.config.AppEnvironment
import io.github.cdimascio.dotenv.dotenv
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("RevenueCatSweeper")

/**
 * Stale-claim sweeper. Short-lived Cloud Run Job (`-PtargetService=coach-rc-sweeper`),
 * scheduled ~every minute. Re-claims RevenueCat events stuck in `processing` or left `failed`
 * (`coach_rc_claim_recoverable_events`) and replays the subscriber-sync flow against their stored
 * payload — **no fresh RevenueCat delivery required**. The same idempotency machinery that guards
 * the webhook guards the sweeper: each row is re-claimed `FOR UPDATE SKIP LOCKED` and capped at
 * `maxAttempts`, so concurrent sweepers and the live webhook never double-process a row.
 *
 * Stateless and idempotent — a re-run simply finds fewer (or zero) eligible rows.
 */
fun main() {
    log.info("[COACH-RC-SWEEP] starting")

    val exitCode = runBlocking {
        try {
            val dotenv = dotenv { ignoreIfMissing = true }
            val isProductionDeployment = !AppEnvironment.fromString(dotenv["APP_ENVIRONMENT"]).isDebug()
            val supabaseUrl = dotenv["SUPABASE_URL"]?.trim()?.ifBlank { null }
                ?: error("Missing SUPABASE_URL")
            val serviceRoleKey = dotenv["SUPABASE_SERVICE_ROLE_KEY"]?.trim()?.ifBlank { null }
                ?: error("Missing SUPABASE_SERVICE_ROLE_KEY")
            val restApiKey = dotenv["REVENUECAT_REST_API_KEY"]?.trim()?.ifBlank { null }
                ?: error("Missing REVENUECAT_REST_API_KEY")
            val restBaseUrl = (dotenv["REVENUECAT_REST_BASE_URL"]?.trim()?.ifBlank { null }
                ?: "https://api.revenuecat.com").trimEnd('/')

            val httpClient = HttpClient(CIO) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 10_000 }
            }

            httpClient.use { client ->
                val gateway = RevenueCatEntitlementGateway(client, supabaseUrl, serviceRoleKey)
                val rest = RevenueCatRestClient(client, restApiKey, restBaseUrl)
                // webhookAuth is unused on the sweeper path (rows are already claimed; no header to verify).
                val service = RevenueCatSyncService(gateway, rest, webhookAuth = "", isProductionDeployment = isProductionDeployment)

                val claimed = gateway.claimRecoverable(
                    staleAfter = "5 minutes",
                    retryAfter = "5 minutes",
                    limit = 100,
                    maxAttempts = 10,
                )
                log.info("[COACH-RC-SWEEP] reclaimed {} event(s) for replay", claimed.size)

                var processed = 0
                var failed = 0
                for (row in claimed) {
                    val event = row.payload
                    if (event == null) {
                        log.warn("[COACH-RC-SWEEP] eventId={} has no stored payload; marking failed", row.eventId)
                        runCatching { gateway.markFailed(row.eventId, "missing payload for replay") }
                        failed++
                        continue
                    }
                    if (service.runClaimedEvent(event)) processed++ else failed++
                }
                log.info("[COACH-RC-SWEEP] done reclaimed={} processed={} failed={}", claimed.size, processed, failed)
            }
            0
        } catch (e: Exception) {
            log.error("[COACH-RC-SWEEP] fatal error", e)
            1
        }
    }
    exitProcess(exitCode)
}
