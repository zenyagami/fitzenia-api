package com.zenthek.ingest.writer

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
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

/**
 * Read/write gateway for `public.off_sync_state` audit rows. Tracks every
 * delta + full reconcile run so the next run can pick up where we left off and
 * Cloud Logging has a queryable trail.
 *
 * Service-role only. RLS on, no client policies.
 */
class OffSyncStateGateway(
    private val httpClient: HttpClient,
    supabaseUrl: String,
    private val serviceRoleKey: String,
) {
    private val log = LoggerFactory.getLogger(OffSyncStateGateway::class.java)
    private val baseUrl = supabaseUrl.trimEnd('/')

    /**
     * Returns the most recent successful run of either kind (`DELTA` or `FULL`).
     * Used by the delta job to compute its checkpoint — if the latest success
     * was a full run, we still pick up from there because a full pass also
     * advances `last_modified_t_max` to the dump's high-water mark.
     */
    suspend fun lastSuccessfulRun(): OffSyncStateRow? {
        val response = httpClient.get("$baseUrl/rest/v1/off_sync_state") {
            applyServiceRoleAuth()
            parameter("status", "eq.OK")
            parameter("order", "finished_at.desc.nullslast")
            parameter("limit", 1)
        }
        if (!response.status.isSuccess()) {
            log.warn("[OFF-INGEST] lastSuccessfulRun failed status={}", response.status.value)
            return null
        }
        return response.body<List<OffSyncStateRow>>().firstOrNull()
    }

    /**
     * Returns any RUNNING row started within [staleAfterSeconds] (rows older
     * than that are treated as crashed and ignored). Callers use this for
     * coarse-grained mutual exclusion between concurrent ingest invocations.
     */
    suspend fun activeRunningRow(staleAfterSeconds: Long = 6 * 3600L): OffSyncStateRow? {
        val response = httpClient.get("$baseUrl/rest/v1/off_sync_state") {
            applyServiceRoleAuth()
            parameter("status", "eq.RUNNING")
            parameter("order", "started_at.desc")
            parameter("limit", 5)
        }
        if (!response.status.isSuccess()) {
            log.warn("[OFF-INGEST] activeRunningRow failed status={}", response.status.value)
            return null
        }
        val rows = response.body<List<OffSyncStateRow>>()
        // Naïve-but-good-enough staleness gate: row must have started within the
        // configured window. This avoids needing a tight DB clock comparison —
        // the staleness threshold is generous (default 6h, matches Job timeout +
        // headroom). A truly stuck row blocks at most one cycle.
        val cutoff = java.time.Instant.now().minusSeconds(staleAfterSeconds)
        return rows.firstOrNull { row ->
            runCatching { java.time.Instant.parse(row.startedAt) }
                .map { it.isAfter(cutoff) }
                .getOrDefault(true) // err on the side of "still alive" if parsing fails
        }
    }

    /**
     * Inserts a new RUNNING row and returns its server-assigned id. The job
     * updates this row to OK/FAILED/CANCELLED as it progresses.
     */
    suspend fun beginRun(jobKind: String, dryRun: Boolean): String {
        val response = httpClient.post("$baseUrl/rest/v1/off_sync_state") {
            applyServiceRoleAuth()
            contentType(ContentType.Application.Json)
            header("Prefer", "return=representation")
            setBody(BeginRunBody(jobKind = jobKind, dryRun = dryRun))
        }
        if (!response.status.isSuccess()) {
            val body = runCatching { response.bodyAsText() }.getOrDefault("<no body>")
            error("[OFF-INGEST] beginRun failed status=${response.status.value} body=${body.take(500)}")
        }
        val rows = response.body<List<OffSyncStateRow>>()
        return rows.firstOrNull()?.id ?: error("[OFF-INGEST] beginRun returned no row")
    }

    /** Final-state update on a completed (OK / FAILED / CANCELLED) run. */
    suspend fun finishRun(
        id: String,
        status: String,
        rowsInserted: Long = 0L,
        rowsUpdated: Long = 0L,
        rowsSoftDeleted: Long = 0L,
        lastModifiedTMax: Long? = null,
        deltaFilesProcessed: List<String>? = null,
        errorMessage: String? = null,
    ) {
        val body = FinishRunBody(
            status = status,
            finishedAt = java.time.Instant.now().toString(),
            rowsInserted = rowsInserted,
            rowsUpdated = rowsUpdated,
            rowsSoftDeleted = rowsSoftDeleted,
            lastModifiedTMax = lastModifiedTMax,
            deltaFilesProcessed = deltaFilesProcessed,
            errorMessage = errorMessage,
        )
        // The PATCH lands at the very end of a 1–2 hour ingest, when PostgREST
        // workers are saturated by the preceding bulk upsert + soft-delete.
        // A single retry on HttpRequestTimeoutException turns a saturated-pooler
        // blip into a successful audit write — the PATCH is naturally idempotent
        // (same id, same final fields).
        val response = runCatching { sendFinishPatch(id, body) }
            .getOrElse { first ->
                if (first !is HttpRequestTimeoutException) throw first
                log.warn("[OFF-INGEST] finishRun timed out; retrying once id={}", id)
                kotlinx.coroutines.delay(5_000L)
                sendFinishPatch(id, body)
            }
        if (!response.status.isSuccess()) {
            val text = runCatching { response.bodyAsText() }.getOrDefault("<no body>")
            log.error(
                "[OFF-INGEST] finishRun failed status={} id={} body={}",
                response.status.value, id, text.take(500),
            )
            // Don't throw — failing to log shouldn't roll back the actual ingest.
        }
    }

    private suspend fun sendFinishPatch(id: String, body: FinishRunBody) =
        httpClient.patch("$baseUrl/rest/v1/off_sync_state") {
            applyServiceRoleAuth()
            contentType(ContentType.Application.Json)
            applyLongTimeout()
            parameter("id", "eq.$id")
            setBody(body)
        }

    /**
     * Hard-deletes the row. Used by tests to cleanup; not invoked by jobs.
     * Kept here rather than a separate test helper to centralize the gateway
     * surface area.
     */
    suspend fun deleteRun(id: String) {
        httpClient.delete("$baseUrl/rest/v1/off_sync_state") {
            applyServiceRoleAuth()
            parameter("id", "eq.$id")
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyServiceRoleAuth() {
        header("apikey", serviceRoleKey)
        bearerAuth(serviceRoleKey)
        header(HttpHeaders.Accept, "application/json")
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyLongTimeout() {
        // 90s, not 30s: the finishRun PATCH lands at the tail of a multi-hour
        // reconcile when PostgREST/pooler is starved. The PATCH itself is
        // typically <100 ms; the budget is for queueing time on a hot DB.
        timeout {
            requestTimeoutMillis = 90_000L
            connectTimeoutMillis = 10_000L
        }
    }
}

@Serializable
data class OffSyncStateRow(
    val id: String,
    @SerialName("job_kind") val jobKind: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("finished_at") val finishedAt: String? = null,
    val status: String,
    @SerialName("last_modified_t_max") val lastModifiedTMax: Long? = null,
    @SerialName("rows_inserted") val rowsInserted: Long = 0L,
    @SerialName("rows_updated") val rowsUpdated: Long = 0L,
    @SerialName("rows_soft_deleted") val rowsSoftDeleted: Long = 0L,
    @SerialName("delta_files_processed") val deltaFilesProcessed: List<String>? = null,
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
    @SerialName("last_modified_t_max") val lastModifiedTMax: Long? = null,
    @SerialName("delta_files_processed") val deltaFilesProcessed: List<String>? = null,
    @SerialName("error_message") val errorMessage: String? = null,
)
