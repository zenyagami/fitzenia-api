package com.zenthek.revenuecat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the RevenueCat → Supabase entitlement sync.
 *
 * All fields are nullable / defaulted because the handler is **defensive, not exhaustive**
 * unknown future event types still parse and still trigger a sync when an identity
 * resolves. We never branch hard on `type` except for the `TEST` short-circuit.
 */

// ── Inbound webhook ────────────────────────────────────────────────────────
// The webhook body is `{"event": {...}, "api_version": "..."}`; the service extracts the
// `event` object manually (it needs the raw JsonElement for idempotent payload storage).

@Serializable
data class RevenueCatEvent(
    val id: String? = null,
    val type: String? = null,
    @SerialName("app_user_id") val appUserId: String? = null,
    @SerialName("original_app_user_id") val originalAppUserId: String? = null,
    val aliases: List<String> = emptyList(),
    // TRANSFER carries these instead of app_user_id; potentially arrays.
    @SerialName("transferred_from") val transferredFrom: List<String> = emptyList(),
    @SerialName("transferred_to") val transferredTo: List<String> = emptyList(),
    // "SANDBOX" | "PRODUCTION" — only consulted for non-TEST syncs.
    val environment: String? = null,
)

// ── RevenueCat REST: GET /v1/subscribers/{app_user_id} ─────────────────────

@Serializable
data class RevenueCatSubscriberResponse(
    val subscriber: RevenueCatSubscriber? = null,
)

@Serializable
data class RevenueCatSubscriber(
    val entitlements: Map<String, RevenueCatEntitlement> = emptyMap(),
    val subscriptions: Map<String, RevenueCatSubscription> = emptyMap(),
    // Consumables (credit top-ups): product_id → every purchase of it, each with a unique id.
    @SerialName("non_subscriptions") val nonSubscriptions: Map<String, List<RevenueCatNonSubscription>> = emptyMap(),
)

@Serializable
data class RevenueCatNonSubscription(
    /** RevenueCat's unique transaction id — the idempotency key for credit grants. */
    val id: String? = null,
    @SerialName("purchase_date") val purchaseDate: String? = null,
    val store: String? = null,
    @SerialName("is_sandbox") val isSandbox: Boolean = false,
)

@Serializable
data class RevenueCatEntitlement(
    @SerialName("expires_date") val expiresDate: String? = null,
    @SerialName("grace_period_expires_date") val gracePeriodExpiresDate: String? = null,
    @SerialName("product_identifier") val productIdentifier: String? = null,
    @SerialName("purchase_date") val purchaseDate: String? = null,
)

@Serializable
data class RevenueCatSubscription(
    val store: String? = null,
    @SerialName("expires_date") val expiresDate: String? = null,
    // "trial" while inside the free-trial window, "normal"/"intro" otherwise (v1 subscriber
    // API uses lowercase — webhook events use uppercase, but we never trust the event body).
    @SerialName("period_type") val periodType: String? = null,
)

// ── Outbound to coach_reconcile_user_entitlements (matches its jsonb shape) ──

/**
 * One element of the `p_entitlements` JSON array passed to
 * `public.coach_reconcile_user_entitlements`. Field names mirror the RECORDSET the
 * SQL function unpacks — do not rename without changing the function.
 */
@Serializable
data class EntitlementReconcileItem(
    @SerialName("entitlement_id") val entitlementId: String,
    val active: Boolean,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("grace_period_ends_at") val gracePeriodEndsAt: String? = null,
    @SerialName("product_id") val productId: String? = null,
    val store: String? = null,
    @SerialName("is_trial") val isTrial: Boolean = false,
)

/**
 * One element of the `p_grants` JSON array passed to `public.coach_grant_credit_topups`.
 * Field names mirror the RECORDSET the SQL function unpacks — do not rename without
 * changing the function. Idempotent on [rcTransactionId] (UNIQUE, ON CONFLICT DO NOTHING).
 */
@Serializable
data class TopUpGrantItem(
    @SerialName("rc_transaction_id") val rcTransactionId: String,
    @SerialName("product_id") val productId: String,
    val store: String? = null,
    val credits: Long,
    @SerialName("purchased_at") val purchasedAt: String? = null,
)

/** One row returned by `public.coach_rc_claim_recoverable_events` (sweeper recall). */
@Serializable
data class ClaimedRevenueCatEventRow(
    @SerialName("event_id") val eventId: String,
    @SerialName("event_type") val eventType: String? = null,
    val payload: RevenueCatEvent? = null,
    @SerialName("identity_candidates") val identityCandidates: List<String> = emptyList(),
    val attempts: Int = 0,
)
