-- Fix: add the unique constraint and cache-hit index that were missing from the initial
-- prod deploy of ai_progress_ladder.
--
-- The unique constraint on (user_id, request_key) is required for PostgREST's
-- on_conflict=user_id,request_key upsert to work. Without it, PostgREST returns
-- HTTP 400 immediately — the INSERT never reaches Postgres.
--
-- Root cause: production was migrated from a pre-commit draft of 20260501000001 that
-- did not yet include these statements.

-- ADD CONSTRAINT (not CREATE UNIQUE INDEX) so it registers in pg_constraint.
-- PostgreSQL does not support IF NOT EXISTS for ADD CONSTRAINT, so we use a DO block.
do $$
begin
    if not exists (
        select 1 from pg_constraint
        where conname = 'ai_progress_ladder_user_request_key_key'
          and conrelid = 'public.ai_progress_ladder'::regclass
    ) then
        alter table public.ai_progress_ladder
            add constraint ai_progress_ladder_user_request_key_key
            unique (user_id, request_key);
    end if;
end$$;

-- Cache-hit lookup index: same user re-uploads the same photo bytes.
create index if not exists ai_progress_ladder_source_content_hash_idx
    on public.ai_progress_ladder (user_id, source_content_hash);
