package com.zenthek.upstream.gemini

import com.zenthek.ai.GatekeeperRejectionReason
import com.zenthek.ai.GatekeeperVerdict
import com.zenthek.ai.ProgressGatekeeperClient
import com.zenthek.config.AiProgressProjectionConfig
import com.zenthek.service.UpstreamFailureException
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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.util.Base64

/**
 * Single Gemini Flash Lite call that gates the upload AND estimates body fat. The single-call
 * design keeps the latency and cost overhead of the gatekeeper at ~$0.001 + ~1s, regardless of
 * whether the client supplied currentBodyFatPercent.
 *
 * No context cache — the prompt is short enough that caching adds complexity without a payoff.
 */
class GeminiProgressGatekeeperClient(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val config: AiProgressProjectionConfig,
) : ProgressGatekeeperClient {

    private val log = LoggerFactory.getLogger(GeminiProgressGatekeeperClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun verify(imageBytes: ByteArray, mimeType: String): GatekeeperVerdict {
        val base64Image = Base64.getEncoder().encodeToString(imageBytes)
        val systemPrompt = SYSTEM_PROMPT
        val userPrompt = USER_PROMPT

        val requestBody = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", systemPrompt) } }
            }
            putJsonArray("contents") {
                addJsonObject {
                    putJsonArray("parts") {
                        addJsonObject { put("text", userPrompt) }
                        addJsonObject {
                            putJsonObject("inline_data") {
                                put("mime_type", mimeType)
                                put("data", base64Image)
                            }
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("maxOutputTokens", 1024)
                put("responseMimeType", "application/json")
                put("responseJsonSchema", responseSchema())
            }
        }

        log.info("[GATEKEEPER] verify model={} bytes={}", config.gatekeeperModel, imageBytes.size)
        val response = httpClient.post(
            "https://generativelanguage.googleapis.com/v1beta/models/${config.gatekeeperModel}:generateContent?key=$apiKey"
        ) {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
            timeout { requestTimeoutMillis = config.gatekeeperTimeoutMs }
        }

        val responseText = response.bodyAsText()
        if (!response.status.isSuccess()) {
            log.warn("[GATEKEEPER] non-2xx status={} body={}", response.status, responseText.take(500))
            throw UpstreamFailureException("Gatekeeper upstream returned ${response.status.value}")
        }

        val content = runCatching {
            json.parseToJsonElement(responseText).jsonObject["candidates"]
                ?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("content")
                ?.jsonObject?.get("parts")
                ?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("text")
                ?.jsonPrimitive?.content
        }.getOrNull() ?: run {
            log.warn("[GATEKEEPER] could not extract content; raw={}", responseText.take(500))
            throw UpstreamFailureException("Gatekeeper response missing content")
        }

        val parsed = runCatching { json.parseToJsonElement(content).jsonObject }.getOrNull() ?: run {
            log.warn("[GATEKEEPER] content not JSON: {}", content.take(500))
            throw UpstreamFailureException("Gatekeeper content not JSON")
        }

        val isAcceptable = parsed["isAcceptable"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val confidence = parsed["confidence"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val reasons = parsed["rejectionReasons"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.content.let { name -> runCatching { GatekeeperRejectionReason.valueOf(name) }.getOrNull() } }
            ?: emptyList()
        val estimatedBf = parsed["estimatedBodyFatPercent"]?.jsonPrimitive?.doubleOrNull
        val estimatedBfConfidence = parsed["estimatedBodyFatConfidence"]?.jsonPrimitive?.doubleOrNull
        val estimatedBfNotes = parsed["estimatedBodyFatNotes"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

        return GatekeeperVerdict(
            isAcceptable = isAcceptable,
            rejectionReasons = reasons,
            confidence = confidence,
            estimatedBodyFatPercent = estimatedBf,
            estimatedBodyFatConfidence = estimatedBfConfidence,
            estimatedBodyFatNotes = estimatedBfNotes,
            model = config.gatekeeperModel,
        )
    }

    private fun responseSchema() = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("isAcceptable") { put("type", "boolean") }
            putJsonObject("rejectionReasons") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "string")
                    putJsonArray("enum") {
                        GatekeeperRejectionReason.entries.forEach { add(it.name) }
                    }
                }
            }
            putJsonObject("confidence") {
                put("type", "number")
                put("minimum", 0.0)
                put("maximum", 1.0)
            }
            putJsonObject("estimatedBodyFatPercent") {
                put("type", "number")
                put("minimum", 3.0)
                put("maximum", 60.0)
            }
            putJsonObject("estimatedBodyFatConfidence") {
                put("type", "number")
                put("minimum", 0.0)
                put("maximum", 1.0)
            }
            putJsonObject("estimatedBodyFatNotes") { put("type", "string") }
        }
        putJsonArray("required") {
            add("isAcceptable")
            add("rejectionReasons")
            add("confidence")
        }
    }

    companion object {
        private val SYSTEM_PROMPT = """
You are a strict gatekeeper for a fitness app's body-recomposition image-generation feature.
You receive a user's submitted photo and must decide whether it is acceptable as input to a
visualization that will modify body composition.

ACCEPT a photo only if ALL of the following hold:
  * exactly one person is clearly visible from the front
  * the person's face is visible and identifiable as an adult (>= 18)
  * the torso is visible enough to render body composition meaningfully
  * the photo is non-explicit (clothing acceptable for typical fitness progress photos: shirt + shorts, sports bra + leggings, swimwear OK; full nudity REJECT)
  * the photo is photographic, not heavily edited / cartoon / generated

REJECT in any of these cases:
  * NOT_BODY_PHOTO: not a person, or person's body is not the subject
  * NOT_FRONT_FACING: side / back / extreme angle
  * MULTIPLE_PEOPLE: more than one person in frame
  * MINOR_DETECTED: subject appears under 18
  * NSFW: explicit nudity or sexualized posing
  * TOO_LOW_QUALITY: too dark / blurry / occluded to render
  * FACE_NOT_VISIBLE: face is cropped, masked, or otherwise not visible

If the photo is ACCEPTABLE, also estimate the subject's body-fat percentage from visual cues
(muscle definition, waist, abdomen, arms). Provide:
  * estimatedBodyFatPercent — your best estimate as a number (3 to 60 typical range)
  * estimatedBodyFatConfidence — 0.0 to 1.0
  * estimatedBodyFatNotes — short rationale

If REJECTED, omit body-fat fields or set them to null. Always return well-formed JSON.
""".trimIndent()

        private val USER_PROMPT = """
Decide whether this photo is an acceptable front-facing body photo for body-recomposition
visualization, and (only if acceptable) estimate body-fat percentage. Respond with JSON only,
matching the response schema.
""".trimIndent()
    }
}
