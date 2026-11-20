package com.zenthek.service

import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.OverloadedException
import com.anthropic.errors.PaymentRequiredException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException as AnthropicUnauthorizedException
import com.zenthek.model.ImageAnalysisOutcome
import com.zenthek.model.ImageAnalyzer
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/** Primary (Claude) gets this long before we give up and ask the secondary. */
private const val PRIMARY_TIMEOUT_MS = 20_000L

/**
 * Whole-call budget (primary + secondary). Cloud Run's request timeout is 120s;
 * the remaining ~15s covers body upload, auth, the route's nutrition guard and
 * the response write. On expiry we throw [UpstreamFailureException] (→ 502)
 * instead of letting Cloud Run cut the connection.
 */
private const val TOTAL_BUDGET_MS = 105_000L

/** Credentials / billing / model-id problems: won't fix themselves within a request or two. */
private const val LONG_COOLDOWN_MS = 30 * 60_000L

/** Load shedding (rate limit, overload, slow responses). */
private const val SHORT_COOLDOWN_MS = 60_000L

private const val SPEND_LIMIT_ERROR_CODE = "enforced_spend_limit_reached"

/**
 * Tries [primary] first; on any failure — timeout, API error, unusable output,
 * or a result the nutrition guard would reject — answers the same request with
 * [secondary]. Client disconnects / shutdown (non-timeout cancellation) are
 * rethrown, never retried.
 *
 * A per-instance cooldown skips the primary entirely after failures that would
 * keep repeating (out of credit, bad key, rate limited), so a dead primary costs
 * one probe per instance per cooldown window rather than latency on every scan.
 */
class FallbackImageAnalyzer(
    private val primary: ImageAnalyzer,
    private val secondary: ImageAnalyzer,
) : ImageAnalyzer {
    private val log = LoggerFactory.getLogger(FallbackImageAnalyzer::class.java)
    private val primaryDisabledUntil = AtomicLong(0L)

    override suspend fun analyzeImage(
        imageBytes: ByteArray,
        mealTitle: String?,
        additionalContext: String?,
        locale: String?,
        mimeType: String
    ): ImageAnalysisOutcome {
        val startedAt = System.currentTimeMillis()

        val fallbackReason: String = if (startedAt < primaryDisabledUntil.get()) {
            "cooldown"
        } else {
            try {
                val outcome = withTimeoutOrNull(PRIMARY_TIMEOUT_MS) {
                    primary.analyzeImage(imageBytes, mealTitle, additionalContext, locale, mimeType)
                }
                if (outcome == null) {
                    coolDown(SHORT_COOLDOWN_MS)
                    "timeout"
                } else {
                    // Dry-run the route's guard: a primary answer it would reject is
                    // worth a second opinion. The route re-runs it on whatever we return.
                    ImageAnalysisNutritionGuard.sanitize(outcome.response)
                    return outcome
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                classify(e)
            }
        }

        val primaryLatencyMs = System.currentTimeMillis() - startedAt
        log.warn("[IMAGE-AI] fallback=secondary reason={} primaryLatencyMs={}", fallbackReason, primaryLatencyMs)

        val remainingMs = TOTAL_BUDGET_MS - primaryLatencyMs
        return withTimeoutOrNull(remainingMs) {
            secondary.analyzeImage(imageBytes, mealTitle, additionalContext, locale, mimeType)
        } ?: throw UpstreamFailureException("Image analysis timed out")
    }

    /** Applies the matching cooldown and returns a log-safe reason (no payloads, no keys). */
    private fun classify(e: Exception): String {
        val status = (e as? AnthropicServiceException)?.statusCode()
        when (e) {
            is PaymentRequiredException,
            is AnthropicUnauthorizedException,
            is PermissionDeniedException,
            is NotFoundException -> coolDown(LONG_COOLDOWN_MS)

            is RateLimitException ->
                if (e.errorCode().orElse(null) == SPEND_LIMIT_ERROR_CODE) coolDown(LONG_COOLDOWN_MS)
                else coolDown(SHORT_COOLDOWN_MS)

            is OverloadedException -> coolDown(SHORT_COOLDOWN_MS)
        }
        return buildString {
            append(e.javaClass.simpleName)
            if (status != null) append(" status=").append(status)
            // Guard / ClaudeAnalysisException messages are our own text — safe to log.
            if (e !is AnthropicServiceException) e.message?.let { append(" msg=").append(it.take(200)) }
        }
    }

    private fun coolDown(durationMs: Long) {
        val until = System.currentTimeMillis() + durationMs
        primaryDisabledUntil.updateAndGet { maxOf(it, until) }
    }
}
