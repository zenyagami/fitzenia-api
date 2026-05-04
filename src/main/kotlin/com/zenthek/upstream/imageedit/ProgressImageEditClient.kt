package com.zenthek.upstream.imageedit

/**
 * Provider-neutral contract for the projection-rung image generator. Implementations call
 * either OpenAI's `/v1/images/edits` (gpt-image-2) or Gemini's `:generateContent` with
 * `responseModalities=["IMAGE"]` (nano banana). The active implementation is selected at
 * startup from `AiProgressProjectionConfig.provider`.
 */
interface ProgressImageEditClient {
    data class Result(
        val bytes: ByteArray,
        val usageInputTokens: Int?,
        val usageOutputTokens: Int?,
        val usageCachedInputTokens: Int?,
    )

    suspend fun edit(
        sourceBytes: ByteArray,
        sourceMimeType: String,
        sourceFilename: String,
        prompt: String,
        userId: String,
    ): Result
}

/**
 * Provider-neutral signal that the upstream safety classifier rejected the request
 * (OpenAI moderation_blocked / Gemini SAFETY finishReason / etc). The orchestrator catches
 * this distinct from a generic upstream failure to surface a tailored MODERATION_BLOCKED
 * SSE error code.
 */
class ImageEditModerationException(message: String) : RuntimeException(message)
