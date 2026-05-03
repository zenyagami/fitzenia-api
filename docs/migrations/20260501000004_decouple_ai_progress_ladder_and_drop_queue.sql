-- DEV-ONLY MIGRATION. Do NOT apply to prod.
-- Prod gets the consolidated final-state from 20260501000001 directly.
--
-- This migration realigns the dev DB (which has the v1 schema with source_photo_id,
-- deleted_at, the cascade-cleanup queue, and the trigger) to the final design where:
--   * the AI feature is decoupled from progress_photo (no FK)
--   * there is no client soft-delete state on the server (client manages its own undo UX)
--   * there is no queue + trigger for cascade cleanup — the DELETE endpoint handles
--     storage cleanup synchronously via service-role HTTP
--   * client cannot DELETE directly via Supabase REST — only service-role inside the
--     backend DELETE endpoint can delete (forces blob cleanup through the right path)
--   * source photo bytes are persisted as step_index=0 of the ladder (kind='SOURCE'),
--     identified by sha256(bytes) instead of a FK to progress_photo

-- 1. Drop the cascade-cleanup queue + trigger.
drop trigger  if exists trg_enqueue_rung_storage_deletion on public.ai_progress_ladder_rung;
drop function if exists public.enqueue_rung_storage_deletion();
drop table    if exists public.pending_storage_deletion;

-- 2. Drop client UPDATE / DELETE policies. Only SELECT remains for owners; service-role
--    handles all writes via the backend endpoints.
drop policy if exists ai_progress_ladder_update_own       on public.ai_progress_ladder;
drop policy if exists ai_progress_ladder_delete_own       on public.ai_progress_ladder;
drop policy if exists ai_progress_ladder_rung_delete_own  on public.ai_progress_ladder_rung;

revoke insert, update, delete on public.ai_progress_ladder      from authenticated;
revoke insert, update, delete on public.ai_progress_ladder_rung from authenticated;

-- 3. Drop the cache-hit lookup index that referenced source_photo_id.
drop index if exists public.ai_progress_ladder_source_photo_idx;

-- 4. Drop the FK constraint to progress_photo, then the column itself.
alter table public.ai_progress_ladder
    drop constraint if exists ai_progress_ladder_source_photo_id_fkey;

alter table public.ai_progress_ladder
    drop column if exists source_photo_id;

-- 5. Drop deleted_at + its index.
drop index if exists public.ai_progress_ladder_deleted_idx;

alter table public.ai_progress_ladder
    drop column if exists deleted_at;

-- 6. Add the new columns. source_content_hash is the cache key + photo identifier.
alter table public.ai_progress_ladder
    add column if not exists source_content_hash    text,
    add column if not exists source_width           int,
    add column if not exists source_height          int,
    add column if not exists target_weight_kg       numeric(6, 2),
    add column if not exists target_body_fat_percent numeric(5, 2),
    add column if not exists body_fat_source        text;

-- Make source_content_hash NOT NULL. Table is empty in dev so a straight ALTER works.
alter table public.ai_progress_ladder
    alter column source_content_hash set not null;

-- 7. Cache-hit lookup index for the new world.
create index if not exists ai_progress_ladder_source_content_hash_idx
    on public.ai_progress_ladder (user_id, source_content_hash);

-- 8. Add `kind` to rungs (default PROJECTION; check constraint).
alter table public.ai_progress_ladder_rung
    add column if not exists kind text not null default 'PROJECTION'
        check (kind in ('SOURCE', 'PROJECTION'));
