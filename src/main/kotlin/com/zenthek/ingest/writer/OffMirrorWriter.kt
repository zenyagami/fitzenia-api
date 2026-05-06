package com.zenthek.ingest.writer

import com.zenthek.ingest.OffMirrorRow
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory

/**
 * Writes OFF mirror rows back to Supabase via the service-role REST endpoint.
 *
 * - [upsertBatch] calls `public.upsert_off_products(items JSONB)` with a
 *   bounded batch of [OffMirrorRow] (~500 rows recommended). Returns the
 *   inserted/updated counts the RPC reports back.
 * - [softDeleteUnseen] calls `public.soft_delete_off_unseen(p_before)` once at
 *   the END of a successful full reconcile. `p_before` MUST be the wall clock
 *   captured BEFORE streaming began.
 *
 * Both endpoints bypass RLS (service-role); never invoke this writer from a
 * user-scoped request path. It is only constructed inside the ingest Job.
 */
class OffMirrorWriter(
    private val httpClient: HttpClient,
    supabaseUrl: String,
    private val serviceRoleKey: String,
) {
    private val log = LoggerFactory.getLogger(OffMirrorWriter::class.java)
    private val baseUrl = supabaseUrl.trimEnd('/')

    /** Upserts a batch via the bulk RPC. Returns counts; throws on non-2xx. */
    suspend fun upsertBatch(rows: List<OffMirrorRow>): UpsertCounts {
        return upsertBatchInternal(rows, depth = 0)
    }

    private suspend fun upsertBatchInternal(rows: List<OffMirrorRow>, depth: Int): UpsertCounts {
        if (rows.isEmpty()) return UpsertCounts.EMPTY
        val startedAt = System.nanoTime()
        val response = try {
            httpClient.post("$baseUrl/rest/v1/rpc/upsert_off_products") {
                applyServiceRoleAuth()
                applyLongTimeout()
                contentType(ContentType.Application.Json)
                // PostgREST RPC wraps args under their declared names:
                // CREATE FUNCTION upsert_off_products(items JSONB) → body = {"items":[...]}
                setBody(UpsertEnvelope(items = rows))
            }
        } catch (timeout: HttpRequestTimeoutException) {
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            return splitTimedOutBatchOrThrow(
                rows = rows,
                depth = depth,
                elapsedMs = elapsedMs,
                timeout = timeout,
            )
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        if (!response.status.isSuccess()) {
            val body = runCatching { response.bodyAsText() }.getOrDefault("<no body>")
            val failure = parseSupabaseFailure(body)
            if (failure.code == STATEMENT_TIMEOUT_CODE && rows.size > 1) {
                log.warn(
                    "[OFF-INGEST] upsertBatch timeout; splitting batch status={} code={} message={} batchSize={} " +
                        "elapsedMs={} depth={} sampleCodes={} body={}",
                    response.status.value, failure.code, failure.message, rows.size,
                    elapsedMs, depth, sampleCodes(rows), body.take(500),
                )
                return splitBatch(rows, depth)
            }
            log.error(
                "[OFF-INGEST] upsertBatch failed status={} code={} message={} batchSize={} elapsedMs={} depth={} " +
                    "sampleCodes={} body={}",
                response.status.value, failure.code, failure.message, rows.size,
                elapsedMs, depth, sampleCodes(rows), body.take(500),
            )
            throw IllegalStateException("upsert_off_products RPC failed with ${response.status.value}")
        }
        // The RPC returns a TABLE, which PostgREST renders as a single-row JSON array.
        val parsed = response.body<List<UpsertResult>>().firstOrNull() ?: UpsertResult(0L, 0L)
        return UpsertCounts(inserted = parsed.inserted, updated = parsed.updated)
    }

    private suspend fun splitTimedOutBatchOrThrow(
        rows: List<OffMirrorRow>,
        depth: Int,
        elapsedMs: Long,
        timeout: HttpRequestTimeoutException,
    ): UpsertCounts {
        if (rows.size == 1) {
            log.error(
                "[OFF-INGEST] upsertBatch client timeout on single row batchSize={} elapsedMs={} depth={} " +
                    "sampleCodes={} message={}",
                rows.size, elapsedMs, depth, sampleCodes(rows), timeout.message,
            )
            throw timeout
        }

        log.warn(
            "[OFF-INGEST] upsertBatch client timeout; splitting batch batchSize={} elapsedMs={} depth={} " +
                "sampleCodes={} message={}",
            rows.size, elapsedMs, depth, sampleCodes(rows), timeout.message,
        )
        return splitBatch(rows, depth)
    }

    private suspend fun splitBatch(rows: List<OffMirrorRow>, depth: Int): UpsertCounts {
        val mid = rows.size / 2
        return upsertBatchInternal(rows.subList(0, mid), depth + 1) +
            upsertBatchInternal(rows.subList(mid, rows.size), depth + 1)
    }

    /**
     * Soft-deletes rows whose `synced_at` is older than [before]. Must be
     * called only at the end of a successful full reconcile.
     */
    suspend fun softDeleteUnseen(before: String): Long {
        val response = httpClient.post("$baseUrl/rest/v1/rpc/soft_delete_off_unseen") {
            applyServiceRoleAuth()
            applyLongTimeout()
            contentType(ContentType.Application.Json)
            setBody(SoftDeleteEnvelope(p_before = before))
        }
        if (!response.status.isSuccess()) {
            val body = runCatching { response.bodyAsText() }.getOrDefault("<no body>")
            log.error(
                "[OFF-INGEST] softDeleteUnseen failed status={} body={}",
                response.status.value, body.take(500),
            )
            throw IllegalStateException("soft_delete_off_unseen RPC failed with ${response.status.value}")
        }
        // PostgREST scalar-RPC body shape can be a bare number (`42`) or a
        // wrapped array (`[42]`) depending on the Supabase build. Parse
        // defensively so a successful run never trips on the last call.
        val bodyText = response.bodyAsText().trim()
        return parseScalarLong(bodyText)
    }

    private fun parseScalarLong(body: String): Long {
        if (body.isBlank()) return 0L
        val element: JsonElement = runCatching { Json.parseToJsonElement(body) }
            .getOrElse { return body.toLongOrNull() ?: 0L }
        return when (element) {
            is JsonPrimitive -> element.longOrNull ?: element.content.toLongOrNull() ?: 0L
            is JsonArray -> (element.firstOrNull() as? JsonPrimitive)?.longOrNull ?: 0L
            else -> 0L
        }
    }

    private fun parseSupabaseFailure(body: String): SupabaseFailure {
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return SupabaseFailure()
        return SupabaseFailure(
            code = obj.stringField("code"),
            message = obj.stringField("message"),
        )
    }

    private fun JsonObject.stringField(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull

    private fun sampleCodes(rows: List<OffMirrorRow>): String =
        rows.asSequence().take(5).joinToString(",") { it.code }

    private fun io.ktor.client.request.HttpRequestBuilder.applyServiceRoleAuth() {
        header("apikey", serviceRoleKey)
        bearerAuth(serviceRoleKey)
        header(HttpHeaders.Accept, "application/json")
    }

    /** Bulk RPCs may take >15s on a 500-row UPSERT against a busy DB. */
    private fun io.ktor.client.request.HttpRequestBuilder.applyLongTimeout() {
        timeout {
            requestTimeoutMillis = 60_000L
            connectTimeoutMillis = 10_000L
        }
    }

    data class UpsertCounts(val inserted: Long, val updated: Long) {
        operator fun plus(other: UpsertCounts) =
            UpsertCounts(inserted + other.inserted, updated + other.updated)

        companion object {
            val EMPTY = UpsertCounts(0L, 0L)
        }
    }

    private data class SupabaseFailure(
        val code: String? = null,
        val message: String? = null,
    )

    private companion object {
        const val STATEMENT_TIMEOUT_CODE = "57014"
    }
}

@Serializable
private data class UpsertEnvelope(val items: List<OffMirrorRow>)

@Serializable
private data class SoftDeleteEnvelope(val p_before: String)

@Serializable
private data class UpsertResult(
    @SerialName("inserted") val inserted: Long = 0L,
    @SerialName("updated") val updated: Long = 0L,
)
