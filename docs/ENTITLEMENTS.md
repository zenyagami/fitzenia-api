# Entitlements — backend-wide premium system

> **Scope.** This doc owns the entitlement contract for the whole backend. It was born with the AI Coach
> RevenueCat sync (see [`AI_COACH.md`](AI_COACH.md) → Entitlements),
> but `public.user_entitlement` is **not coach-specific**: any current or future feature (coach,
> client/web premium surfaces, per-feature gates) reads the same table, and any billing provider
> (RevenueCat today, an own implementation later) writes it. The provider abstraction is **the
> table, not a Kotlin interface** — readers never know who wrote a row.

---

## 1. The contract: `public.user_entitlement`

One row per `(user_id, entitlement_id)` (composite PK). Defined in
`db/migrations/005_coach_baseline.sql` (the consolidated coach schema reference; the
`source` column and `revenuecat_app_user_id` / `revenuecat_environment` are folded in there).

| Column | Type | Meaning |
|---|---|---|
| `user_id` | uuid FK → `auth.users` (CASCADE) | The Supabase user |
| `entitlement_id` | text | e.g. `premium` — what the gate checks |
| `active` | boolean | **The only column access control reads** |
| `expires_at` / `grace_period_ends_at` | timestamptz? | Informational (writer already folded them into `active`) |
| `product_id` / `store` | text? | Provider-agnostic purchase metadata |
| `source` | text, NOT NULL, default `'revenuecat'` | **Writer that owns the row**: `revenuecat` \| `manual` (future: `stripe`, `own_billing`, …) |
| `revenuecat_app_user_id` / `revenuecat_environment` | text? | RC-specific metadata; null/ignorable for other sources |
| `updated_at` | timestamptz | Last writer touch |

**Security:** RLS enabled. Clients have **self-`SELECT` only** (`user_id = auth.uid()`) — a mobile
or web client can read its own entitlement directly from Supabase to drive UI. All writes are
service-role (webhook, sweeper, manual SQL). There is deliberately no client write path.
Partial index `user_entitlement_active_idx ON (user_id) WHERE active = true` serves the gate query.

**Deletion:** `public.delete_user_data(p_user_id)` (account wipe) removes the rows; the FK cascade
covers auth-level deletion.

### `source` semantics — the rule that makes everything coexist

Each writer only manages rows it owns:

- The RevenueCat reconcile (`coach_internal.reconcile_user_entitlements`) **only deactivates rows
  with `source = 'revenuecat'`**. Rows it inserts/updates are stamped `source = 'revenuecat'`.
- `source = 'manual'` rows are **never touched by the RC sync** — a DB grant survives every
  webhook, expiration, and transfer event.
- A future provider gets its own `source` value and the same scoping rule in its writer.

**Accepted edge case:** if a user holds a manual `premium` row and later *also* subscribes to the
same entitlement id via RC, the reconcile upsert claims the row (`source → 'revenuecat'`, PK
conflict). When that RC subscription expires the row goes inactive — the earlier manual grant does
not resurrect. Re-grant manually if intended (snippet below).

---

## 2. Readers (how to gate on premium)

**Rule:** a user is premium for feature X ⇔ a row `(user_id, entitlement_id='premium',
active=true)` exists. Readers never filter on `source` — a manual grant is as good as a paid one.

- **Coach:** `com.zenthek.coach.auth.PremiumGate.requirePremium(call)` — service-role PostgREST
  read, throws `ForbiddenException("PREMIUM_REQUIRED")` (403). **Fail-closed by design**: a
  transient Supabase error denies access rather than opening the gate (decision 2026-07-02).
- **Future backend routes:** replicate the same one-row query (or lift `PremiumGate` out of the
  coach package into a shared module when a second server-side consumer actually appears).
- **Clients (mobile/web):** self-read via Supabase RLS:
  `GET /rest/v1/user_entitlement?select=entitlement_id,active,expires_at` with the user's JWT.

---

## 3. Writers

### 3.1 RevenueCat sync (current biller) — lives in the **main fitzenia-api**

Code: `com.zenthek.revenuecat` (models, `RevenueCatRestClient`, `RevenueCatEntitlementGateway`,
`RevenueCatSyncService`, `RevenueCatSweeperMain`). It runs in the main API service — always
deployed, never scales to zero (the coach service does) — plus a Cloud Run Job for the sweeper.

- **`POST /webhooks/revenuecat`** (public route, static-secret `Authorization` header compared
  constant-time). Flow: auth → atomic event claim (`coach_internal.processed_revenuecat_event`
  state machine: `processing → processed | failed`, advisory-locked, attempts-capped) →
  identity resolution (every UUID-shaped candidate among `app_user_id` / `original_app_user_id` /
  `aliases` / `transferred_*`, verified against GoTrue admin) → **full reconcile against
  `GET /v1/subscribers/{id}`** — the event body's entitlement state is never trusted (webhooks
  arrive out of order). `TEST` events short-circuit. Sync failure → 5xx so RC redelivers.
- **Stale-claim sweeper** (`-PtargetService=coach-rc-sweeper`, ~1-min schedule): re-claims
  events stuck `processing`/`failed` via `coach_rc_claim_recoverable_events` and replays the
  stored payload — no fresh RC delivery needed.
- **Identity model:** the app calls `Purchases.logIn(supabaseUserId)`, so RC `app_user_id` **is**
  `auth.users.id`.
- **Env (optional-at-load; route 503s until wired):** `REVENUECAT_WEBHOOK_AUTH`,
  `REVENUECAT_REST_API_KEY`, optional `REVENUECAT_REST_BASE_URL`.

### 3.2 Manual grants (DB)

Grant (or extend) premium to any user with one service-role statement — safe against the RC sync:

```sql
INSERT INTO public.user_entitlement (user_id, entitlement_id, active, source)
VALUES ('<user-uuid>', 'premium', true, 'manual')
ON CONFLICT (user_id, entitlement_id)
DO UPDATE SET active = true, source = 'manual', updated_at = now();
```

Revoke: `UPDATE public.user_entitlement SET active = false, updated_at = now() WHERE user_id = '<uuid>' AND entitlement_id = 'premium';`

Alternative while RC is the biller: **RevenueCat promotional ("granted") entitlements** — granted
in the RC dashboard/REST, they flow down the normal webhook+reconcile path with zero backend work,
and stay consistent across the user's devices. Use manual DB rows when you want independence from
RC (or RC is unreachable); use RC promos when you want RC to stay the single source of truth.

---

## 4. Migrating off RevenueCat (replace-the-writer recipe)

1. Build the new biller's webhook/receiver (own package, e.g. `com.zenthek.billing`) in
   fitzenia-api. Reuse the same patterns: idempotent event claim table, snapshot-based reconcile,
   sweeper for redelivery.
2. Its reconcile writes `public.user_entitlement` with a new `source` value (e.g. `'own_billing'`)
   and scopes deactivation to that source — exactly mirroring the RC function.
3. Readers (`PremiumGate`, clients) need **zero changes**.
4. Run both writers side by side during the transition; each only manages its own rows. Decommission
   RC by unsetting `REVENUECAT_*` (webhook → 503) once its last subscription expires.
5. The `revenuecat_*` columns and `coach_internal.processed_revenuecat_event` become inert
   historical data; drop them in a cleanup migration whenever convenient.

---

## 5. Naming note (historical)

The SQL surfaces are coach-prefixed — `public.coach_rc_*`, `public.coach_reconcile_user_entitlements`,
the `coach_internal` schema, `[COACH-RC]` log tags — because the system was built inside the AI
Coach effort. It is functionally backend-wide; the names were deliberately kept (decision
2026-07-02) to avoid churn on already-deployed dev objects. Don't read scope into the prefix.

## 6. Migration files

| File | Contents |
|---|---|
| `db/migrations/005_coach_baseline.sql` | The whole deployed coach schema (consolidated 2026-07-03). Includes `user_entitlement` (with `source` + `revenuecat_app_user_id` / `revenuecat_environment`), the RC event state machine, `claim`/`mark`/`reconcile_user_entitlements` + `public.coach_rc_*` wrappers, and the rest of the coach objects. Reflects live dev/prod; **not** the old split `005–009` files (removed — they had drifted). |
