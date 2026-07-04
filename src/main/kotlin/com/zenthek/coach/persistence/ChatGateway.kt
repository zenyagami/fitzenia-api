package com.zenthek.coach.persistence

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory
import java.security.MessageDigest

@Serializable
data class ChatRow(val id: String, val title: String)

@Serializable
data class ChatSummaryRow(
    val id: String,
    val title: String,
    @SerialName("message_count") val messageCount: Int,
    @SerialName("last_message_at") val lastMessageAt: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class MessageRow(val id: String)

@Serializable
data class HistoryMessageRow(
    val id: String,
    val role: String,
    val content: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class SummaryRow(
    @SerialName("chat_id") val chatId: String,
    @SerialName("up_to_message_id") val upToMessageId: String,
    val summary: String,
    val tokens: Int,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class MessageDetailRow(
    val id: String,
    val role: String,
    val content: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
private data class CreateChatBody(val user_id: String, val locale: String, val title: String)

@Serializable
private data class ArchiveChatBody(@SerialName("archived_at") val archivedAt: String)

@Serializable
private data class UpdateChatTitleBody(val title: String, val title_generated: Boolean)

@Serializable
private data class InsertSummaryBody(
    val chat_id: String,
    val up_to_message_id: String,
    val user_id: String,
    val summary: String,
    val tokens: Int,
)

@Serializable
private data class InsertMessageBody(
    val chat_id: String,
    val user_id: String,
    val role: String,
    val content: String,
    val request_id: String,
    val input_tokens: Int? = null,
    val output_tokens: Int? = null,
    val cached_tokens: Int? = null,
    val pro_input_tokens: Int? = null,
    val pro_output_tokens: Int? = null,
    val model_used: String? = null,
    val finish_reason: String? = null,
    val safety_action: String? = null,
    val citations: JsonElement? = null,
    val escalated: Boolean = false,
)

@Serializable
private data class InsertTraceBody(
    val message_id: String,
    val user_id: String,
    val rag_query_hash: String,
    val retrieved: JsonElement? = null,
    val prompt_version: Int,
)

class ChatGateway(
    private val httpClient: HttpClient,
    private val supabaseUrl: String,
    private val serviceRoleKey: String,
) {
    private val log = LoggerFactory.getLogger(ChatGateway::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun createChat(userId: String, locale: String, title: String = "New chat"): ChatRow {
        val response = httpClient.post("$supabaseUrl/rest/v1/coach_chat") {
            serviceRoleHeaders()
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CreateChatBody.serializer(), CreateChatBody(userId, locale, title)))
        }
        val bodyText: String = response.body()
        if (!response.status.isSuccess()) {
            log.error("[COACH-PERSIST] createChat failed userId={} status={}", userId, response.status)
            error("createChat failed: ${response.status}")
        }
        return json.decodeFromString<List<ChatRow>>(bodyText).first()
    }

    suspend fun insertMessage(
        chatId: String,
        userId: String,
        role: String,
        content: String,
        requestId: String,
        inputTokens: Int? = null,
        outputTokens: Int? = null,
        cachedTokens: Int? = null,
        proInputTokens: Int? = null,
        proOutputTokens: Int? = null,
        modelUsed: String? = null,
        finishReason: String? = null,
        safetyAction: String? = null,
        citations: JsonElement? = null,
        escalated: Boolean = false,
    ): MessageRow {
        val body = InsertMessageBody(
            chat_id = chatId,
            user_id = userId,
            role = role,
            content = content,
            request_id = requestId,
            input_tokens = inputTokens,
            output_tokens = outputTokens,
            cached_tokens = cachedTokens,
            pro_input_tokens = proInputTokens,
            pro_output_tokens = proOutputTokens,
            model_used = modelUsed,
            finish_reason = finishReason,
            safety_action = safetyAction,
            citations = citations,
            escalated = escalated,
        )
        val response = httpClient.post("$supabaseUrl/rest/v1/coach_message") {
            serviceRoleHeaders()
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(InsertMessageBody.serializer(), body))
        }
        val bodyText: String = response.body()
        if (!response.status.isSuccess()) {
            log.error("[COACH-PERSIST] insertMessage failed chatId={} role={} status={} body={}", chatId, role, response.status, bodyText)
            error("insertMessage failed: ${response.status}")
        }
        return json.decodeFromString<List<MessageRow>>(bodyText).first()
    }

    suspend fun insertTrace(
        messageId: String,
        userId: String,
        ragQuery: String,
        retrieved: JsonElement? = null,
        promptVersion: Int,
    ) {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(ragQuery.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val body = InsertTraceBody(
            message_id = messageId,
            user_id = userId,
            rag_query_hash = hash,
            retrieved = retrieved,
            prompt_version = promptVersion,
        )
        val response = httpClient.post("$supabaseUrl/rest/v1/coach_trace") {
            serviceRoleHeaders()
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(InsertTraceBody.serializer(), body))
        }
        if (!response.status.isSuccess()) {
            val bodyText: String = response.body()
            log.error("[COACH-PERSIST] insertTrace failed messageId={} status={} body={}", messageId, response.status, bodyText)
        }
    }

    suspend fun listChats(userId: String): List<ChatSummaryRow> {
        val response = httpClient.get(
            "$supabaseUrl/rest/v1/coach_chat?user_id=eq.$userId&archived_at=is.null&order=updated_at.desc&select=id,title,message_count,last_message_at,created_at,updated_at"
        ) {
            serviceRoleHeaders()
        }
        val bodyText: String = response.body()
        if (!response.status.isSuccess()) {
            log.error("[COACH-PERSIST] listChats failed userId={} status={}", userId, response.status)
            error("listChats failed: ${response.status}")
        }
        return json.decodeFromString(bodyText)
    }

    suspend fun getMessagesFull(chatId: String, userId: String): List<MessageDetailRow> {
        val response = httpClient.get(
            "$supabaseUrl/rest/v1/coach_message?chat_id=eq.$chatId&user_id=eq.$userId&order=created_at.asc&select=id,role,content,created_at"
        ) {
            serviceRoleHeaders()
        }
        val bodyText: String = response.body()
        if (!response.status.isSuccess()) {
            log.error("[COACH-PERSIST] getMessagesFull failed chatId={} status={}", chatId, response.status)
            error("getMessagesFull failed: ${response.status}")
        }
        return json.decodeFromString(bodyText)
    }

    /** Archives the chat. Returns true if found + owned; false if not found or not owned by this user. */
    suspend fun archiveChat(chatId: String, userId: String): Boolean {
        val now = java.time.Instant.now().toString()
        val response = httpClient.patch(
            "$supabaseUrl/rest/v1/coach_chat?id=eq.$chatId&user_id=eq.$userId"
        ) {
            serviceRoleHeaders()
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(ArchiveChatBody.serializer(), ArchiveChatBody(archivedAt = now)))
        }
        val bodyText: String = response.body()
        if (!response.status.isSuccess()) {
            log.error("[COACH-PERSIST] archiveChat failed chatId={} userId={} status={}", chatId, userId, response.status)
            error("archiveChat failed: ${response.status}")
        }
        return json.decodeFromString<List<ChatRow>>(bodyText).isNotEmpty()
    }

    /** Deletes all messages for a chat. Call only after archiveChat confirms ownership. */
    suspend fun deleteChatMessages(chatId: String, userId: String) {
        val response = httpClient.delete(
            "$supabaseUrl/rest/v1/coach_message?chat_id=eq.$chatId&user_id=eq.$userId"
        ) {
            serviceRoleHeaders()
        }
        if (!response.status.isSuccess()) {
            log.error("[COACH-PERSIST] deleteChatMessages failed chatId={} status={}", chatId, response.status)
            error("deleteChatMessages failed: ${response.status}")
        }
    }

    suspend fun getMessages(chatId: String, limit: Int = 50): List<HistoryMessageRow> {
        val response = httpClient.get(
            "$supabaseUrl/rest/v1/coach_message?chat_id=eq.$chatId&order=created_at.desc&limit=$limit&select=id,role,content,created_at"
        ) {
            serviceRoleHeaders()
        }
        val bodyText: String = response.body()
        if (!response.status.isSuccess()) {
            log.error("[COACH-PERSIST] getMessages failed chatId={} status={}", chatId, response.status)
            error("getMessages failed: ${response.status}")
        }
        // desc fetch gives most-recent N; reverse to restore chronological order for the LLM
        return json.decodeFromString<List<HistoryMessageRow>>(bodyText).reversed()
    }

    suspend fun updateChatTitle(chatId: String, userId: String, title: String) {
        val response = httpClient.patch(
            "$supabaseUrl/rest/v1/coach_chat?id=eq.$chatId&user_id=eq.$userId"
        ) {
            serviceRoleHeaders()
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(UpdateChatTitleBody.serializer(), UpdateChatTitleBody(title = title, title_generated = true)))
        }
        if (!response.status.isSuccess()) {
            val bodyText: String = response.body()
            log.warn("[COACH-PERSIST] updateChatTitle failed chatId={} status={} body={}", chatId, response.status, bodyText)
        }
    }

    suspend fun getLatestSummary(chatId: String): SummaryRow? {
        val response = httpClient.get(
            "$supabaseUrl/rest/v1/coach_summary?chat_id=eq.$chatId&order=created_at.desc&limit=1"
        ) {
            serviceRoleHeaders()
        }
        val bodyText: String = response.body()
        if (!response.status.isSuccess()) {
            log.warn("[COACH-PERSIST] getLatestSummary failed chatId={} status={}", chatId, response.status)
            return null
        }
        return json.decodeFromString<List<SummaryRow>>(bodyText).firstOrNull()
    }

    suspend fun insertSummary(chatId: String, userId: String, upToMessageId: String, summary: String, tokens: Int) {
        val body = InsertSummaryBody(
            chat_id = chatId,
            up_to_message_id = upToMessageId,
            user_id = userId,
            summary = summary,
            tokens = tokens,
        )
        val response = httpClient.post("$supabaseUrl/rest/v1/coach_summary") {
            serviceRoleHeaders()
            header("Prefer", "resolution=ignore-duplicates")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(InsertSummaryBody.serializer(), body))
        }
        if (!response.status.isSuccess()) {
            val bodyText: String = response.body()
            log.warn("[COACH-PERSIST] insertSummary failed chatId={} status={} body={}", chatId, response.status, bodyText)
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.serviceRoleHeaders() {
        header("apikey", serviceRoleKey)
        header("Authorization", "Bearer $serviceRoleKey")
    }
}
