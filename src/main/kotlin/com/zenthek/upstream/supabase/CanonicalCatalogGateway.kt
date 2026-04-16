package com.zenthek.upstream.supabase

import com.zenthek.config.SupabaseConfig
import com.zenthek.model.CanonicalEquivalentCandidate
import com.zenthek.model.CanonicalFoodEntity
import com.zenthek.model.CanonicalQueryMapRow
import com.zenthek.model.InsertCanonicalFoodsPayload
import com.zenthek.model.InsertCanonicalFoodsResult
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
import org.slf4j.LoggerFactory

/**
 * Shared-catalog read + write for Smart Food Search. Uses the Supabase
 * service-role key, which bypasses RLS — DO NOT use this client on any
 * user-scoped request path. It is only for the backend-owned canonical
 * food catalog.
 */
interface CanonicalCatalogGateway {
    /** Read all query-map rows for a normalized (query, locale, country). Empty on miss. */
    suspend fun lookupQueryMappings(
        normalizedQuery: String,
        locale: String,
        country: String
    ): Result<List<CanonicalQueryMapRow>>

    /** Batch-read canonical foods with embedded servings and terms. Order of ids is not preserved in output. */
    suspend fun readCanonicals(ids: List<String>): Result<List<CanonicalFoodEntity>>

    /** Call the `insert_canonical_foods` RPC. See db/migrations/001_canonical_food_catalog.sql. */
    suspend fun insertCanonicalFoods(payload: InsertCanonicalFoodsPayload): Result<InsertCanonicalFoodsResult>

    /**
     * Find up to `limit` existing canonical foods whose English primary term is
     * similar to the given english-like name. Used only for cross-locale linking
     * candidate generation in the generate LLM call. Uses PostgREST `ilike` in
     * v1 (no pg_trgm operator access via REST); upgrade to a trigram RPC later
     * if recall becomes the bottleneck.
     */
    suspend fun findEquivalentCanonicalCandidates(
        englishLikeName: String,
        limit: Int = 3
    ): Result<List<CanonicalEquivalentCandidate>>
}

class CanonicalCatalogClient(
    private val httpClient: HttpClient,
    private val config: SupabaseConfig,
    private val serviceRoleKey: String
) : CanonicalCatalogGateway {

    private val log = LoggerFactory.getLogger(CanonicalCatalogClient::class.java)
    private val baseUrl = config.url.trimEnd('/')

    override suspend fun lookupQueryMappings(
        normalizedQuery: String,
        locale: String,
        country: String
    ): Result<List<CanonicalQueryMapRow>> = runCatching {
        val response = httpClient.get("$baseUrl/rest/v1/canonical_food_query_map") {
            applyServiceRoleAuth()
            parameter("normalized_query", "eq.$normalizedQuery")
            parameter("locale", "eq.$locale")
            parameter("country", "eq.$country")
            parameter("select", "canonical_food_id,rank")
            parameter("order", "rank.asc")
        }
        if (!response.status.isSuccess()) {
            log.error("[CATALOG] lookupQueryMappings failed status={}", response.status.value)
            throw IllegalStateException("Catalog query-map lookup failed with ${response.status.value}")
        }
        response.body<List<CanonicalQueryMapRow>>()
    }

    override suspend fun readCanonicals(ids: List<String>): Result<List<CanonicalFoodEntity>> = runCatching {
        if (ids.isEmpty()) return@runCatching emptyList()
        // PostgREST in-filter format: ?id=in.(uuid1,uuid2,uuid3)
        val inFilter = "in.(${ids.joinToString(",")})"
        val response = httpClient.get("$baseUrl/rest/v1/canonical_food_item") {
            applyServiceRoleAuth()
            parameter("id", inFilter)
            parameter(
                "select",
                "id,canonical_group_id,primary_locale,primary_country,ai_generated," +
                    "model_provider,model_name,confidence," +
                    "servings:canonical_food_serving(*)," +
                    "terms:canonical_food_term(*)"
            )
        }
        if (!response.status.isSuccess()) {
            log.error("[CATALOG] readCanonicals failed status={}", response.status.value)
            throw IllegalStateException("Catalog read failed with ${response.status.value}")
        }
        response.body<List<CanonicalFoodEntity>>()
    }

    override suspend fun insertCanonicalFoods(
        payload: InsertCanonicalFoodsPayload
    ): Result<InsertCanonicalFoodsResult> = runCatching {
        val response = httpClient.post("$baseUrl/rest/v1/rpc/insert_canonical_foods") {
            applyServiceRoleAuth()
            contentType(ContentType.Application.Json)
            // PostgREST RPC wraps a single jsonb parameter under its declared name:
            // CREATE FUNCTION insert_canonical_foods(payload JSONB) -> body = { "payload": { ... } }
            setBody(RpcEnvelope(payload))
        }
        if (!response.status.isSuccess()) {
            log.error(
                "[CATALOG] insertCanonicalFoods RPC failed status={} query={}",
                response.status.value,
                payload.normalizedQuery
            )
            throw IllegalStateException("insert_canonical_foods RPC failed with ${response.status.value}")
        }
        response.body<InsertCanonicalFoodsResult>()
    }

    override suspend fun findEquivalentCanonicalCandidates(
        englishLikeName: String,
        limit: Int
    ): Result<List<CanonicalEquivalentCandidate>> = runCatching {
        // Filter canonical_food_term where locale starts with "en" and name fuzzy-matches the query.
        val response = httpClient.get("$baseUrl/rest/v1/canonical_food_term") {
            applyServiceRoleAuth()
            parameter("locale", "like.en%")
            parameter("is_alias", "eq.false")
            parameter("name", "ilike.*${englishLikeName}*")
            parameter("select", "canonical_food_id,name")
            parameter("limit", limit)
        }
        if (!response.status.isSuccess()) {
            log.warn("[CATALOG] findEquivalentCanonicalCandidates failed status={}", response.status.value)
            throw IllegalStateException("Catalog equivalent lookup failed with ${response.status.value}")
        }
        response.body<List<CanonicalEquivalentTermRow>>()
            .map { CanonicalEquivalentCandidate(it.canonicalFoodId, it.name) }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyServiceRoleAuth() {
        header("apikey", serviceRoleKey)
        bearerAuth(serviceRoleKey)
    }
}

@Serializable
private data class RpcEnvelope(val payload: InsertCanonicalFoodsPayload)

@Serializable
private data class CanonicalEquivalentTermRow(
    @SerialName("canonical_food_id") val canonicalFoodId: String,
    val name: String
)
