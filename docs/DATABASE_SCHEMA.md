# Supabase Database Schema

> Source of truth for the Fitzenia Supabase project (`tpslgveyjldykkkhnifs`).  
> Run all SQL blocks below (in order) to bring a fresh Supabase project to the same state as production.

---

## Setup checklist

1. Create a new Supabase project.
2. Enable **Row-Level Security** (it is ON by default on all tables below — do not disable it).
3. Run the SQL blocks in this file top-to-bottom using the Supabase SQL editor or `psql`.
4. Update `.env` with the new project URL and service-role key.
5. Push the secrets to GCP (see `deploy.md`).

---

## Tables

### `rls_mode_config`

Controls whether RLS runs in dev-bypass mode. Keep `dev_mode = false` in production.

```sql
CREATE TABLE IF NOT EXISTS public.rls_mode_config (
    singleton  BOOLEAN PRIMARY KEY DEFAULT true CHECK (singleton),
    dev_mode   BOOLEAN NOT NULL DEFAULT false,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Seed the single row
INSERT INTO public.rls_mode_config (singleton, dev_mode)
VALUES (true, false)
ON CONFLICT (singleton) DO NOTHING;
```

---

### `user_profile`

One row per registered user. Written exclusively by the backend registration endpoint (`POST /api/user/register`). Never written by the client directly.

```sql
CREATE TABLE IF NOT EXISTS public.user_profile (
    id               TEXT PRIMARY KEY,
    name             TEXT NOT NULL,
    email            TEXT NOT NULL,
    avatar_url       TEXT,
    birth_date       TEXT NOT NULL,
    sex              TEXT NOT NULL,
    height_cm        DOUBLE PRECISION NOT NULL,
    created_at       BIGINT NOT NULL,
    last_modified_at BIGINT NOT NULL,
    user_id          UUID NOT NULL DEFAULT auth.uid()
);

ALTER TABLE public.user_profile ENABLE ROW LEVEL SECURITY;

CREATE POLICY "user_profile: select own rows"
    ON public.user_profile
    FOR SELECT
    USING (user_id = auth.uid());

CREATE POLICY "user_profile: insert own rows"
    ON public.user_profile
    FOR INSERT
    WITH CHECK (user_id = auth.uid());

CREATE POLICY "user_profile: update own rows"
    ON public.user_profile
    FOR UPDATE
    USING (user_id = auth.uid())
    WITH CHECK (user_id = auth.uid());
```

> `name`, `email`, and `avatar_url` are derived by the backend from the authenticated Supabase user during registration.  
> **No `sync_status`** — this table is backend-written only; the client treats it as read-only after the initial registration sync.

---

### `user_goal`

One row per registered user. Written exclusively by the backend registration endpoint.

```sql
CREATE TABLE IF NOT EXISTS public.user_goal (
    id                  TEXT PRIMARY KEY,
    goal_direction      TEXT NOT NULL,
    target_phase        TEXT NOT NULL,
    goal_weight_kg      DOUBLE PRECISION,
    pace_tier           TEXT NOT NULL,
    activity_level      TEXT NOT NULL,
    body_fat_percent    DOUBLE PRECISION NOT NULL DEFAULT 15.0,
    body_fat_range_key  TEXT NOT NULL DEFAULT 'TIER_3',
    exercise_frequency  TEXT NOT NULL DEFAULT 'ONE_TO_THREE',
    steps_activity_band TEXT NOT NULL DEFAULT 'SEDENTARY',
    lifting_experience  TEXT NOT NULL DEFAULT 'NONE',
    protein_preference  TEXT NOT NULL DEFAULT 'MODERATE',
    created_at          BIGINT NOT NULL,
    last_modified_at    BIGINT NOT NULL,
    user_id             UUID NOT NULL DEFAULT auth.uid()
);

ALTER TABLE public.user_goal ENABLE ROW LEVEL SECURITY;

CREATE POLICY "user_goal: select own rows"
    ON public.user_goal
    FOR SELECT
    USING (user_id = auth.uid());

CREATE POLICY "user_goal: insert own rows"
    ON public.user_goal
    FOR INSERT
    WITH CHECK (user_id = auth.uid());

CREATE POLICY "user_goal: update own rows"
    ON public.user_goal
    FOR UPDATE
    USING (user_id = auth.uid())
    WITH CHECK (user_id = auth.uid());
```

> **No `sync_status`** — same reason as `user_profile`.

---

### `calorie_target`

One row per registered user. Written exclusively by the backend registration endpoint.

```sql
CREATE TABLE IF NOT EXISTS public.calorie_target (
    id               TEXT PRIMARY KEY,
    formula          TEXT NOT NULL,
    bmr_kcal         BIGINT NOT NULL,
    tdee_kcal        BIGINT NOT NULL,
    target_kcal      BIGINT NOT NULL,
    target_min_kcal  BIGINT NOT NULL,
    target_max_kcal  BIGINT NOT NULL,
    macro_mode       TEXT NOT NULL,
    protein_target_g BIGINT NOT NULL,
    carbs_target_g   BIGINT NOT NULL,
    fat_target_g     BIGINT NOT NULL,
    applied_pace_tier TEXT NOT NULL,
    floor_clamped    BIGINT NOT NULL DEFAULT 0,
    warning          TEXT,
    created_at       BIGINT NOT NULL,
    last_modified_at BIGINT NOT NULL,
    user_id          UUID NOT NULL DEFAULT auth.uid()
);

ALTER TABLE public.calorie_target ENABLE ROW LEVEL SECURITY;

CREATE POLICY "calorie_target: select own rows"
    ON public.calorie_target
    FOR SELECT
    USING (user_id = auth.uid());

CREATE POLICY "calorie_target: insert own rows"
    ON public.calorie_target
    FOR INSERT
    WITH CHECK (user_id = auth.uid());

CREATE POLICY "calorie_target: update own rows"
    ON public.calorie_target
    FOR UPDATE
    USING (user_id = auth.uid())
    WITH CHECK (user_id = auth.uid());
```

> **`floor_clamped`** is stored as `BIGINT` (0 = false, 1 = true) to match SQLDelight conventions on the client.  
> **`warning`** is nullable; valid values: `FLOOR_CLAMPED`, `PACE_DOWNGRADED`, or NULL.  
> **No `sync_status`** — same reason as above.

---

### `weight_entry`

Client-synced. One row per weight log entry.

```sql
CREATE TABLE IF NOT EXISTS public.weight_entry (
    id               TEXT PRIMARY KEY,
    date             TEXT NOT NULL,
    weight_kg        DOUBLE PRECISION NOT NULL,
    note             TEXT,
    created_at       BIGINT NOT NULL,
    is_deleted       BIGINT NOT NULL DEFAULT 0,
    sync_status      TEXT NOT NULL,
    user_id          UUID NOT NULL DEFAULT auth.uid()
);

ALTER TABLE public.weight_entry ENABLE ROW LEVEL SECURITY;

CREATE POLICY "weight_entry: owner access"
    ON public.weight_entry
    USING (user_id = auth.uid());
```

---

### `diary_entry`

Client-synced. One row per food log entry.

```sql
CREATE TABLE IF NOT EXISTS public.diary_entry (
    id                     TEXT PRIMARY KEY,
    date                   TEXT NOT NULL,
    meal_type              TEXT NOT NULL,
    entry_type             TEXT NOT NULL,
    food_item_id           TEXT,
    food_name_snapshot     TEXT NOT NULL,
    weight_grams           DOUBLE PRECISION,
    serving_name_snapshot  TEXT,
    selected_serving_key   TEXT NOT NULL DEFAULT '',
    my_meal_id             TEXT,
    my_meal_name_snapshot  TEXT,
    calories_kcal          DOUBLE PRECISION NOT NULL,
    protein_g              DOUBLE PRECISION NOT NULL,
    carbs_g                DOUBLE PRECISION NOT NULL,
    fat_g                  DOUBLE PRECISION NOT NULL,
    fiber_g                DOUBLE PRECISION,
    sodium_mg              DOUBLE PRECISION,
    sugar_g                DOUBLE PRECISION,
    saturated_fat_g        DOUBLE PRECISION,
    created_at             BIGINT NOT NULL,
    updated_at             BIGINT NOT NULL,
    last_modified_at       BIGINT NOT NULL,
    is_health_synced       BIGINT NOT NULL DEFAULT 0,
    is_deleted             BIGINT NOT NULL DEFAULT 0,
    sync_status            TEXT NOT NULL,
    user_id                UUID NOT NULL DEFAULT auth.uid()
);

ALTER TABLE public.diary_entry ENABLE ROW LEVEL SECURITY;

CREATE POLICY "diary_entry: owner access"
    ON public.diary_entry
    USING (user_id = auth.uid());
```

---

### `diary_entry_ingredient`

Client-synced. Ingredients of a composite diary entry.

```sql
CREATE TABLE IF NOT EXISTS public.diary_entry_ingredient (
    id                    TEXT PRIMARY KEY,
    diary_entry_id        TEXT NOT NULL REFERENCES public.diary_entry(id),
    food_item_id          TEXT,
    food_name_snapshot    TEXT NOT NULL,
    weight_grams          DOUBLE PRECISION NOT NULL,
    serving_name_snapshot TEXT,
    selected_serving_key  TEXT NOT NULL DEFAULT '',
    calories_kcal         DOUBLE PRECISION NOT NULL,
    protein_g             DOUBLE PRECISION NOT NULL,
    carbs_g               DOUBLE PRECISION NOT NULL,
    fat_g                 DOUBLE PRECISION NOT NULL,
    fiber_g               DOUBLE PRECISION,
    is_deleted            BIGINT NOT NULL DEFAULT 0,
    sync_status           TEXT NOT NULL,
    user_id               UUID NOT NULL DEFAULT auth.uid()
);

ALTER TABLE public.diary_entry_ingredient ENABLE ROW LEVEL SECURITY;

CREATE POLICY "diary_entry_ingredient: owner access"
    ON public.diary_entry_ingredient
    USING (user_id = auth.uid());
```

---

### `food_item`

Client-synced. Custom foods created by the user.

```sql
CREATE TABLE IF NOT EXISTS public.food_item (
    id                       TEXT PRIMARY KEY,
    name                     TEXT NOT NULL,
    brand                    TEXT,
    barcode                  TEXT,
    source_type              TEXT NOT NULL,
    api_source               TEXT,
    image_url                TEXT,
    local_image_path         TEXT,
    serving_key              TEXT NOT NULL,
    serving_weight_g         DOUBLE PRECISION NOT NULL,
    serving_name             TEXT NOT NULL,
    serving_order            BIGINT NOT NULL DEFAULT 0,
    serving_is_generated_unit BIGINT NOT NULL DEFAULT 0,
    calories_kcal            DOUBLE PRECISION NOT NULL,
    protein_g                DOUBLE PRECISION NOT NULL,
    carbs_g                  DOUBLE PRECISION NOT NULL,
    fat_g                    DOUBLE PRECISION NOT NULL,
    fiber_g                  DOUBLE PRECISION,
    sodium_mg                DOUBLE PRECISION,
    sugar_g                  DOUBLE PRECISION,
    saturated_fat_g          DOUBLE PRECISION,
    is_favorite              BIGINT NOT NULL DEFAULT 0,
    is_deleted               BIGINT NOT NULL DEFAULT 0,
    created_at               BIGINT NOT NULL,
    updated_at               BIGINT NOT NULL,
    sync_status              TEXT NOT NULL,
    user_id                  UUID NOT NULL DEFAULT auth.uid()
);

ALTER TABLE public.food_item ENABLE ROW LEVEL SECURITY;

CREATE POLICY "food_item: owner access"
    ON public.food_item
    USING (user_id = auth.uid());
```

---

### `food_item_serving`

Client-synced. Additional serving sizes for a food item.

```sql
CREATE TABLE IF NOT EXISTS public.food_item_serving (
    id               TEXT PRIMARY KEY,
    food_item_id     TEXT NOT NULL REFERENCES public.food_item(id),
    serving_key      TEXT NOT NULL,
    name             TEXT NOT NULL,
    serving_order    BIGINT NOT NULL,
    is_generated_unit BIGINT NOT NULL DEFAULT 0,
    weight_grams     DOUBLE PRECISION NOT NULL,
    calories_kcal    DOUBLE PRECISION NOT NULL,
    protein_g        DOUBLE PRECISION NOT NULL,
    carbs_g          DOUBLE PRECISION NOT NULL,
    fat_g            DOUBLE PRECISION NOT NULL,
    user_id          UUID NOT NULL DEFAULT auth.uid()
);

ALTER TABLE public.food_item_serving ENABLE ROW LEVEL SECURITY;

CREATE POLICY "food_item_serving: owner access"
    ON public.food_item_serving
    USING (user_id = auth.uid());
```

---

### `my_meal`

Client-synced. User-created composite meals.

```sql
CREATE TABLE IF NOT EXISTS public.my_meal (
    id               TEXT PRIMARY KEY,
    name             TEXT NOT NULL,
    image_url        TEXT,
    local_image_path TEXT,
    is_favorite      BIGINT NOT NULL DEFAULT 0,
    is_deleted       BIGINT NOT NULL DEFAULT 0,
    created_at       BIGINT NOT NULL,
    updated_at       BIGINT NOT NULL,
    sync_status      TEXT NOT NULL,
    user_id          UUID NOT NULL DEFAULT auth.uid()
);

ALTER TABLE public.my_meal ENABLE ROW LEVEL SECURITY;

CREATE POLICY "my_meal: owner access"
    ON public.my_meal
    USING (user_id = auth.uid());
```

---

### `my_meal_ingredient`

Client-synced. Ingredients of a composite meal.

```sql
CREATE TABLE IF NOT EXISTS public.my_meal_ingredient (
    id                    TEXT PRIMARY KEY,
    meal_id               TEXT NOT NULL REFERENCES public.my_meal(id),
    food_item_id          TEXT,
    food_name_snapshot    TEXT NOT NULL,
    weight_grams          DOUBLE PRECISION NOT NULL,
    serving_name_snapshot TEXT,
    selected_serving_key  TEXT NOT NULL DEFAULT '',
    calories_kcal         DOUBLE PRECISION NOT NULL,
    protein_g             DOUBLE PRECISION NOT NULL,
    carbs_g               DOUBLE PRECISION NOT NULL,
    fat_g                 DOUBLE PRECISION NOT NULL,
    fiber_g               DOUBLE PRECISION,
    is_deleted            BIGINT NOT NULL DEFAULT 0,
    sync_status           TEXT NOT NULL,
    user_id               UUID NOT NULL DEFAULT auth.uid()
);

ALTER TABLE public.my_meal_ingredient ENABLE ROW LEVEL SECURITY;

CREATE POLICY "my_meal_ingredient: owner access"
    ON public.my_meal_ingredient
    USING (user_id = auth.uid());
```

---

### `recent_food`

Client-synced. Recently used foods for quick re-logging.

```sql
CREATE TABLE IF NOT EXISTS public.recent_food (
    id                    TEXT PRIMARY KEY,
    entry_type            TEXT NOT NULL,
    food_name_snapshot    TEXT NOT NULL,
    serving_name_snapshot TEXT,
    selected_serving_key  TEXT NOT NULL DEFAULT '',
    weight_grams          DOUBLE PRECISION,
    calories_kcal         DOUBLE PRECISION NOT NULL DEFAULT 0,
    protein_g             DOUBLE PRECISION NOT NULL DEFAULT 0,
    carbs_g               DOUBLE PRECISION NOT NULL DEFAULT 0,
    fat_g                 DOUBLE PRECISION NOT NULL DEFAULT 0,
    last_used_at          BIGINT NOT NULL,
    sync_status           TEXT NOT NULL DEFAULT 'PENDING',
    user_id               UUID NOT NULL DEFAULT auth.uid()
);

ALTER TABLE public.recent_food ENABLE ROW LEVEL SECURITY;

CREATE POLICY "recent_food: owner access"
    ON public.recent_food
    USING (user_id = auth.uid());
```

---

## Canonical Food Catalog

Shared global catalog of AI-synthesized canonical foods (e.g. "cheesecake", "flat white") used by Smart Food Search. Service-role write only — these are not user-scoped tables. RLS is enabled with no policies, so only `service_role` (which bypasses RLS) can read or write them. The backend uses a dedicated service-role client (`SUPABASE_SERVICE_ROLE_KEY`); never accessed via user-JWT paths.

DDL lives in `db/migrations/001_canonical_food_catalog.sql` (apply via `psql`).

### `canonical_food_item`

One row per locale-specific canonical food. `canonical_group_id` links cross-locale equivalents (e.g. "cheesecake" / "チーズケーキ") into the same conceptual food when the LLM equivalence check + ±15% nutrition sanity gate accept the link.

```sql
CREATE TABLE IF NOT EXISTS public.canonical_food_item (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    canonical_group_id UUID NOT NULL DEFAULT gen_random_uuid(),
    primary_locale     TEXT NOT NULL,
    primary_country    TEXT NOT NULL DEFAULT 'GLOBAL',
    ai_generated       BOOLEAN NOT NULL DEFAULT true,
    model_provider     TEXT NOT NULL,
    model_name         TEXT NOT NULL,
    confidence         REAL NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE public.canonical_food_item ENABLE ROW LEVEL SECURITY;
```

### `canonical_food_serving`

Servings for a canonical food. Always includes a `100g` serving (server-side validation rejects writes without one). Macros validated for non-negativity and calorie-macro consistency before persist.

```sql
CREATE TABLE IF NOT EXISTS public.canonical_food_serving (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    canonical_food_id UUID NOT NULL REFERENCES public.canonical_food_item(id) ON DELETE CASCADE,
    name              TEXT NOT NULL,
    weight_grams      REAL NOT NULL CHECK (weight_grams > 0),
    calories_kcal     REAL NOT NULL CHECK (calories_kcal >= 0),
    protein_g         REAL NOT NULL CHECK (protein_g >= 0),
    carbs_g           REAL NOT NULL CHECK (carbs_g >= 0),
    fat_g             REAL NOT NULL CHECK (fat_g >= 0),
    fiber_g           REAL,
    sodium_mg         REAL,
    sugar_g           REAL,
    saturated_fat_g   REAL
);

ALTER TABLE public.canonical_food_serving ENABLE ROW LEVEL SECURITY;
```

### `canonical_food_term`

Localized names + aliases for a canonical food. `pg_trgm` GIN index on `name` supports the fuzzy english-equivalent lookup used during cross-locale linking.

```sql
CREATE TABLE IF NOT EXISTS public.canonical_food_term (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    canonical_food_id UUID NOT NULL REFERENCES public.canonical_food_item(id) ON DELETE CASCADE,
    locale            TEXT NOT NULL,
    name              TEXT NOT NULL,
    is_alias          BOOLEAN NOT NULL DEFAULT false
);

ALTER TABLE public.canonical_food_term ENABLE ROW LEVEL SECURITY;
```

### `canonical_food_query_map`

Maps `(normalized_query, locale, country, rank)` → `canonical_food_id`. `rank = 0` is the `bestMatch`; `rank ≥ 1` are `bestMatchCandidates` for broad queries (e.g. `sandwich` → 3 candidate canonicals).

`country` is `NOT NULL DEFAULT 'GLOBAL'` deliberately — Postgres allows multiple NULL rows under a unique key, which would break slot uniqueness, so we use a sentinel string.

```sql
CREATE TABLE IF NOT EXISTS public.canonical_food_query_map (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    normalized_query  TEXT NOT NULL,
    locale            TEXT NOT NULL,
    country           TEXT NOT NULL DEFAULT 'GLOBAL',
    canonical_food_id UUID NOT NULL REFERENCES public.canonical_food_item(id) ON DELETE CASCADE,
    rank              SMALLINT NOT NULL CHECK (rank >= 0),
    UNIQUE (normalized_query, locale, country, rank),
    UNIQUE (normalized_query, locale, country, canonical_food_id)
);

ALTER TABLE public.canonical_food_query_map ENABLE ROW LEVEL SECURITY;
```

### `insert_canonical_foods` (RPC)

The single transactional write entry point. Slot-idempotent and batch-aware. Application code never INSERTs into the catalog tables directly — always via this RPC.

- **Lock**: `pg_advisory_xact_lock(hashtextextended(query|locale|country, 0))` — auto-released at transaction end.
- **Slot check**: if all requested ranks are already filled → return `status: "reused"` with the existing `canonical_food_id`s (no writes). If partial → return `status: "partial"` and surface the inconsistency. If none → insert all transactionally and return `status: "inserted"`.
- **Granted to** `service_role` only; revoked from `anon` and `authenticated`.

See `db/migrations/001_canonical_food_catalog.sql` for the full function body and the orchestrator's response handling for each `status`.

---

## Ownership summary

| Table | Written by | Has `sync_status` |
|---|---|---|
| `user_profile` | Backend only (`POST /api/user/register`) | No |
| `user_goal` | Backend only (`POST /api/user/register`) | No |
| `calorie_target` | Backend only (`POST /api/user/register`) | No |
| `weight_entry` | Client sync | Yes |
| `diary_entry` | Client sync | Yes |
| `diary_entry_ingredient` | Client sync | Yes |
| `food_item` | Client sync | Yes |
| `food_item_serving` | Client sync | Yes |
| `my_meal` | Client sync | Yes |
| `my_meal_ingredient` | Client sync | Yes |
| `recent_food` | Client sync | Yes |
| `rls_mode_config` | Manual seed | — |
| `canonical_food_item` | Backend only (service-role, via `insert_canonical_foods` RPC) | No |
| `canonical_food_serving` | Backend only (service-role, via `insert_canonical_foods` RPC) | No |
| `canonical_food_term` | Backend only (service-role, via `insert_canonical_foods` RPC) | No |
| `canonical_food_query_map` | Backend only (service-role, via `insert_canonical_foods` RPC) | No |

---

## RLS dev-bypass pattern

`rls_mode_config.dev_mode` is used by RLS policies on client-synced tables to allow the backend service role to skip per-user filtering during development. In production this must be `false`.

To temporarily enable during local testing:
```sql
UPDATE public.rls_mode_config SET dev_mode = true WHERE singleton = true;
-- remember to reset:
UPDATE public.rls_mode_config SET dev_mode = false WHERE singleton = true;
```
