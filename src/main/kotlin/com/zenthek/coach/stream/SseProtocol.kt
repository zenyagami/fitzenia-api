package com.zenthek.coach.stream

import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.serialization.Serializable

@Serializable
data class ChatCreatedPayload(val chatId: String, val title: String)

@Serializable
data class TokenPayload(val delta: String)

@Serializable
data class TokenUsage(val input: Int, val output: Int, val cached: Int)

@Serializable
data class DonePayload(
    val chatId: String,
    val messageId: String,
    val tokens: TokenUsage,
    val model: String,
    val escalated: Boolean,
    /** Model selector the turn ran with: auto | fast | pro. */
    val mode: String = "auto",
)

@Serializable
data class SseErrorPayload(val code: String, val message: String)

/**
 * Emitted as an `error` SSE event when the monthly budget reservation is denied.
 * [plan] lets the client pick the right CTA: `trial` → "subscribe to unlock the full
 * allowance" (upgrading lifts the cap immediately); `premium` → wait for [resetAt]
 * (or buy a credit top-up once packs are purchasable in the app).
 */
@Serializable
data class BudgetExceededPayload(
    val code: String,
    val resetAt: String,
    val message: String,
    val plan: String = "premium",
)

@Serializable
data class SafetyPayload(val action: String, val message: String)

@Serializable
data class CitationPayload(val chunkId: String, val source: String, val score: Double)

@Serializable
data class ToolStartPayload(val name: String)

@Serializable
data class ToolDonePayload(val name: String, val ms: Long)

@Serializable
data class TitlePayload(val chatId: String, val title: String)

suspend fun ByteWriteChannel.sendSseEvent(event: String, data: String) {
    writeFully("event: $event\ndata: $data\n\n".toByteArray(Charsets.UTF_8))
    flush()
}

suspend fun ByteWriteChannel.sendPing() {
    writeFully(": ping\n\n".toByteArray(Charsets.UTF_8))
    flush()
}
