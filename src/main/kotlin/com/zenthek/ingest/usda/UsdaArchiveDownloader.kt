package com.zenthek.ingest.usda

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import java.io.StringWriter
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Streams an FDC bulk download zip without buffering the whole archive in
 * memory or on disk. Memory ceiling: one row + one batch of 500 rows.
 *
 * Pipeline:
 *   HTTP body channel → ZipInputStream → first .json entry → Jackson token
 *   parser walks past the wrapper key (`BrandedFoods` / `FoundationFoods`)
 *   into the array, then yields each food object as a kotlinx [JsonObject]
 *   the mapper consumes.
 *
 * Garbage collection happens between rows — the mapper hands off a slim
 * [UsdaMirrorRow] to the writer, the JsonObject becomes unreachable.
 *
 * Each row that fails to parse is logged (up to 50) and skipped; one bad
 * record never breaks the stream.
 */
class UsdaArchiveDownloader(
    private val httpClient: HttpClient,
    private val progressEvery: Long = 100_000L,
) {
    private val log = LoggerFactory.getLogger(UsdaArchiveDownloader::class.java)
    private val jsonFactory = JsonFactory()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Streams every food object from [url] and invokes [block] on each. The
     * downloader picks the first `.json` entry inside the zip (the bulk dumps
     * ship a single JSON file).
     *
     * @param url e.g. `https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_branded_food_json_2026-04-22.zip`
     * @param onProgress invoked every [progressEvery] rows with the running count.
     * @param block invoked for each parsed JSON object. Throwing here aborts the stream.
     */
    suspend fun streamFoods(
        url: String,
        onProgress: (Long) -> Unit = { rows -> log.info("[USDA-INGEST] streamed rows={}", rows) },
        block: suspend (JsonObject) -> Unit,
    ) {
        httpClient.prepareGet(url) {
            timeout {
                // FDC bulk download: ~200 MB compressed. A 4-hour budget covers
                // slow links + a 60-min Branded reconcile with headroom.
                requestTimeoutMillis = 14_400_000L
                connectTimeoutMillis = 30_000L
            }
        }.execute { response ->
            val rawInput = response.bodyAsChannel().toInputStream()
            ZipInputStream(rawInput).use { zip ->
                val entry = generateSequence { zip.nextEntry }
                    .firstOrNull { !it.isDirectory && it.name.endsWith(".json") }
                    ?: error("[USDA-INGEST] no .json entry found inside $url")
                log.info("[USDA-INGEST] streaming entry={} fromUrl={}", entry.name, url)
                streamFromJsonEntry(zip, onProgress, block)
            }
        }
    }

    private suspend fun streamFromJsonEntry(
        zipBody: InputStream,
        onProgress: (Long) -> Unit,
        block: suspend (JsonObject) -> Unit,
    ) {
        var rows = 0L
        var malformed = 0
        jsonFactory.createParser(zipBody).use { parser ->
            // The FDC bulk JSON wrapper is `{"BrandedFoods": [...]}` or
            // `{"FoundationFoods": [...]}` (or, defensively, a bare top-level
            // array). Walk forward until we hit the first START_ARRAY at the
            // wrapper depth — this tolerates either shape without hardcoding
            // the wrapper key.
            advanceToFirstArrayStart(parser)
                ?: error("[USDA-INGEST] no top-level array found in JSON")

            // Now positioned just after the array opens. Iterate objects.
            // Only JSON parse errors are tolerated row-by-row — anything thrown
            // out of `block` (mapper bugs, writer RPC failures) must propagate
            // so the job marks FAILED in usda_sync_state and the next run
            // retries from a clean state.
            while (parser.nextToken() == JsonToken.START_OBJECT) {
                val raw = StringWriter().also { sw ->
                    jsonFactory.createGenerator(sw).use { gen -> gen.copyCurrentStructure(parser) }
                }.toString()
                val obj = try {
                    json.parseToJsonElement(raw) as? JsonObject
                } catch (t: Throwable) {
                    if (malformed < 50) {
                        log.warn("[USDA-INGEST] malformed JSON object skipped (sample): {}", raw.take(200))
                    }
                    malformed++
                    null
                }
                if (obj == null) continue
                block(obj)
                rows++
                if (rows % progressEvery == 0L) onProgress(rows)
            }
        }
        log.info("[USDA-INGEST] stream complete rows={} malformed={}", rows, malformed)
        if (rows > 0) onProgress(rows)
    }

    /**
     * Advances the parser past whatever wrapping it finds (an object with
     * `{ wrapperKey: [...] }`, or a bare top-level array). Returns the
     * START_ARRAY token on success, null if the input is malformed.
     */
    private fun advanceToFirstArrayStart(
        parser: com.fasterxml.jackson.core.JsonParser
    ): JsonToken? {
        var token = parser.nextToken() ?: return null
        // Bare array shape: `[ ... ]`
        if (token == JsonToken.START_ARRAY) return token
        // Object wrapper shape: `{ "BrandedFoods": [ ... ] }`
        if (token != JsonToken.START_OBJECT) return null
        while (true) {
            token = parser.nextToken() ?: return null
            if (token == JsonToken.END_OBJECT) return null
            if (token == JsonToken.FIELD_NAME) {
                val nextValue = parser.nextToken() ?: return null
                if (nextValue == JsonToken.START_ARRAY) return nextValue
                // Skip non-array fields entirely (depths > 0 are walked by skipChildren)
                if (nextValue.isStructStart) parser.skipChildren()
            }
        }
    }
}
