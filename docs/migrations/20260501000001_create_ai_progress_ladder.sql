-- AI Progress Projections — generation tables.
--
-- Tables:
--   ai_progress_ladder        : one cached "future me" image set per (user, source-photo-bytes-hash, targets, prompt version).
--   ai_progress_ladder_rung   : one row per generated image at a specific body-fat-% step.
--                                step_index=0 is the SOURCE photo; step_index 1..N are AI PROJECTIONs.
--
-- Source photo bytes are sent as multipart in the generate request and persisted as
-- step_index=0 of the ladder. The cache key is sha256(bytes), so re-uploading the same
-- photo with the same targets is a cache hit.
--
-- The progress_photo table and progress-photos bucket are intentionally NOT touched by
-- this feature. There is no FK between progress_photo and ai_progress_ladder.
--
-- Cascade chain on hard-delete:
--   ai_progress_ladder ─(ON DELETE CASCADE via ladder_id)─► ai_progress_ladder_rung
--
-- Storage cleanup is handled synchronously by the backend DELETE endpoint
-- (DELETE /api/progress/ladders/{id}), which collects rung storage_paths and wipes
-- the blobs via service-role HTTP before deleting the ladder row. There is no DB
-- trigger and no cleanup queue — Postgres has no way to call out to Supabase Storage,
-- so we keep the storage step in application code where it belongs.
--
-- RLS lockdown: clients can SELECT their own rows but cannot DELETE directly. The only
-- valid delete path is the backend endpoint, which uses service_role to wipe blobs
-- before deleting the row. This forces every delete through the cleanup logic.

create table if not exists public.ai_progress_ladder (
    id                          uuid           primary key default gen_random_uuid(),
    user_id                     uuid           not null references auth.users(id) on delete cascade,
    source_content_hash         text           not null,
    source_width                int,
    source_height               int,
    base_weight_kg              numeric(6, 2)  not null,
    base_body_fat_percent       numeric(5, 2)  not null,
    target_weight_kg            numeric(6, 2)  not null,
    target_body_fat_percent     numeric(5, 2)  not null,
    body_fat_source             text,
    step_body_fat_percent       numeric(4, 2)  not null,
    num_steps                   int            not null check (num_steps between 1 and 10),
    model                       text           not null,
    quality                     text           not null,
    size                        text           not null,
    prompt_version              int            not null,
    request_key                 text           not null,
    gatekeeper_verdict          jsonb,
    status                      text           not null default 'PENDING'
                                                check (status in ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    failure_code                text,
    created_at                  timestamptz    not null default now(),
    updated_at                  timestamptz    not null default now()
);

-- Cross-instance dedup of in-flight generations.
-- Must be a UNIQUE CONSTRAINT (not just a unique index) so PostgREST can resolve the
-- on_conflict=user_id,request_key parameter via pg_constraint. A bare CREATE UNIQUE INDEX
-- is invisible to PostgREST and causes a 400 on every INSERT attempt.
alter table public.ai_progress_ladder
    add constraint ai_progress_ladder_user_request_key_key
    unique (user_id, request_key);

-- Cache-hit lookup path: same user uploads same photo repeatedly.
create index if not exists ai_progress_ladder_source_content_hash_idx
    on public.ai_progress_ladder (user_id, source_content_hash);

alter table public.ai_progress_ladder enable row level security;

-- Owner can SELECT. INSERT/UPDATE/DELETE are service-role only — DELETE specifically
-- routes through the backend endpoint so blobs get wiped before rows.
drop policy if exists ai_progress_ladder_select_own on public.ai_progress_ladder;
create policy ai_progress_ladder_select_own
    on public.ai_progress_ladder
    for select
    using (auth.uid() = user_id);

grant select on public.ai_progress_ladder to authenticated;


create table if not exists public.ai_progress_ladder_rung (
    id                              uuid        primary key default gen_random_uuid(),
    ladder_id                       uuid        not null references public.ai_progress_ladder(id) on delete cascade,
    user_id                         uuid        not null,
    step_index                      int         not null,
    kind                            text        not null default 'PROJECTION'
                                                 check (kind in ('SOURCE', 'PROJECTION')),
    projected_body_fat_percent      numeric(5, 2)  not null,
    projected_weight_kg             numeric(6, 2)  not null,
    storage_path                    text,
    openai_model                    text,
    usage_input_tokens              int,
    usage_output_tokens             int,
    usage_cached_input_tokens       int,
    cost_micros                     bigint,
    status                          text        not null default 'PENDING'
                                                 check (status in ('PENDING', 'SUCCEEDED', 'FAILED')),
    failure_code                    text,
    created_at                      timestamptz not null default now()
);

create unique index if not exists ai_progress_ladder_rung_ladder_step_uidx
    on public.ai_progress_ladder_rung (ladder_id, step_index);

create index if not exists ai_progress_ladder_rung_user_idx
    on public.ai_progress_ladder_rung (user_id);

alter table public.ai_progress_ladder_rung enable row level security;

-- Owner can SELECT. INSERT/UPDATE/DELETE are service-role only. Cascade DELETEs from
-- ladder → rung happen under service-role (the backend orchestrator), bypassing RLS.
drop policy if exists ai_progress_ladder_rung_select_own on public.ai_progress_ladder_rung;
create policy ai_progress_ladder_rung_select_own
    on public.ai_progress_ladder_rung
    for select
    using (auth.uid() = user_id);

grant select on public.ai_progress_ladder_rung to authenticated;

-- Realtime: the client subscribes to rung INSERTs as a redundant truth source for the
-- generate-stream SSE. Also subscribes to ladder UPDATEs for status transitions.
do $$
begin
    if not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'ai_progress_ladder_rung'
    ) then
        execute 'alter publication supabase_realtime add table public.ai_progress_ladder_rung';
    end if;
    if not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'ai_progress_ladder'
    ) then
        execute 'alter publication supabase_realtime add table public.ai_progress_ladder';
    end if;
end$$;
