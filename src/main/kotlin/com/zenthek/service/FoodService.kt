package com.zenthek.service

import com.zenthek.mapper.OpenFoodFactsMapper
import com.zenthek.mapper.UsdaMapper
import com.zenthek.model.FoodItem
import com.zenthek.upstream.openfoodfacts.OpenFoodFactsClient
import com.zenthek.upstream.supabase.OffMirrorGateway
import com.zenthek.upstream.usda.UsdaClient
import com.zenthek.upstream.usda.UsdaMirrorGateway
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory

class FoodService(
    private val offClient: OpenFoodFactsClient,
    private val usdaClient: UsdaClient,
    /**
     * Local OFF mirror gateway — non-null in production (when
     * `OFF_MIRROR_READ_ENABLED=true`), null in development. When non-null, the
     * barcode flow consults the mirror first and falls through to the live OFF
     * + USDA pair only on a miss. Null preserves the legacy behavior byte-for-byte.
     */
    private val offMirror: OffMirrorGateway? = null,
    /**
     * Local USDA mirror gateway — non-null in production (when
     * `USDA_MIRROR_READ_ENABLED=true`), null in development. Consulted after
     * the OFF mirror miss and before the live OFF/USDA fallback. US-only data
     * (no country filter on `candidatesFor`).
     */
    private val usdaMirror: UsdaMirrorGateway? = null,
) {
    private val log = LoggerFactory.getLogger(FoodService::class.java)

    suspend fun getByBarcode(
        barcode: String,
        country: String? = null,
        ipCountry: String? = null,
    ): FoodItem? {
        // Mirror first (OFF then USDA). A hit avoids any live HTTP call; OFF
        // latency on non-US barcodes and FDC API rate limits were the original
        // motivations.
        if (offMirror != null) {
            val mirrorHit = offMirror.findByBarcode(barcode)
                .onFailure { log.warn("[FOOD] OFF mirror barcode lookup failed: {}", it.message) }
                .getOrNull()
                ?.let { OpenFoodFactsMapper.mapMirrorRow(it) }
            if (mirrorHit != null) {
                log.debug("[FOOD] off_mirror_hit barcode={}", barcode)
                return mirrorHit
            }
        }
        if (usdaMirror != null) {
            val mirrorHit = usdaMirror.findByBarcode(barcode)
                .onFailure { log.warn("[FOOD] USDA mirror barcode lookup failed: {}", it.message) }
                .getOrNull()
                ?.let { UsdaMapper.mapMirrorRow(it) }
            if (mirrorHit != null) {
                log.debug("[FOOD] usda_mirror_hit barcode={}", barcode)
                return mirrorHit
            }
        }

        val usPreferred = isUsPreferred(country, ipCountry)
        var lastException: Exception? = null

        suspend fun tryOff(): FoodItem? = try {
            offClient.getByBarcode(barcode)
        } catch (e: Exception) {
            lastException = e
            null
        }

        suspend fun tryUsda(): FoodItem? = try {
            usdaClient.getByBarcode(barcode)
        } catch (e: Exception) {
            lastException = e
            null
        }

        val result = if (usPreferred) {
            tryUsda() ?: tryOff()
        } else {
            tryOff() ?: tryUsda()
        }
        if (result != null) return result

        if (lastException != null) {
            throw UpstreamFailureException("All upstream APIs failed during barcode lookup: ${lastException.message}")
        }
        return null
    }

    private fun isUsPreferred(country: String?, ipCountry: String?): Boolean {
        val resolved = sequenceOf(country, ipCountry)
            .mapNotNull { it?.trim()?.takeIf(String::isNotBlank)?.uppercase() }
            .firstOrNull { it.length == 2 && it.all(Char::isLetter) && it !in CDN_UNKNOWN_SENTINELS }
        return resolved == "US"
    }

    suspend fun autocomplete(query: String, limit: Int): List<String> = coroutineScope {
        val offDeferred = async { runCatching { offClient.autocomplete(query, limit) }.getOrDefault(emptyList()) }

        (offDeferred.await())
            .distinct()
            .take(limit)
    }

    // search() + mergeAndDeduplicate() were removed — the Smart Search flow in
    // SmartSearchOrchestrator replaces them. Barcode + autocomplete paths are
    // unchanged; FatSecret is still used for autocomplete (so its client stays
    // wired) but its search endpoint is no longer invoked anywhere.

    private companion object {
        private val CDN_UNKNOWN_SENTINELS = setOf("XX", "T1", "ZZ")
    }
}
