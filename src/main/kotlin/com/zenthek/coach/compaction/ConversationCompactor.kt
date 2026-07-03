package com.zenthek.coach.compaction

import com.zenthek.coach.agent.CoachAgentFactory
import com.zenthek.coach.persistence.ChatGateway
import com.zenthek.coach.persistence.HistoryMessageRow
import org.slf4j.LoggerFactory

private const val COMPACT_THRESHOLD = 8_000
private const val KEEP_LAST = 10

data class PreparedHistory(
    val history: List<HistoryMessageRow>,
    val summaryContext: String?,
)

class ConversationCompactor(
    private val agentFactory: CoachAgentFactory,
    private val chatGateway: ChatGateway,
) {
    private val log = LoggerFactory.getLogger(ConversationCompactor::class.java)

    suspend fun prepareHistory(
        chatId: String,
        userId: String,
        rawHistory: List<HistoryMessageRow>,
    ): PreparedHistory {
        val existingSummary = runCatching { chatGateway.getLatestSummary(chatId) }
            .onFailure { e -> log.warn("[COMPACT] getLatestSummary failed chatId={}", chatId, e) }
            .getOrNull()

        val totalChars = rawHistory.sumOf { it.content.length }

        if (totalChars <= COMPACT_THRESHOLD || rawHistory.size <= KEEP_LAST) {
            return PreparedHistory(rawHistory, existingSummary?.summary)
        }

        val toKeep = rawHistory.takeLast(KEEP_LAST)
        val toSummarize = rawHistory.dropLast(KEEP_LAST)
        val upToMessageId = toSummarize.last().id

        val summaryText = if (existingSummary?.upToMessageId == upToMessageId) {
            existingSummary.summary
        } else {
            runCatching {
                val (text, tokens) = agentFactory.generateSummary(toSummarize, existingSummary?.summary)
                runCatching { chatGateway.insertSummary(chatId, userId, upToMessageId, text, tokens) }
                    .onFailure { e -> log.warn("[COMPACT] insertSummary failed chatId={}", chatId, e) }
                text
            }.onFailure { e ->
                log.warn("[COMPACT] generateSummary failed chatId={}", chatId, e)
            }.getOrElse { existingSummary?.summary ?: return PreparedHistory(rawHistory, null) }
        }

        log.debug("[COMPACT] compacted chatId={} kept={} summarized={}", chatId, toKeep.size, toSummarize.size)
        return PreparedHistory(toKeep, summaryText)
    }
}
