package com.zenthek.upstream.gemini

import com.zenthek.model.ImageAnalysisResponse
import com.zenthek.model.ImageAnalyzer
import com.zenthek.model.ImageAnalyzerFactory.IMAGE_ANALYZE_SYSTEM_PROMPT
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.util.Base64

enum class GeminiThinkingBudget(val tokens: Int) {
    LOW(1024),
    MEDIUM(8192),
    HIGH(24576)
}

private const val GEMINI_MODEL = "gemini-3.1-flash-lite-preview"
private const val CACHE_TTL_SECONDS = 3600L // 1 hour
private val json = Json { ignoreUnknownKeys = true }

class GeminiApiService(
    private val client: HttpClient,
    private val apiKey: String
) : ImageAnalyzer {
    private val log = LoggerFactory.getLogger(GeminiApiService::class.java)
    private val cacheMutex = Mutex()
    private var cachedContentName: String? = null
    private var cacheExpiresAt: Long = 0L

    private suspend fun getOrCreateCache(): String = cacheMutex.withLock {
        val now = System.currentTimeMillis()
        val existing = cachedContentName
        if (existing != null && now < cacheExpiresAt) return@withLock existing

        log.info("Creating Gemini context cache for system prompt")
        val response = client.post(
            "https://generativelanguage.googleapis.com/v1beta/cachedContents?key=$apiKey"
        ) {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("model", "models/$GEMINI_MODEL")
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") { addJsonObject { put("text", IMAGE_ANALYZE_SYSTEM_PROMPT) } }
                }
                put("ttl", "${CACHE_TTL_SECONDS}s")
            })
        }

        val responseText = response.bodyAsText()
        val name = try {
            json.parseToJsonElement(responseText).jsonObject["name"]?.jsonPrimitive?.content
                ?: error("Missing 'name' in cache response: $responseText")
        } catch (e: Exception) {
            log.error("Failed to create Gemini context cache: ${e.message}. Response: $responseText")
            throw e
        }

        cachedContentName = name
        cacheExpiresAt = now + (CACHE_TTL_SECONDS - 60) * 1000L // refresh 1 min before expiry
        log.info("Gemini context cache created: $name, expires in ${CACHE_TTL_SECONDS}s")
        name
    }

    override suspend fun analyzeImage(
        imageBytes: ByteArray,
        mealTitle: String?,
        additionalContext: String?,
        locale: String?,
        mimeType: String
    ): ImageAnalysisResponse {
        log.info("analyzeImage called via Gemini [$GEMINI_MODEL], mimeType=$mimeType, mealTitle=$mealTitle")

        val base64Image = Base64.getEncoder().encodeToString(imageBytes)

        val userText = buildString {
            if (!locale.isNullOrBlank()) append("Locale: $locale — return all text fields in the language of this locale.\n")
            if (!mealTitle.isNullOrBlank()) append("The user described this meal as: \"$mealTitle\"\n")
            if (!additionalContext.isNullOrBlank()) append("Additional context: \"$additionalContext\"\n")
            append("Analyze the food in this image.")
        }

        val cacheName = getOrCreateCache()

        val requestBody = buildJsonObject {
            put("cachedContent", cacheName)
            putJsonArray("contents") {
                addJsonObject {
                    putJsonArray("parts") {
                        addJsonObject { put("text", userText) }
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
                put("maxOutputTokens", 3000)
                put("responseMimeType", "application/json")
                putJsonObject("thinkingConfig") {
                    put("thinkingBudget", GeminiThinkingBudget.MEDIUM.tokens)
                }
            }
        }

        log.info("Calling Gemini generateContent for model=$GEMINI_MODEL")
        val response = client.post(
            "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent?key=$apiKey"
        ) {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
            timeout { requestTimeoutMillis = 90_000 }
        }
        log.info("Gemini generateContent response status=${response.status}")

        val responseText = response.bodyAsText()
        //log.debug("Gemini raw response: $responseText")
        val content = try {
            json.parseToJsonElement(responseText).jsonObject["candidates"]
                ?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("content")
                ?.jsonObject?.get("parts")
                ?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("text")
                ?.jsonPrimitive?.content
                ?: error("Unexpected Gemini response structure: $responseText")
        } catch (e: Exception) {
            log.error("Failed to extract content from Gemini response: ${e.message}. Raw response: $responseText")
            throw e
        }

        return try {
            json.decodeFromString(ImageAnalysisResponse.serializer(), content)
        } catch (e: Exception) {
            log.error("Failed to deserialize Gemini content: ${e.message}. Content: $content")
            throw e
        }
    }
}
