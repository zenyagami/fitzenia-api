-- Migration: 002_off_mirror
-- Purpose: Local mirror of the Open Food Facts (OFF) catalog.
--          Daily delta + weekly full-reconcile populate this table; Fitzenia-api
--          reads it first for barcode lookups and as candidate recall in
--          SmartSearchOrchestrator. Service-role write only. RLS enabled with no
--          policies (service-role bypasses RLS).
--
--          Schema applied to BOTH dev and prod (parity prevents drift bugs);
--          ingestion runs in prod only. Dev copy stays empty unless OFF_MIRROR_WRITE_ENABLED
--          is explicitly flipped on.
--
-- Apply: psql against the Supabase project (no migration runner exists in this repo).
-- Plan ref: /Users/zenkun/.claude/plans/we-need-to-come-rosy-stonebraker.md

-- pg_trgm provides the `%` operator and `similarity()` function used by
-- `off_search_candidates` below.
--
-- On Supabase, the extension can live in either schema depending on when the
-- project was created — newer projects put extensions in the `extensions`
-- schema, older projects keep them in `public`. We don't relocate it here
-- (`IF NOT EXISTS` is a no-op when already present); instead the function's
-- search_path includes BOTH schemas so the unqualified operator resolves
-- regardless of where pg_trgm lives.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ---------------------------------------------------------------------------
-- off_food
-- ---------------------------------------------------------------------------
-- Hybrid schema: common per-100g macros are flattened into typed columns for
-- direct query/index; long-tail nutrients live in `nutriments` JSONB.
CREATE TABLE IF NOT EXISTS public.off_food (
                                               code                    TEXT PRIMARY KEY,
                                               product_name            TEXT,
                                               product_name_localized  JSONB,                          -- {"en": "...", "es": "..."} compiled from product_name_<lc>
                                               brands                  TEXT[],                         -- v3 list, v1 csv-split — always normalized to array
                                               primary_brand           TEXT GENERATED ALWAYS AS (brands[1]) STORED,
    countries_tags          TEXT[],                         -- e.g. ["en:united-states", "en:mexico"]
    lang                    TEXT,
    lc                      TEXT,
    serving_size            TEXT,
    serving_quantity        NUMERIC,
    image_url               TEXT,
    -- Flattened common macros (per 100g)
    energy_kcal_100g        NUMERIC,
    protein_100g            NUMERIC,
    carbs_100g              NUMERIC,
    sugars_100g             NUMERIC,
    fat_100g                NUMERIC,
    saturated_fat_100g      NUMERIC,
    fiber_100g              NUMERIC,
    sodium_100g             NUMERIC,
    -- Long-tail nutrients live here
    nutriments              JSONB,
    completeness            NUMERIC,
    last_modified_t         BIGINT NOT NULL,
    rev                     INTEGER,
    synced_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at              TIMESTAMPTZ
    );

CREATE INDEX IF NOT EXISTS idx_off_food_name_trgm
    ON public.off_food USING gin (product_name gin_trgm_ops)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_off_food_countries
    ON public.off_food USING gin (countries_tags)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_off_food_modified
    ON public.off_food (last_modified_t);

CREATE INDEX IF NOT EXISTS idx_off_food_brand
    ON public.off_food (primary_brand)
    WHERE deleted_at IS NULL;

-- Required by `soft_delete_off_unseen` so the full-reconcile tombstone pass is
-- a partial-index scan, not a 3M-row sequential scan. Without this the UPDATE
-- can blow PostgREST's statement_timeout on weekly full runs.
CREATE INDEX IF NOT EXISTS idx_off_food_synced_at_active
    ON public.off_food (synced_at)
    WHERE deleted_at IS NULL;

ALTER TABLE public.off_food ENABLE ROW LEVEL SECURITY;
-- No policies: anon/authenticated denied by default; service-role bypasses RLS.

-- ---------------------------------------------------------------------------
-- off_sync_state — high-water mark + run audit
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.off_sync_state (
                                                     id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_kind               TEXT NOT NULL CHECK (job_kind IN ('DELTA', 'FULL')),
    started_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at            TIMESTAMPTZ,
    status                 TEXT NOT NULL DEFAULT 'RUNNING'
    CHECK (status IN ('RUNNING', 'OK', 'FAILED', 'CANCELLED')),
    last_modified_t_max    BIGINT,
    rows_inserted          BIGINT NOT NULL DEFAULT 0,
    rows_updated           BIGINT NOT NULL DEFAULT 0,
    rows_soft_deleted      BIGINT NOT NULL DEFAULT 0,
    delta_files_processed  TEXT[],
    error_message          TEXT,
    dry_run                BOOLEAN NOT NULL DEFAULT FALSE
    );

CREATE INDEX IF NOT EXISTS idx_off_sync_state_kind_finished
    ON public.off_sync_state (job_kind, finished_at DESC)
    WHERE status = 'OK';

ALTER TABLE public.off_sync_state ENABLE ROW LEVEL SECURITY;
-- No policies.

-- ---------------------------------------------------------------------------
-- upsert_off_products RPC — delta-path bulk UPSERT from a JSONB array
-- ---------------------------------------------------------------------------
-- Always refreshes `synced_at` on conflict (so unchanged rows are not
-- accidentally tombstoned by the full-reconcile soft-delete pass).
-- Resurrects rows by clearing `deleted_at` when the same code reappears.
CREATE OR REPLACE FUNCTION public.upsert_off_products(items JSONB)
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
                                                 code                   TEXT,
                                                 product_name           TEXT,
                                                 product_name_localized JSONB,
                                                 brands                 TEXT[],
                                                 countries_tags         TEXT[],
                                                 lang                   TEXT,
                                                 lc                     TEXT,
                                                 serving_size           TEXT,
                                                 serving_quantity       NUMERIC,
                                                 image_url              TEXT,
                                                 energy_kcal_100g       NUMERIC,
                                                 protein_100g           NUMERIC,
                                                 carbs_100g             NUMERIC,
                                                 sugars_100g            NUMERIC,
                                                 fat_100g               NUMERIC,
                                                 saturated_fat_100g     NUMERIC,
                                                 fiber_100g             NUMERIC,
                                                 sodium_100g            NUMERIC,
                                                 nutriments             JSONB,
                                                 completeness           NUMERIC,
                                                 last_modified_t        BIGINT,
                                                 rev                    INTEGER
        )
),
     upserted AS (
INSERT INTO public.off_food (
    code, product_name, product_name_localized, brands, countries_tags,
    lang, lc, serving_size, serving_quantity, image_url,
    energy_kcal_100g, protein_100g, carbs_100g, sugars_100g, fat_100g,
    saturated_fat_100g, fiber_100g, sodium_100g,
    nutriments, completeness, last_modified_t, rev,
    synced_at, deleted_at
)
SELECT
    s.code, s.product_name, s.product_name_localized, s.brands, s.countries_tags,
    s.lang, s.lc, s.serving_size, s.serving_quantity, s.image_url,
    s.energy_kcal_100g, s.protein_100g, s.carbs_100g, s.sugars_100g, s.fat_100g,
    s.saturated_fat_100g, s.fiber_100g, s.sodium_100g,
    s.nutriments, s.completeness, s.last_modified_t, s.rev,
    now(), NULL
FROM source s
WHERE s.code IS NOT NULL
    ON CONFLICT (code) DO UPDATE SET
    product_name           = EXCLUDED.product_name,
                              product_name_localized = EXCLUDED.product_name_localized,
                              brands                 = EXCLUDED.brands,
                              countries_tags         = EXCLUDED.countries_tags,
                              lang                   = EXCLUDED.lang,
                              lc                     = EXCLUDED.lc,
                              serving_size           = EXCLUDED.serving_size,
                              serving_quantity       = EXCLUDED.serving_quantity,
                              image_url              = EXCLUDED.image_url,
                              energy_kcal_100g       = EXCLUDED.energy_kcal_100g,
                              protein_100g           = EXCLUDED.protein_100g,
                              carbs_100g             = EXCLUDED.carbs_100g,
                              sugars_100g            = EXCLUDED.sugars_100g,
                              fat_100g               = EXCLUDED.fat_100g,
                              saturated_fat_100g     = EXCLUDED.saturated_fat_100g,
                              fiber_100g             = EXCLUDED.fiber_100g,
                              sodium_100g            = EXCLUDED.sodium_100g,
                              nutriments             = EXCLUDED.nutriments,
                              completeness           = EXCLUDED.completeness,
                              last_modified_t        = EXCLUDED.last_modified_t,
                              rev                    = EXCLUDED.rev,
                              synced_at              = now(),
                              deleted_at             = NULL
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

REVOKE EXECUTE ON FUNCTION public.upsert_off_products(JSONB) FROM PUBLIC, anon, authenticated;
GRANT  EXECUTE ON FUNCTION public.upsert_off_products(JSONB) TO service_role;

-- ---------------------------------------------------------------------------
-- off_search_candidates RPC — pg_trgm-ranked candidate recall for SmartSearch
-- ---------------------------------------------------------------------------
-- Returns up to `p_limit` undeleted off_food rows whose product_name fuzzy-matches
-- `p_query`. Optional country filter (e.g. 'en:united-states') restricts to a
-- specific market. Service-role only; the API gateway calls this from
-- SmartSearchOrchestrator's upstream fan-out.
CREATE OR REPLACE FUNCTION public.off_search_candidates(
    p_query   TEXT,
    p_country TEXT DEFAULT NULL,
    p_limit   INT  DEFAULT 25
)
RETURNS SETOF public.off_food
LANGUAGE sql
SECURITY INVOKER
-- Both `public` and `extensions` are listed so the unqualified `%` operator
-- and `similarity()` function resolve regardless of which schema this
-- Supabase project's pg_trgm is installed into.
SET search_path = public, extensions, pg_temp
AS $$
SELECT *
FROM public.off_food
WHERE deleted_at IS NULL
  AND product_name % p_query
       AND (p_country IS NULL OR countries_tags @> ARRAY[p_country])
ORDER BY similarity(product_name, p_query) DESC
    LIMIT p_limit;
$$;

REVOKE EXECUTE ON FUNCTION public.off_search_candidates(TEXT, TEXT, INT) FROM PUBLIC, anon, authenticated;
GRANT  EXECUTE ON FUNCTION public.off_search_candidates(TEXT, TEXT, INT) TO service_role;

-- ---------------------------------------------------------------------------
-- soft_delete_off_unseen RPC — full-reconcile soft-delete pass
-- ---------------------------------------------------------------------------
-- Tombstones every undeleted row whose `synced_at` is older than `p_before`.
-- Called once at the END of a successful full reconcile run; `p_before` is
-- the run-start wall clock captured BEFORE streaming began. Because the
-- upsert RPC refreshes `synced_at = now()` on every conflict (matched or not),
-- any row missed by the dump is the only one left under the cutoff and gets
-- soft-deleted here. Returns the row count for the audit log.
CREATE OR REPLACE FUNCTION public.soft_delete_off_unseen(p_before TIMESTAMPTZ)
RETURNS BIGINT
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public, pg_temp
AS $$
DECLARE v_count BIGINT;
BEGIN
WITH d AS (
UPDATE public.off_food
SET deleted_at = now()
WHERE deleted_at IS NULL
  AND synced_at  < p_before
    RETURNING 1
    )
SELECT COUNT(*) INTO v_count FROM d;
RETURN v_count;
END;
$$;

REVOKE EXECUTE ON FUNCTION public.soft_delete_off_unseen(TIMESTAMPTZ) FROM PUBLIC, anon, authenticated;
GRANT  EXECUTE ON FUNCTION public.soft_delete_off_unseen(TIMESTAMPTZ) TO service_role;

-- ---------------------------------------------------------------------------
-- Rollback (commented out — copy and run manually if needed)
-- ---------------------------------------------------------------------------
-- DROP FUNCTION IF EXISTS public.soft_delete_off_unseen(TIMESTAMPTZ);
-- DROP FUNCTION IF EXISTS public.off_search_candidates(TEXT, TEXT, INT);
-- DROP FUNCTION IF EXISTS public.upsert_off_products(JSONB);
-- DROP TABLE    IF EXISTS public.off_sync_state;
-- DROP TABLE    IF EXISTS public.off_food;
