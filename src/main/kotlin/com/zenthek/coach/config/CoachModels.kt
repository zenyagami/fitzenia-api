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

    // ── Monthly budget caps (cost-weighted credits) ──────────────────────────
    // Passed as arguments to coach_budget_reserve. Hardcoded constants (same
    // convention as the model IDs above) — a change is a code review + deploy, not
    // a runtime env flip. Sizing rationale + pricing tables: docs/AI_COACH.md.

    /**
     * Monthly cost-weighted credit cap. 1 credit = 1 Lite input token ($0.25/1M),
     * so 2.2M credits ≈ $0.55 ≈ €0.51 worst-case COGS per user per month
     * (~161 typical Lite messages, or ~23 escalated ones).
     */
    const val CAP_CREDITS_PER_MONTH: Long = 2_200_000L

    /** Reduced cap while the entitlement is in its free-trial period (12.5% of monthly). */
    const val CAP_CREDITS_TRIAL: Long = 275_000L

    /**
     * Message-count backstop. Purely an abuse guard — the credit cap binds far
     * earlier for any real workload (2.2M / 8.4k credits ≈ 261 of the very
     * lightest Lite turns, vs 1000 here).
     */
    const val CAP_MESSAGES_PER_MONTH = 1_000

    /** Per-turn output reservation passed to coach_budget_reserve (= Lite maxOutputTokens). */
    const val RESERVED_OUTPUT_TOKENS = 1024

    /** Pro-pass output reservation for `mode=pro` (= the escalation pass maxOutputTokens). */
    const val RESERVED_OUTPUT_TOKENS_PRO = 2048

    // ── Credit weights ───────────────────────────────────────────────────────
    // Mirror of coach_internal.budget_credits() — the SQL function is authoritative
    // (reconcile + stale sweeper); these are only used for the reserve-time estimate,
    // which reconcile corrects. Exact price ratios: Lite $0.25/$1.50, Pro $1.50/$9.00
    // per 1M input/output tokens.
    const val WEIGHT_LITE_INPUT = 1
    const val WEIGHT_LITE_OUTPUT = 6
    const val WEIGHT_PRO_INPUT = 6
    const val WEIGHT_PRO_OUTPUT = 36

    /** `(cap_messages, cap_credits)` for the caller's plan. */
    fun budgetCapsFor(isTrial: Boolean = false): Pair<Int, Long> =
        CAP_MESSAGES_PER_MONTH to (if (isTrial) CAP_CREDITS_TRIAL else CAP_CREDITS_PER_MONTH)

    // ── Top-up packs (RevenueCat consumables) ────────────────────────────────
    // product_id → credits granted. Ids must exactly match the store / RevenueCat
    // product identifiers. Unknown consumable ids in a subscriber snapshot are
    // skipped (logged) so non-coach consumables can ship later without a deploy here.
    // Pricing rationale: docs/AI_COACH.md (≈€5 pack, 67–73% margin).
    val TOPUP_PRODUCT_CREDITS: Map<String, Long> = mapOf(
        "coach_credits_5m" to 5_000_000L,
    )
}
