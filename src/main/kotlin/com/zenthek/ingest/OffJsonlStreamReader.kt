package com.zenthek.ingest

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * Streams a gzipped JSONL OFF dump (delta or full) line-by-line into JsonObject
 * events without buffering the whole payload in memory.
 *
 * Pipeline: HTTP body → gzip stream → text reader → readLine → Json.decodeFromString.
 * Each row is decoded permissively (`ignoreUnknownKeys = true`); a malformed line
 * is logged and skipped — one bad row never aborts the stream.
 *
 * The caller owns the [HttpClient]; this reader does not close it. The underlying
 * input stream IS closed when the [block] returns.
 */
class OffJsonlStreamReader(
    private val httpClient: HttpClient,
    private val json: Json = LENIENT_JSON,
) {
    private val log = LoggerFactory.getLogger(OffJsonlStreamReader::class.java)

    /**
     * Open [url] as a gzip-decoded JSONL stream and invoke [block] for each
     * decoded JsonObject. Lines that fail to parse are logged and skipped.
     *
     * @param onProgress optional callback invoked every [progressEvery] decoded
     *                   rows so callers can log throughput. Defaults to a no-op.
     */
    suspend fun streamRows(
        url: String,
        progressEvery: Long = 100_000L,
        onProgress: suspend (rowsRead: Long) -> Unit = {},
        requestTimeoutMillis: Long = 4 * 60 * 60 * 1000L, // 4h, matches Job timeout
        block: suspend (JsonObject) -> Unit,
    ) {
        log.info("[OFF-INGEST] streamRows opening url={}", url)
        var rows = 0L
        var malformed = 0L
        httpClient.prepareGet(url) {
            applyLongRequestTimeout(requestTimeoutMillis)
        }.execute { response ->
            val rawInput = response.bodyAsChannel().toInputStream()
            BufferedReader(InputStreamReader(GZIPInputStream(rawInput, READ_BUFFER), Charsets.UTF_8))
                .use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) continue
                        val obj = try {
                            json.decodeFromString(JsonObject.serializer(), line)
                        } catch (t: Throwable) {
                            malformed += 1
                            if (malformed <= MALFORMED_LOG_LIMIT) {
                                log.warn("[OFF-INGEST] malformed JSONL line skipped (count={}) msg={}", malformed, t.message)
                            }
                            continue
                        }
                        block(obj)
                        rows += 1
                        if (progressEvery > 0 && rows % progressEvery == 0L) {
                            onProgress(rows)
                        }
                    }
                }
        }
        log.info("[OFF-INGEST] streamRows finished url={} rows={} malformed={}", url, rows, malformed)
    }

    private fun HttpRequestBuilder.applyLongRequestTimeout(millis: Long) {
        // The shared HttpClient defaults to 15s; OFF dumps are multi-GB so we
        // override per call. Connect timeout is left at the global default
        // because TCP handshake is cheap.
        timeout { requestTimeoutMillis = millis }
    }

    companion object {
        private const val READ_BUFFER = 64 * 1024
        private const val MALFORMED_LOG_LIMIT = 50L

        val LENIENT_JSON: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }
}
