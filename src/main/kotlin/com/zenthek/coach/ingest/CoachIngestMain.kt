package com.zenthek.coach.ingest

import com.zenthek.coach.rag.EMBEDDING_DIM
import com.zenthek.coach.rag.EMBEDDING_FORMAT_VERSION
import com.zenthek.coach.rag.GEMINI_EMBEDDING_MODEL
import com.zenthek.coach.rag.EmbeddingClient
import io.github.cdimascio.dotenv.dotenv
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("CoachIngest")

fun main(args: Array<String>) {
    val section = args.firstOrNull { it.startsWith("--section=") }
        ?.removePrefix("--section=") ?: "app"
    val rebuild = args.any { it == "--rebuild=true" || it == "--rebuild" }

    log.info("[COACH-INGEST] starting section={} rebuild={}", section, rebuild)

    val exitCode = runBlocking {
        try {
            val dotenv = dotenv { ignoreIfMissing = true }
            val supabaseUrl = dotenv["SUPABASE_URL"]?.trim()?.ifBlank { null }
                ?: error("Missing SUPABASE_URL")
            val serviceRoleKey = dotenv["SUPABASE_SERVICE_ROLE_KEY"]?.trim()?.ifBlank { null }
                ?: error("Missing SUPABASE_SERVICE_ROLE_KEY")
            val geminiApiKey = dotenv["GEMINI_API_KEY"]?.trim()?.ifBlank { null }
                ?: error("Missing GEMINI_API_KEY")

            val httpClient = io.ktor.client.HttpClient(CIO) {
                install(HttpTimeout) { requestTimeoutMillis = 120_000; connectTimeoutMillis = 10_000 }
            }
            val embeddingClient = EmbeddingClient(httpClient, geminiApiKey)
            val gateway = CoachKbGateway(httpClient, supabaseUrl, serviceRoleKey)

            val docs = loadCorpus(section)
            log.info("[COACH-INGEST] loaded {} docs from corpus section={}", docs.size, section)

            var embedded = 0
            var skipped = 0
            for (doc in docs) {
                val changed = gateway.isDocChanged(doc, rebuild)
                if (!changed) {
                    log.info("[COACH-INGEST] skip doc={} (hash unchanged)", doc.id)
                    skipped++
                    continue
                }
                log.info("[COACH-INGEST] embedding doc={} chunks={}", doc.id, doc.chunks.size)
                val embeddings = embedAllChunks(embeddingClient, doc)
                gateway.upsertDoc(doc, embeddings)
                embedded++
                log.info("[COACH-INGEST] committed doc={}", doc.id)
            }

            log.info("[COACH-INGEST] done embedded={} skipped={}", embedded, skipped)
            0
        } catch (e: Exception) {
            log.error("[COACH-INGEST] fatal error", e)
            1
        }
    }
    exitProcess(exitCode)
}

private suspend fun embedAllChunks(
    client: EmbeddingClient,
    doc: CorpusDoc,
): List<List<Float>> = coroutineScope {
    doc.chunks.map { chunk ->
        async { client.embedChunk(doc.title, chunk) }
    }.awaitAll()
}

// ── Corpus loading ────────────────────────────────────────────────────────────

@Serializable
data class CorpusDoc(
    val id: String,
    val title: String,
    val section: String,
    val locale: String = "en",
    @SerialName("source_uri") val sourceUri: String? = null,
    val chunks: List<String>,
)

private val corpusJson = Json { ignoreUnknownKeys = true }

fun loadCorpus(section: String): List<CorpusDoc> {
    val resourceDir = "coach/corpus/$section"
    val classLoader = Thread.currentThread().contextClassLoader
        ?: CoachIngestMain::class.java.classLoader

    // List resources in the directory via classpath scanning
    val dirUrl = classLoader.getResource(resourceDir)
        ?: error("Corpus directory not found on classpath: $resourceDir")

    val files = java.io.File(dirUrl.toURI()).listFiles { f -> f.extension == "json" }
        ?: error("Cannot list corpus files in $resourceDir")

    return files.sortedBy { it.name }.map { file ->
        val text = file.readText()
        corpusJson.decodeFromString<CorpusDoc>(text)
    }
}

// Build the canonical content_md from chunks (joined with double newline)
fun CorpusDoc.contentMd(): String = chunks.joinToString("\n\n")

fun CorpusDoc.contentHash(): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(contentMd().toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

// Rough token estimate: 1 token ≈ 4 chars in English
fun estimateTokens(text: String): Int = maxOf(1, text.length / 4)

// ── Supabase gateway ──────────────────────────────────────────────────────────

class CoachKbGateway(
    private val httpClient: io.ktor.client.HttpClient,
    private val supabaseUrl: String,
    private val serviceRoleKey: String,
) {
    private val log = LoggerFactory.getLogger(CoachKbGateway::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class DocRow(@SerialName("content_hash") val contentHash: String)

    @Serializable
    private data class DocInsert(
        val id: String,
        val title: String,
        val section: String,
        val locale: String,
        val content_md: String,
        val content_hash: String,
        val source_uri: String?,
        val version: Int,
        val updated_at: String,
    )

    @Serializable
    private data class ChunkInsert(
        val doc_id: String,
        val section: String,
        val chunk_index: Int,
        val text: String,
        val tokens: Int,
        val embedding: String,        // bracketed-string format for pgvector via PostgREST
        val embedding_model: String,
        val embedding_dim: Int,
        val embedding_format_version: String,
    )

    /** Returns true if the doc needs re-embedding (new, changed hash, or rebuild=true). */
    suspend fun isDocChanged(doc: CorpusDoc, rebuild: Boolean): Boolean {
        if (rebuild) return true
        val response = httpClient.get(
            "$supabaseUrl/rest/v1/coach_kb_doc?id=eq.${doc.id}&select=content_hash&limit=1"
        ) { serviceRoleHeaders() }
        val body: String = response.body()
        if (!response.status.isSuccess()) return true
        val rows = json.decodeFromString<List<DocRow>>(body)
        if (rows.isEmpty()) return true
        return rows.first().contentHash != doc.contentHash()
    }

    /** Upsert doc + delete old chunks + insert new chunks in one per-doc commit. */
    suspend fun upsertDoc(doc: CorpusDoc, embeddings: List<List<Float>>) {
        val now = java.time.Instant.now().toString()
        val insert = DocInsert(
            id = doc.id,
            title = doc.title,
            section = doc.section,
            locale = doc.locale,
            content_md = doc.contentMd(),
            content_hash = doc.contentHash(),
            source_uri = doc.sourceUri,
            version = 1,
            updated_at = now,
        )
        val upsertResp = httpClient.post("$supabaseUrl/rest/v1/coach_kb_doc") {
            serviceRoleHeaders()
            header("Prefer", "resolution=merge-duplicates,return=minimal")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(DocInsert.serializer(), insert))
        }
        if (!upsertResp.status.isSuccess()) {
            val body: String = upsertResp.body()
            log.error("[COACH-INGEST] upsert doc={} status={} body={}", doc.id, upsertResp.status, body)
            error("upsertDoc failed for ${doc.id}: ${upsertResp.status}")
        }

        // Delete old chunks so we start clean
        val delResp = httpClient.delete(
            "$supabaseUrl/rest/v1/coach_kb_chunk?doc_id=eq.${doc.id}"
        ) { serviceRoleHeaders() }
        if (!delResp.status.isSuccess()) {
            val body: String = delResp.body()
            log.error("[COACH-INGEST] delete chunks doc={} status={} body={}", doc.id, delResp.status, body)
            error("deleteChunks failed for ${doc.id}: ${delResp.status}")
        }

        // Insert new chunks
        val chunkRows = doc.chunks.mapIndexed { idx, text ->
            val vec = embeddings[idx]
            ChunkInsert(
                doc_id = doc.id,
                section = doc.section,
                chunk_index = idx,
                text = text,
                tokens = estimateTokens(text),
                embedding = vec.joinToString(",", "[", "]"),
                embedding_model = GEMINI_EMBEDDING_MODEL,
                embedding_dim = EMBEDDING_DIM,
                embedding_format_version = EMBEDDING_FORMAT_VERSION,
            )
        }

        // Insert in batches of 50 to stay within request size limits
        chunkRows.chunked(50).forEach { batch ->
            val batchJson = json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(ChunkInsert.serializer()),
                batch,
            )
            val insertResp = httpClient.post("$supabaseUrl/rest/v1/coach_kb_chunk") {
                serviceRoleHeaders()
                header("Prefer", "return=minimal")
                contentType(ContentType.Application.Json)
                setBody(batchJson)
            }
            if (!insertResp.status.isSuccess()) {
                val body: String = insertResp.body()
                log.error("[COACH-INGEST] insert chunks doc={} batch status={} body={}", doc.id, insertResp.status, body)
                error("insertChunks failed for ${doc.id}: ${insertResp.status}")
            }
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.serviceRoleHeaders() {
        header("apikey", serviceRoleKey)
        header("Authorization", "Bearer $serviceRoleKey")
    }
}

// Needed for classloader reference in loadCorpus
private object CoachIngestMain
