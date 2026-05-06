package com.zenthek.ingest

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import org.slf4j.LoggerFactory

/**
 * Parses OFF's delta index file at `https://static.openfoodfacts.org/data/delta/index.txt`.
 *
 * The index is a whitespace-separated list of filenames. Filenames look like:
 *
 *     openfoodfacts_products_1777968550_1778046948.json.gz
 *
 * Each filename embeds two UNIX timestamps; the trailing one is the upper bound
 * of the products' `last_modified_t` window the file covers. Files we want to
 * fetch are those whose upper bound is **strictly greater** than our last
 * processed checkpoint.
 *
 * OFF retains roughly the last 14 days of deltas. If our checkpoint is older
 * than that window the caller must trigger a full reconcile — the gap is
 * surfaced via [DeltaWindowGap] in the consuming job, not here.
 */
class OffDeltaIndexParser(
    private val httpClient: HttpClient,
    private val indexUrl: String = DEFAULT_INDEX_URL,
) {
    private val log = LoggerFactory.getLogger(OffDeltaIndexParser::class.java)

    suspend fun fetchIndex(): List<DeltaFile> {
        val response = httpClient.get(indexUrl)
        if (!response.status.isSuccess()) {
            error("[OFF-INGEST] delta index fetch failed status=${response.status.value}")
        }
        val body = response.bodyAsText()
        return parse(body).also {
            log.info("[OFF-INGEST] delta index parsed entries={} sample={}",
                it.size,
                it.take(3).joinToString { f -> f.fileName })
        }
    }

    /**
     * Parses raw index text into [DeltaFile] entries, sorted ascending by
     * upper-bound timestamp. Entries that don't match the expected pattern are
     * silently dropped — never let a stray comment break the run.
     */
    fun parse(indexBody: String): List<DeltaFile> {
        return indexBody
            .splitToSequence(' ', '\t', '\n', '\r')
            .map(String::trim)
            .filter(String::isNotBlank)
            .mapNotNull { token ->
                val match = FILE_PATTERN.matchEntire(token) ?: return@mapNotNull null
                val from = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val to = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                DeltaFile(fileName = token, fromTs = from, toTs = to)
            }
            .toList()
            .sortedBy { it.toTs }
    }

    /**
     * Filters [files] to those whose upper bound is greater than [checkpointT].
     * Returns the entries in ascending order so the consumer can apply them
     * deterministically.
     */
    fun filterNew(files: List<DeltaFile>, checkpointT: Long): List<DeltaFile> {
        return files.filter { it.toTs > checkpointT }.sortedBy { it.toTs }
    }

    /**
     * Builds the absolute URL for fetching a delta file. Same host/dir as the
     * index file.
     */
    fun urlFor(file: DeltaFile, baseUrl: String = DEFAULT_DELTA_DIR): String =
        "$baseUrl/${file.fileName}"

    companion object {
        const val DEFAULT_INDEX_URL = "https://static.openfoodfacts.org/data/delta/index.txt"
        const val DEFAULT_DELTA_DIR = "https://static.openfoodfacts.org/data/delta"

        // openfoodfacts_products_<from>_<to>.json.gz
        private val FILE_PATTERN = Regex("""openfoodfacts_products_(\d+)_(\d+)\.json\.gz""")
    }
}

/**
 * One entry in OFF's delta index. [toTs] is the inclusive upper bound of the
 * `last_modified_t` window covered by this file; persisted as the new
 * checkpoint after the file is successfully ingested.
 */
data class DeltaFile(
    val fileName: String,
    val fromTs: Long,
    val toTs: Long,
)
