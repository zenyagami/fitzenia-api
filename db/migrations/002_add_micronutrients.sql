-- Migration: 002_add_micronutrients
-- Purpose: Extend canonical_food_serving with 4 additional micronutrients:
--          cholesterol, potassium, calcium, iron. All in mg, all nullable.
--          Also replaces the insert_canonical_foods RPC to read/write them.
--
-- Apply: via Supabase dashboard SQL editor OR `psql -f db/migrations/002_add_micronutrients.sql`.

ALTER TABLE public.canonical_food_serving
  ADD COLUMN IF NOT EXISTS cholesterol_mg REAL,
  ADD COLUMN IF NOT EXISTS potassium_mg   REAL,
  ADD COLUMN IF NOT EXISTS calcium_mg     REAL,
  ADD COLUMN IF NOT EXISTS iron_mg        REAL;

CREATE OR REPLACE FUNCTION public.insert_canonical_foods(payload JSONB)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_normalized_query TEXT      := payload->>'normalized_query';
    v_locale           TEXT      := payload->>'locale';
    v_country          TEXT      := COALESCE(NULLIF(payload->>'country', ''), 'GLOBAL');
    v_items            JSONB     := payload->'items';
    v_lock_key         BIGINT;
    v_requested_ranks  SMALLINT[];
    v_total_requested  INT;
    v_existing_count   INT;
    v_rank_map         JSONB;
    v_item             JSONB;
    v_serving          JSONB;
    v_term             JSONB;
    v_rank             SMALLINT;
    v_canonical_id     UUID;
    v_group_id         UUID;
BEGIN
    IF v_normalized_query IS NULL OR v_locale IS NULL
       OR v_items IS NULL OR jsonb_array_length(v_items) = 0 THEN
        RAISE EXCEPTION
            'insert_canonical_foods: payload must include normalized_query, locale, and non-empty items[]';
    END IF;

    v_lock_key := hashtextextended(v_normalized_query || '|' || v_locale || '|' || v_country, 0);
    PERFORM pg_advisory_xact_lock(v_lock_key);

    SELECT array_agg((item->>'rank')::SMALLINT)
      INTO v_requested_ranks
      FROM jsonb_array_elements(v_items) AS item;

    v_total_requested := array_length(v_requested_ranks, 1);

    SELECT jsonb_object_agg(rank::TEXT, canonical_food_id),
           COUNT(*)
      INTO v_rank_map, v_existing_count
      FROM public.canonical_food_query_map
     WHERE normalized_query = v_normalized_query
       AND locale           = v_locale
       AND country          = v_country
       AND rank             = ANY(v_requested_ranks);

    IF v_existing_count = v_total_requested THEN
        RETURN jsonb_build_object(
            'rank_to_canonical_food_id', COALESCE(v_rank_map, '{}'::JSONB),
            'status', 'reused'
        );
    ELSIF v_existing_count > 0 THEN
        RETURN jsonb_build_object(
            'rank_to_canonical_food_id', COALESCE(v_rank_map, '{}'::JSONB),
            'status', 'partial'
        );
    END IF;

    v_rank_map := '{}'::JSONB;

    FOR v_item IN SELECT * FROM jsonb_array_elements(v_items) LOOP
        v_rank := (v_item->>'rank')::SMALLINT;

        IF v_item ? 'link_to_canonical_group_id'
           AND v_item->>'link_to_canonical_group_id' IS NOT NULL THEN
            v_group_id := (v_item->>'link_to_canonical_group_id')::UUID;
        ELSE
            v_group_id := gen_random_uuid();
        END IF;

        INSERT INTO public.canonical_food_item (
            canonical_group_id, primary_locale, primary_country,
            ai_generated, model_provider, model_name, confidence
        ) VALUES (
            v_group_id,
            v_locale,
            v_country,
            COALESCE((v_item->'canonical_food'->>'ai_generated')::BOOLEAN, true),
            v_item->'canonical_food'->>'model_provider',
            v_item->'canonical_food'->>'model_name',
            (v_item->'canonical_food'->>'confidence')::REAL
        )
        RETURNING id INTO v_canonical_id;

        FOR v_serving IN SELECT * FROM jsonb_array_elements(v_item->'servings') LOOP
            INSERT INTO public.canonical_food_serving (
                canonical_food_id, name, weight_grams,
                calories_kcal, protein_g, carbs_g, fat_g,
                fiber_g, sodium_mg, sugar_g, saturated_fat_g,
                cholesterol_mg, potassium_mg, calcium_mg, iron_mg
            ) VALUES (
                v_canonical_id,
                v_serving->>'name',
                (v_serving->>'weight_grams')::REAL,
                (v_serving->>'calories_kcal')::REAL,
                (v_serving->>'protein_g')::REAL,
                (v_serving->>'carbs_g')::REAL,
                (v_serving->>'fat_g')::REAL,
                NULLIF(v_serving->>'fiber_g', '')::REAL,
                NULLIF(v_serving->>'sodium_mg', '')::REAL,
                NULLIF(v_serving->>'sugar_g', '')::REAL,
                NULLIF(v_serving->>'saturated_fat_g', '')::REAL,
                NULLIF(v_serving->>'cholesterol_mg', '')::REAL,
                NULLIF(v_serving->>'potassium_mg', '')::REAL,
                NULLIF(v_serving->>'calcium_mg', '')::REAL,
                NULLIF(v_serving->>'iron_mg', '')::REAL
            );
        END LOOP;

        FOR v_term IN SELECT * FROM jsonb_array_elements(v_item->'terms') LOOP
            INSERT INTO public.canonical_food_term (
                canonical_food_id, locale, name, is_alias
            ) VALUES (
                v_canonical_id,
                v_term->>'locale',
                v_term->>'name',
                COALESCE((v_term->>'is_alias')::BOOLEAN, false)
            );
        END LOOP;

        INSERT INTO public.canonical_food_query_map (
            normalized_query, locale, country, canonical_food_id, rank
        ) VALUES (
            v_normalized_query, v_locale, v_country, v_canonical_id, v_rank
        )
        ON CONFLICT DO NOTHING;

        v_rank_map := v_rank_map || jsonb_build_object(v_rank::TEXT, v_canonical_id);
    END LOOP;

    RETURN jsonb_build_object(
        'rank_to_canonical_food_id', v_rank_map,
        'status', 'inserted'
    );
END;
$$;

REVOKE EXECUTE ON FUNCTION public.insert_canonical_foods(JSONB) FROM PUBLIC, anon, authenticated;
GRANT  EXECUTE ON FUNCTION public.insert_canonical_foods(JSONB) TO service_role;
