-- Migration: 003_off_mirror_timeout_hardening
-- Purpose: Reduce write amplification in the OFF mirror upsert RPC so full
--          reconciles are less likely to hit Supabase/PostgREST statement_timeout.

CREATE OR REPLACE FUNCTION public.upsert_off_products(items JSONB)
RETURNS TABLE(inserted BIGINT, updated BIGINT)
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_inserted BIGINT;
    v_changed  BIGINT;
    v_touched  BIGINT;
BEGIN
WITH source_raw AS (
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
     source AS (
         SELECT DISTINCT ON (code) *
         FROM source_raw
         WHERE code IS NOT NULL
         ORDER BY code, last_modified_t DESC NULLS LAST, rev DESC NULLS LAST
     ),
     inserted_rows AS (
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
         ON CONFLICT (code) DO NOTHING
         RETURNING 1
     ),
     changed_rows AS (
         UPDATE public.off_food t
         SET product_name           = s.product_name,
             product_name_localized = s.product_name_localized,
             brands                 = s.brands,
             countries_tags         = s.countries_tags,
             lang                   = s.lang,
             lc                     = s.lc,
             serving_size           = s.serving_size,
             serving_quantity       = s.serving_quantity,
             image_url              = s.image_url,
             energy_kcal_100g       = s.energy_kcal_100g,
             protein_100g           = s.protein_100g,
             carbs_100g             = s.carbs_100g,
             sugars_100g            = s.sugars_100g,
             fat_100g               = s.fat_100g,
             saturated_fat_100g     = s.saturated_fat_100g,
             fiber_100g             = s.fiber_100g,
             sodium_100g            = s.sodium_100g,
             nutriments             = s.nutriments,
             completeness           = s.completeness,
             last_modified_t        = s.last_modified_t,
             rev                    = s.rev,
             synced_at              = now(),
             deleted_at             = NULL
         FROM source s
         WHERE t.code = s.code
           AND (
               t.last_modified_t IS DISTINCT FROM s.last_modified_t
               OR t.rev IS DISTINCT FROM s.rev
               OR t.deleted_at IS NOT NULL
           )
         RETURNING 1
     ),
     touched_rows AS (
         UPDATE public.off_food t
         SET synced_at = now()
         FROM source s
         WHERE t.code = s.code
           AND t.deleted_at IS NULL
           AND t.last_modified_t IS NOT DISTINCT FROM s.last_modified_t
           AND t.rev IS NOT DISTINCT FROM s.rev
         RETURNING 1
     )
SELECT
    (SELECT COUNT(*) FROM inserted_rows),
    (SELECT COUNT(*) FROM changed_rows),
    (SELECT COUNT(*) FROM touched_rows)
INTO v_inserted, v_changed, v_touched;

inserted := COALESCE(v_inserted, 0);
updated  := COALESCE(v_changed, 0) + COALESCE(v_touched, 0);
RETURN NEXT;
END;
$$;

REVOKE EXECUTE ON FUNCTION public.upsert_off_products(JSONB) FROM PUBLIC, anon, authenticated;
GRANT  EXECUTE ON FUNCTION public.upsert_off_products(JSONB) TO service_role;
