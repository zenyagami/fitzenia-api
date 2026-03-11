package com.zenthek.upstream.fatsecret

import com.zenthek.mapper.FatSecretMapper
import com.zenthek.model.FoodItem
import com.zenthek.upstream.fatsecret.dto.FatSecretAutocompleteResponse
import com.zenthek.upstream.fatsecret.dto.FatSecretBarcodeResponse
import com.zenthek.upstream.fatsecret.dto.FatSecretFoodDetailResponse
import com.zenthek.upstream.fatsecret.dto.FatSecretV5SearchResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import org.slf4j.LoggerFactory

class FatSecretClient(
    private val httpClient: HttpClient,
    private val tokenManager: FatSecretTokenManager
) {
    private val log = LoggerFactory.getLogger(FatSecretClient::class.java)
    private val serverApi = "https://platform.fatsecret.com/rest/server.api"
    private val searchV5Url = "https://platform.fatsecret.com/rest/foods/search/v5"
    private val autocompleteV2Url = "https://platform.fatsecret.com/rest/food/autocomplete/v2"

    suspend fun getByBarcode(barcode: String): FoodItem? {
        log.info("[FS] getByBarcode barcode={}", barcode)
        val token = tokenManager.getToken()

        // 1. Resolve barcode → food_id
        val barcodeResponse = httpClient.post(serverApi) {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(FormDataContent(Parameters.build {
                append("method", "food.find_id_for_barcode")
                append("barcode", barcode)
                append("format", "json")
            }))
        }
        log.debug("[FS] food.find_id_for_barcode barcode={} status={}", barcode, barcodeResponse.status)
        if (!barcodeResponse.status.isSuccess()) return null

        val barcodeDto = barcodeResponse.body<FatSecretBarcodeResponse>()
        val foodId = barcodeDto.food_id?.value ?: run {
            log.debug("[FS] getByBarcode barcode={} not found in FatSecret", barcode)
            return null
        }

        // 2. Fetch full detail via food.get.v4
        log.debug("[FS] food.get.v4 foodId={}", foodId)
        val detailResponse = httpClient.post(serverApi) {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(FormDataContent(Parameters.build {
                append("method", "food.get.v4")
                append("food_id", foodId)
                append("format", "json")
            }))
        }
        log.debug("[FS] food.get.v4 foodId={} status={}", foodId, detailResponse.status)
        if (!detailResponse.status.isSuccess()) return null

        val detailDto = detailResponse.body<FatSecretFoodDetailResponse>()
        return detailDto.food?.let { FatSecretMapper.mapDetail(it, barcode) }
    }

    suspend fun search(query: String, page: Int, pageSize: Int): List<FoodItem> {
        log.info("[FS] search query={} page={} pageSize={}", query, page, pageSize)
        val token = tokenManager.getToken()

        val response = httpClient.get(searchV5Url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("search_expression", query)
            parameter("page_number", page)
            parameter("max_results", pageSize)
            parameter("format", "json")
        }
        log.debug("[FS] search query={} status={}", query, response.status)
        if (!response.status.isSuccess()) return emptyList()

        val dto = response.body<FatSecretV5SearchResponse>()
        val results = dto.foodsSearch.results?.food
            ?.mapNotNull { FatSecretMapper.mapDetail(it) }
            ?: emptyList()
        log.info("[FS] search query={} returned={} (total={})", query, results.size, dto.foodsSearch.totalResults)
        return results
    }

    suspend fun autocomplete(query: String, limit: Int): List<String> {
        log.info("[FS] autocomplete query={} limit={}", query, limit)
        val token = tokenManager.getToken()

        val response = httpClient.get(autocompleteV2Url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("expression", query)
            parameter("max_results", limit)
            parameter("format", "json")
        }
        log.debug("[FS] autocomplete query={} status={}", query, response.status)
        if (!response.status.isSuccess()) return emptyList()

        val dto = response.body<FatSecretAutocompleteResponse>()
        val suggestions = dto.suggestions?.suggestion ?: emptyList()
        log.info("[FS] autocomplete query={} suggestions={}", query, suggestions.size)
        return suggestions
    }
}
