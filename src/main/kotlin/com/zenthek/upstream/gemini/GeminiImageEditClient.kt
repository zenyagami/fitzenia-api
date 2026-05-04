package com.zenthek.upstream.gemini

import com.zenthek.config.AiProgressProjectionConfig
import com.zenthek.service.UpstreamFailureException
import com.zenthek.upstream.imageedit.ImageEditModerationException
import com.zenthek.upstream.imageedit.ProgressImageEditClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.util.Base64

/**
 * Gemini image-edit upstream (a.k.a. "nano banana"). Calls
 * `models/{model}:generateContent` with `responseModalities=["IMAGE"]` so the model returns
 * an inline_data image part rather than text. The same `sourceBytes` are passed in alongside
 * the prompt — Gemini handles the edit-vs-generate distinction by conditioning on the input
 * image.
 *
 * Drop-in alternative to [com.zenthek.upstream.openai.OpenAiImageEditClient] selected via
 * `AiProgressProjectionConfig.provider`. Throws [ImageEditModerationException] when the
 * response is blocked by safety (finishReason / promptFeedback.blockReason).
 */
class GeminiImageEditClient(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val config: AiProgressProjectionConfig,
) : ProgressImageEditClient {

    private val log = LoggerFactory.getLogger(GeminiImageEditClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun edit(
        sourceBytes: ByteArray,
        sourceMimeType: String,
        sourceFilename: String,
        prompt: String,
        userId: String,
    ): ProgressImageEditClient.Result {
        log.info("[IMAGE-EDIT] model={} bytes={} promptLen={}", config.geminiImageModel, sourceBytes.size, prompt.length)

        val base64Image = Base64.getEncoder().encodeToString(sourceBytes)
        val requestBody = kotlinx.serialization.json.buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    putJsonArray("parts") {
                        addJsonObject { put("text", prompt) }
                        addJsonObject {
                            putJsonObject("inline_data") {
                                put("mime_type", sourceMimeType)
                                put("data", base64Image)
                            }
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add("IMAGE") }
            }
        }

        val response = httpClient.post(
            "https://generativelanguage.googleapis.com/v1beta/models/${config.geminiImageModel}:generateContent?key=$apiKey"
        ) {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
            timeout { requestTimeoutMillis = config.generateTimeoutMs }
        }

        val responseText = response.bodyAsText()
        if (!response.status.isSuccess()) {
            log.warn("[IMAGE-EDIT-GEMINI] non-2xx status={} body={}", response.status, responseText.take(2000))
            if (isContentPolicyBlock(responseText)) {
                throw ImageEditModerationException(extractErrorMessage(responseText) ?: "Photo rejected by Gemini safety system")
            }
            throw UpstreamFailureException("Gemini generateContent returned ${response.status.value}")
        }

        val rootObject = runCatching { json.parseToJsonElement(responseText).jsonObject }.getOrNull()
            ?: throw UpstreamFailureException("Gemini generateContent response not JSON")

        // Prompt-level safety block (input rejected before generation).
        rootObject["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.content?.let { reason ->
            log.warn("[IMAGE-EDIT-GEMINI] prompt blocked reason={}", reason)
            throw ImageEditModerationException("Gemini blocked input photo: $reason")
        }

        val firstCandidate = rootObject["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw UpstreamFailureException("Gemini generateContent response missing candidates")

        val finishReason = firstCandidate["finishReason"]?.jsonPrimitive?.content
        if (finishReason in MODERATION_FINISH_REASONS) {
            log.warn("[IMAGE-EDIT-GEMINI] candidate blocked finishReason={}", finishReason)
            throw ImageEditModerationException("Gemini blocked image generation: $finishReason")
        }

        val parts = firstCandidate["content"]?.jsonObject?.get("parts")?.jsonArray
            ?: throw UpstreamFailureException("Gemini generateContent response missing content.parts")

        // Walk the parts looking for the first inline_data with a base64 payload. The model
        // can interleave text + image parts; we only want the image bytes.
        val imageData = parts.firstNotNullOfOrNull { part ->
            part.jsonObject["inline_data"]?.jsonObject?.get("data")?.jsonPrimitive?.content
                ?: part.jsonObject["inlineData"]?.jsonObject?.get("data")?.jsonPrimitive?.content
        } ?: throw UpstreamFailureException("Gemini generateContent response missing inline_data image")

        val decoded = runCatching { Base64.getDecoder().decode(imageData) }.getOrNull()
            ?: throw UpstreamFailureException("Gemini generateContent inline_data invalid base64")

        val usage = rootObject["usageMetadata"]?.jsonObject
        val inputTokens = usage?.get("promptTokenCount")?.jsonPrimitive?.intOrNull
        val outputTokens = usage?.get("candidatesTokenCount")?.jsonPrimitive?.intOrNull
        val cachedInputTokens = usage?.get("cachedContentTokenCount")?.jsonPrimitive?.intOrNull

        return ProgressImageEditClient.Result(
            bytes = decoded,
            usageInputTokens = inputTokens,
            usageOutputTokens = outputTokens,
            usageCachedInputTokens = cachedInputTokens,
        )
    }

    private fun isContentPolicyBlock(body: String): Boolean {
        val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return false
        val error = parsed["error"]?.jsonObject ?: return false
        val status = error["status"]?.jsonPrimitive?.content
        if (status == "PERMISSION_DENIED" || status == "FAILED_PRECONDITION") return false
        val message = error["message"]?.jsonPrimitive?.content.orEmpty()
        return message.contains("safety", ignoreCase = true) ||
            message.contains("blocked", ignoreCase = true) ||
            message.contains("policy", ignoreCase = true)
    }

    private fun extractErrorMessage(body: String): String? {
        val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        return parsed["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
    }

    companion object {
        private val MODERATION_FINISH_REASONS = setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII")
    }
}
