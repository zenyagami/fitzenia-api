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

    suspend fun search(query: String, page: Int, pageSize: Int): List<FoodItem> = coroutineScope {
        val offDeferred = async { runCatching { offClient.search(query, page, pageSize) }.getOrNull() }
        val fsDeferred = async { runCatching { fsClient.search(query, page, pageSize) }.getOrNull() }
        val usdaDeferred = async { runCatching { usdaClient.search(query, page, pageSize) }.getOrNull() }

        val offResults = offDeferred.await()
        val fsResults = fsDeferred.await()
        val usdaResults = usdaDeferred.await()

        if (offResults == null && fsResults == null && usdaResults == null) {
            throw UpstreamFailureException("All upstream APIs failed during search.")
        }

        mergeAndDeduplicate(
            offResults ?: emptyList(),
            fsResults ?: emptyList(),
            usdaResults ?: emptyList()
        )
    }

    private fun mergeAndDeduplicate(
        off: List<FoodItem>,
        fs: List<FoodItem>,
        usda: List<FoodItem>
    ): List<FoodItem> {
        val finalResults = mutableListOf<FoodItem>()
        val seenBarcodes = mutableSetOf<String>()
        val seenNameBrands = mutableSetOf<Pair<String, String?>>()

        // Interleave strategy variables
        val maxLen = maxOf(off.size, fs.size, usda.size)
        
        for (i in 0 until maxLen) {
            val candidates = listOfNotNull(
                off.getOrNull(i),
                fs.getOrNull(i),
                usda.getOrNull(i)
            )

            for (item in candidates) {
                // Deduplicate by Barcode
                if (item.barcode != null && seenBarcodes.contains(item.barcode)) continue
                
                // Deduplicate by normalized Name + Brand pairing
                val normalizedName = item.name.lowercase().trim()
                val normalizedBrand = item.brand?.lowercase()?.trim()
                val nameBrandPair = Pair(normalizedName, normalizedBrand)

                if (seenNameBrands.contains(nameBrandPair)) continue

                // It's a unique fresh item, add to pool
                if (item.barcode != null) seenBarcodes.add(item.barcode)
                seenNameBrands.add(nameBrandPair)
                finalResults.add(item)
            }
        }

        return finalResults
    }
}
