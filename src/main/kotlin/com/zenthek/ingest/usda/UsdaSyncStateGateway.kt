package com.zenthek.ingest.usda

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.LocalDate

/**
 * Read/write gateway for `public.usda_sync_state` audit rows. Mirrors
 * `OffSyncStateGateway` with two differences:
 *   - 8-hour staleness window (vs OFF's 6h) — Branded reconcile is bigger.
 *   - `release_date` checkpoint exposed via [lastSuccessfulReleaseDate] so
 *     `UsdaFullReconcileJob` can no-op when the same release is already mirrored.
 *
 * Service-role only. RLS on, no client policies.
 */
class UsdaSyncStateGateway(
    private val httpClient: HttpClient,
    supabaseUrl: String,
    private val serviceRoleKey: String,
) {
    private val log = LoggerFactory.getLogger(UsdaSyncStateGateway::class.java)
    private val baseUrl = supabaseUrl.trimEnd('/')

    /**
     * Returns the release_date of the most recent OK or NO_NEW_RELEASE run.
     * Either status proves we have already observed that release; the
     * reconcile job uses this to short-circuit without streaming again.
     */
    suspend fun lastSuccessfulReleaseDate(): LocalDate? {
        val response = httpClient.get("$baseUrl/rest/v1/usda_sync_state") {
            applyServiceRoleAuth()
            // PostgREST `or` filter — both terminal "we know about this release" statuses qualify.
            parameter("or", "(status.eq.OK,status.eq.NO_NEW_RELEASE)")
            parameter("order", "release_date.desc.nullslast")
            parameter("limit", 1)
        }
        if (!response.status.isSuccess()) {
            log.warn("[USDA-INGEST] lastSuccessfulReleaseDate failed status={}", response.status.value)
            return null
        }
        val row = response.body<List<UsdaSyncStateRow>>().firstOrNull() ?: return null
        return row.releaseDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    }

    /**
     * Returns any RUNNING row started within [staleAfterSeconds]. Default 8h
     * for USDA — Branded reconcile is bigger than OFF.
     */
    suspend fun activeRunningRow(staleAfterSeconds: Long = 8 * 3600L): UsdaSyncStateRow? {
        val response = httpClient.get("$baseUrl/rest/v1/usda_sync_state") {
            applyServiceRoleAuth()
            parameter("status", "eq.RUNNING")
            parameter("order", "started_at.desc")
            parameter("limit", 5)
        }
        if (!response.status.isSuccess()) {
            log.warn("[USDA-INGEST] activeRunningRow failed status={}", response.status.value)
            return null
        }
        val rows = response.body<List<UsdaSyncStateRow>>()
        val cutoff = java.time.Instant.now().minusSeconds(staleAfterSeconds)
        return rows.firstOrNull { row ->
            runCatching { java.time.Instant.parse(row.startedAt) }
                .map { it.isAfter(cutoff) }
                .getOrDefault(true)
        }
    }

    suspend fun beginRun(jobKind: String, dryRun: Boolean): String {
        val response = httpClient.post("$baseUrl/rest/v1/usda_sync_state") {
            applyServiceRoleAuth()
            contentType(ContentType.Application.Json)
            header("Prefer", "return=representation")
            setBody(BeginRunBody(jobKind = jobKind, dryRun = dryRun))
        }
        if (!response.status.isSuccess()) {
            val body = runCatching { response.bodyAsText() }.getOrDefault("<no body>")
            error("[USDA-INGEST] beginRun failed status=${response.status.value} body=${body.take(500)}")
        }
        val rows = response.body<List<UsdaSyncStateRow>>()
        return rows.firstOrNull()?.id ?: error("[USDA-INGEST] beginRun returned no row")
    }

    suspend fun finishRun(
        id: String,
        status: String,
        rowsInserted: Long = 0L,
        rowsUpdated: Long = 0L,
        rowsSoftDeleted: Long = 0L,
        releaseDate: LocalDate? = null,
        errorMessage: String? = null,
    ) {
        val body = FinishRunBody(
            status = status,
            finishedAt = java.time.Instant.now().toString(),
            rowsInserted = rowsInserted,
            rowsUpdated = rowsUpdated,
            rowsSoftDeleted = rowsSoftDeleted,
            releaseDate = releaseDate?.toString(),
            errorMessage = errorMessage,
        )
        // One retry on timeout — see OffSyncStateGateway for the rationale.
        val response = runCatching { sendFinishPatch(id, body) }
            .getOrElse { first ->
                if (first !is HttpRequestTimeoutException) throw first
                log.warn("[USDA-INGEST] finishRun timed out; retrying once id={}", id)
                kotlinx.coroutines.delay(5_000L)
                sendFinishPatch(id, body)
            }
        if (!response.status.isSuccess()) {
            val text = runCatching { response.bodyAsText() }.getOrDefault("<no body>")
            log.error(
                "[USDA-INGEST] finishRun failed status={} id={} body={}",
                response.status.value, id, text.take(500),
            )
        }
    }

    private suspend fun sendFinishPatch(id: String, body: FinishRunBody) =
        httpClient.patch("$baseUrl/rest/v1/usda_sync_state") {
            applyServiceRoleAuth()
            contentType(ContentType.Application.Json)
            applyLongTimeout()
            parameter("id", "eq.$id")
            setBody(body)
        }

    private fun io.ktor.client.request.HttpRequestBuilder.applyServiceRoleAuth() {
        header("apikey", serviceRoleKey)
        bearerAuth(serviceRoleKey)
        header(HttpHeaders.Accept, "application/json")
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyLongTimeout() {
        timeout {
            requestTimeoutMillis = 90_000L
            connectTimeoutMillis = 10_000L
        }
    }
}

@Serializable
data class UsdaSyncStateRow(
    val id: String,
    @SerialName("job_kind") val jobKind: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("finished_at") val finishedAt: String? = null,
    val status: String,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("rows_inserted") val rowsInserted: Long = 0L,
    @SerialName("rows_updated") val rowsUpdated: Long = 0L,
    @SerialName("rows_soft_deleted") val rowsSoftDeleted: Long = 0L,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("dry_run") val dryRun: Boolean = false,
)

@Serializable
private data class BeginRunBody(
    @SerialName("job_kind") val jobKind: String,
    @SerialName("dry_run") val dryRun: Boolean,
    val status: String = "RUNNING",
)

@Serializable
private data class FinishRunBody(
    val status: String,
    @SerialName("finished_at") val finishedAt: String,
    @SerialName("rows_inserted") val rowsInserted: Long,
    @SerialName("rows_updated") val rowsUpdated: Long,
    @SerialName("rows_soft_deleted") val rowsSoftDeleted: Long,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
)
