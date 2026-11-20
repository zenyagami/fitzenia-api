package com.zenthek.upstream.anthropic

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.zenthek.model.ImageAnalysisOutcome
import com.zenthek.model.ImageAnalysisResponse
import com.zenthek.model.ImageAnalyzer
import com.zenthek.model.ImageAnalyzerFactory.IMAGE_ANALYZE_SYSTEM_PROMPT
import com.zenthek.model.ImageAnalyzerFactory.buildImageAnalyzeUserPrompt
import com.zenthek.model.ImageAnalyzerFactory.claudeStrictImageAnalysisResponseSchema
import com.zenthek.model.applyAtwaterFallback
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.util.Base64

/** Claude produced a response we can't use (refusal, truncation, bad JSON). Triggers the Gemini fallback. */
class ClaudeAnalysisException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

private const val MAX_OUTPUT_TOKENS = 16_000L
private val json = Json { ignoreUnknownKeys = true }

/**
 * Meal-photo analysis on Claude (Anthropic Messages API, official Java SDK).
 *
 * Same system prompt, user prompt and response contract as [com.zenthek.upstream.gemini.GeminiApiService];
 * the schema is the Claude-strict variant so structured outputs constrain decoding. Thinking is left at
 * the model default (adaptive). The system prompt is cached with a 1h TTL because scan traffic is bursty.
 *
 * Intended to run behind [com.zenthek.service.FallbackImageAnalyzer], which owns timeouts and fallback —
 * this class just throws on anything unusable.
 */
class ClaudeImageAnalyzer(
    private val client: AnthropicClient,
    private val model: String,
) : ImageAnalyzer {
    private val log = LoggerFactory.getLogger(ClaudeImageAnalyzer::class.java)

    private val systemBlocks = listOf(
        TextBlockParam.builder()
            .text(IMAGE_ANALYZE_SYSTEM_PROMPT)
            .cacheControl(CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.TTL_1H).build())
            .build()
    )

    private val outputConfig: OutputConfig = OutputConfig.builder()
        .effort(OutputConfig.Effort.MEDIUM)
        .format(
            JsonOutputFormat.builder()
                .schema(
                    JsonOutputFormat.Schema.builder()
                        .putAllAdditionalProperties(
                            claudeStrictImageAnalysisResponseSchema().mapValues { (_, v) -> v.toJsonValue() }
                        )
                        .build()
                )
                .build()
        )
        .build()

    override suspend fun analyzeImage(
        imageBytes: ByteArray,
        mealTitle: String?,
        additionalContext: String?,
        locale: String?,
        mimeType: String
    ): ImageAnalysisOutcome {
        val userText = buildImageAnalyzeUserPrompt(
            mealTitle = mealTitle,
            additionalContext = additionalContext,
            locale = locale
        )

        val image = ImageBlockParam.builder()
            .source(
                Base64ImageSource.builder()
                    .mediaType(Base64ImageSource.MediaType.of(mimeType))
                    .data(Base64.getEncoder().encodeToString(imageBytes))
                    .build()
            )
            .build()

        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(MAX_OUTPUT_TOKENS)
            .systemOfTextBlockParams(systemBlocks)
            .outputConfig(outputConfig)
            .addUserMessageOfContentBlockParams(
                listOf(ContentBlockParam.ofImage(image), ContentBlockParam.ofText(userText))
            )
            .build()

        val startedAt = System.currentTimeMillis()
        val message = client.async().messages().create(params).await()
        val latencyMs = System.currentTimeMillis() - startedAt

        val usage = message.usage()
        val stopReason = message.stopReason().orElse(null)
        log.info(
            "[IMAGE-AI] provider=claude model={} latencyMs={} in={} cacheRead={} cacheWrite={} out={} stop={}",
            model,
            latencyMs,
            usage.inputTokens(),
            usage.cacheReadInputTokens().orElse(0L),
            usage.cacheCreationInputTokens().orElse(0L),
            usage.outputTokens(),
            stopReason,
        )

        if (stopReason != StopReason.END_TURN) {
            val category = message.stopDetails().flatMap { it.category() }.map { ":$it" }.orElse("")
            throw ClaudeAnalysisException("stop=$stopReason$category")
        }

        val content = message.content()
            .mapNotNull { block -> block.text().map { it.text() }.orElse(null) }
            .joinToString("")
        if (content.isBlank()) throw ClaudeAnalysisException("empty_text")

        val response = try {
            json.decodeFromString(ImageAnalysisResponse.serializer(), content).applyAtwaterFallback()
        } catch (e: Exception) {
            throw ClaudeAnalysisException("bad_json", e)
        }
        return ImageAnalysisOutcome(response = response, provider = "claude", model = model)
    }
}

private fun JsonElement.toJsonValue(): JsonValue = JsonValue.from(toPlain())

private fun JsonElement.toPlain(): Any? = when (this) {
    is JsonNull -> null
    is JsonObject -> mapValues { (_, v) -> v.toPlain() }
    is JsonArray -> map { it.toPlain() }
    is JsonPrimitive -> when {
        isString -> content
        booleanOrNull != null -> booleanOrNull
        longOrNull != null -> longOrNull
        else -> doubleOrNull
    }
}
