package com.zenthek.upstream.openai

import com.zenthek.config.AiProgressProjectionConfig
import com.zenthek.service.UpstreamFailureException
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.util.Base64

/**
 * Wrapper around OpenAI's `POST /v1/images/edits` endpoint, used to generate one projection
 * rung per call. Returns the image bytes (decoded from `b64_json`) plus token usage so the
 * orchestrator can persist it on the rung row.
 *
 * **Tier note:** Tier 1 caps `gpt-image-2` at 5 IPM and requires a verified org. With our
 * default of 3 rungs in parallel, a single ladder fits inside the cap with 2 IPM headroom
 * for retries / concurrent users.
 */
class OpenAiImageEditClient(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val config: AiProgressProjectionConfig,
) {
    private val log = LoggerFactory.getLogger(OpenAiImageEditClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    data class Result(
        val bytes: ByteArray,
        val usageInputTokens: Int?,
        val usageOutputTokens: Int?,
        val usageCachedInputTokens: Int?,
    )

    /**
     * @param sourceBytes the source photo bytes (the same bytes for every rung in a ladder
     *                    so OpenAI's input-image cache can kick in across rungs).
     * @param prompt the rung-specific prompt; identifies the projection step's target stats.
     * @param userId opaque per-user identifier passed to OpenAI for abuse monitoring.
     */
    suspend fun edit(
        sourceBytes: ByteArray,
        sourceMimeType: String,
        sourceFilename: String,
        prompt: String,
        userId: String,
    ): Result {
        log.info("[IMAGE-EDIT] model={} bytes={} promptLen={}", config.openAiModel, sourceBytes.size, prompt.length)

        val response = httpClient.post("https://api.openai.com/v1/images/edits") {
            headers {
                append(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            timeout { requestTimeoutMillis = config.generateTimeoutMs }
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("model", config.openAiModel)
                        append("prompt", prompt)
                        append("size", config.size)
                        append("quality", config.quality)
                        append("n", "1")
                        // gpt-image-2 always returns base64 PNG by default; the legacy DALL-E
                        // params (`response_format`, `output_format`, `output_compression`) are
                        // not accepted on /v1/images/edits and trigger a 400 if sent.
                        append("user", userId)
                        append(
                            "image",
                            sourceBytes,
                            Headers.build {
                                append(HttpHeaders.ContentType, sourceMimeType)
                                append(HttpHeaders.ContentDisposition, "filename=\"$sourceFilename\"")
                            },
                        )
                    },
                ),
            )
        }

        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            log.warn("[IMAGE-EDIT] non-2xx status={} body={}", response.status, body.take(2000))
            // Distinguish content-policy rejections from generic upstream failures so the
            // orchestrator can surface a tailored message ("photo flagged by safety system,
            // try one with athletic wear") instead of a generic "Generation failed".
            if (isContentPolicyBlock(body)) {
                throw OpenAiContentPolicyException(extractErrorMessage(body) ?: "Photo rejected by OpenAI safety system")
            }
            throw UpstreamFailureException("OpenAI images.edits returned ${response.status.value}")
        }

        val rootObject = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw UpstreamFailureException("OpenAI images.edits response not JSON")

        val firstData = rootObject["data"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw UpstreamFailureException("OpenAI images.edits response missing data[0]")

        val b64 = firstData["b64_json"]?.jsonPrimitive?.content
            ?: throw UpstreamFailureException("OpenAI images.edits response missing b64_json")

        val decoded = runCatching { Base64.getDecoder().decode(b64) }.getOrNull()
            ?: throw UpstreamFailureException("OpenAI images.edits b64_json invalid")

        val usage = rootObject["usage"]?.jsonObject
        val inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull
        val outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.intOrNull
        val cachedInputTokens = usage?.get("input_tokens_details")?.jsonObject?.get("cached_tokens")?.jsonPrimitive?.intOrNull

        return Result(
            bytes = decoded,
            usageInputTokens = inputTokens,
            usageOutputTokens = outputTokens,
            usageCachedInputTokens = cachedInputTokens,
        )
    }

    private fun isContentPolicyBlock(body: String): Boolean {
        val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return false
        val error = parsed["error"]?.jsonObject ?: return false
        val code = error["code"]?.jsonPrimitive?.content
        if (code == "moderation_blocked" || code == "content_policy_violation") return true
        val message = error["message"]?.jsonPrimitive?.content.orEmpty()
        return message.contains("safety system", ignoreCase = true) ||
            message.contains("safety_violations", ignoreCase = true) ||
            message.contains("content policy", ignoreCase = true)
    }

    private fun extractErrorMessage(body: String): String? {
        val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        return parsed["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
    }
}

/** Thrown when OpenAI's safety classifier rejects the request (e.g. minimal-clothing
 *  body shots flagged as `sexual`). Distinct from generic upstream failures so callers
 *  can surface a tailored, actionable error to the user. */
class OpenAiContentPolicyException(message: String) : RuntimeException(message)
