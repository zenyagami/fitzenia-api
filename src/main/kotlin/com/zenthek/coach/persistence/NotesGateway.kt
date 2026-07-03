package com.zenthek.coach.persistence

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
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

@Serializable
data class CoachUserNoteRow(
    val id: String,
    val note: String,
    val category: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
private data class NoteWriteRequest(
    val user_id: String,
    val note: String,
    val category: String,
    val source: String,
)

@Serializable
private data class NoteIdRow(val id: String)

class NotesGateway(
    private val httpClient: HttpClient,
    private val supabaseUrl: String,
    private val serviceRoleKey: String,
) {
    companion object {
        private val log = LoggerFactory.getLogger(NotesGateway::class.java)
        private val json = Json { ignoreUnknownKeys = true }
        private const val MAX_NOTES = 50
    }

    private fun HttpRequestBuilder.serviceRoleHeaders() {
        header("apikey", serviceRoleKey)
        header("Authorization", "Bearer $serviceRoleKey")
        header("Accept", "application/json")
    }

    suspend fun getUserNotes(userId: String, limit: Int = 10): List<CoachUserNoteRow> {
        return try {
            val response = httpClient.get(
                "$supabaseUrl/rest/v1/coach_user_note?user_id=eq.$userId&select=id,note,category,created_at&order=created_at.desc&limit=$limit"
            ) { serviceRoleHeaders() }
            if (response.status.isSuccess()) json.decodeFromString(response.body<String>())
            else {
                log.warn("[NOTES] getUserNotes failed status={} userId={}", response.status.value, userId)
                emptyList()
            }
        } catch (e: Exception) {
            log.warn("[NOTES] getUserNotes exception userId={}", userId, e)
            emptyList()
        }
    }

    suspend fun writeNote(userId: String, category: String, note: String): String? {
        return try {
            evictOldestIfNeeded(userId)
            val response = httpClient.post("$supabaseUrl/rest/v1/coach_user_note") {
                serviceRoleHeaders()
                header("Prefer", "return=representation")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(NoteWriteRequest.serializer(), NoteWriteRequest(userId, note, category, "coach")))
            }
            if (response.status.isSuccess()) {
                json.decodeFromString<List<CoachUserNoteRow>>(response.body<String>()).firstOrNull()?.id
            } else {
                log.warn("[NOTES] writeNote failed status={} userId={}", response.status.value, userId)
                null
            }
        } catch (e: Exception) {
            log.warn("[NOTES] writeNote exception userId={}", userId, e)
            null
        }
    }

    suspend fun deleteNote(userId: String, noteId: String): Boolean {
        return try {
            val response = httpClient.delete(
                "$supabaseUrl/rest/v1/coach_user_note?id=eq.$noteId&user_id=eq.$userId"
            ) {
                serviceRoleHeaders()
                header("Prefer", "return=representation")
            }
            if (response.status.isSuccess()) {
                json.decodeFromString<List<NoteIdRow>>(response.body<String>()).isNotEmpty()
            } else {
                log.warn("[NOTES] deleteNote failed status={} noteId={} userId={}", response.status.value, noteId, userId)
                false
            }
        } catch (e: Exception) {
            log.warn("[NOTES] deleteNote exception noteId={} userId={}", noteId, userId, e)
            false
        }
    }

    private suspend fun evictOldestIfNeeded(userId: String) {
        try {
            val response = httpClient.get(
                "$supabaseUrl/rest/v1/coach_user_note?user_id=eq.$userId&select=id&order=created_at.asc"
            ) { serviceRoleHeaders() }
            if (!response.status.isSuccess()) return
            val rows = json.decodeFromString<List<NoteIdRow>>(response.body<String>())
            if (rows.size < MAX_NOTES) return
            val evictCount = rows.size - MAX_NOTES + 1
            val ids = rows.take(evictCount).joinToString(",") { it.id }
            httpClient.delete("$supabaseUrl/rest/v1/coach_user_note?id=in.($ids)&user_id=eq.$userId") {
                serviceRoleHeaders()
            }
        } catch (e: Exception) {
            log.warn("[NOTES] evictOldestIfNeeded failed userId={}", userId, e)
        }
    }
}
