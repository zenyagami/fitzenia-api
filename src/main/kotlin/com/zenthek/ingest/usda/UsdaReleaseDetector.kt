package com.zenthek.ingest.usda

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import org.slf4j.LoggerFactory
import java.time.LocalDate

/**
 * Resolves the latest available FDC bulk-download URLs by scraping the
 * `/download-datasets/` index page. FDC's public API has no endpoint that
 * exposes bulk-release metadata, so this is the cheapest reliable signal.
 *
 * Failure mode is benign: if the regex matches nothing (page redesign), the
 * job returns FAILED and the next monthly cron retries. A one-line regex fix
 * unblocks. We don't try to be cute here — bi-annual cadence means a missed
 * cycle is at most ~6 months of staleness, the live FDC API still serves
 * mirror-miss reads.
 */
class UsdaReleaseDetector(
    private val httpClient: HttpClient,
    private val indexUrl: String = DEFAULT_INDEX_URL,
) {
    private val log = LoggerFactory.getLogger(UsdaReleaseDetector::class.java)

    /**
     * Resolves the latest Branded + Foundation download URLs. Both must be
     * present — if either is missing the manifest is null and the caller
     * should mark the run FAILED.
     */
    suspend fun fetchManifest(): UsdaReleaseManifest? {
        val response = httpClient.get(indexUrl)
        if (!response.status.isSuccess()) {
            log.warn("[USDA-INGEST] release index fetch failed status={} url={}", response.status.value, indexUrl)
            return null
        }
        val html = response.bodyAsText()
        val branded = bestMatch(html, BRANDED_PATTERN) ?: run {
            log.warn("[USDA-INGEST] no Branded zip URL matched in index")
            return null
        }
        val foundation = bestMatch(html, FOUNDATION_PATTERN) ?: run {
            log.warn("[USDA-INGEST] no Foundation zip URL matched in index")
            return null
        }
        return UsdaReleaseManifest(
            brandedUrl = branded.url,
            brandedDate = branded.date,
            foundationUrl = foundation.url,
            foundationDate = foundation.date,
        )
    }

    /**
     * Scans every match in the page, parses the date, and returns the most
     * recent. The FDC index lists historical releases too; descending sort
     * by date isolates the current one.
     */
    private fun bestMatch(html: String, pattern: Regex): UsdaReleaseLink? {
        val candidates = pattern.findAll(html).mapNotNull { match ->
            val (datePart) = match.destructured
            runCatching { LocalDate.parse(datePart) }.getOrNull()?.let { date ->
                UsdaReleaseLink(url = absolutize(match.value), date = date)
            }
        }.toList()
        return candidates.maxByOrNull { it.date }
    }

    private fun absolutize(href: String): String =
        if (href.startsWith("http")) href else "https://fdc.nal.usda.gov/$href".replace("//", "/").replace(":/", "://")

    companion object {
        const val DEFAULT_INDEX_URL = "https://fdc.nal.usda.gov/download-datasets/"

        // The page link href is something like
        // `https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_branded_food_json_2026-04-22.zip`
        // (with or without the leading host). Both shapes are matched.
        private val BRANDED_PATTERN = Regex(
            """(?:https?://[^"\s]*?)?/fdc-datasets/FoodData_Central_branded_food_json_(\d{4}-\d{2}-\d{2})\.zip"""
        )
        private val FOUNDATION_PATTERN = Regex(
            """(?:https?://[^"\s]*?)?/fdc-datasets/FoodData_Central_foundation_food_json_(\d{4}-\d{2}-\d{2})\.zip"""
        )
    }
}

data class UsdaReleaseManifest(
    val brandedUrl: String,
    val brandedDate: LocalDate,
    val foundationUrl: String,
    val foundationDate: LocalDate,
) {
    /** Most recent of the two release dates — used as the checkpoint key. */
    val maxDate: LocalDate = if (brandedDate.isAfter(foundationDate)) brandedDate else foundationDate
}

private data class UsdaReleaseLink(val url: String, val date: LocalDate)
