-- Migration: 003_off_mirror_trim
-- Purpose:   Reduce off_food storage / index-maintenance footprint by dropping
--            columns and indexes that the API never reads.
--
--            After the first full reconcile (~3M rows on Supabase Pro), the
--            project hit "exhausting multiple resources" and the end-of-run
--            audit PATCH against off_sync_state timed out at 30s. The Kotlin
--            timeout has been bumped separately; this migration relieves the
--            steady-state pressure so the next reconcile lands more comfortably
--            on the same compute tier.
--
-- Drops (write-only — never selected by OffMirrorGateway.SELECT_COLUMNS):
--   - product_name_localized JSONB   (largest per-row cost)
--   - lc                      TEXT
--   - completeness            NUMERIC
--   - rev                     INTEGER
--
-- Drops (no query consumer):
--   - idx_off_food_modified  (BTREE on last_modified_t — nothing filters by it)
--   - idx_off_food_brand     (BTREE on primary_brand — selected, never WHERE'd)
--
-- Kept (still required):
--   - last_modified_t        — defensive; cheap; enables future skip-unchanged
--   - synced_at, deleted_at  — required by soft_delete_off_unseen + every partial index
--   - idx_off_food_name_trgm, idx_off_food_countries, idx_off_food_synced_at_active
--
-- Plan ref: /Users/zenkun/.claude/plans/we-need-to-come-rosy-stonebraker.md
-- Apply:    psql against the Supabase project (no migration runner exists in this repo).
--           Apply to BOTH dev (tpslgveyjldykkkhnifs) and prod (anqvtpesmddllplyhkrc)
--           for schema parity. Dev tables stay empty so the impact there is nil.

-- ---------------------------------------------------------------------------
-- 1. Replace upsert_off_products with the trimmed signature FIRST.
-- ---------------------------------------------------------------------------
-- Order matters: jsonb_to_recordset will silently ignore extra keys, so
-- replacing the function before dropping the columns is safe even if a stale
-- ingest run is mid-flight. (The reverse — dropping columns before the RPC
-- references them — would error.)
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
    WITH parsed AS (
        SELECT * FROM jsonb_to_recordset(items) AS x(
            code                 TEXT,
            product_name         TEXT,
            brands               TEXT[],
            countries_tags       TEXT[],
            lang                 TEXT,
            serving_size         TEXT,
            serving_quantity     NUMERIC,
            image_url            TEXT,
            energy_kcal_100g     NUMERIC,
            protein_100g         NUMERIC,
            carbs_100g           NUMERIC,
            sugars_100g          NUMERIC,
            fat_100g             NUMERIC,
            saturated_fat_100g   NUMERIC,
            fiber_100g           NUMERIC,
            sodium_100g          NUMERIC,
            nutriments           JSONB,
            last_modified_t      BIGINT
        )
        WHERE code IS NOT NULL
    ),
    -- The OFF dump occasionally contains the same `code` twice in a single
    -- batch (revision history, mirrored bulk-export quirks). Postgres rejects
    -- ON CONFLICT DO UPDATE if two source rows target the same conflict row
    -- in one statement (SQLSTATE 21000). Pick the newest revision per code.
    source AS (
        SELECT DISTINCT ON (code) *
        FROM parsed
        ORDER BY code, last_modified_t DESC NULLS LAST
    ),
    upserted AS (
        INSERT INTO public.off_food (
            code, product_name, brands, countries_tags, lang,
            serving_size, serving_quantity, image_url,
            energy_kcal_100g, protein_100g, carbs_100g, sugars_100g, fat_100g,
            saturated_fat_100g, fiber_100g, sodium_100g,
            nutriments, last_modified_t,
            synced_at, deleted_at
        )
        SELECT
            s.code, s.product_name, s.brands, s.countries_tags, s.lang,
            s.serving_size, s.serving_quantity, s.image_url,
            s.energy_kcal_100g, s.protein_100g, s.carbs_100g, s.sugars_100g, s.fat_100g,
            s.saturated_fat_100g, s.fiber_100g, s.sodium_100g,
            s.nutriments, s.last_modified_t,
            now(), NULL
        FROM source s
        ON CONFLICT (code) DO UPDATE SET
            product_name       = EXCLUDED.product_name,
            brands             = EXCLUDED.brands,
            countries_tags     = EXCLUDED.countries_tags,
            lang               = EXCLUDED.lang,
            serving_size       = EXCLUDED.serving_size,
            serving_quantity   = EXCLUDED.serving_quantity,
            image_url          = EXCLUDED.image_url,
            energy_kcal_100g   = EXCLUDED.energy_kcal_100g,
            protein_100g       = EXCLUDED.protein_100g,
            carbs_100g         = EXCLUDED.carbs_100g,
            sugars_100g        = EXCLUDED.sugars_100g,
            fat_100g           = EXCLUDED.fat_100g,
            saturated_fat_100g = EXCLUDED.saturated_fat_100g,
            fiber_100g         = EXCLUDED.fiber_100g,
            sodium_100g        = EXCLUDED.sodium_100g,
            nutriments         = EXCLUDED.nutriments,
            last_modified_t    = EXCLUDED.last_modified_t,
            synced_at          = now(),
            deleted_at         = NULL
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
-- 2. Drop the unused indexes (cheap; do this BEFORE the column drop so the
-- ALTER TABLE has fewer index dependencies to reason about).
-- ---------------------------------------------------------------------------
DROP INDEX IF EXISTS public.idx_off_food_modified;
DROP INDEX IF EXISTS public.idx_off_food_brand;

-- ---------------------------------------------------------------------------
-- 3. Drop the unused columns.
-- ---------------------------------------------------------------------------
-- Postgres marks the columns dead in pg_attribute but does not physically
-- reclaim space until VACUUM rewrites the rows. The follow-up VACUUM (ANALYZE)
-- below kicks off the cleanup without taking an exclusive lock.
ALTER TABLE public.off_food
    DROP COLUMN IF EXISTS product_name_localized,
    DROP COLUMN IF EXISTS lc,
    DROP COLUMN IF EXISTS completeness,
    DROP COLUMN IF EXISTS rev;

-- ---------------------------------------------------------------------------
-- 4. Reclaim space + refresh planner stats — RUN SEPARATELY.
-- ---------------------------------------------------------------------------
-- VACUUM cannot run inside a transaction block, and the Supabase SQL Editor
-- wraps every script in a transaction (error 25001). Run this manually after
-- the migration commits. Either:
--   - Supabase Dashboard → Database → "Run SQL" with **just** this one line,
--     or
--   - psql via the connection pooler:
--       VACUUM (ANALYZE) public.off_food;
-- For full on-disk compaction, run `VACUUM FULL public.off_food` separately
-- in a maintenance window (it locks the table) or use pg_repack online.
--
-- Skipping this is non-fatal: autovacuum will eventually rewrite the dead
-- column tuples on its own. Running it explicitly just shortens that window.

-- ---------------------------------------------------------------------------
-- Rollback (commented out — copy and run manually if needed)
-- ---------------------------------------------------------------------------
-- ALTER TABLE public.off_food
--     ADD COLUMN IF NOT EXISTS product_name_localized JSONB,
--     ADD COLUMN IF NOT EXISTS lc                     TEXT,
--     ADD COLUMN IF NOT EXISTS completeness           NUMERIC,
--     ADD COLUMN IF NOT EXISTS rev                    INTEGER;
-- CREATE INDEX IF NOT EXISTS idx_off_food_modified ON public.off_food (last_modified_t);
-- CREATE INDEX IF NOT EXISTS idx_off_food_brand    ON public.off_food (primary_brand) WHERE deleted_at IS NULL;
-- (Restoring the old upsert_off_products signature requires re-running the
-- equivalent block from 002_off_mirror.sql.)
