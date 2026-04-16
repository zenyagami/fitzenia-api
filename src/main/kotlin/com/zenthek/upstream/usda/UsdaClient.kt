package com.zenthek.upstream.usda

import com.zenthek.mapper.UsdaMapper
import com.zenthek.model.FoodItem
import com.zenthek.model.UpstreamSearchPage
import com.zenthek.upstream.usda.dto.UsdaSearchResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*

class UsdaClient(
    private val httpClient: HttpClient,
    private val apiKey: String
) {
    private val baseUrl = "https://api.nal.usda.gov/fdc/v1"

    suspend fun getByBarcode(barcode: String): FoodItem? {
        val response = httpClient.get("$baseUrl/foods/search") {
            parameter("api_key", apiKey)
            parameter("query", barcode)
            parameter("dataType", "Branded")
        }

        if (!response.status.isSuccess()) return null

        val dto = response.body<UsdaSearchResponse>()
        val exactMatch = dto.foods.firstOrNull { it.gtinUpc == barcode } ?: return null

        return UsdaMapper.mapSearchItem(exactMatch)
    }

    suspend fun search(query: String, page: Int, pageSize: Int): List<FoodItem> =
        searchPaged(query, page, pageSize, locale = null, country = null).items.map { it.foodItem }

    /**
     * Search with pagination metadata + ResultKind classification from USDA's
     * `dataType`. Foundation/SR Legacy/Survey (FNDDS) map to GENERIC; Branded
     * and anything else map to BRANDED. `hasMore` is derived from the
     * response's `currentPage`/`totalPages` (both 1-indexed).
     *
     * [locale] and [country] are accepted to keep the [UpstreamSearchFn]
     * signature uniform with OFF, but USDA is US-English only and ignores them.
     */
    @Suppress("UNUSED_PARAMETER")
    internal suspend fun searchPaged(
        query: String,
        page: Int,
        pageSize: Int,
        locale: String?,
        country: String?
    ): UpstreamSearchPage {
        val response = httpClient.get("$baseUrl/foods/search") {
            parameter("api_key", apiKey)
            parameter("query", query)
            parameter("pageNumber", page + 1) // USDA is 1-indexed; caller uses 0-indexed
            parameter("pageSize", pageSize)
            parameter("dataType", "Branded,Foundation,SR Legacy")
        }

        if (!response.status.isSuccess()) return UpstreamSearchPage.EMPTY

        val dto = response.body<UsdaSearchResponse>()
        val items = dto.foods.mapNotNull { UsdaMapper.mapSearchItemWithKind(it) }
        val hasMore = dto.currentPage < dto.totalPages
        return UpstreamSearchPage(items, hasMore)
    }
}
