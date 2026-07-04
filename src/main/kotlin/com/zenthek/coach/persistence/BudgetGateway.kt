package com.zenthek.coach.persistence

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Service-role gateway for the atomic monthly cost-weighted credit budget.
 *
 * Calls the PostgREST-exposed `public.coach_budget_*` wrappers, which delegate to the
 * `coach_internal.budget_*` functions. Bypasses RLS — only ever invoked from the
 * coach turn orchestrator with the JWT-derived `userId`, never a client-supplied id.
 *
 * 1 credit = 1 Lite input token; the authoritative weighting is
 * `coach_internal.budget_credits()` (see docs/AI_COACH.md).
 *
 *   reserve   → turn-start, idempotent on (userId, requestId); draws the estimate
 *               from the monthly pot first, then purchased top-up packs
 *   reconcile → turn-end, refunds the estimate and re-draws the actual weighted
 *               cost from per-segment token counts (Lite vs Pro); never rejects
 *   release   → stream cancel / error, exact refund of the recorded draw split
 */
class BudgetGateway(
    private val httpClient: HttpClient,
    private val supabaseUrl: String,
    private val serviceRoleKey: String,
) {
    private val log = LoggerFactory.getLogger(BudgetGateway::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class ReserveBody(
        @SerialName("p_user_id") val userId: String,
        @SerialName("p_request_id") val requestId: String,
        @SerialName("p_period") val period: Int,
        @SerialName("p_input_max") val inputMax: Int,
        @SerialName("p_output_max") val outputMax: Int,
        @SerialName("p_credits_max") val creditsMax: Long,
        @SerialName("p_cap_messages") val capMessages: Int,
        @SerialName("p_cap_credits") val capCredits: Long,
    )

    @Serializable
    private data class ReconcileBody(
        @SerialName("p_reservation_id") val reservationId: String,
        @SerialName("p_lite_input") val liteInput: Int,
        @SerialName("p_lite_output") val liteOutput: Int,
        @SerialName("p_pro_input") val proInput: Int,
        @SerialName("p_pro_output") val proOutput: Int,
        @SerialName("p_cached_input") val cachedInput: Int,
    )

    @Serializable
    private data class ReleaseBody(@SerialName("p_reservation_id") val reservationId: String)

    @Serializable
    private data class UsageBody(
        @SerialName("p_user_id") val userId: String,
        @SerialName("p_period") val period: Int,
    )

    /**
     * Atomic budget reservation. Returns [BudgetReserveResult]; when `allowed == false`
     * the `reason` is `cap_messages` or `cap_credits`. Throws on transport failure so the
     * caller can decide its fail-open policy.
     *
     * [estimatedCredits] is the Kotlin-side weighted estimate for this turn; reconcile
     * replaces it with the SQL-computed actual, so estimation drift is harmless.
     */
    suspend fun reserve(
        userId: String,
        requestId: String,
        period: Int,
        inputMax: Int,
        outputMax: Int,
        estimatedCredits: Long,
        capMessages: Int,
        capCredits: Long,
    ): BudgetReserveResult {
        val body = ReserveBody(
            userId, requestId, period, inputMax, outputMax,
            estimatedCredits.coerceAtLeast(0), capMessages, capCredits,
        )
        val response = httpClient.post("$supabaseUrl/rest/v1/rpc/coach_budget_reserve") {
            serviceRoleHeaders()
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(ReserveBody.serializer(), body))
        }
        val bodyText: String = response.body()
        if (!response.status.isSuccess()) {
            log.error("[COACH-BUDGET] reserve failed userId={} status={} body={}", userId, response.status, bodyText)
            error("coach_budget_reserve failed: ${response.status}")
        }
        // The function RETURNS TABLE(...) → PostgREST yields a single-row JSON array.
        return json.decodeFromString<List<BudgetReserveResult>>(bodyText).first()
    }

    /**
     * Settle the reservation to the actual per-segment token counts (Lite pass vs Pro
     * escalation pass — different credit weights). [cachedInput] is the cached share of
     * the Lite input (quarter weight); pass 0 until the stream exposes cached counts.
     * Idempotent: a no-op once settled.
     */
    suspend fun reconcile(
        reservationId: String,
        liteInput: Int,
        liteOutput: Int,
        proInput: Int,
        proOutput: Int,
        cachedInput: Int = 0,
    ) {
        val body = ReconcileBody(
            reservationId,
            liteInput.coerceAtLeast(0),
            liteOutput.coerceAtLeast(0),
            proInput.coerceAtLeast(0),
            proOutput.coerceAtLeast(0),
            cachedInput.coerceAtLeast(0),
        )
        val response = httpClient.post("$supabaseUrl/rest/v1/rpc/coach_budget_reconcile") {
            serviceRoleHeaders()
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(ReconcileBody.serializer(), body))
        }
        if (!response.status.isSuccess()) {
            val bodyText: String = response.body()
            log.error("[COACH-BUDGET] reconcile failed reservationId={} status={} body={}", reservationId, response.status, bodyText)
            error("coach_budget_reconcile failed: ${response.status}")
        }
    }

    /** Reverse the full reservation. Idempotent: a no-op unless the reservation is still 'reserved'. */
    suspend fun release(reservationId: String) {
        val response = httpClient.post("$supabaseUrl/rest/v1/rpc/coach_budget_release") {
            serviceRoleHeaders()
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(ReleaseBody.serializer(), ReleaseBody(reservationId)))
        }
        if (!response.status.isSuccess()) {
            val bodyText: String = response.body()
            log.error("[COACH-BUDGET] release failed reservationId={} status={} body={}", reservationId, response.status, bodyText)
            error("coach_budget_release failed: ${response.status}")
        }
    }

    /** Read-only usage snapshot for the current period (always one row; zeros when unused). */
    suspend fun usage(userId: String, period: Int): BudgetUsageRow {
        val response = httpClient.post("$supabaseUrl/rest/v1/rpc/coach_usage") {
            serviceRoleHeaders()
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(UsageBody.serializer(), UsageBody(userId, period)))
        }
        val bodyText: String = response.body()
        if (!response.status.isSuccess()) {
            log.error("[COACH-BUDGET] usage failed userId={} status={} body={}", userId, response.status, bodyText)
            error("coach_usage failed: ${response.status}")
        }
        return json.decodeFromString<List<BudgetUsageRow>>(bodyText).first()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.serviceRoleHeaders() {
        header("apikey", serviceRoleKey)
        header("Authorization", "Bearer $serviceRoleKey")
    }
}

@Serializable
data class BudgetReserveResult(
    val allowed: Boolean,
    val reason: String? = null,
    @SerialName("reservation_id") val reservationId: String? = null,
    val status: String? = null,
)

@Serializable
data class BudgetUsageRow(
    @SerialName("credits_used") val creditsUsed: Long = 0,
    @SerialName("pro_credits_used") val proCreditsUsed: Long = 0,
    @SerialName("messages_used") val messagesUsed: Int = 0,
    @SerialName("topup_remaining") val topupRemaining: Long = 0,
    @SerialName("topup_granted") val topupGranted: Long = 0,
)
