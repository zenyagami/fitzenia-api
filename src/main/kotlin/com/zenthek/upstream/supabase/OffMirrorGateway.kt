package com.zenthek.upstream.supabase

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
 * Read-side access to the locally mirrored Open Food Facts catalog
 * (`public.off_food`). Service-role only — bypasses RLS, never invoke from a
 * user-scoped path. Constructed only when `config.offMirror.readEnabled` is on
 * (production by default).
 *
 * Two access patterns:
 *  - [findByBarcode]: PK lookup, used by `FoodService.getByBarcode` before the
 *    live OFF/USDA fallback.
 *  - [candidatesFor]: pg_trgm-ranked recall, called from
 *    `SmartSearchOrchestrator` in parallel with the live OFF + USDA fan-out.
 */
class OffMirrorGateway(
    private val httpClient: HttpClient,
    config: SupabaseConfig,
    private val serviceRoleKey: String,
) {
    private val log = LoggerFactory.getLogger(OffMirrorGateway::class.java)
    private val baseUrl = config.normalizedUrl

    suspend fun findByBarcode(code: String): Result<OffMirrorProduct?> = runCatching {
        val response = httpClient.get("$baseUrl/rest/v1/off_food") {
            applyServiceRoleAuth()
            parameter("code", "eq.$code")
            parameter("deleted_at", "is.null")
            parameter("select", SELECT_COLUMNS)
            parameter("limit", 1)
        }
        if (!response.status.isSuccess()) {
            log.warn("[OFF-MIRROR] findByBarcode failed status={} code={}", response.status.value, code)
            throw IllegalStateException("off_food lookup failed with ${response.status.value}")
        }
        response.body<List<OffMirrorProduct>>().firstOrNull()
    }

    suspend fun candidatesFor(
        query: String,
        country: String? = null,
        limit: Int = 25,
    ): Result<List<OffMirrorProduct>> = runCatching {
        val countryTag = country?.takeIf { it.isNotBlank() && it != "GLOBAL" }?.let { "en:${it.lowercase()}" }
        val response = httpClient.post("$baseUrl/rest/v1/rpc/off_search_candidates") {
            applyServiceRoleAuth()
            contentType(ContentType.Application.Json)
            setBody(SearchEnvelope(p_query = query, p_country = countryTag, p_limit = limit))
        }
        if (!response.status.isSuccess()) {
            log.warn(
                "[OFF-MIRROR] candidatesFor failed status={} query={} country={}",
                response.status.value, query, countryTag,
            )
            throw IllegalStateException("off_search_candidates RPC failed with ${response.status.value}")
        }
        response.body<List<OffMirrorProduct>>()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyServiceRoleAuth() {
        header("apikey", serviceRoleKey)
        bearerAuth(serviceRoleKey)
        header(HttpHeaders.Accept, "application/json")
    }

    companion object {
        // Keep this list in sync with `OffMirrorProduct`.
        private const val SELECT_COLUMNS =
            "code,product_name,brands,primary_brand,countries_tags,lang," +
                "serving_size,serving_quantity,image_url," +
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
data class OffMirrorProduct(
    val code: String,
    @SerialName("product_name") val productName: String? = null,
    val brands: List<String>? = null,
    @SerialName("primary_brand") val primaryBrand: String? = null,
    @SerialName("countries_tags") val countriesTags: List<String>? = null,
    val lang: String? = null,
    @SerialName("serving_size") val servingSize: String? = null,
    @SerialName("serving_quantity") val servingQuantity: Float? = null,
    @SerialName("image_url") val imageUrl: String? = null,
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
    val p_country: String? = null,
    val p_limit: Int,
)
