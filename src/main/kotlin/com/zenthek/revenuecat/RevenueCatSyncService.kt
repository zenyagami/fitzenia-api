package com.zenthek.revenuecat

import com.zenthek.coach.config.CoachModels
import com.zenthek.config.AppEnvironment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Instant

/** How the webhook route should ack RevenueCat. */
enum class WebhookAck { OK, UNAUTHORIZED, BAD_REQUEST, RETRY }

/**
 * Maps this deployment's [AppEnvironment] to the RC-environment string [RevenueCatSyncService.syncUserById]
 * expects for its on-demand (non-webhook) callers. Deliberately deployment-scoped, not
 * request-scoped: a client can never influence which value this resolves to, so a
 * DEVELOPMENT/staging deployment self-heals sandbox top-ups on-demand while a PRODUCTION
 * deployment keeps assuming real (non-sandbox) purchases for its anti-fraud guard.
 */
fun AppEnvironment.toRevenueCatSyncEnvironment(): String = if (isDebug()) "SANDBOX" else "PRODUCTION"

/**
 * Shared RevenueCat → Supabase entitlement sync. One service, two callers:
 *
 *  - the `POST /webhooks/revenuecat` route ([handleWebhook]) — auth → claim → branch;
 *  - the stale-claim sweeper Job ([runClaimedEvent]) — replays already-claimed rows.
 *
 * The rule is **identity-driven, never a hard switch on event-type**: any non-`TEST`
 * event with a resolvable Supabase identity triggers a full subscriber-state reconcile against
 * `GET /v1/subscribers/{id}` (the event body's entitlement state is never trusted — webhooks
 * arrive out of order). Identity model (confirmed): the RevenueCat `app_user_id` IS the Supabase
 * `auth.users.id` (the app calls `Purchases.logIn(supabaseUserId)`).
 */
class RevenueCatSyncService(
    private val gateway: RevenueCatEntitlementGateway,
    private val rest: RevenueCatRestClient,
    // Only the webhook path ([handleWebhook]) needs this. Callers that only use [syncUserById]
    // (the coach PremiumGate's lazy sync-on-miss) construct the service without it.
    private val webhookAuth: String = "",
    // Deployment-scoped, constructor-level — never derived from a per-call/event value. Gates the
    // sandbox top-up anti-fraud check in [mapTopUpGrants] the same way for every caller (webhook,
    // sweeper, or on-demand pull): a sandbox-flagged purchase never earns full value on a PRODUCTION
    // deployment, no matter which environment string an individual call or RC event reports.
    private val isProductionDeployment: Boolean = false,
) {
    private val log = LoggerFactory.getLogger(RevenueCatSyncService::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ── Route entry point ──────────────────────────────────────────────────

    /** Total (never throws): validates auth, claims, and dispatches. Returns the HTTP ack to send. */
    suspend fun handleWebhook(authHeader: String?, rawBody: String): WebhookAck {
        if (!authMatches(authHeader)) {
            log.warn("[COACH-RC] webhook auth mismatch")
            return WebhookAck.UNAUTHORIZED
        }
        val parsed = parseEvent(rawBody) ?: return WebhookAck.BAD_REQUEST
        val (event, eventJson) = parsed
        val eventId = event.id?.takeIf { it.isNotBlank() } ?: return WebhookAck.BAD_REQUEST
        val eventType = event.type?.takeIf { it.isNotBlank() } ?: return WebhookAck.BAD_REQUEST

        val candidates = collectIdentityCandidates(event)
        val claim = try {
            gateway.claimEvent(eventId, eventType, eventJson, candidates)
        } catch (e: Exception) {
            log.error("[COACH-RC] claim error eventId={}: {}", eventId, e.message)
            return WebhookAck.RETRY
        }

        return when (claim) {
            // Another worker owns it / it's a duplicate after success: ack and do nothing.
            "already_processing", "already_processed" -> {
                log.info("[COACH-RC] eventId={} claim={} (no-op)", eventId, claim)
                WebhookAck.OK
            }
            "inserted", "retry_failed" -> if (runClaimedEvent(event)) WebhookAck.OK else WebhookAck.RETRY
            else -> {
                log.warn("[COACH-RC] eventId={} unexpected claim result={}", eventId, claim)
                WebhookAck.OK
            }
        }
    }

    // ── Shared post-claim flow (route + sweeper) ───────────────────────────

    /**
     * Run sync for an event whose idempotency row is already in `processing`. Marks the row
     * processed/failed itself. Returns `true` when the event is settled (ack 200) and `false`
     * on a genuine sync failure (the route should ack 5xx so RevenueCat retries).
     */
    suspend fun runClaimedEvent(event: RevenueCatEvent): Boolean {
        val eventId = event.id?.takeIf { it.isNotBlank() } ?: run {
            log.warn("[COACH-RC] claimed event has no id; dropping")
            return true
        }
        return try {
            if (event.type?.trim()?.uppercase() == "TEST") {
                gateway.markProcessed(eventId, "test_event")
                log.info("[COACH-RC] eventId={} type=TEST short-circuit", eventId)
                return true
            }

            val userIds = resolveUsers(event)
            if (userIds.isEmpty()) {
                log.warn("[COACH-RC] coach.rc.unknown_app_user_id eventId={} no resolvable identity", eventId)
                gateway.markProcessed(eventId, "no_resolvable_identity")
                return true
            }

            val environment = normalizeEnvironment(event.environment)
            for (userId in userIds) {
                syncUser(userId, environment)
            }
            gateway.markProcessed(eventId, null)
            log.info("[COACH-RC] eventId={} type={} reconciled users={}", eventId, event.type, userIds.size)
            true
        } catch (e: Exception) {
            log.error("[COACH-RC] eventId={} sync failed: {}", eventId, e.message)
            runCatching { gateway.markFailed(eventId, e.message ?: "sync error") }
            false
        }
    }

    /**
     * On-demand single-user reconcile for lazy sync-on-miss. Fetches the live RC subscriber
     * snapshot for [userId] (== `app_user_id`) and reconciles `user_entitlement`. Used by the coach
     * `PremiumGate` to backfill existing subscribers who never fired a fresh webhook. Throws on an
     * RC/Supabase failure so the caller can decide whether to grant or deny.
     */
    suspend fun syncUserById(userId: String, environment: String = "PRODUCTION") =
        syncUser(userId, environment)

    private suspend fun syncUser(userId: String, environment: String) {
        // app_user_id == userId, so the same id fetches the RC subscriber and keys reconcile.
        val subscriber = rest.fetchSubscriber(userId)
        val items = mapEntitlements(subscriber)
        gateway.reconcile(userId, rcAppUserId = userId, environment = environment, entitlements = items)
        // Credit top-ups (consumables) ride the same snapshot: idempotent on the RC
        // transaction id, so webhook replays and sweeper re-syncs never double-grant.
        val grants = mapTopUpGrants(subscriber)
        if (grants.isNotEmpty()) {
            val inserted = gateway.grantTopUps(userId, environment, grants)
            if (inserted > 0) {
                log.info("[COACH-RC] topup_granted userId={} newPacks={}", userId, inserted)
            }
        }

        // A PRODUCTION deployment never full-grants a sandbox-flagged purchase (mapTopUpGrants
        // already dropped it above) — regardless of transport (webhook, sweeper replay, or on-demand
        // pull) or what environment string that particular call happens to report; a delivered
        // webhook reports the EVENT's environment, which can say SANDBOX even while this deployment
        // is PRODUCTION. Instead it earns a small bounded "proves the purchase unlocks something"
        // grant, capped once per user lifetime, so a repeatable $0 sandbox purchase (App Store
        // Review, a TestFlight external tester, or ad-hoc QA — none with a knowable user id in
        // advance) can never be farmed for real value.
        if (isProductionDeployment) {
            val testTierGrants = sandboxTestTierGrants(subscriber, userId)
            if (testTierGrants.isNotEmpty()) {
                val inserted = gateway.grantTopUps(userId, "SANDBOX", testTierGrants)
                if (inserted > 0) {
                    log.info("[COACH-RC] sandbox_test_tier_granted userId={} newPacks={}", userId, inserted)
                }
            }
        }
    }

    /**
     * Known coach credit packs from the subscriber's consumables. Gated on the *deployment*
     * ([isProductionDeployment]), never the per-call `environment` string (that string is
     * event-driven on the webhook path and deployment-driven on the pull path — conflating them
     * let a delivered sandbox webhook full-grant on prod). PRODUCTION only full-grants genuinely
     * non-sandbox purchases here; any other deployment (dev/staging) full-grants the sandbox
     * purchases it's expected to only ever see. A sandbox purchase reaching PRODUCTION is instead
     * handled by [sandboxTestTierGrants]. Unknown consumable product ids are skipped — they may be
     * future non-coach products.
     */
    private fun mapTopUpGrants(subscriber: RevenueCatSubscriber): List<TopUpGrantItem> {
        return subscriber.nonSubscriptions.flatMap { (productId, purchases) ->
            val credits = CoachModels.TOPUP_PRODUCT_CREDITS[productId]
            if (credits == null) {
                log.debug("[COACH-RC] unknown consumable productId={} skipped", productId)
                return@flatMap emptyList()
            }
            purchases.mapNotNull { purchase ->
                val txnId = purchase.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (purchase.isSandbox == isProductionDeployment) return@mapNotNull null
                TopUpGrantItem(
                    rcTransactionId = txnId,
                    productId = productId,
                    store = purchase.store,
                    credits = credits,
                    purchasedAt = purchase.purchaseDate,
                )
            }
        }
    }

    /**
     * Bounded grant for genuine sandbox purchases synced under a PRODUCTION deployment. Grants
     * [SANDBOX_TEST_TIER_GRANT_CREDITS] per sandbox purchase up to
     * [SANDBOX_TEST_TIER_LIFETIME_CAP_CREDITS] across the user's entire lifetime (every sandbox
     * purchase they ever make) — always stored tagged `environment=SANDBOX` regardless of the
     * deployment's sync environment, both for accounting clarity and because the cap check below
     * sums exactly that tag.
     */
    private suspend fun sandboxTestTierGrants(subscriber: RevenueCatSubscriber, userId: String): List<TopUpGrantItem> {
        val sandboxPurchases = subscriber.nonSubscriptions.values.flatten()
            .filter { it.isSandbox }
            .sortedBy { it.purchaseDate ?: "" }
        if (sandboxPurchases.isEmpty()) return emptyList()

        var remaining = SANDBOX_TEST_TIER_LIFETIME_CAP_CREDITS - gateway.sandboxTopupCreditsGranted(userId)
        if (remaining <= 0) return emptyList()

        val grants = mutableListOf<TopUpGrantItem>()
        for (purchase in sandboxPurchases) {
            if (remaining <= 0) break
            val txnId = purchase.id?.takeIf { it.isNotBlank() } ?: continue
            val credits = minOf(SANDBOX_TEST_TIER_GRANT_CREDITS, remaining)
            grants += TopUpGrantItem(
                rcTransactionId = txnId,
                productId = "sandbox_test_tier",
                store = purchase.store,
                credits = credits,
                purchasedAt = purchase.purchaseDate,
            )
            remaining -= credits
        }
        return grants
    }

    /** Resolve every UUID-shaped candidate against `auth.users`; transient lookup errors throw. */
    private suspend fun resolveUsers(event: RevenueCatEvent): Set<String> {
        val resolved = linkedSetOf<String>()
        for (candidate in collectIdentityCandidates(event)) {
            if (!UUID_REGEX.matches(candidate)) continue
            when (gateway.resolveUser(candidate)) {
                UserResolution.EXISTS -> resolved.add(candidate)
                UserResolution.NOT_FOUND ->
                    log.warn("[COACH-RC] coach.rc.unknown_app_user_id candidate has no auth user")
            }
        }
        return resolved
    }

    private fun mapEntitlements(subscriber: RevenueCatSubscriber): List<EntitlementReconcileItem> {
        val now = Instant.now()
        return subscriber.entitlements.map { (entitlementId, ent) ->
            val subscription = ent.productIdentifier?.let { subscriber.subscriptions[it] }
            EntitlementReconcileItem(
                entitlementId = entitlementId,
                active = isActive(ent.expiresDate, ent.gracePeriodExpiresDate, now),
                expiresAt = ent.expiresDate,
                gracePeriodEndsAt = ent.gracePeriodExpiresDate,
                productId = ent.productIdentifier,
                store = subscription?.store,
                isTrial = subscription?.periodType.equals("trial", ignoreCase = true),
            )
        }
    }

    /** Active = no expiry (lifetime), or expiry/grace still in the future. Unparseable expiry fails open. */
    private fun isActive(expiresDate: String?, graceDate: String?, now: Instant): Boolean {
        if (expiresDate == null) return true
        val expires = parseInstant(expiresDate)
        if (expires == null) {
            log.warn("[COACH-RC] unparseable expires_date; keeping entitlement active")
            return true
        }
        if (expires.isAfter(now)) return true
        val grace = graceDate?.let { parseInstant(it) }
        return grace != null && grace.isAfter(now)
    }

    private fun parseInstant(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()

    private fun normalizeEnvironment(env: String?): String =
        if (env?.trim()?.uppercase() == "SANDBOX") "SANDBOX" else "PRODUCTION"

    /** All ids the event references, trimmed + de-duplicated, order-preserving. */
    private fun collectIdentityCandidates(event: RevenueCatEvent): List<String> =
        buildList {
            event.appUserId?.let { add(it) }
            event.originalAppUserId?.let { add(it) }
            addAll(event.aliases)
            addAll(event.transferredFrom)
            addAll(event.transferredTo)
        }.map { it.trim() }.filter { it.isNotBlank() }.distinct()

    private fun parseEvent(rawBody: String): Pair<RevenueCatEvent, JsonElement>? {
        val root = runCatching { Json.parseToJsonElement(rawBody) }.getOrNull() as? JsonObject ?: return null
        val eventElem = root["event"] as? JsonObject ?: return null
        val event = runCatching {
            json.decodeFromJsonElement(RevenueCatEvent.serializer(), eventElem)
        }.getOrNull() ?: return null
        return event to eventElem
    }

    /** Constant-time comparison of the inbound `Authorization` header against the configured secret. */
    private fun authMatches(provided: String?): Boolean {
        if (provided == null) return false
        return MessageDigest.isEqual(
            provided.toByteArray(Charsets.UTF_8),
            webhookAuth.toByteArray(Charsets.UTF_8),
        )
    }

    private companion object {
        val UUID_REGEX =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        /**
         * Credits granted per individual sandbox purchase on a PRODUCTION deployment (~3 Lite turns).
         * Small on purpose: enough for App Review / TestFlight / QA to see a purchase visibly deliver
         * value, negligible on its own as an abuse vector.
         */
        const val SANDBOX_TEST_TIER_GRANT_CREDITS = 50_000L

        /**
         * Lifetime cap (across every sandbox purchase a user ever makes) for the bounded sandbox
         * test-tier grant on a PRODUCTION deployment. Kept above [SANDBOX_TEST_TIER_GRANT_CREDITS] so
         * that a reviewer's *repeat* sandbox purchases each keep delivering (~6 purchases × ~3 turns) —
         * App Review commonly buys a consumable more than once, and a second purchase silently
         * granting nothing reads as "paid, got nothing" and fails review. Still ~14% of the monthly
         * cap (2.2M) total and sandbox-only, so it's negligible as an abuse vector. Tune via code
         * review + deploy.
         */
        const val SANDBOX_TEST_TIER_LIFETIME_CAP_CREDITS = 300_000L
    }
}
