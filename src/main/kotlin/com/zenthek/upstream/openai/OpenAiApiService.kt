package com.zenthek.upstream.openai

import com.zenthek.model.ImageAnalysisResponse
import com.zenthek.model.ImageAnalyzer
import com.zenthek.model.ImageAnalyzerFactory.IMAGE_ANALYZE_SYSTEM_PROMPT
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import java.util.*


private val json = Json { ignoreUnknownKeys = true }

class OpenAiApiService(
    private val client: HttpClient,
    private val apiKey: String
) : ImageAnalyzer {
    private fun buildImageRequest(
        imageBytes: ByteArray,
        mealTitle: String?,
        additionalContext: String?,
        locale: String?,
        mimeType: String
    ): Pair<String, String> {
        val dataUrl = "data:$mimeType;base64,${Base64.getEncoder().encodeToString(imageBytes)}"
        val userText = buildString {
            if (!locale.isNullOrBlank()) append("Locale: $locale — return all text fields in the language of this locale.\n")
            if (!mealTitle.isNullOrBlank()) append("The user described this meal as: \"$mealTitle\"\n")
            if (!additionalContext.isNullOrBlank()) append("Additional context: \"$additionalContext\"\n")
            append("Analyze the food in this image.")
        }
        return dataUrl to userText
    }

    override suspend fun analyzeImage(
        imageBytes: ByteArray,
        mealTitle: String?,
        additionalContext: String?,
        locale: String?,
        mimeType: String
    ): ImageAnalysisResponse {
        val (dataUrl, userText) = buildImageRequest(imageBytes, mealTitle, additionalContext, locale, mimeType)

        val requestBody = buildJsonObject {
            put("model", "gpt-5-mini")
            put("max_output_tokens", 6000)
            putJsonObject("reasoning") { put("effort", "low") }
            put("instructions", IMAGE_ANALYZE_SYSTEM_PROMPT)
            putJsonArray("input") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "input_text")
                            put("text", userText)
                        }
                        addJsonObject {
                            put("type", "input_image")
                            put("image_url", dataUrl)
                        }
                    }
                }
            }
        }

        val response = client.post("https://api.openai.com/v1/responses") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
            timeout { requestTimeoutMillis = 120_000 }
        }

        val responseText = response.bodyAsText()
        val responseJson = json.parseToJsonElement(responseText).jsonObject

        // Responses API: output is an array; find the message item and extract output_text
        val content = responseJson["output"]
            ?.jsonArray
            ?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "message" }
            ?.jsonObject?.get("content")
            ?.jsonArray
            ?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "output_text" }
            ?.jsonObject?.get("text")
            ?.jsonPrimitive?.content
            ?: error("Unexpected OpenAI response structure: $responseText")

        return json.decodeFromString(ImageAnalysisResponse.serializer(), content)
    }
}
