-- Migration: 004_usda_mirror
-- Purpose: Local mirror of the USDA FoodData Central (FDC) Branded + Foundation
--          datasets. A monthly Cloud Run Job probes for a new release; same
--          release → no-op. Bi-annual real ingest (April + December). Fitzenia-api
--          consults this table BEFORE the live FDC API for both barcode lookup
--          and SmartSearchOrchestrator candidate recall. Service-role write only.
--          RLS enabled with no policies (service-role bypasses RLS).
--
--          Schema applied to BOTH dev and prod (parity prevents drift bugs);
--          ingestion runs in prod only. Dev copy stays empty unless
--          USDA_MIRROR_WRITE_ENABLED is explicitly flipped on.
--
-- Apply: psql against the Supabase project (no migration runner exists in this repo).
-- Plan ref: /Users/zenkun/.claude/plans/enchanted-bubbling-chipmunk.md

-- pg_trgm provides the `%` operator and `similarity()` function used by
-- `usda_search_candidates` below. Already enabled by 002_off_mirror.sql; the
-- IF NOT EXISTS guard makes this idempotent.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ---------------------------------------------------------------------------
-- usda_food
-- ---------------------------------------------------------------------------
-- Hybrid schema: common per-100g macros are flattened into typed columns for
-- direct query/index; long-tail nutrients live in `nutriments` JSONB keyed by
-- FDC nutrientId.
--
-- Unit conventions (match the live FDC API + UsdaMapper.kt):
--   - energy_kcal_100g .. fiber_100g  → grams per 100g (FDC nutrientId 1004,1005,1003,1079,1258,2000)
--   - sodium_100g                     → milligrams per 100g (FDC nutrientId 1093 reports in mg natively)
--   - nutriments JSONB                → keyed "1087", "1089", etc., values in FDC's native unit per 100g
--
-- This deliberately diverges from the OFF mirror (which stores sodium in grams,
-- multiplied ×1000 in the mapper) so the USDA mirror mapper can pass values
-- through unchanged.
CREATE TABLE IF NOT EXISTS public.usda_food (
    fdc_id                       BIGINT PRIMARY KEY,
    -- 'branded_food' | 'foundation_food'. Foundation = generic whole foods.
    data_type                    TEXT NOT NULL CHECK (data_type IN ('branded_food', 'foundation_food')),
    description                  TEXT NOT NULL,
    brand_owner                  TEXT,
    brand_name                   TEXT,
    branded_food_category        TEXT,
    market_country               TEXT,
    -- Barcode (branded only). Not the PK — fdc_id is the FDC stable identifier.
    gtin_upc                     TEXT,
    ingredients                  TEXT,
    -- Serving context (branded only — Foundation rows leave these null and
    -- the mapper emits a single "100g" serving).
    serving_size                 NUMERIC,
    serving_size_unit            TEXT,
    household_serving_full_text  TEXT,
    -- Flattened common macros (per 100g). See unit notes above.
    energy_kcal_100g             NUMERIC,
    protein_100g                 NUMERIC,
    carbs_100g                   NUMERIC,
    sugars_100g                  NUMERIC,
    fat_100g                     NUMERIC,
    saturated_fat_100g           NUMERIC,
    fiber_100g                   NUMERIC,
    sodium_100g                  NUMERIC,
    -- Long-tail nutrients live here, keyed by FDC nutrientId as a string
    -- (e.g. "1087" for calcium, "1089" for iron). Matches UsdaNutrientId
    -- constants in UsdaMapper.kt for direct lookup at read time.
    nutriments                   JSONB NOT NULL DEFAULT '{}'::jsonb,
    publication_date             DATE,
    modified_date                DATE,
    available_date               DATE,
    synced_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at                   TIMESTAMPTZ
);

-- Barcode PK lookup. Sparse index (only branded rows have gtin_upc).
CREATE INDEX IF NOT EXISTS idx_usda_food_gtin_active
    ON public.usda_food (gtin_upc)
    WHERE deleted_at IS NULL AND gtin_upc IS NOT NULL;

-- pg_trgm fuzzy recall for SmartSearch candidatesFor.
CREATE INDEX IF NOT EXISTS idx_usda_food_desc_trgm
    ON public.usda_food USING gin (description gin_trgm_ops)
    WHERE deleted_at IS NULL;

-- Filter Branded vs Foundation when ranking GENERIC vs BRANDED candidates.
CREATE INDEX IF NOT EXISTS idx_usda_food_data_type
    ON public.usda_food (data_type)
    WHERE deleted_at IS NULL;

-- Brand recall (sparse — Foundation rows are unbranded).
CREATE INDEX IF NOT EXISTS idx_usda_food_brand_owner_trgm
    ON public.usda_food USING gin (brand_owner gin_trgm_ops)
    WHERE deleted_at IS NULL AND brand_owner IS NOT NULL;

-- Required by `soft_delete_usda_unseen` so the soft-delete pass after a full
-- reconcile is a partial-index scan, not a 430k-row sequential scan.
CREATE INDEX IF NOT EXISTS idx_usda_food_synced_at_active
    ON public.usda_food (synced_at)
    WHERE deleted_at IS NULL;

ALTER TABLE public.usda_food ENABLE ROW LEVEL SECURITY;
-- No policies: anon/authenticated denied by default; service-role bypasses RLS.

-- ---------------------------------------------------------------------------
-- usda_sync_state — release checkpoint + run audit
-- ---------------------------------------------------------------------------
-- One job kind ('FULL' — FDC ships only full snapshots, no deltas). The
-- `release_date` column stores the YYYY-MM-DD parsed from the FDC zip URL so
-- the next run can no-op when the same release is already mirrored.
CREATE TABLE IF NOT EXISTS public.usda_sync_state (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_kind               TEXT NOT NULL CHECK (job_kind IN ('FULL')),
    started_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at            TIMESTAMPTZ,
    -- NO_NEW_RELEASE: same release_date as the last OK row → no work performed.
    status                 TEXT NOT NULL DEFAULT 'RUNNING'
        CHECK (status IN ('RUNNING', 'OK', 'FAILED', 'CANCELLED', 'NO_NEW_RELEASE')),
    -- The YYYY-MM-DD parsed from the FDC zip URL (e.g. '2026-04-22' from
    -- FoodData_Central_branded_food_json_2026-04-22.zip). When branded and
    -- foundation have different dates we store the max (the most recent).
    release_date           DATE,
    rows_inserted          BIGINT NOT NULL DEFAULT 0,
    rows_updated           BIGINT NOT NULL DEFAULT 0,
    rows_soft_deleted      BIGINT NOT NULL DEFAULT 0,
    error_message          TEXT,
    dry_run                BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_usda_sync_state_finished
    ON public.usda_sync_state (finished_at DESC)
    WHERE status = 'OK';

CREATE INDEX IF NOT EXISTS idx_usda_sync_state_release
    ON public.usda_sync_state (release_date DESC)
    WHERE status = 'OK';

ALTER TABLE public.usda_sync_state ENABLE ROW LEVEL SECURITY;
-- No policies.

-- ---------------------------------------------------------------------------
-- upsert_usda_foods RPC — bulk UPSERT from a JSONB array
-- ---------------------------------------------------------------------------
-- Always refreshes `synced_at` on conflict (so unchanged rows are not
-- accidentally tombstoned by the soft-delete pass). Resurrects soft-deleted
-- rows by clearing `deleted_at` when the same fdc_id reappears.
CREATE OR REPLACE FUNCTION public.upsert_usda_foods(items JSONB)
RETURNS TABLE(inserted BIGINT, updated BIGINT)
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_inserted BIGINT;
    v_updated  BIGINT;
BEGIN
    WITH source AS (
        SELECT * FROM jsonb_to_recordset(items) AS x(
            fdc_id                      BIGINT,
            data_type                   TEXT,
            description                 TEXT,
            brand_owner                 TEXT,
            brand_name                  TEXT,
            branded_food_category       TEXT,
            market_country              TEXT,
            gtin_upc                    TEXT,
            ingredients                 TEXT,
            serving_size                NUMERIC,
            serving_size_unit           TEXT,
            household_serving_full_text TEXT,
            energy_kcal_100g            NUMERIC,
            protein_100g                NUMERIC,
            carbs_100g                  NUMERIC,
            sugars_100g                 NUMERIC,
            fat_100g                    NUMERIC,
            saturated_fat_100g          NUMERIC,
            fiber_100g                  NUMERIC,
            sodium_100g                 NUMERIC,
            nutriments                  JSONB,
            publication_date            DATE,
            modified_date               DATE,
            available_date              DATE
        )
    ),
    upserted AS (
        INSERT INTO public.usda_food (
            fdc_id, data_type, description, brand_owner, brand_name,
            branded_food_category, market_country, gtin_upc, ingredients,
            serving_size, serving_size_unit, household_serving_full_text,
            energy_kcal_100g, protein_100g, carbs_100g, sugars_100g, fat_100g,
            saturated_fat_100g, fiber_100g, sodium_100g,
            nutriments, publication_date, modified_date, available_date,
            synced_at, deleted_at
        )
        SELECT
            s.fdc_id, s.data_type, s.description, s.brand_owner, s.brand_name,
            s.branded_food_category, s.market_country, s.gtin_upc, s.ingredients,
            s.serving_size, s.serving_size_unit, s.household_serving_full_text,
            s.energy_kcal_100g, s.protein_100g, s.carbs_100g, s.sugars_100g, s.fat_100g,
            s.saturated_fat_100g, s.fiber_100g, s.sodium_100g,
            COALESCE(s.nutriments, '{}'::jsonb), s.publication_date, s.modified_date, s.available_date,
            now(), NULL
        FROM source s
        WHERE s.fdc_id IS NOT NULL
          AND s.data_type IS NOT NULL
          AND s.description IS NOT NULL
        ON CONFLICT (fdc_id) DO UPDATE SET
            data_type                   = EXCLUDED.data_type,
            description                 = EXCLUDED.description,
            brand_owner                 = EXCLUDED.brand_owner,
            brand_name                  = EXCLUDED.brand_name,
            branded_food_category       = EXCLUDED.branded_food_category,
            market_country              = EXCLUDED.market_country,
            gtin_upc                    = EXCLUDED.gtin_upc,
            ingredients                 = EXCLUDED.ingredients,
            serving_size                = EXCLUDED.serving_size,
            serving_size_unit           = EXCLUDED.serving_size_unit,
            household_serving_full_text = EXCLUDED.household_serving_full_text,
            energy_kcal_100g            = EXCLUDED.energy_kcal_100g,
            protein_100g                = EXCLUDED.protein_100g,
            carbs_100g                  = EXCLUDED.carbs_100g,
            sugars_100g                 = EXCLUDED.sugars_100g,
            fat_100g                    = EXCLUDED.fat_100g,
            saturated_fat_100g          = EXCLUDED.saturated_fat_100g,
            fiber_100g                  = EXCLUDED.fiber_100g,
            sodium_100g                 = EXCLUDED.sodium_100g,
            nutriments                  = EXCLUDED.nutriments,
            publication_date            = EXCLUDED.publication_date,
            modified_date               = EXCLUDED.modified_date,
            available_date              = EXCLUDED.available_date,
            synced_at                   = now(),
            deleted_at                  = NULL
        RETURNING (xmax = 0) AS was_insert
    )
    SELECT
        COUNT(*) FILTER (WHERE was_insert),
        COUNT(*) FILTER (WHERE NOT was_insert)
    INTO v_inserted, v_updated
    FROM upserted;

    inserted := COALESCE(v_inserted, 0);
    updated  := COALESCE(v_updated, 0);
    RETURN NEXT;
END;
$$;

REVOKE EXECUTE ON FUNCTION public.upsert_usda_foods(JSONB) FROM PUBLIC, anon, authenticated;
GRANT  EXECUTE ON FUNCTION public.upsert_usda_foods(JSONB) TO service_role;

-- ---------------------------------------------------------------------------
-- usda_search_candidates RPC — pg_trgm-ranked candidate recall for SmartSearch
-- ---------------------------------------------------------------------------
-- Returns up to `p_limit` undeleted usda_food rows whose description fuzzy-
-- matches `p_query`. NO country parameter — FDC is US-only data.
-- Service-role only.
CREATE OR REPLACE FUNCTION public.usda_search_candidates(
    p_query TEXT,
    p_limit INT DEFAULT 25
)
RETURNS SETOF public.usda_food
LANGUAGE sql
SECURITY INVOKER
-- Both `public` and `extensions` are listed so the unqualified `%` operator
-- and `similarity()` function resolve regardless of which schema this
-- Supabase project's pg_trgm is installed into.
SET search_path = public, extensions, pg_temp
AS $$
    SELECT *
    FROM public.usda_food
    WHERE deleted_at IS NULL
      AND description % p_query
    ORDER BY similarity(description, p_query) DESC
    LIMIT p_limit;
$$;

REVOKE EXECUTE ON FUNCTION public.usda_search_candidates(TEXT, INT) FROM PUBLIC, anon, authenticated;
GRANT  EXECUTE ON FUNCTION public.usda_search_candidates(TEXT, INT) TO service_role;

-- ---------------------------------------------------------------------------
-- soft_delete_usda_unseen RPC — full-reconcile soft-delete pass
-- ---------------------------------------------------------------------------
-- Tombstones every undeleted row whose `synced_at` is older than `p_before`.
-- Called once at the END of a successful full reconcile run; `p_before` is
-- the run-start wall clock captured BEFORE streaming began. Because the
-- upsert RPC refreshes `synced_at = now()` on every conflict (matched or not),
-- any row missed by the dump is the only one left under the cutoff and gets
-- soft-deleted here. Returns the row count for the audit log.
CREATE OR REPLACE FUNCTION public.soft_delete_usda_unseen(p_before TIMESTAMPTZ)
RETURNS BIGINT
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public, pg_temp
AS $$
DECLARE v_count BIGINT;
BEGIN
    WITH d AS (
        UPDATE public.usda_food
        SET deleted_at = now()
        WHERE deleted_at IS NULL
          AND synced_at  < p_before
        RETURNING 1
    )
    SELECT COUNT(*) INTO v_count FROM d;
    RETURN v_count;
END;
$$;

REVOKE EXECUTE ON FUNCTION public.soft_delete_usda_unseen(TIMESTAMPTZ) FROM PUBLIC, anon, authenticated;
GRANT  EXECUTE ON FUNCTION public.soft_delete_usda_unseen(TIMESTAMPTZ) TO service_role;

-- ---------------------------------------------------------------------------
-- Rollback (commented out — copy and run manually if needed)
-- ---------------------------------------------------------------------------
-- DROP FUNCTION IF EXISTS public.soft_delete_usda_unseen(TIMESTAMPTZ);
-- DROP FUNCTION IF EXISTS public.usda_search_candidates(TEXT, INT);
-- DROP FUNCTION IF EXISTS public.upsert_usda_foods(JSONB);
-- DROP TABLE    IF EXISTS public.usda_sync_state;
-- DROP TABLE    IF EXISTS public.usda_food;
