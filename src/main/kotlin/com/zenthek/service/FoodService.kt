package com.zenthek.service

import com.zenthek.model.FoodItem
import com.zenthek.upstream.openfoodfacts.OpenFoodFactsClient
import com.zenthek.upstream.usda.UsdaClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class FoodService(
    private val offClient: OpenFoodFactsClient,
    private val usdaClient: UsdaClient
) {
    suspend fun getByBarcode(
        barcode: String,
        country: String? = null,
        ipCountry: String? = null,
    ): FoodItem? {
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
