package com.zenthek.upstream.openfoodfacts

import com.zenthek.mapper.OpenFoodFactsMapper
import com.zenthek.model.FoodItem
import com.zenthek.model.UpstreamSearchPage
import com.zenthek.upstream.openfoodfacts.dto.OpenFoodFactsProductResponse
import com.zenthek.upstream.openfoodfacts.dto.OpenFoodFactsV3SearchResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory

class OpenFoodFactsClient(private val httpClient: HttpClient) {
    private val log = LoggerFactory.getLogger(OpenFoodFactsClient::class.java)
    private val baseUrl = "https://world.openfoodfacts.org"
    private val searchBaseUrl = "https://search.openfoodfacts.org"
    private val userAgent = "Fitzenio/1.0 (Android/iOS app; contact@fitzenio.com)"

    suspend fun getByBarcode(barcode: String): FoodItem? {
        log.info("[OFF] getByBarcode barcode={}", barcode)
        val response = httpClient.get("$baseUrl/api/v3/product/$barcode") {
            header(HttpHeaders.UserAgent, userAgent)
            parameter("fields", "code,product_name,brands,serving_size,serving_quantity,image_url,nutriments")
        }
        log.debug("[OFF] getByBarcode barcode={} status={}", barcode, response.status)

        if (!response.status.isSuccess()) return null

        val dto = response.body<OpenFoodFactsProductResponse>()
        if (dto.status != "success" || dto.product == null) {
            log.debug("[OFF] getByBarcode barcode={} not found (status={})", barcode, dto.status)
            return null
        }

        return OpenFoodFactsMapper.map(dto.product)
    }

    suspend fun search(query: String, page: Int, pageSize: Int): List<FoodItem> =
        searchPaged(query, page, pageSize, locale = null, country = null).items.map { it.foodItem }

    /**
     * Search with pagination metadata + ResultKind classification. Used by the
     * Smart Search orchestrator; the legacy [search] method delegates to this
     * and discards the extra info for the old FoodService flat-search path.
     *
     * When [locale] is provided, the ISO 639-1 language code is extracted and
     * passed to OFF as `langs=` (biases toward product names in that language).
     *
     * When [country] resolves to a valid countries_tags slug (e.g. `DE` →
     * `en:germany`), this method fires **two parallel OFF calls**:
     *   1. **Filtered**: `q = <query> AND countries_tags:"en:germany"` — returns
     *      only products sold in that country (dominant on page 0).
     *   2. **Unfiltered**: plain `q = <query>` — global fallback so the user
     *      still sees cross-border branded items after the local ones.
     *
     * The two result sets are merged: filtered items first (preserving their
     * OFF order), then unfiltered items de-duplicated by product code, capped
     * at `pageSize`. This mirrors the OFF mobile app's behavior (which shows
     * ~108 local products for `frischkäse` in Germany) without hard-filtering
     * away global context.
     *
     * When no country is provided, a single unfiltered call runs as before.
     */
    internal suspend fun searchPaged(
        query: String,
        page: Int,
        pageSize: Int,
        locale: String?,
        country: String?
    ): UpstreamSearchPage = coroutineScope {
        val countryTag = countryToTag(country)
        val langs = buildLangs(locale, country)

        if (countryTag == null) {
            log.info(
                "[OFF] searchPaged query={} page={} pageSize={} langs={} countryTag=null (single call)",
                query, page, pageSize, langs
            )
            return@coroutineScope fetchOffPage(query, page, pageSize, langs, filterCountryTag = null)
        }

        // Two parallel calls. Network RTT dominates; both finish together.
        val filteredDeferred = async {
            fetchOffPage(query, page, pageSize, langs, filterCountryTag = countryTag)
        }
        val globalDeferred = async {
            fetchOffPage(query, page, pageSize, langs, filterCountryTag = null)
        }
        val filtered = filteredDeferred.await()
        val global = globalDeferred.await()

        // Merge: filtered items keep their order and come first. Global items
        // are appended only if not already in the filtered set (dedup by id).
        val filteredIds = filtered.items.mapTo(mutableSetOf()) { it.foodItem.id }
        val globalDeduped = global.items.filter { it.foodItem.id !in filteredIds }
        val combined = filtered.items + globalDeduped
        val merged = combined.take(pageSize)
        // hasMore: either source has more pages, OR we had to drop items at the cap.
        val hasMore = filtered.hasMore || global.hasMore || combined.size > pageSize

        log.info(
            "[OFF] searchPaged merged query={} page={} countryTag={} filtered={} globalDedup={} merged={} hasMore={}",
            query, page, countryTag, filtered.items.size, globalDeduped.size, merged.size, hasMore
        )
        UpstreamSearchPage(merged, hasMore)
    }

    /**
     * Single OFF search-a-licious request. When `filterCountryTag` is non-null
     * it's embedded in the `q` string using Lucene AND syntax to restrict the
     * result set to products sold in that country.
     */
    private suspend fun fetchOffPage(
        query: String,
        page: Int,
        pageSize: Int,
        langs: String?,
        filterCountryTag: String?
    ): UpstreamSearchPage {
        val effectiveQuery = if (filterCountryTag != null) {
            // Implicit AND via space — explicit `AND` confuses search-a-licious's Luqum parser
            // for multi-word free text and silently produces zero hits.
            "${query.trim()} countries_tags:\"$filterCountryTag\""
        } else {
            query
        }
        val response = httpClient.get("$searchBaseUrl/search") {
            header(HttpHeaders.UserAgent, userAgent)
            parameter("q", effectiveQuery)
            parameter("page", page + 1) // OFF is 1-indexed; caller uses 0-indexed
            parameter("page_size", pageSize)
            parameter(
                "fields",
                "code,product_name,brands,serving_size,serving_quantity,image_url,nutriments,countries_tags,lang,lc"
            )
            parameter("sort_by", "unique_scans_n")
            if (langs != null) parameter("langs", langs)
        }
        if (!response.status.isSuccess()) {
            log.warn("[OFF] fetchOffPage status={} q={}", response.status.value, effectiveQuery)
            return UpstreamSearchPage.EMPTY
        }
        val dto = response.body<OpenFoodFactsV3SearchResponse>()
        val rankedHits = stableRankByLanguage(dto.hits, langs)
        val items = rankedHits.mapNotNull { OpenFoodFactsMapper.mapV3SearchWithKind(it) }
        val offPageOneIndexed = (page + 1).coerceAtLeast(1)
        val hasMore = dto.count > offPageOneIndexed * pageSize
        log.info(
            "[OFF] fetchOffPage filterCountryTag={} returned={} total={} hasMore={}",
            filterCountryTag, items.size, dto.count, hasMore
        )
        return UpstreamSearchPage(items, hasMore)
    }

    suspend fun autocomplete(query: String, limit: Int): List<String> {
        log.info("[OFF] autocomplete query={} limit={}", query, limit)
        val response = httpClient.get("$searchBaseUrl/search") {
            header(HttpHeaders.UserAgent, userAgent)
            parameter("q", query)
            parameter("page", 1)
            parameter("page_size", limit)
            parameter("fields", "product_name,brands")
        }
        log.debug("[OFF] autocomplete query={} status={}", query, response.status)

        if (!response.status.isSuccess()) return emptyList()

        val dto = response.body<OpenFoodFactsV3SearchResponse>()
        val suggestions = dto.hits
            .mapNotNull { it.productName?.trim()?.ifBlank { null } }
            .distinct()
        log.info("[OFF] autocomplete query={} suggestions={}", query, suggestions.size)
        return suggestions
    }

    /**
     * Extracts the ISO 639-1 language code from a BCP 47 locale string.
     * `de-DE` → `de`, `en-DE-u-mu-celsius` → `en`, `es` → `es`.
     * Returns null for invalid or blank input so the caller can omit the param.
     */
    private fun extractLangs(locale: String?): String? {
        val first = locale?.split('-', '_')?.firstOrNull()?.trim()?.lowercase() ?: return null
        return first.takeIf { it.length == 2 && it.all(Char::isLetter) }
    }

    /**
     * Build the OFF `langs` parameter as a comma-separated list combining the
     * country's primary language, the user's locale language, and English as
     * a final fallback. This widens the language fields OFF searches so a
     * German user typing "olive oil" can still match a product whose name is
     * "Olivenöl" via the German fields. Order matters — earlier langs influence
     * scoring more.
     */
    private fun buildLangs(locale: String?, country: String?): String? {
        val ordered = linkedSetOf<String>()
        countryPrimaryLang(country)?.let { ordered.add(it) }
        extractLangs(locale)?.let { ordered.add(it) }
        ordered.add("en")
        return ordered.joinToString(",").takeIf { it.isNotBlank() }
    }

    /**
     * Map an ISO 3166-1 alpha-2 country code to its primary ISO 639-1 language.
     * Hand-curated for the markets we support — covers Western Europe, North/South
     * America, and a few key Asian markets. Returns null when unknown so the caller
     * falls back to the locale-derived language only.
     */
    /**
     * Stable-rank OFF hits so products native to the user's preferred languages
     * come first. Uses each hit's primary `lang` (or `lc` fallback) compared
     * against the comma-separated `langsCsv` we sent on the request. Hits whose
     * primary language matches earlier-listed langs rank higher; unknown-language
     * hits go last. Within the same bucket, OFF's relevance order is preserved.
     */
    private fun stableRankByLanguage(
        hits: List<com.zenthek.upstream.openfoodfacts.dto.OpenFoodFactsV3SearchProduct>,
        langsCsv: String?
    ): List<com.zenthek.upstream.openfoodfacts.dto.OpenFoodFactsV3SearchProduct> {
        val priorities = langsCsv
            ?.split(',')
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
            ?: return hits
        if (priorities.isEmpty()) return hits
        val unknownRank = priorities.size
        return hits.withIndex()
            .sortedBy { (idx, hit) ->
                val hitLang = (hit.lang ?: hit.lc)?.trim()?.lowercase()
                val rank = if (hitLang == null) unknownRank
                else priorities.indexOf(hitLang).let { if (it == -1) unknownRank else it }
                rank.toLong() * 1_000_000L + idx.toLong()
            }
            .map { it.value }
    }

    private fun countryPrimaryLang(country: String?): String? {
        val iso = country?.trim()?.uppercase()?.takeIf { it.length == 2 && it.all(Char::isLetter) } ?: return null
        return COUNTRY_PRIMARY_LANG[iso]
    }

    /**
     * Converts an ISO 3166-1 alpha-2 code into OFF's countries_tags slug format.
     * `DE` → `en:germany`, `US` → `en:united-states`, `MX` → `en:mexico`.
     * Returns null for `null`, `GLOBAL`, or any code Java's Locale doesn't recognize.
     */
    private fun countryToTag(country: String?): String? {
        val iso = country?.trim()?.uppercase()?.takeIf { it.length == 2 && it.all(Char::isLetter) } ?: return null
        if (iso == "GLOBAL") return null
        val englishName = java.util.Locale.Builder().setRegion(iso).build()
            .getDisplayCountry(java.util.Locale.ENGLISH)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return "en:" + englishName.lowercase().replace(Regex("\\s+"), "-")
    }

    private companion object {
        private val COUNTRY_PRIMARY_LANG: Map<String, String> = mapOf(
            // Europe
            "DE" to "de", "AT" to "de", "CH" to "de",
            "FR" to "fr", "BE" to "fr", "LU" to "fr",
            "IT" to "it",
            "ES" to "es",
            "PT" to "pt",
            "NL" to "nl",
            "PL" to "pl",
            "SE" to "sv", "NO" to "no", "DK" to "da", "FI" to "fi",
            "GR" to "el",
            "CZ" to "cs", "SK" to "sk", "HU" to "hu", "RO" to "ro", "BG" to "bg",
            "RU" to "ru", "UA" to "uk", "TR" to "tr",
            "GB" to "en", "IE" to "en",
            // Americas
            "US" to "en", "CA" to "en",
            "MX" to "es", "AR" to "es", "CL" to "es", "CO" to "es", "PE" to "es", "VE" to "es",
            "BR" to "pt",
            // Asia / Oceania
            "JP" to "ja", "KR" to "ko", "CN" to "zh", "TW" to "zh", "HK" to "zh",
            "IN" to "en", "ID" to "id", "MY" to "ms", "TH" to "th", "VN" to "vi", "PH" to "en",
            "AU" to "en", "NZ" to "en",
            // Middle East / Africa
            "SA" to "ar", "AE" to "ar", "EG" to "ar", "IL" to "he",
            "ZA" to "en"
        )
    }
}
