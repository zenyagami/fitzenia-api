-- Migration: 001_canonical_food_catalog
-- Purpose: Shared canonical food catalog for Smart Food Search.
--          Stores AI-synthesized canonical foods (e.g. "cheesecake", "flat white"),
--          their nutrition, localized names, and the (query,locale,country)->canonical mapping.
--          Service-role write only. RLS enabled with no policies (service-role bypasses RLS).
--
-- Apply: psql against the Supabase project (no migration runner exists in this repo).
-- Plan ref: /Users/zenkun/.claude/plans/scalable-mapping-crayon.md

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ---------------------------------------------------------------------------
-- canonical_food_item
-- ---------------------------------------------------------------------------
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

CREATE INDEX IF NOT EXISTS idx_canonical_food_item_group
    ON public.canonical_food_item (canonical_group_id);

ALTER TABLE public.canonical_food_item ENABLE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- canonical_food_serving
-- ---------------------------------------------------------------------------
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

CREATE INDEX IF NOT EXISTS idx_canonical_food_serving_food
    ON public.canonical_food_serving (canonical_food_id);

ALTER TABLE public.canonical_food_serving ENABLE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- canonical_food_term
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.canonical_food_term (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    canonical_food_id UUID NOT NULL REFERENCES public.canonical_food_item(id) ON DELETE CASCADE,
    locale            TEXT NOT NULL,
    name              TEXT NOT NULL,
    is_alias          BOOLEAN NOT NULL DEFAULT false
);

CREATE INDEX IF NOT EXISTS idx_canonical_food_term_food
    ON public.canonical_food_term (canonical_food_id);

CREATE INDEX IF NOT EXISTS idx_canonical_food_term_locale_name
    ON public.canonical_food_term (locale, name);

-- pg_trgm GIN index for fuzzy english-equivalent lookup during cross-locale linking
CREATE INDEX IF NOT EXISTS idx_canonical_food_term_name_trgm
    ON public.canonical_food_term USING gin (name gin_trgm_ops);

ALTER TABLE public.canonical_food_term ENABLE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- canonical_food_query_map
-- ---------------------------------------------------------------------------
-- country is NOT NULL DEFAULT 'GLOBAL' deliberately:
-- Postgres allows multiple NULL rows under a unique key, which would break the
-- slot-uniqueness guarantee. We use a sentinel string instead of nullable.
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

CREATE INDEX IF NOT EXISTS idx_canonical_food_query_map_lookup
    ON public.canonical_food_query_map (normalized_query, locale, country);

ALTER TABLE public.canonical_food_query_map ENABLE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- insert_canonical_foods RPC
-- ---------------------------------------------------------------------------
-- Slot-idempotent, batch-aware. Called by the backend with service_role.
-- See plan: "Persistence RPC" section.
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

    -- Advisory lock keyed on the conceptual (query, locale, country) slot.
    -- Auto-released at transaction end. Guards against concurrent racers.
    v_lock_key := hashtextextended(v_normalized_query || '|' || v_locale || '|' || v_country, 0);
    PERFORM pg_advisory_xact_lock(v_lock_key);

    SELECT array_agg((item->>'rank')::SMALLINT)
      INTO v_requested_ranks
      FROM jsonb_array_elements(v_items) AS item;

    v_total_requested := array_length(v_requested_ranks, 1);

    -- Build the rank -> canonical_food_id map from any rows that already exist
    -- in the requested rank set. Used for both reused and partial responses.
    SELECT jsonb_object_agg(rank::TEXT, canonical_food_id),
           COUNT(*)
      INTO v_rank_map, v_existing_count
      FROM public.canonical_food_query_map
     WHERE normalized_query = v_normalized_query
       AND locale           = v_locale
       AND country          = v_country
       AND rank             = ANY(v_requested_ranks);

    IF v_existing_count = v_total_requested THEN
        -- All slots filled: retry-after-timeout case, return existing.
        RETURN jsonb_build_object(
            'rank_to_canonical_food_id', COALESCE(v_rank_map, '{}'::JSONB),
            'status', 'reused'
        );
    ELSIF v_existing_count > 0 THEN
        -- Anomalous partial state: surface it, do not attempt repair.
        RETURN jsonb_build_object(
            'rank_to_canonical_food_id', COALESCE(v_rank_map, '{}'::JSONB),
            'status', 'partial'
        );
    END IF;

    -- All slots empty: insert all items transactionally.
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
                fiber_g, sodium_mg, sugar_g, saturated_fat_g
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
                NULLIF(v_serving->>'saturated_fat_g', '')::REAL
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

-- Restrict execution to service_role only. Backend (with SUPABASE_SERVICE_ROLE_KEY)
-- is the only caller; anon and authenticated roles must not invoke this.
REVOKE EXECUTE ON FUNCTION public.insert_canonical_foods(JSONB) FROM PUBLIC, anon, authenticated;
GRANT  EXECUTE ON FUNCTION public.insert_canonical_foods(JSONB) TO service_role;

-- ---------------------------------------------------------------------------
-- Rollback (commented out — copy and run manually if needed)
-- ---------------------------------------------------------------------------
-- DROP FUNCTION IF EXISTS public.insert_canonical_foods(JSONB);
-- DROP TABLE    IF EXISTS public.canonical_food_query_map;
-- DROP TABLE    IF EXISTS public.canonical_food_term;
-- DROP TABLE    IF EXISTS public.canonical_food_serving;
-- DROP TABLE    IF EXISTS public.canonical_food_item;
