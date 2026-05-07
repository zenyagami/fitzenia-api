package com.zenthek.upstream.usda

import com.zenthek.config.SupabaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

/**
 * Read-side access to the locally mirrored USDA FoodData Central catalog
 * (`public.usda_food`). Service-role only — bypasses RLS, never invoke from a
 * user-scoped path. Constructed only when `config.usdaMirror.readEnabled` is on
 * (production by default).
 *
 * Two access patterns:
 *  - [findByBarcode]: gtin_upc lookup, used by `FoodService.getByBarcode` after
 *    the OFF mirror miss and before the live OFF/USDA fallback.
 *  - [candidatesFor]: pg_trgm-ranked recall, called from `SmartSearchOrchestrator`
 *    in the mirror phase. **No country filter** — FDC is US-only data.
 */
class UsdaMirrorGateway(
    private val httpClient: HttpClient,
    config: SupabaseConfig,
    private val serviceRoleKey: String,
) {
    private val log = LoggerFactory.getLogger(UsdaMirrorGateway::class.java)
    private val baseUrl = config.normalizedUrl

    suspend fun findByBarcode(code: String): Result<UsdaMirrorProduct?> = runCatching {
        val response = httpClient.get("$baseUrl/rest/v1/usda_food") {
            applyServiceRoleAuth()
            parameter("gtin_upc", "eq.$code")
            parameter("deleted_at", "is.null")
            parameter("select", SELECT_COLUMNS)
            parameter("limit", 1)
        }
        if (!response.status.isSuccess()) {
            log.warn("[USDA-MIRROR] findByBarcode failed status={} code={}", response.status.value, code)
            throw IllegalStateException("usda_food lookup failed with ${response.status.value}")
        }
        response.body<List<UsdaMirrorProduct>>().firstOrNull()
    }

    suspend fun candidatesFor(
        query: String,
        limit: Int = 25,
    ): Result<List<UsdaMirrorProduct>> = runCatching {
        val response = httpClient.post("$baseUrl/rest/v1/rpc/usda_search_candidates") {
            applyServiceRoleAuth()
            contentType(ContentType.Application.Json)
            setBody(SearchEnvelope(p_query = query, p_limit = limit))
        }
        if (!response.status.isSuccess()) {
            log.warn(
                "[USDA-MIRROR] candidatesFor failed status={} query={}",
                response.status.value, query,
            )
            throw IllegalStateException("usda_search_candidates RPC failed with ${response.status.value}")
        }
        response.body<List<UsdaMirrorProduct>>()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyServiceRoleAuth() {
        header("apikey", serviceRoleKey)
        bearerAuth(serviceRoleKey)
        header(HttpHeaders.Accept, "application/json")
    }

    companion object {
        // Keep this list in sync with `UsdaMirrorProduct`.
        private const val SELECT_COLUMNS =
            "fdc_id,data_type,description,brand_owner,brand_name," +
                "branded_food_category,gtin_upc,serving_size,serving_size_unit," +
                "household_serving_full_text," +
                "energy_kcal_100g,protein_100g,carbs_100g,sugars_100g,fat_100g," +
                "saturated_fat_100g,fiber_100g,sodium_100g,nutriments"
    }
}

/**
 * Slim mirror row exposed to the API. Only the fields the mapper needs to
 * produce a `FoodItem` are present — long-tail nutrients still live in
 * [nutriments] for callers that want them.
 */
@Serializable
data class UsdaMirrorProduct(
    @SerialName("fdc_id") val fdcId: Long,
    @SerialName("data_type") val dataType: String,
    val description: String,
    @SerialName("brand_owner") val brandOwner: String? = null,
    @SerialName("brand_name") val brandName: String? = null,
    @SerialName("branded_food_category") val brandedFoodCategory: String? = null,
    @SerialName("gtin_upc") val gtinUpc: String? = null,
    @SerialName("serving_size") val servingSize: Float? = null,
    @SerialName("serving_size_unit") val servingSizeUnit: String? = null,
    @SerialName("household_serving_full_text") val householdServingFullText: String? = null,
    @SerialName("energy_kcal_100g") val energyKcal100g: Float? = null,
    @SerialName("protein_100g") val protein100g: Float? = null,
    @SerialName("carbs_100g") val carbs100g: Float? = null,
    @SerialName("sugars_100g") val sugars100g: Float? = null,
    @SerialName("fat_100g") val fat100g: Float? = null,
    @SerialName("saturated_fat_100g") val saturatedFat100g: Float? = null,
    @SerialName("fiber_100g") val fiber100g: Float? = null,
    @SerialName("sodium_100g") val sodium100g: Float? = null,
    val nutriments: JsonObject? = null,
)

@Serializable
private data class SearchEnvelope(
    val p_query: String,
    val p_limit: Int,
)
