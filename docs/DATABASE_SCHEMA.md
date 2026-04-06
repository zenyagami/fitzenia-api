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
    birth_date       TEXT NOT NULL,
    sex              TEXT NOT NULL,
    height_cm        DOUBLE PRECISION NOT NULL,
    created_at       BIGINT NOT NULL,
    last_modified_at BIGINT NOT NULL,
    user_id          UUID NOT NULL DEFAULT auth.uid()
);

ALTER TABLE public.user_profile ENABLE ROW LEVEL SECURITY;

CREATE POLICY "user_profile: owner access"
    ON public.user_profile
    USING (user_id = auth.uid());
```

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

CREATE POLICY "user_goal: owner access"
    ON public.user_goal
    USING (user_id = auth.uid());
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

CREATE POLICY "calorie_target: owner access"
    ON public.calorie_target
    USING (user_id = auth.uid());
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

---

## RLS dev-bypass pattern

`rls_mode_config.dev_mode` is used by RLS policies on client-synced tables to allow the backend service role to skip per-user filtering during development. In production this must be `false`.

To temporarily enable during local testing:
```sql
UPDATE public.rls_mode_config SET dev_mode = true WHERE singleton = true;
-- remember to reset:
UPDATE public.rls_mode_config SET dev_mode = false WHERE singleton = true;
```
