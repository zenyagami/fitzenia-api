# Supabase Database Schema

> Reference for the Fitzenia dev Supabase project (`tpslgveyjldykkkhnifs`), which is treated as the
> source of truth as of 2026-07-04. This reflects the *live* schema (introspected via `list_tables`
> / `information_schema` / `pg_policies`), including tables created directly against Supabase with
> no corresponding file in `db/migrations/` (schema drift — noted per table below). It is a
> reference, not a runnable top-to-bottom script: some RPCs referenced here live only in
> `db/migrations/001_canonical_food_catalog.sql`, and drifted tables have no migration file at all.

---

## Setup checklist

1. Create a new Supabase project.
2. Enable **Row-Level Security** (it is ON by default on all tables below — do not disable it).
3. Apply `db/migrations/*.sql` in order for the tables that have migrations; recreate drifted tables (marked below) by hand from this doc.
4. Update `.env` with the new project URL and service-role key.
5. Push the secrets to GCP (see `DEPLOY.md`).

---

## RLS helper functions

Two SQL functions back nearly every client-owner policy in `public`:

```sql
CREATE OR REPLACE FUNCTION public.is_dev_rls_mode()
RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path TO 'public'
AS $$
    SELECT COALESCE(
        (SELECT dev_mode FROM public.rls_mode_config WHERE singleton = TRUE LIMIT 1),
        FALSE
    );
$$;

CREATE OR REPLACE FUNCTION public.can_access_user_row(row_user_id uuid)
RETURNS boolean
LANGUAGE sql STABLE
SET search_path TO 'public'
AS $$
    SELECT public.is_dev_rls_mode() OR (auth.uid() IS NOT NULL AND row_user_id = auth.uid());
$$;
```

Client-owner tables all follow the same four-policy shape (see `dev_or_owner_*` below):

```sql
CREATE POLICY "dev_or_owner_select" ON public.<table> FOR SELECT USING (can_access_user_row(user_id));
CREATE POLICY "dev_or_owner_insert" ON public.<table> FOR INSERT WITH CHECK (can_access_user_row(user_id));
CREATE POLICY "dev_or_owner_update" ON public.<table> FOR UPDATE USING (can_access_user_row(user_id)) WITH CHECK (can_access_user_row(user_id));
CREATE POLICY "dev_or_owner_delete" ON public.<table> FOR DELETE USING (can_access_user_row(user_id));
```

Applies verbatim (role `authenticated`) to: `user_profile`, `user_goal`, `calorie_target`,
`weight_entry`, `diary_entry`, `diary_entry_ingredient`, `food_item`, `food_item_serving`,
`my_meal`, `my_meal_ingredient`, `recent_food`. `journey` has the same four policies but role
`public` instead of `authenticated`.

Where a table's real policy set differs from this default (backend-only writes, single combined
policy, etc.), it's called out under that table.

---

## Core user tables

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
    user_id          UUID NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE
);

ALTER TABLE public.user_profile ENABLE ROW LEVEL SECURITY;
-- dev_or_owner_{select,insert,update,delete} — see "RLS helper functions"
```

> **No `sync_status`** — this table is backend-written only; the client treats it as read-only after the initial registration sync.

### `user_goal`

One row per registered user. Written exclusively by the backend registration endpoint.

```sql
CREATE TABLE IF NOT EXISTS public.user_goal (
    id                     TEXT PRIMARY KEY,
    goal_direction         TEXT NOT NULL,
    target_phase           TEXT NOT NULL,
    goal_weight_kg         DOUBLE PRECISION,
    pace_tier              TEXT NOT NULL,
    activity_level         TEXT NOT NULL,
    body_fat_percent       DOUBLE PRECISION NOT NULL DEFAULT 15.0,
    body_fat_range_key     TEXT NOT NULL DEFAULT 'TIER_3',
    exercise_frequency     TEXT NOT NULL DEFAULT 'ONE_TO_THREE',
    steps_activity_band    TEXT NOT NULL DEFAULT 'SEDENTARY',
    lifting_experience     TEXT NOT NULL DEFAULT 'NONE',
    protein_preference     TEXT NOT NULL DEFAULT 'MODERATE',
    adaptive_tdee_enabled  BOOLEAN NOT NULL DEFAULT false,
    created_at             BIGINT NOT NULL,
    last_modified_at       BIGINT NOT NULL,
    user_id                UUID NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE
);

ALTER TABLE public.user_goal ENABLE ROW LEVEL SECURITY;
-- dev_or_owner_{select,insert,update,delete}
```

> **No `sync_status`** — same reason as `user_profile`. `adaptive_tdee_enabled` is new since the last doc pass.

### `calorie_target`

One row per registered user. Written exclusively by the backend registration endpoint.

```sql
CREATE TABLE IF NOT EXISTS public.calorie_target (
    id                TEXT PRIMARY KEY,
    formula           TEXT NOT NULL,
    bmr_kcal          BIGINT NOT NULL,
    tdee_kcal         BIGINT NOT NULL,
    target_kcal       BIGINT NOT NULL,
    target_min_kcal   BIGINT NOT NULL,
    target_max_kcal   BIGINT NOT NULL,
    macro_mode        TEXT NOT NULL,
    protein_target_g  BIGINT NOT NULL,
    carbs_target_g    BIGINT NOT NULL,
    fat_target_g      BIGINT NOT NULL,
    applied_pace_tier TEXT NOT NULL,
    floor_clamped     BIGINT NOT NULL DEFAULT 0,
    warning           TEXT,
    tdee_mode         TEXT,
    tdee_confidence   TEXT,
    created_at        BIGINT NOT NULL,
    last_modified_at  BIGINT NOT NULL,
    user_id           UUID NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE
);

ALTER TABLE public.calorie_target ENABLE ROW LEVEL SECURITY;
-- dev_or_owner_{select,insert,update,delete}
```

> **`floor_clamped`** is stored as `BIGINT` (0 = false, 1 = true) to match SQLDelight conventions on the client.
> **`warning`** is nullable; valid values: `FLOOR_CLAMPED`, `PACE_DOWNGRADED`, or NULL.
> **`tdee_mode` / `tdee_confidence`** are new columns supporting adaptive TDEE — no CHECK constraint enforced in Postgres, values are validated application-side.
> **No `sync_status`** — same reason as above.

### `calorie_target_history`

Append-only log of past calorie targets per user. One row is inserted whenever the active `calorie_target` changes. Written by the backend only.

```sql
CREATE TABLE IF NOT EXISTS public.calorie_target_history (
    id                UUID PRIMARY KEY,
    user_id           UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    effective_from    TEXT NOT NULL,
    target_min_kcal   BIGINT NOT NULL,
    target_max_kcal   BIGINT NOT NULL,
    target_kcal       BIGINT NOT NULL,
    bmr_kcal          BIGINT NOT NULL,
    tdee_kcal         BIGINT NOT NULL,
    formula           TEXT NOT NULL,
    macro_mode        TEXT NOT NULL,
    protein_target_g  BIGINT NOT NULL,
    carbs_target_g    BIGINT NOT NULL,
    fat_target_g      BIGINT NOT NULL,
    applied_pace_tier TEXT NOT NULL,
    floor_clamped     BIGINT NOT NULL DEFAULT 0,
    warning           TEXT,
    tdee_mode         TEXT,
    tdee_confidence   TEXT,
    created_at        BIGINT NOT NULL,
    UNIQUE (user_id, effective_from)
);

ALTER TABLE public.calorie_target_history ENABLE ROW LEVEL SECURITY;

CREATE POLICY "calorie_target_history_owner"
    ON public.calorie_target_history
    FOR ALL
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);
```

> **`id` is `UUID`, not `TEXT`** — corrected from an earlier revision of this doc. Single combined `ALL` policy (role `public`), not the four-policy `dev_or_owner_*` shape.

### `weight_entry`

Client-synced. One row per weight log entry.

```sql
CREATE TABLE IF NOT EXISTS public.weight_entry (
    id               TEXT PRIMARY KEY,
    date             TEXT NOT NULL,
    weight_kg        DOUBLE PRECISION NOT NULL,
    note             TEXT,
    created_at       BIGINT NOT NULL,
    body_fat_percent DOUBLE PRECISION,
    source           TEXT NOT NULL DEFAULT 'MANUAL',
    is_deleted       BOOLEAN NOT NULL DEFAULT false,
    user_id          UUID NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE
);

ALTER TABLE public.weight_entry ENABLE ROW LEVEL SECURITY;
-- dev_or_owner_{select,insert,update,delete}
```

> **`is_deleted`** is a new soft-delete column since the last doc pass.

### `daily_activity`

Client-synced. One row per day per user — step count synced automatically from the phone's health
platform (Health Connect / HealthKit). Read by the AI Coach's `getRecentSteps` tool. **Schema
drift**: created directly against Supabase, no matching file in `db/migrations/`.

```sql
CREATE TABLE IF NOT EXISTS public.daily_activity (
    id                UUID PRIMARY KEY,
    user_id           UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    date              DATE NOT NULL,
    steps_count       INTEGER NOT NULL CHECK (steps_count >= 0),
    created_at        BIGINT NOT NULL,
    last_modified_at  BIGINT NOT NULL,
    is_deleted        BOOLEAN NOT NULL DEFAULT false
);

ALTER TABLE public.daily_activity ENABLE ROW LEVEL SECURITY;

CREATE POLICY "daily_activity_select_own" ON public.daily_activity FOR SELECT USING (auth.uid() = user_id);
CREATE POLICY "daily_activity_insert_own" ON public.daily_activity FOR INSERT WITH CHECK (auth.uid() = user_id);
CREATE POLICY "daily_activity_update_own" ON public.daily_activity FOR UPDATE USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
CREATE POLICY "daily_activity_delete_own" ON public.daily_activity FOR DELETE USING (auth.uid() = user_id);
```

> No unique constraint on `(user_id, date)` — the client behaves today (verified: zero duplicate
> dates in dev), but the coach's `getRecentSteps` tool defensively collapses to one figure per date
> (max `steps_count`) rather than assuming it. Policies use raw `auth.uid()`, not `can_access_user_row()` (no dev-bypass).

### `journey`

Client-synced. Historical record of a completed or abandoned goal period (start/end weight, body fat, dates) — effectively an archive row created when a user's active goal changes. **Schema drift**: no matching file in `db/migrations/`.

```sql
CREATE TABLE IF NOT EXISTS public.journey (
    id                       TEXT PRIMARY KEY,
    user_id                  UUID NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
    goal_direction           TEXT NOT NULL,
    target_phase             TEXT NOT NULL,
    pace_tier                TEXT NOT NULL,
    target_weight_kg         DOUBLE PRECISION,
    target_body_fat_percent  DOUBLE PRECISION,
    body_fat_percent         DOUBLE PRECISION NOT NULL,
    body_fat_range_key       TEXT NOT NULL,
    activity_level           TEXT NOT NULL,
    exercise_frequency       TEXT NOT NULL,
    steps_activity_band      TEXT NOT NULL,
    lifting_experience       TEXT NOT NULL,
    protein_preference       TEXT NOT NULL,
    started_at               TEXT NOT NULL,
    goal_date                TEXT,
    ended_at                 TEXT,
    start_weight_kg          DOUBLE PRECISION NOT NULL,
    start_body_fat_percent   DOUBLE PRECISION,
    end_weight_kg            DOUBLE PRECISION,
    end_body_fat_percent     DOUBLE PRECISION,
    created_at               BIGINT NOT NULL,
    last_modified_at         BIGINT NOT NULL,
    is_deleted               BOOLEAN NOT NULL DEFAULT false
);

ALTER TABLE public.journey ENABLE ROW LEVEL SECURITY;
-- dev_or_owner_{select,insert,update,delete}, role `public` (not `authenticated`)
```

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
    portion_multiplier     DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    created_at             BIGINT NOT NULL,
    updated_at             BIGINT NOT NULL,
    last_modified_at       BIGINT NOT NULL,
    is_health_synced       BIGINT NOT NULL DEFAULT 0,
    aggregate_id           TEXT,
    image_url              TEXT,
    user_id                UUID NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE
);

ALTER TABLE public.diary_entry ENABLE ROW LEVEL SECURITY;
-- dev_or_owner_{select,insert,update,delete}
```

> **`portion_multiplier`** is a new column since the last doc pass.

### `diary_entry_ingredient`

Client-synced. Ingredients of a composite diary entry.

```sql
CREATE TABLE IF NOT EXISTS public.diary_entry_ingredient (
    id                    TEXT PRIMARY KEY,
    diary_entry_id        TEXT NOT NULL REFERENCES public.diary_entry(id) ON DELETE CASCADE,
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
    quantity              NUMERIC NOT NULL DEFAULT 1.0,
    servings_json         TEXT,
    user_id               UUID NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE
);

ALTER TABLE public.diary_entry_ingredient ENABLE ROW LEVEL SECURITY;
-- dev_or_owner_{select,insert,update,delete}
```

> **`servings_json`** is a new column since the last doc pass.

### `food_item`

Client-synced. Custom foods created by the user.

```sql
CREATE TABLE IF NOT EXISTS public.food_item (
    id                        TEXT PRIMARY KEY,
    name                      TEXT NOT NULL,
    brand                     TEXT,
    barcode                   TEXT,
    source_type               TEXT NOT NULL,
    api_source                TEXT,
    image_url                 TEXT,
    serving_key               TEXT NOT NULL,
    serving_weight_g          DOUBLE PRECISION NOT NULL,
    serving_name              TEXT NOT NULL,
    serving_order             BIGINT NOT NULL DEFAULT 0,
    serving_is_generated_unit BIGINT NOT NULL DEFAULT 0,
    calories_kcal             DOUBLE PRECISION NOT NULL,
    protein_g                 DOUBLE PRECISION NOT NULL,
    carbs_g                   DOUBLE PRECISION NOT NULL,
    fat_g                     DOUBLE PRECISION NOT NULL,
    fiber_g                   DOUBLE PRECISION,
    sodium_mg                 DOUBLE PRECISION,
    sugar_g                   DOUBLE PRECISION,
    saturated_fat_g           DOUBLE PRECISION,
    is_favorite               BIGINT NOT NULL DEFAULT 0,
    created_at                BIGINT NOT NULL,
    updated_at                BIGINT NOT NULL,
    preferred_serving_key     TEXT,
    preferred_quantity        DOUBLE PRECISION,
    user_id                   UUID NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE
);

ALTER TABLE public.food_item ENABLE ROW LEVEL SECURITY;
-- dev_or_owner_{select,insert,update,delete}
```

### `food_item_serving`

Client-synced. Additional serving sizes for a food item.

```sql
CREATE TABLE IF NOT EXISTS public.food_item_serving (
    id                TEXT PRIMARY KEY,
    food_item_id      TEXT NOT NULL REFERENCES public.food_item(id) ON DELETE CASCADE,
    serving_key       TEXT NOT NULL,
    name              TEXT NOT NULL,
    serving_order     BIGINT NOT NULL,
    is_generated_unit BIGINT NOT NULL DEFAULT 0,
    weight_grams      DOUBLE PRECISION NOT NULL,
    calories_kcal     DOUBLE PRECISION NOT NULL,
    protein_g         DOUBLE PRECISION NOT NULL,
    carbs_g           DOUBLE PRECISION NOT NULL,
    fat_g             DOUBLE PRECISION NOT NULL,
    user_id           UUID NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE
);

ALTER TABLE public.food_item_serving ENABLE ROW LEVEL SECURITY;
-- dev_or_owner_{select,insert,update,delete}
```

### `my_meal`

Client-synced. User-created composite meals.

```sql
CREATE TABLE IF NOT EXISTS public.my_meal (
    id                    TEXT PRIMARY KEY,
    name                  TEXT NOT NULL,
    image_url             TEXT,
    is_favorite           BIGINT NOT NULL DEFAULT 0,
    created_at            BIGINT NOT NULL,
    updated_at            BIGINT NOT NULL,
    weight_grams          REAL NOT NULL DEFAULT 0,
    serving_name_snapshot TEXT DEFAULT '1 portion',
    selected_serving_key  TEXT NOT NULL DEFAULT 'default_portion',
    user_id               UUID NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE
);

ALTER TABLE public.my_meal ENABLE ROW LEVEL SECURITY;
-- dev_or_owner_{select,insert,update,delete}
```

### `my_meal_ingredient`

Client-synced. Ingredients of a composite meal.

```sql
CREATE TABLE IF NOT EXISTS public.my_meal_ingredient (
    id                    TEXT PRIMARY KEY,
    meal_id               TEXT NOT NULL REFERENCES public.my_meal(id) ON DELETE CASCADE,
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
    servings_json         TEXT,
    user_id               UUID NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE
);

ALTER TABLE public.my_meal_ingredient ENABLE ROW LEVEL SECURITY;
-- dev_or_owner_{select,insert,update,delete}
```

> **`servings_json`** is a new column since the last doc pass.

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
    aggregate_id          TEXT,
    image_url             TEXT,
    components_snapshot   TEXT,
    user_id               UUID NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE
);

ALTER TABLE public.recent_food ENABLE ROW LEVEL SECURITY;
-- dev_or_owner_{select,insert,update,delete}
```

### `progress_photo`

User progress photos stored in Supabase Storage. One row per photo upload; `pose` is constrained to the four canonical angles.

```sql
CREATE TABLE IF NOT EXISTS public.progress_photo (
    id               UUID PRIMARY KEY,
    user_id          UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    taken_date       DATE NOT NULL,
    pose             TEXT NOT NULL CHECK (pose = ANY (ARRAY['FRONT', 'SIDE_LEFT', 'SIDE_RIGHT', 'BACK'])),
    storage_path     TEXT NOT NULL,
    image_url        TEXT NOT NULL,
    width            INTEGER,
    height           INTEGER,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, taken_date, pose)
);

ALTER TABLE public.progress_photo ENABLE ROW LEVEL SECURITY;

CREATE POLICY "progress_photo_owner"
    ON public.progress_photo
    FOR ALL
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);
```

---

## AI Progress Projections ("the ladder")

Backs `POST /api/progress/ladders/generate-stream` and `DELETE /api/progress/ladders/{id}` — see `AGENTS.md` → "AI Progress Projections" for the full feature design. Both tables are client-**readable** only; all writes go through the backend service role.

### `ai_progress_ladder`

One row per generated (or cache-hit) ladder.

```sql
CREATE TABLE IF NOT EXISTS public.ai_progress_ladder (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                   UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    base_weight_kg            NUMERIC(6,2) NOT NULL,
    base_body_fat_percent     NUMERIC(5,2) NOT NULL,
    step_body_fat_percent     NUMERIC(4,2) NOT NULL,
    num_steps                 INTEGER NOT NULL CHECK (num_steps >= 1 AND num_steps <= 10),
    model                     TEXT NOT NULL,
    quality                   TEXT NOT NULL,
    size                      TEXT NOT NULL,
    prompt_version            INTEGER NOT NULL,
    request_key               TEXT NOT NULL,
    status                    TEXT NOT NULL DEFAULT 'PENDING' CHECK (status = ANY (ARRAY['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED'])),
    failure_code              TEXT,
    gatekeeper_verdict        JSONB,
    source_content_hash       TEXT NOT NULL,
    source_width              INTEGER,
    source_height             INTEGER,
    target_weight_kg          NUMERIC(6,2),
    target_body_fat_percent   NUMERIC(5,2),
    body_fat_source           TEXT,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE public.ai_progress_ladder ENABLE ROW LEVEL SECURITY;

CREATE POLICY "ai_progress_ladder_select_own" ON public.ai_progress_ladder FOR SELECT USING (auth.uid() = user_id);
```

### `ai_progress_ladder_rung`

One row per rung (`kind='SOURCE'` for the uploaded photo at `step_index=0`, `kind='PROJECTION'` for each generated step). In the `supabase_realtime` publication so a dropped SSE connection doesn't lose progress.

```sql
CREATE TABLE IF NOT EXISTS public.ai_progress_ladder_rung (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ladder_id                   UUID NOT NULL REFERENCES public.ai_progress_ladder(id) ON DELETE CASCADE,
    user_id                     UUID NOT NULL,
    step_index                  INTEGER NOT NULL,
    projected_body_fat_percent  NUMERIC(5,2) NOT NULL,
    projected_weight_kg         NUMERIC(6,2) NOT NULL,
    storage_path                TEXT,
    openai_model                TEXT,
    usage_input_tokens          INTEGER,
    usage_output_tokens         INTEGER,
    usage_cached_input_tokens   INTEGER,
    cost_micros                 BIGINT,
    status                      TEXT NOT NULL DEFAULT 'PENDING' CHECK (status = ANY (ARRAY['PENDING', 'SUCCEEDED', 'FAILED'])),
    failure_code                TEXT,
    kind                        TEXT NOT NULL DEFAULT 'PROJECTION' CHECK (kind = ANY (ARRAY['SOURCE', 'PROJECTION'])),
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE public.ai_progress_ladder_rung ENABLE ROW LEVEL SECURITY;

CREATE POLICY "ai_progress_ladder_rung_select_own" ON public.ai_progress_ladder_rung FOR SELECT USING (auth.uid() = user_id);
```

---

## Entitlements

### `user_entitlement`

Server-authoritative premium/subscription status per user, driven by RevenueCat webhooks (`POST /webhooks/revenuecat`) plus lazy sync-on-miss from the coach's `PremiumGate`. See `docs/ENTITLEMENTS.md`. Client-**readable** only.

```sql
CREATE TABLE IF NOT EXISTS public.user_entitlement (
    user_id                  UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    entitlement_id           TEXT NOT NULL,
    active                   BOOLEAN NOT NULL,
    expires_at               TIMESTAMPTZ,
    grace_period_ends_at     TIMESTAMPTZ,
    product_id               TEXT,
    store                    TEXT,
    revenuecat_app_user_id   TEXT,
    revenuecat_environment   TEXT CHECK (revenuecat_environment = ANY (ARRAY['SANDBOX', 'PRODUCTION'])),
    source                   TEXT NOT NULL DEFAULT 'revenuecat',
    is_trial                 BOOLEAN NOT NULL DEFAULT false,
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, entitlement_id)
);

ALTER TABLE public.user_entitlement ENABLE ROW LEVEL SECURITY;

CREATE POLICY "user_entitlement_select_own" ON public.user_entitlement FOR SELECT USING (auth.uid() = user_id);
```

---

## Canonical Food Catalog

Shared global catalog of AI-synthesized canonical foods (e.g. "cheesecake", "flat white") used by Smart Food Search. Service-role write only — these are not user-scoped tables. RLS is enabled with **no policies**, so only `service_role` (which bypasses RLS) can read or write them. The backend uses a dedicated service-role client (`SUPABASE_SERVICE_ROLE_KEY`); never accessed via user-JWT paths.

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
    saturated_fat_g   REAL,
    cholesterol_mg    REAL,
    potassium_mg      REAL,
    calcium_mg        REAL,
    iron_mg           REAL
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

## OFF mirror

Production-only local mirror of the Open Food Facts catalog. See `AGENTS.md` → "OFF mirror" for the read/write path design, indexes, and ODbL attribution requirements. RLS enabled, **no policies** — service-role only.

```sql
CREATE TABLE IF NOT EXISTS public.off_food (
    code                TEXT PRIMARY KEY,
    product_name        TEXT,
    brands              TEXT[],
    primary_brand       TEXT DEFAULT brands[1],
    countries_tags      TEXT[],
    lang                TEXT,
    serving_size        TEXT,
    serving_quantity    NUMERIC,
    image_url           TEXT,
    energy_kcal_100g    NUMERIC,
    protein_100g        NUMERIC,
    carbs_100g          NUMERIC,
    sugars_100g         NUMERIC,
    fat_100g            NUMERIC,
    saturated_fat_100g  NUMERIC,
    fiber_100g          NUMERIC,
    sodium_100g         NUMERIC,
    nutriments          JSONB,
    last_modified_t     BIGINT NOT NULL,
    synced_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS public.off_sync_state (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_kind               TEXT NOT NULL CHECK (job_kind = ANY (ARRAY['DELTA', 'FULL'])),
    started_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at            TIMESTAMPTZ,
    status                 TEXT NOT NULL DEFAULT 'RUNNING' CHECK (status = ANY (ARRAY['RUNNING', 'OK', 'FAILED', 'CANCELLED'])),
    last_modified_t_max    BIGINT,
    rows_inserted          BIGINT NOT NULL DEFAULT 0,
    rows_updated           BIGINT NOT NULL DEFAULT 0,
    rows_soft_deleted      BIGINT NOT NULL DEFAULT 0,
    delta_files_processed  TEXT[],
    error_message          TEXT,
    dry_run                BOOLEAN NOT NULL DEFAULT false
);

ALTER TABLE public.off_food ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.off_sync_state ENABLE ROW LEVEL SECURITY;
```

---

## USDA mirror

Production-only local mirror of USDA FoodData Central (Branded + Foundation). See `AGENTS.md` → "USDA mirror" for the read/write path design and unit-divergence notes (`sodium_100g` is mg, not g). RLS enabled, **no policies** — service-role only.

```sql
CREATE TABLE IF NOT EXISTS public.usda_food (
    fdc_id                        BIGINT PRIMARY KEY,
    data_type                     TEXT NOT NULL CHECK (data_type = ANY (ARRAY['branded_food', 'foundation_food'])),
    description                   TEXT NOT NULL,
    brand_owner                   TEXT,
    brand_name                    TEXT,
    branded_food_category          TEXT,
    market_country                TEXT,
    gtin_upc                       TEXT,
    ingredients                    TEXT,
    serving_size                   NUMERIC,
    serving_size_unit               TEXT,
    household_serving_full_text    TEXT,
    energy_kcal_100g                NUMERIC,
    protein_100g                    NUMERIC,
    carbs_100g                      NUMERIC,
    sugars_100g                     NUMERIC,
    fat_100g                        NUMERIC,
    saturated_fat_100g               NUMERIC,
    fiber_100g                       NUMERIC,
    sodium_100g                      NUMERIC,  -- milligrams, not grams (FDC nutrient 1093)
    nutriments                      JSONB NOT NULL DEFAULT '{}',
    publication_date                 DATE,
    modified_date                    DATE,
    available_date                   DATE,
    synced_at                        TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at                       TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS public.usda_sync_state (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_kind            TEXT NOT NULL CHECK (job_kind = 'FULL'),
    started_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at         TIMESTAMPTZ,
    status              TEXT NOT NULL DEFAULT 'RUNNING' CHECK (status = ANY (ARRAY['RUNNING', 'OK', 'FAILED', 'CANCELLED', 'NO_NEW_RELEASE'])),
    release_date        DATE,
    rows_inserted       BIGINT NOT NULL DEFAULT 0,
    rows_updated        BIGINT NOT NULL DEFAULT 0,
    rows_soft_deleted   BIGINT NOT NULL DEFAULT 0,
    error_message       TEXT,
    dry_run             BOOLEAN NOT NULL DEFAULT false
);

ALTER TABLE public.usda_food ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.usda_sync_state ENABLE ROW LEVEL SECURITY;
```

---

## AI Coach ("Fitzy")

Full design + ops runbook: `docs/AI_COACH.md`. Client wire contract: `docs/AI_COACH_CLIENT.md`. All tables below are premium-gated via `user_entitlement`. Client access is **SELECT-only** on the `public` tables (writes are the coach service, service-role); `coach_kb_doc`, `coach_kb_chunk`, and everything in `coach_internal` have RLS enabled with **no policies at all** (service-role only, not even client SELECT).

### `coach_chat`

One row per conversation thread.

```sql
CREATE TABLE IF NOT EXISTS public.coach_chat (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    title            TEXT NOT NULL DEFAULT 'New chat',
    title_generated  BOOLEAN NOT NULL DEFAULT false,
    locale           TEXT NOT NULL CHECK (char_length(locale) >= 2 AND char_length(locale) <= 32),
    message_count    INTEGER NOT NULL DEFAULT 0 CHECK (message_count >= 0),
    last_message_at  TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    archived_at      TIMESTAMPTZ,
    UNIQUE (id, user_id)
);

ALTER TABLE public.coach_chat ENABLE ROW LEVEL SECURITY;
CREATE POLICY "coach_chat_select_own" ON public.coach_chat FOR SELECT USING (auth.uid() = user_id);
```

### `coach_message`

One row per user/assistant turn. `(chat_id, user_id)` FK'd to `coach_chat(id, user_id)` so a message can't be misattached to another user's chat.

```sql
CREATE TABLE IF NOT EXISTS public.coach_message (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chat_id             UUID NOT NULL,
    user_id             UUID NOT NULL,
    request_id          UUID NOT NULL,
    role                TEXT NOT NULL CHECK (role = ANY (ARRAY['user', 'assistant'])),
    content             TEXT NOT NULL CHECK (char_length(content) >= 1 AND char_length(content) <= 20000),
    citations           JSONB,
    input_tokens        INTEGER CHECK (input_tokens >= 0),
    output_tokens       INTEGER CHECK (output_tokens >= 0),
    cached_tokens       INTEGER CHECK (cached_tokens >= 0),
    pro_input_tokens    INTEGER CHECK (pro_input_tokens >= 0),
    pro_output_tokens   INTEGER CHECK (pro_output_tokens >= 0),
    model_used          TEXT,
    escalated           BOOLEAN NOT NULL DEFAULT false,
    safety_action       TEXT,
    finish_reason       TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, request_id, role),
    FOREIGN KEY (chat_id, user_id) REFERENCES public.coach_chat(id, user_id) ON DELETE CASCADE
);

ALTER TABLE public.coach_message ENABLE ROW LEVEL SECURITY;
CREATE POLICY "coach_message_select_own" ON public.coach_message FOR SELECT USING (auth.uid() = user_id);
```

### `coach_summary`

Rolling conversation summary, one row per `(chat_id, up_to_message_id)` checkpoint.

```sql
CREATE TABLE IF NOT EXISTS public.coach_summary (
    chat_id           UUID NOT NULL,
    up_to_message_id  UUID NOT NULL REFERENCES public.coach_message(id) ON DELETE CASCADE,
    user_id           UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    summary           TEXT NOT NULL CHECK (char_length(summary) >= 1 AND char_length(summary) <= 5000),
    tokens            INTEGER NOT NULL CHECK (tokens >= 0),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (chat_id, up_to_message_id),
    FOREIGN KEY (chat_id) REFERENCES public.coach_chat(id) ON DELETE CASCADE,
    FOREIGN KEY (chat_id, user_id) REFERENCES public.coach_chat(id, user_id) ON DELETE CASCADE
);

ALTER TABLE public.coach_summary ENABLE ROW LEVEL SECURITY;
CREATE POLICY "coach_summary_select_own" ON public.coach_summary FOR SELECT USING (auth.uid() = user_id);
```

### `coach_trace`

Per-message diagnostic trace (RAG retrieval, tool calls, safety events, latency). Used for debugging/observability, not shown to the client UI in normal operation.

```sql
CREATE TABLE IF NOT EXISTS public.coach_trace (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id       UUID NOT NULL REFERENCES public.coach_message(id) ON DELETE CASCADE,
    user_id          UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    rag_query_hash   TEXT,
    retrieved        JSONB,
    tool_calls       JSONB,
    safety_events    JSONB,
    duration_ms      INTEGER CHECK (duration_ms >= 0),
    prompt_version   INTEGER NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE public.coach_trace ENABLE ROW LEVEL SECURITY;
CREATE POLICY "coach_trace_select_own" ON public.coach_trace FOR SELECT USING (auth.uid() = user_id);
```

### `coach_user_note`

Cross-chat memory: coach-extracted or user-stated preferences/restrictions/goal context.

```sql
CREATE TABLE IF NOT EXISTS public.coach_user_note (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    note        TEXT NOT NULL CHECK (char_length(note) >= 1 AND char_length(note) <= 500),
    category    TEXT NOT NULL CHECK (category = ANY (ARRAY['preference', 'restriction', 'goal_context', 'other'])),
    source      TEXT NOT NULL CHECK (source = ANY (ARRAY['coach', 'user'])),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE public.coach_user_note ENABLE ROW LEVEL SECURITY;
CREATE POLICY "coach_user_note_select_own" ON public.coach_user_note FOR SELECT USING (auth.uid() = user_id);
```

### `coach_budget`

Monthly token/credit budget usage per user, `PRIMARY KEY (user_id, period_yyyymm)`.

```sql
CREATE TABLE IF NOT EXISTS public.coach_budget (
    user_id             UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    period_yyyymm       INTEGER NOT NULL CHECK (period_yyyymm >= 202001 AND period_yyyymm <= 299912),
    messages_used       INTEGER NOT NULL DEFAULT 0 CHECK (messages_used >= 0),
    input_tokens_used   BIGINT NOT NULL DEFAULT 0 CHECK (input_tokens_used >= 0),
    output_tokens_used  BIGINT NOT NULL DEFAULT 0 CHECK (output_tokens_used >= 0),
    credits_used        BIGINT NOT NULL DEFAULT 0 CHECK (credits_used >= 0),
    pro_credits_used    BIGINT NOT NULL DEFAULT 0 CHECK (pro_credits_used >= 0),
    last_message_at     TIMESTAMPTZ,
    PRIMARY KEY (user_id, period_yyyymm)
);

ALTER TABLE public.coach_budget ENABLE ROW LEVEL SECURITY;
CREATE POLICY "coach_budget_select_own" ON public.coach_budget FOR SELECT USING (auth.uid() = user_id);
```

### `coach_credit_topup`

One row per purchased token top-up (RevenueCat consumable), tracking remaining balance.

```sql
CREATE TABLE IF NOT EXISTS public.coach_credit_topup (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    rc_transaction_id   TEXT NOT NULL UNIQUE,
    product_id          TEXT NOT NULL,
    store               TEXT,
    environment         TEXT,
    credits_granted     BIGINT NOT NULL CHECK (credits_granted > 0),
    credits_remaining   BIGINT NOT NULL CHECK (credits_remaining >= 0 AND credits_remaining <= credits_granted),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ
);

ALTER TABLE public.coach_credit_topup ENABLE ROW LEVEL SECURITY;
CREATE POLICY "coach_credit_topup_select_own" ON public.coach_credit_topup FOR SELECT USING (user_id = auth.uid());
```

### `coach_kb_doc` / `coach_kb_chunk`

Curated RAG knowledge base. `coach_kb_doc` is one row per source document (`id` is a slug, e.g. `nutrition/protein-basics`); `coach_kb_chunk` is one row per embedded chunk. **Service-role only — RLS enabled, no policies at all** (not even client SELECT).

```sql
CREATE TABLE IF NOT EXISTS public.coach_kb_doc (
    id           TEXT PRIMARY KEY CHECK (id ~ '^[a-z0-9][a-z0-9_/-]{2,159}$'),
    title        TEXT NOT NULL CHECK (char_length(title) >= 1 AND char_length(title) <= 300),
    section      TEXT NOT NULL CHECK (section = ANY (ARRAY['app', 'nutrition', 'training', 'recipes', 'general'])),
    locale       TEXT NOT NULL DEFAULT 'en' CHECK (locale = 'en'),
    content_md   TEXT NOT NULL CHECK (char_length(content_md) >= 1 AND char_length(content_md) <= 200000),
    content_hash TEXT NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    source_uri   TEXT,
    version      INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.coach_kb_chunk (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_id                    TEXT NOT NULL REFERENCES public.coach_kb_doc(id) ON DELETE CASCADE,
    section                   TEXT NOT NULL CHECK (section = ANY (ARRAY['app', 'nutrition', 'training', 'recipes', 'general'])),
    chunk_index               INTEGER NOT NULL CHECK (chunk_index >= 0),
    text                      TEXT NOT NULL CHECK (char_length(text) >= 1 AND char_length(text) <= 12000),
    tokens                    INTEGER NOT NULL CHECK (tokens >= 1 AND tokens <= 8192),
    embedding                 vector(768) NOT NULL,
    embedding_model           TEXT NOT NULL,
    embedding_dim             INTEGER NOT NULL CHECK (embedding_dim = 768),
    embedding_format_version  TEXT NOT NULL,
    metadata                  JSONB,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (doc_id, chunk_index)
);

ALTER TABLE public.coach_kb_doc ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.coach_kb_chunk ENABLE ROW LEVEL SECURITY;
```

> `embedding` uses the `vector` (pgvector) extension, 768-dim to match `gemini-embedding-2`. `coach-ingest` (Cloud Run Job) is the only writer.

### `coach_internal` schema (service-role only, no client access whatsoever)

Internal bookkeeping — request de-dup, in-flight turn leasing, and budget reservation/reconciliation. Not exposed via PostgREST to any client role; RLS is enabled with no policies as a defense-in-depth measure even though the schema itself isn't reachable by `anon`/`authenticated`.

```sql
CREATE TABLE IF NOT EXISTS coach_internal.coach_turn (
    user_id               UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    request_id            UUID NOT NULL,
    chat_id               UUID NOT NULL,
    state                 TEXT NOT NULL CHECK (state = ANY (ARRAY['processing', 'completed', 'failed'])),
    lease_expires_at      TIMESTAMPTZ,
    attempts              INTEGER NOT NULL DEFAULT 1 CHECK (attempts > 0),
    user_message_id       UUID REFERENCES public.coach_message(id) ON DELETE SET NULL,
    assistant_message_id  UUID REFERENCES public.coach_message(id) ON DELETE SET NULL,
    last_error            TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, request_id),
    FOREIGN KEY (chat_id, user_id) REFERENCES public.coach_chat(id, user_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS coach_internal.coach_budget_reservation (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    period_yyyymm     INTEGER NOT NULL,
    request_id        UUID NOT NULL,
    reserved_input    INTEGER NOT NULL CHECK (reserved_input >= 0),
    reserved_output   INTEGER NOT NULL CHECK (reserved_output >= 0),
    actual_input      INTEGER CHECK (actual_input >= 0),
    actual_output     INTEGER CHECK (actual_output >= 0),
    state             TEXT NOT NULL CHECK (state = ANY (ARRAY['reserved', 'reconciled', 'released', 'exempt'])),
    reserved_credits  BIGINT CHECK (reserved_credits >= 0),
    actual_credits    BIGINT CHECK (actual_credits >= 0),
    monthly_credits   BIGINT CHECK (monthly_credits >= 0),
    cap_credits       BIGINT CHECK (cap_credits >= 1),
    topup_draws       JSONB NOT NULL DEFAULT '[]',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    settled_at        TIMESTAMPTZ,
    UNIQUE (user_id, request_id),
    FOREIGN KEY (user_id, period_yyyymm) REFERENCES public.coach_budget(user_id, period_yyyymm) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS coach_internal.processed_revenuecat_event (
    event_id              TEXT PRIMARY KEY,
    state                 TEXT NOT NULL CHECK (state = ANY (ARRAY['processing', 'processed', 'failed'])),
    attempts              INTEGER NOT NULL DEFAULT 1 CHECK (attempts > 0),
    received_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at            TIMESTAMPTZ,
    processed_at          TIMESTAMPTZ,
    last_error            TEXT,
    event_type            TEXT NOT NULL,
    payload               JSONB NOT NULL,
    identity_candidates   TEXT[] NOT NULL
);

ALTER TABLE coach_internal.coach_turn ENABLE ROW LEVEL SECURITY;
ALTER TABLE coach_internal.coach_budget_reservation ENABLE ROW LEVEL SECURITY;
ALTER TABLE coach_internal.processed_revenuecat_event ENABLE ROW LEVEL SECURITY;
```

> `coach_budget_reservation.topup_draws` records which `coach_credit_topup` rows were drawn down to cover a reservation, for reconciliation. `processed_revenuecat_event` is the RevenueCat webhook idempotency ledger — see `docs/AI_COACH.md` for the `coach-rc-sweeper` recovery job.

---

## `rls_mode_config`

Controls whether RLS runs in dev-bypass mode via `is_dev_rls_mode()` (see "RLS helper functions"). Keep `dev_mode = false` in production.

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

To temporarily enable during local testing:
```sql
UPDATE public.rls_mode_config SET dev_mode = true WHERE singleton = true;
-- remember to reset:
UPDATE public.rls_mode_config SET dev_mode = false WHERE singleton = true;
```

---

## Ownership summary

| Table | Written by | Client access |
|---|---|---|
| `user_profile` | Backend only (`POST /api/user/register`) | Full CRUD (own rows) |
| `user_goal` | Backend only (`POST /api/user/register`) | Full CRUD (own rows) |
| `calorie_target` | Backend only (`POST /api/user/register`) | Full CRUD (own rows) |
| `calorie_target_history` | Backend only (append on target change) | Full CRUD (own rows, single `ALL` policy) |
| `weight_entry` | Client sync | Full CRUD (own rows) |
| `daily_activity` | Client sync (health platform) | Full CRUD (own rows) |
| `journey` | Client sync | Full CRUD (own rows) |
| `diary_entry` | Client sync | Full CRUD (own rows) |
| `diary_entry_ingredient` | Client sync | Full CRUD (own rows) |
| `food_item` | Client sync | Full CRUD (own rows) |
| `food_item_serving` | Client sync | Full CRUD (own rows) |
| `my_meal` | Client sync | Full CRUD (own rows) |
| `my_meal_ingredient` | Client sync | Full CRUD (own rows) |
| `recent_food` | Client sync | Full CRUD (own rows) |
| `progress_photo` | Client upload (Supabase Storage) | Full CRUD (own rows, single `ALL` policy) |
| `ai_progress_ladder` | Backend only (service-role) | SELECT own rows |
| `ai_progress_ladder_rung` | Backend only (service-role) | SELECT own rows |
| `user_entitlement` | Backend only (RevenueCat webhook + lazy sync) | SELECT own rows |
| `rls_mode_config` | Manual seed | None (no policies; service-role bypass) |
| `canonical_food_*` (4 tables) | Backend only (service-role, via `insert_canonical_foods` RPC) | None (no policies) |
| `off_food`, `off_sync_state` | Ingest Job only (service-role) | None (no policies) |
| `usda_food`, `usda_sync_state` | Ingest Job only (service-role) | None (no policies) |
| `coach_chat`, `coach_message`, `coach_summary`, `coach_trace`, `coach_user_note`, `coach_budget`, `coach_credit_topup` | Coach service only (service-role) | SELECT own rows |
| `coach_kb_doc`, `coach_kb_chunk` | `coach-ingest` Job only (service-role) | None (no policies) |
| `coach_internal.*` (3 tables) | Coach service only (service-role) | None — not exposed via PostgREST, RLS with no policies as defense-in-depth |
