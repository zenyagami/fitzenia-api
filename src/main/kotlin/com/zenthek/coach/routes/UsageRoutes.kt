package com.zenthek.coach.routes

import com.zenthek.auth.SUPABASE_AUTH_PROVIDER
import com.zenthek.auth.requireAuthenticatedUser
import com.zenthek.coach.auth.CoachPlan
import com.zenthek.coach.auth.PremiumGate
import com.zenthek.coach.config.CoachModels
import com.zenthek.coach.persistence.BudgetGateway
import com.zenthek.coach.persistence.BudgetUsageRow
import com.zenthek.revenuecat.RevenueCatSyncService
import com.zenthek.routes.RateLimitNames
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val usageLog = LoggerFactory.getLogger("com.zenthek.coach.routes.UsageRoutes")

/**
 * Monthly usage snapshot for the client's usage bars (Claude-style):
 *
 *   All models  ████████░░░░░░░  42%
 *   Pro         ██░░░░░░░░░░░░░   9%
 *
 * `total` is the monthly credit pot; `pro.usedCredits` is the monotonic share of
 * spend attributable to the Pro model (display-only — there is no separate Pro cap).
 * `topUp.remaining` counts purchased credits, drawn only after the monthly pot.
 */
@Serializable
data class UsageBucket(val used: Long, val limit: Long, val percent: Int)

@Serializable
data class ProUsage(val usedCredits: Long, val percentOfLimit: Int)

@Serializable
data class TopUpUsage(
    /** Total purchased credits across non-expired packs (drained packs included). */
    val granted: Long,
    /** Purchased credits still drawable. Used = granted − remaining. */
    val remaining: Long,
)

@Serializable
data class CoachUsageResponse(
    val period: Int,
    val resetAt: String,
    val plan: String,
    val total: UsageBucket,
    val pro: ProUsage,
    val topUp: TopUpUsage,
    val messagesUsed: Int,
)

fun Application.configureUsageRouting(
    budgetGateway: BudgetGateway,
    premiumGate: PremiumGate,
    // Present only when REVENUECAT_REST_API_KEY is configured (same gate as lazy sync-on-miss).
    revenueCatSync: RevenueCatSyncService? = null,
) {
    routing {
        authenticate(SUPABASE_AUTH_PROVIDER) {
            rateLimit(RateLimitName(RateLimitNames.COACH_MANAGEMENT)) {
                get("/api/coach/usage") {
                    val plan = premiumGate.requirePremium(call)
                    val user = call.requireAuthenticatedUser()
                    val period = BudgetPeriod.currentYyyymm()
                    val usage = budgetGateway.usage(user.userId, period)
                    call.respond(HttpStatusCode.OK, buildUsageResponse(plan, period, usage))
                }

                // Force-sync the caller's RevenueCat subscriber, then return fresh usage.
                // This is the "restore purchases" safety net: it reconciles entitlements AND grants
                // any credit top-ups the webhook missed (idempotent on rc_transaction_id). The client
                // should call it right after a purchase completes and on coach-screen open, so a
                // dropped webhook never leaves a paying user without their credits — premium users
                // never trigger lazy-sync-on-miss (they don't miss the gate), so without this their
                // top-ups depend entirely on the webhook.
                post("/api/coach/purchases/sync") {
                    val user = call.requireAuthenticatedUser()
                    if (revenueCatSync == null) {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "SYNC_UNAVAILABLE", "message" to "Purchase sync is not configured"),
                        )
                        return@post
                    }
                    // PRODUCTION env: real users make production purchases; sandbox purchases are free
                    // and must never grant real credits in prod (the grant path is env-gated).
                    val synced = runCatching { revenueCatSync.syncUserById(user.userId) }
                        .onFailure { e ->
                            usageLog.error("[COACH-SYNC] purchase sync failed userId={} error={}", user.userId, e.message)
                        }
                        .isSuccess
                    if (!synced) {
                        call.respond(
                            HttpStatusCode.BadGateway,
                            mapOf("error" to "SYNC_FAILED", "message" to "Could not reach the store; try again"),
                        )
                        return@post
                    }
                    // requirePremium re-reads the just-synced entitlement (grants already persisted).
                    val plan = premiumGate.requirePremium(call)
                    val period = BudgetPeriod.currentYyyymm()
                    val usage = budgetGateway.usage(user.userId, period)
                    call.respond(HttpStatusCode.OK, buildUsageResponse(plan, period, usage))
                }
            }
        }
    }
}

private fun buildUsageResponse(plan: CoachPlan, period: Int, usage: BudgetUsageRow): CoachUsageResponse {
    val (_, capCredits) = CoachModels.budgetCapsFor(isTrial = plan == CoachPlan.TRIAL)
    return CoachUsageResponse(
        period = period,
        resetAt = BudgetPeriod.nextResetIso(),
        plan = plan.wire,
        total = UsageBucket(
            used = usage.creditsUsed,
            limit = capCredits,
            percent = percentOf(usage.creditsUsed, capCredits),
        ),
        pro = ProUsage(
            usedCredits = usage.proCreditsUsed,
            percentOfLimit = percentOf(usage.proCreditsUsed, capCredits),
        ),
        topUp = TopUpUsage(granted = usage.topupGranted, remaining = usage.topupRemaining),
        messagesUsed = usage.messagesUsed,
    )
}

/** Integer percent clamped to 0..100 (reconcile overshoot can push used past the cap). */
private fun percentOf(used: Long, limit: Long): Int =
    if (limit <= 0) 0 else ((used.coerceAtLeast(0) * 100) / limit).toInt().coerceIn(0, 100)
