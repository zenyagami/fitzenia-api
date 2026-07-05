package com.zenthek.revenuecat

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import java.net.URLEncoder

/** Result of resolving a RevenueCat identity candidate against `auth.users`. */
enum class UserResolution { EXISTS, NOT_FOUND }

/**
 * Service-role gateway for the RevenueCat sync. Bypasses RLS — only ever invoked
 * from the webhook route + the stale-claim sweeper, never on a user JWT path.
 *
 * Calls the PostgREST-exposed `public.coach_rc_*` / `public.coach_reconcile_user_entitlements`
 * wrappers (coach_internal is not an exposed schema). Identity resolution uses the GoTrue admin
 * API (`GET /auth/v1/admin/users/{id}`) — same admin surface the account-delete path already uses.
 */
class RevenueCatEntitlementGateway(
    private val httpClient: HttpClient,
    private val supabaseUrl: String,
    private val serviceRoleKey: String,
) {
    private val log = LoggerFactory.getLogger(RevenueCatEntitlementGateway::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Atomically claim the event. Returns one of
     * `inserted` | `retry_failed` | `already_processing` | `already_processed`.
     * [payload] is stored as jsonb so the sweeper can replay the event without a fresh delivery.
     */
    suspend fun claimEvent(
        eventId: String,
        eventType: String,
        payload: JsonElement,
        identityCandidates: List<String>,
    ): String {
        val body = buildJsonObject {
            put("p_event_id", eventId)
            put("p_event_type", eventType)
            put("p_payload", payload)
            putJsonArray("p_identity_candidates") { identityCandidates.forEach { add(it) } }
        }
        val response = httpClient.post("$supabaseUrl/rest/v1/rpc/coach_rc_claim_event") {
            serviceRoleHeaders()
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonElement.serializer(), body))
        }
        val bodyText: String = response.body()
        if (!response.status.isSuccess()) {
            log.error("[COACH-RC] claim failed eventId={} status={} body={}", eventId, response.status, bodyText)
            error("coach_rc_claim_event failed: ${response.status}")
        }
        // Scalar text return → PostgREST yields a bare JSON string, e.g. "inserted". Be robust to
        // either wrapping shape (array / object) just in case, then fall back to raw text.
        return firstScalarText(bodyText)
    }

    /** Pull the first scalar string out of any PostgREST shape: `"x"`, `["x"]`, or `[{"fn":"x"}]`. */
    private fun firstScalarText(bodyText: String): String {
        fun firstPrimitive(el: JsonElement): kotlinx.serialization.json.JsonPrimitive? = when (el) {
            is kotlinx.serialization.json.JsonPrimitive -> el
            is kotlinx.serialization.json.JsonArray -> el.firstOrNull()?.let { firstPrimitive(it) }
            is kotlinx.serialization.json.JsonObject -> el.values.firstOrNull()?.let { firstPrimitive(it) }
            else -> null
        }
        val parsed = runCatching { Json.parseToJsonElement(bodyText) }.getOrNull()
        return parsed?.let { firstPrimitive(it)?.content } ?: bodyText.trim().trim('"')
    }

    suspend fun markProcessed(eventId: String, lastNote: String?) {
        // p_last_note has a SQL DEFAULT NULL — omit the key entirely when there's no note.
        val body = buildJsonObject {
            put("p_event_id", eventId)
            if (lastNote != null) put("p_last_note", lastNote)
        }
        rpcVoid("coach_rc_mark_event_processed", body, "markProcessed eventId=$eventId")
    }

    suspend fun markFailed(eventId: String, error: String) {
        val body = buildJsonObject {
            put("p_event_id", eventId)
            put("p_error", error.take(2000))
        }
        rpcVoid("coach_rc_mark_event_failed", body, "markFailed eventId=$eventId")
    }

    /** Replace the full entitlement set for one user. [entitlements] → the function's jsonb arg. */
    suspend fun reconcile(
        userId: String,
        rcAppUserId: String,
        environment: String,
        entitlements: List<EntitlementReconcileItem>,
    ) {
        val entitlementsJson = json.encodeToJsonElement(
            kotlinx.serialization.builtins.ListSerializer(EntitlementReconcileItem.serializer()),
            entitlements,
        )
        val body = buildJsonObject {
            put("p_user_id", userId)
            put("p_revenuecat_app_user_id", rcAppUserId)
            put("p_environment", environment)
            put("p_entitlements", entitlementsJson)
        }
        rpcVoid("coach_reconcile_user_entitlements", body, "reconcile userId=$userId")
    }

    /**
     * Grant credit top-up packs from RC consumable purchases. Idempotent on each grant's
     * `rc_transaction_id` (ON CONFLICT DO NOTHING). Returns the number of NEW packs inserted.
     */
    suspend fun grantTopUps(
        userId: String,
        environment: String,
        grants: List<TopUpGrantItem>,
    ): Int {
        val grantsJson = json.encodeToJsonElement(
            kotlinx.serialization.builtins.ListSerializer(TopUpGrantItem.serializer()),
            grants,
        )
        val body = buildJsonObject {
            put("p_user_id", userId)
            put("p_environment", environment)
            put("p_grants", grantsJson)
        }
        val response = httpClient.post("$supabaseUrl/rest/v1/rpc/coach_grant_credit_topups") {
            serviceRoleHeaders()
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonElement.serializer(), body))
        }
        val bodyText: String = response.body()
        if (!response.status.isSuccess()) {
            log.error("[COACH-RC] grantTopUps failed userId={} status={} body={}", userId, response.status, bodyText)
            error("coach_grant_credit_topups failed: ${response.status}")
        }
        return bodyText.trim().toIntOrNull() ?: 0
    }

    @Serializable
    private data class SandboxTopupCreditsRow(@SerialName("credits_granted") val creditsGranted: Long)

    /**
     * Sum of `credits_granted` across all SANDBOX-environment top-ups this user has ever received.
     * Used to cap the bounded sandbox test-tier grant (see [RevenueCatSyncService]) — a handful of
     * rows per user, so summing client-side beats standing up a dedicated RPC.
     */
    suspend fun sandboxTopupCreditsGranted(userId: String): Long {
        val response = httpClient.get("$supabaseUrl/rest/v1/coach_credit_topup") {
            serviceRoleHeaders()
            parameter("user_id", "eq.$userId")
            parameter("environment", "eq.SANDBOX")
            parameter("select", "credits_granted")
        }
        val bodyText: String = response.body()
        if (!response.status.isSuccess()) {
            log.error("[COACH-RC] sandboxTopupCreditsGranted failed userId={} status={} body={}", userId, response.status, bodyText)
            error("sandboxTopupCreditsGranted failed: ${response.status}")
        }
        return json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(SandboxTopupCreditsRow.serializer()),
            bodyText,
        ).sumOf { it.creditsGranted }
    }

    /** Sweeper recall: atomically re-claim stuck/failed events for replay. */
    suspend fun claimRecoverable(
        staleAfter: String = "5 minutes",
        retryAfter: String = "5 minutes",
        limit: Int = 100,
        maxAttempts: Int = 10,
    ): List<ClaimedRevenueCatEventRow> {
        val body = buildJsonObject {
            put("p_stale_after", staleAfter)
            put("p_retry_after", retryAfter)
            put("p_limit", limit)
            put("p_max_attempts", maxAttempts)
        }
        val response = httpClient.post("$supabaseUrl/rest/v1/rpc/coach_rc_claim_recoverable_events") {
            serviceRoleHeaders()
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonElement.serializer(), body))
        }
        val bodyText: String = response.body()
        if (!response.status.isSuccess()) {
            log.error("[COACH-RC] claimRecoverable failed status={} body={}", response.status, bodyText)
            error("coach_rc_claim_recoverable_events failed: ${response.status}")
        }
        return json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(ClaimedRevenueCatEventRow.serializer()),
            bodyText,
        )
    }

    /**
     * Resolve a (UUID-shaped) RevenueCat identity candidate to a Supabase user via GoTrue admin.
     * `200` → [UserResolution.EXISTS]; `404` → [UserResolution.NOT_FOUND] (skip, log). Any other
     * status / transport error throws so the caller marks the event failed and lets it retry — a
     * transient GoTrue blip must never be mistaken for "no such user".
     */
    suspend fun resolveUser(userId: String): UserResolution {
        val encoded = URLEncoder.encode(userId, Charsets.UTF_8)
        val response = httpClient.get("$supabaseUrl/auth/v1/admin/users/$encoded") {
            serviceRoleHeaders()
        }
        return when {
            response.status.isSuccess() -> UserResolution.EXISTS
            response.status == HttpStatusCode.NotFound -> UserResolution.NOT_FOUND
            else -> {
                log.error("[COACH-RC] GoTrue user lookup failed status={}", response.status)
                error("GoTrue admin user lookup failed: ${response.status}")
            }
        }
    }

    private suspend fun rpcVoid(fn: String, body: JsonElement, ctx: String) {
        val response = httpClient.post("$supabaseUrl/rest/v1/rpc/$fn") {
            serviceRoleHeaders()
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonElement.serializer(), body))
        }
        if (!response.status.isSuccess()) {
            val bodyText: String = response.body()
            log.error("[COACH-RC] {} failed ({}) status={} body={}", fn, ctx, response.status, bodyText)
            error("$fn failed: ${response.status}")
        }
    }

    private fun HttpRequestBuilder.serviceRoleHeaders() {
        header("apikey", serviceRoleKey)
        header("Authorization", "Bearer $serviceRoleKey")
    }
}
