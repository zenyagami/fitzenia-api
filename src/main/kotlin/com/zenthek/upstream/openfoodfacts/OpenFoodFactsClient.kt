package com.zenthek.upstream.openfoodfacts

import com.zenthek.model.FoodItem
import com.zenthek.upstream.openfoodfacts.dto.OpenFoodFactsProductResponse
import com.zenthek.upstream.openfoodfacts.dto.OpenFoodFactsV3SearchResponse
import com.zenthek.mapper.OpenFoodFactsMapper
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
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

    suspend fun search(query: String, page: Int, pageSize: Int): List<FoodItem> {
        log.info("[OFF] search query={} page={} pageSize={}", query, page, pageSize)
        val response = httpClient.get("$searchBaseUrl/search") {
            header(HttpHeaders.UserAgent, userAgent)
            parameter("q", query)
            parameter("page", page + 1) // 1-indexed
            parameter("page_size", pageSize)
            parameter("fields", "code,product_name,brands,serving_size,serving_quantity,image_url,nutriments")
            parameter("sort_by", "unique_scans_n")
        }
        log.debug("[OFF] search query={} status={}", query, response.status)

        if (!response.status.isSuccess()) return emptyList()

        val dto = response.body<OpenFoodFactsV3SearchResponse>()
        val results = dto.hits.mapNotNull { OpenFoodFactsMapper.mapV3Search(it) }
        log.info("[OFF] search query={} returned={} (total={})", query, results.size, dto.count)
        return results
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
}
