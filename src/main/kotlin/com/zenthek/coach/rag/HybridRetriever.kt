package com.zenthek.coach.rag

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

data class RetrievedChunk(
    val chunkId: String,
    val docId: String,
    val section: String,
    val text: String,
    val score: Double,
)

private val MEAL_KEYWORDS = setOf(
    "recipe", "recipes", "meal", "cook", "cooking", "breakfast", "lunch", "dinner",
    "snack", "snacks", "eat", "eating", "food", "dish", "ingredient", "prepare"
)
private val BULK_CUT_KEYWORDS = setOf(
    "cut", "cutting", "bulk", "bulking", "deficit", "surplus", "protein",
    "calorie", "calories", "macro", "macros", "lose weight", "gain weight",
    "fat loss", "muscle gain", "body recomp"
)
private const val SECTION_BOOST = 0.30

class HybridRetriever(
    private val httpClient: HttpClient,
    private val supabaseUrl: String,
    private val serviceRoleKey: String,
    private val embeddingClient: EmbeddingClient,
) {
    private val log = LoggerFactory.getLogger(HybridRetriever::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class SearchRequest(
        val p_query_embedding: List<Float>,
        val p_query_text: String,
        val p_sections: List<String>? = null,
    )

    @Serializable
    private data class ChunkRow(
        @SerialName("chunk_id") val chunkId: String,
        @SerialName("doc_id") val docId: String,
        val section: String,
        @SerialName("chunk_index") val chunkIndex: Int,
        val text: String,
        @SerialName("rrf_score") val rrfScore: Double,
    )

    suspend fun retrieve(query: String): List<RetrievedChunk> = runCatching {
        val embedding = embeddingClient.embedQuery(query)
        val lowerQuery = query.lowercase()
        val isMealQuery = MEAL_KEYWORDS.any { lowerQuery.contains(it) }
        val isBulkCutQuery = !isMealQuery && BULK_CUT_KEYWORDS.any { lowerQuery.contains(it) }

        // Hard-bias recipes for meal asks; no SQL filter for other query types
        val sections: List<String>? = if (isMealQuery) listOf("recipes") else null

        val reqBody = SearchRequest(
            p_query_embedding = embedding,
            p_query_text = query,
            p_sections = sections,
        )
        val response = httpClient.post("$supabaseUrl/rest/v1/rpc/search_coach_kb_hybrid") {
            header("apikey", serviceRoleKey)
            header("Authorization", "Bearer $serviceRoleKey")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(SearchRequest.serializer(), reqBody))
        }
        if (!response.status.isSuccess()) {
            val body: String = response.body()
            log.error("[RAG] search_coach_kb_hybrid failed status={} body={}", response.status, body)
            error("RAG search failed: ${response.status}")
        }
        val body: String = response.body()
        val rows = json.decodeFromString<List<ChunkRow>>(body)

        // +30% score boost for nutrition+app on cut/bulk queries; re-sort descending
        val biased: List<ChunkRow> = if (isBulkCutQuery) {
            rows.map { row ->
                if (row.section == "nutrition" || row.section == "app") {
                    row.copy(rrfScore = row.rrfScore * (1.0 + SECTION_BOOST))
                } else row
            }.sortedByDescending { it.rrfScore }
        } else {
            rows
        }

        biased.map { row ->
            RetrievedChunk(
                chunkId = row.chunkId,
                docId = row.docId,
                section = row.section,
                text = row.text,
                score = row.rrfScore,
            )
        }
    }.getOrElse { e ->
        log.error("[RAG] retrieve failed — degrading to no-context answer", e)
        emptyList()
    }
}
