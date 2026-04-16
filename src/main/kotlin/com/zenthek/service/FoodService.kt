package com.zenthek.service

import com.zenthek.model.FoodItem
import com.zenthek.upstream.fatsecret.FatSecretClient
import com.zenthek.upstream.openfoodfacts.OpenFoodFactsClient
import com.zenthek.upstream.usda.UsdaClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class FoodService(
    private val offClient: OpenFoodFactsClient,
    private val fsClient: FatSecretClient,
    private val usdaClient: UsdaClient
) {
    suspend fun getByBarcode(barcode: String): FoodItem? {
        var lastException: Exception? = null

        // 1. Try Open Food Facts
        try {
            val offResult = offClient.getByBarcode(barcode)
            if (offResult != null) return offResult
        } catch (e: Exception) {
            lastException = e
        }

        // 2. Try USDA
        try {
            val usdaResult = usdaClient.getByBarcode(barcode)
            if (usdaResult != null) return usdaResult
        } catch (e: Exception) {
            lastException = e
        }

        // 3. Try FatSecret
        try {
            val fsResult = fsClient.getByBarcode(barcode)
            if (fsResult != null) return fsResult
        } catch (e: Exception) {
            lastException = e
        }

        // If all threw exceptions
        if (lastException != null) {
            throw UpstreamFailureException("All upstream APIs failed during barcode lookup: ${lastException.message}")
        }

        // All returned null successfully
        return null
    }

    suspend fun autocomplete(query: String, limit: Int): List<String> = coroutineScope {
        val offDeferred = async { runCatching { offClient.autocomplete(query, limit) }.getOrDefault(emptyList()) }
        val fsDeferred = async { runCatching { fsClient.autocomplete(query, limit) }.getOrDefault(emptyList()) }

        (offDeferred.await() + fsDeferred.await())
            .distinct()
            .take(limit)
    }

    // search() + mergeAndDeduplicate() were removed — the Smart Search flow in
    // SmartSearchOrchestrator replaces them. Barcode + autocomplete paths are
    // unchanged; FatSecret is still used for autocomplete (so its client stays
    // wired) but its search endpoint is no longer invoked anywhere.
}
