package com.zenthek.coach.rag

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

const val GEMINI_EMBEDDING_MODEL = "gemini-embedding-2"
const val EMBEDDING_DIM = 768
const val EMBEDDING_FORMAT_VERSION = "v1"

// Text wrappers are part of the embedding contract; bump EMBEDDING_FORMAT_VERSION when these change.
fun docEmbedText(title: String, chunkText: String) = "title: $title | text: $chunkText"
fun queryEmbedText(query: String) = "task: question answering | query: $query"

@Serializable
private data class EmbedPart(val text: String)

@Serializable
private data class EmbedContent(val parts: List<EmbedPart>)

@Serializable
private data class EmbedRequest(
    val content: EmbedContent,
    val output_dimensionality: Int = EMBEDDING_DIM,
)

@Serializable
private data class EmbedValues(val values: List<Float>)

// Gemini embedContent returns "embedding" (singular), not "embeddings" (plural)
@Serializable
private data class EmbedResponse(val embedding: EmbedValues)

class EmbeddingClient(
    private val httpClient: HttpClient,
    private val apiKey: String,
    concurrency: Int = 8,
) {
    private val log = LoggerFactory.getLogger(EmbeddingClient::class.java)
    private val semaphore = Semaphore(concurrency)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val url =
        "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_EMBEDDING_MODEL:embedContent"

    suspend fun embedChunk(title: String, chunkText: String): List<Float> =
        embed(docEmbedText(title, chunkText))

    suspend fun embedQuery(query: String): List<Float> =
        embed(queryEmbedText(query))

    private suspend fun embed(text: String): List<Float> = semaphore.withPermit {
        val req = EmbedRequest(content = EmbedContent(parts = listOf(EmbedPart(text))))
        val response = httpClient.post(url) {
            header("x-goog-api-key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(EmbedRequest.serializer(), req))
        }
        if (!response.status.isSuccess()) {
            val body: String = response.body()
            log.error("[EMBED] embedContent failed status={} body={}", response.status, body)
            error("embedContent failed: ${response.status}")
        }
        val body: String = response.body()
        json.decodeFromString<EmbedResponse>(body).embedding.values
    }
}
