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
 * Service-role gateway for the atomic monthly token budget.
 *
 * Calls the PostgREST-exposed `public.coach_budget_*` wrappers, which delegate to the
 * `coach_internal.budget_*` functions. Bypasses RLS — only ever invoked from the
 * coach turn orchestrator with the JWT-derived `userId`, never a client-supplied id.
 *
 *   reserve   → turn-start, idempotent on (userId, requestId)
 *   reconcile → turn-end, replaces the reserved estimate with actual token counts
 *   release   → stream cancel / error, reverses the reservation (no-op once settled)
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
        @SerialName("p_cap_messages") val capMessages: Int,
        @SerialName("p_cap_tokens") val capTokens: Long,
    )

    @Serializable
    private data class ReconcileBody(
        @SerialName("p_reservation_id") val reservationId: String,
        @SerialName("p_actual_input") val actualInput: Int,
        @SerialName("p_actual_output") val actualOutput: Int,
    )

    @Serializable
    private data class ReleaseBody(@SerialName("p_reservation_id") val reservationId: String)

    /**
     * Atomic budget reservation. Returns [BudgetReserveResult]; when `allowed == false`
     * the `reason` is `cap_messages` or `cap_tokens`. Throws on transport failure so the
     * caller can decide its fail-open policy.
     */
    suspend fun reserve(
        userId: String,
        requestId: String,
        period: Int,
        inputMax: Int,
        outputMax: Int,
        capMessages: Int,
        capTokens: Long,
    ): BudgetReserveResult {
        val body = ReserveBody(userId, requestId, period, inputMax, outputMax, capMessages, capTokens)
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

    /** Adjust the reservation from the reserved estimate to actual token usage. Idempotent: a no-op once settled. */
    suspend fun reconcile(reservationId: String, actualInput: Int, actualOutput: Int) {
        val body = ReconcileBody(reservationId, actualInput.coerceAtLeast(0), actualOutput.coerceAtLeast(0))
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
