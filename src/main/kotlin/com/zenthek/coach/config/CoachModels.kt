package com.zenthek.coach.config

/**
 * AI Coach model + behavior constants.
 *
 * These are **not secrets** and they are intentionally **not env vars**. They
 * follow the same convention as `AiProgressProjectionConfig` (hardcoded in
 * `loadAiProgressProjectionConfig()`): model IDs are coupled to the system
 * prompt version and the conversation cache key, so a change must go through
 * code review + deploy — not a silent runtime env flip.
 *
 * Only genuine secrets (`GEMINI_API_KEY`, `SUPABASE_*`) stay in env/Secret Manager.
 */
object CoachModels {
    /** Primary chat model (classify + generate). */
    const val PRIMARY = "gemini-3.1-flash-lite"

    /** Escalation model for complex reasoning / truncation retries. */
    // Gemini 3.5 Flash.
    const val ESCALATION = "gemini-3.5-flash"

    /** IANA tz used when the client omits `userTz` for tool date resolution. */
    const val USER_TZ_FALLBACK = "UTC"

    // ── Monthly budget caps ──────────────────────────────────────────────────
    // Passed as arguments to coach_budget_reserve. Hardcoded constants (same
    // convention as the model IDs above) — a change is a code review + deploy, not
    // a runtime env flip.

    /** Hard monthly message cap for the launch model. */
    const val CAP_MESSAGES_PER_MONTH = 380

    /**
     * Secondary monthly token ceiling. The spec pins the message cap but leaves the
     * token cap as an unspecified backstop, so this is derived to bind *after* the
     * message cap under the hard per-turn limits (input ≤ 20k rejected pre-LLM,
     * output ≤ 1024): 400 × (20_000 + 1_024) = 8_409_600. It only fires if a turn
     * somehow slips the per-turn input cap, never on normal usage.
     */
    const val CAP_TOKENS_PER_MONTH: Long = 8_409_600L

    /** Per-turn output reservation passed to coach_budget_reserve (= maxOutputTokens). */
    const val RESERVED_OUTPUT_TOKENS = 1024

    /** Boot-time `model → (cap_messages, cap_tokens)` lookup. */
    fun budgetCapsFor(@Suppress("UNUSED_PARAMETER") model: String): Pair<Int, Long> =
        CAP_MESSAGES_PER_MONTH to CAP_TOKENS_PER_MONTH
}
