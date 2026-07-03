-- ============================================================================
-- 005_coach_baseline.sql — AI Coach schema (consolidated baseline reference)
-- ============================================================================
--
-- WHAT THIS IS. The single, faithful reference for the entire AI Coach database
-- schema as it is actually DEPLOYED on both Supabase projects:
--   dev  = tpslgveyjldykkkhnifs
--   prod = anqvtpesmddllplyhkrc   (applied 2026-07-03)
--
-- PROVENANCE. Dev was built incrementally by 19 timestamp migrations
-- (20260620080044 … 20260702134348, recorded in supabase_migrations). On
-- 2026-07-03 those 19 migrations were replayed verbatim onto prod, and dev↔prod
-- parity was verified (identical column/index/RLS-policy signatures, realtime
-- publication, and 38/39 pg_get_functiondef md5s — see the pg_trgm note below for
-- the 39th). This file CONSOLIDATES that deployed state: every object appears once,
-- in final form (later intermediate revisions of the budget/RC functions folded in).
--
-- This SUPERSEDES the previous split references (005_coach_core, 006_coach_kb,
-- 007_coach_kb_search, 008_coach_budget, 009_coach_revenuecat, and
-- docs/migrations/20260630000001_extend_delete_user_data_for_coach.sql), which had
-- drifted from the live schema (they were missing the turn state machine +
-- coach_internal.coach_turn, coach_search_kb, coach_replace_kb_doc,
-- coach_retention_sweep, coach_write_user_note, coach_release_stale_budget_reservations,
-- and the columns coach_message.request_id / coach_chat.title_generated /
-- user_entitlement.source; and carried a stale rc_app_user_id column dev/prod never had).
--
-- REFERENCE ONLY. Neither dev nor prod is built from this file — both were built by
-- replaying the real timestamp migrations. Applying this file as-is targets a fresh,
-- coach-less database. Object order respects FK + LANGUAGE sql body validation
-- (tables → coach_internal.* functions → public.* wrappers → delete_user_data →
-- realtime publication).
--
-- ENVIRONMENT DIVERGENCE (the one non-identical object). `public.coach_search_kb`
-- (an internal helper NOT called by the shipping code — the code uses
-- `search_coach_kb_hybrid`) references the pg_trgm function `similarity()` with a
-- schema qualifier. This file uses the DEV form `extensions.similarity(...)` because
-- dev installs pg_trgm in the `extensions` schema. Prod installs pg_trgm in `public`,
-- so on prod that reference is `public.similarity(...)`. `search_coach_kb_hybrid`
-- avoids the problem entirely via `SET search_path = extensions, public, pg_temp`.
-- ============================================================================

-- ── Schema + extensions ─────────────────────────────────────────────────────

create schema if not exists coach_internal;
revoke all on schema coach_internal from public, anon, authenticated;
grant usage on schema coach_internal to service_role;

create extension if not exists vector with schema extensions;

-- ============================================================================
-- TABLES
-- ============================================================================

-- ── public.user_entitlement (backend-wide entitlement mirror) ───────────────
create table public.user_entitlement (
    user_id uuid not null references auth.users(id) on delete cascade,
    entitlement_id text not null,
    active boolean not null,
    expires_at timestamptz,
    grace_period_ends_at timestamptz,
    product_id text,
    store text,
    revenuecat_app_user_id text,
    revenuecat_environment text check (revenuecat_environment in ('SANDBOX', 'PRODUCTION')),
    source text not null default 'revenuecat',
    updated_at timestamptz not null default now(),
    primary key (user_id, entitlement_id)
);

create index user_entitlement_active_idx
    on public.user_entitlement (user_id, entitlement_id)
    where active = true;

alter table public.user_entitlement enable row level security;
revoke all on table public.user_entitlement from anon, authenticated;
grant select on table public.user_entitlement to authenticated;
grant select, insert, update, delete on table public.user_entitlement to service_role;

create policy user_entitlement_select_own
    on public.user_entitlement
    for select
    to authenticated
    using ((select auth.uid()) = user_id);

comment on table public.user_entitlement is
    'Backend-maintained RevenueCat entitlement mirror; authenticated users have self-select only.';
comment on column public.user_entitlement.source is
    'Writer that owns this row: revenuecat (webhook sync) | manual (DB grant). Reconcile only touches revenuecat rows.';

-- ── coach_internal.processed_revenuecat_event (webhook idempotency) ─────────
create table coach_internal.processed_revenuecat_event (
    event_id text primary key,
    state text not null check (state in ('processing', 'processed', 'failed')),
    attempts integer not null default 1 check (attempts > 0),
    received_at timestamptz not null default now(),
    started_at timestamptz,
    processed_at timestamptz,
    last_error text,
    event_type text not null,
    payload jsonb not null,
    identity_candidates text[] not null
);

create index processed_revenuecat_event_recovery_idx
    on coach_internal.processed_revenuecat_event (started_at)
    where state = 'processing';
create index processed_revenuecat_event_cleanup_idx
    on coach_internal.processed_revenuecat_event (processed_at)
    where state = 'processed';

alter table coach_internal.processed_revenuecat_event enable row level security;
revoke all on table coach_internal.processed_revenuecat_event from public, anon, authenticated;
grant select, insert, update, delete on table coach_internal.processed_revenuecat_event to service_role;

comment on table coach_internal.processed_revenuecat_event is
    'Durable idempotency and recovery state for RevenueCat webhook processing.';

-- ── public.coach_chat ───────────────────────────────────────────────────────
create table public.coach_chat (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    title text not null default 'New chat',
    title_generated boolean not null default false,
    locale text not null check (char_length(locale) between 2 and 32),
    message_count integer not null default 0 check (message_count >= 0),
    last_message_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    archived_at timestamptz,
    unique (id, user_id)
);

create index coach_chat_user_active_idx
    on public.coach_chat (user_id, updated_at desc)
    where archived_at is null;

alter table public.coach_chat enable row level security;
revoke all on table public.coach_chat from anon, authenticated;
grant select on table public.coach_chat to authenticated;
grant select, insert, update, delete on table public.coach_chat to service_role;

create policy coach_chat_select_own
    on public.coach_chat
    for select
    to authenticated
    using ((select auth.uid()) = user_id);

comment on table public.coach_chat is
    'AI Coach chat metadata. Authenticated clients have self-select only.';

-- ── public.coach_message ────────────────────────────────────────────────────
create table public.coach_message (
    id uuid primary key default gen_random_uuid(),
    chat_id uuid not null,
    user_id uuid not null,
    request_id uuid not null,
    role text not null check (role in ('user', 'assistant')),
    content text not null check (char_length(content) between 1 and 20000),
    citations jsonb,
    input_tokens integer check (input_tokens >= 0),
    output_tokens integer check (output_tokens >= 0),
    cached_tokens integer check (cached_tokens >= 0),
    model_used text,
    escalated boolean not null default false,
    safety_action text,
    finish_reason text,
    created_at timestamptz not null default now(),
    foreign key (chat_id, user_id)
        references public.coach_chat(id, user_id)
        on delete cascade,
    unique (user_id, request_id, role)
);

create index coach_message_chat_created_idx
    on public.coach_message (chat_id, created_at, id);
create index coach_message_user_request_idx
    on public.coach_message (user_id, request_id);
create index coach_message_chat_user_fk_idx
    on public.coach_message (chat_id, user_id);

alter table public.coach_message enable row level security;
revoke all on table public.coach_message from anon, authenticated;
grant select on table public.coach_message to authenticated;
grant select, insert, update, delete on table public.coach_message to service_role;

create policy coach_message_select_own
    on public.coach_message
    for select
    to authenticated
    using ((select auth.uid()) = user_id);

comment on table public.coach_message is
    'Completed AI Coach user/assistant messages. Authenticated clients have self-select only.';

-- ── coach_internal.coach_turn (per-request idempotency + turn lease) ────────
create table coach_internal.coach_turn (
    user_id uuid not null references auth.users(id) on delete cascade,
    request_id uuid not null,
    chat_id uuid not null,
    state text not null check (state in ('processing', 'completed', 'failed')),
    lease_expires_at timestamptz,
    attempts integer not null default 1 check (attempts > 0),
    user_message_id uuid references public.coach_message(id) on delete set null,
    assistant_message_id uuid references public.coach_message(id) on delete set null,
    last_error text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (user_id, request_id),
    foreign key (chat_id, user_id)
        references public.coach_chat(id, user_id)
        on delete cascade
);

create index coach_turn_chat_processing_idx
    on coach_internal.coach_turn (chat_id, lease_expires_at)
    where state = 'processing';
create index coach_turn_chat_user_fk_idx
    on coach_internal.coach_turn (chat_id, user_id);
create index coach_turn_user_message_fk_idx
    on coach_internal.coach_turn (user_message_id)
    where user_message_id is not null;
create index coach_turn_assistant_message_fk_idx
    on coach_internal.coach_turn (assistant_message_id)
    where assistant_message_id is not null;

alter table coach_internal.coach_turn enable row level security;
revoke all on table coach_internal.coach_turn from public, anon, authenticated;
grant select, insert, update, delete on table coach_internal.coach_turn to service_role;

comment on table coach_internal.coach_turn is
    'Private request idempotency and cross-instance turn lease state.';

-- ── public.coach_kb_doc / public.coach_kb_chunk (RAG corpus) ────────────────
create table public.coach_kb_doc (
    id text primary key check (id ~ '^[a-z0-9][a-z0-9_/-]{2,159}$'),
    title text not null check (char_length(title) between 1 and 300),
    section text not null check (section in ('app', 'nutrition', 'training', 'recipes', 'general')),
    locale text not null default 'en' check (locale = 'en'),
    content_md text not null check (char_length(content_md) between 1 and 200000),
    content_hash text not null check (content_hash ~ '^[0-9a-f]{64}$'),
    source_uri text,
    version integer not null default 1 check (version > 0),
    updated_at timestamptz not null default now()
);

alter table public.coach_kb_doc enable row level security;
revoke all on table public.coach_kb_doc from public, anon, authenticated;
grant select, insert, update, delete on table public.coach_kb_doc to service_role;

create table public.coach_kb_chunk (
    id uuid primary key default gen_random_uuid(),
    doc_id text not null references public.coach_kb_doc(id) on delete cascade,
    section text not null check (section in ('app', 'nutrition', 'training', 'recipes', 'general')),
    chunk_index integer not null check (chunk_index >= 0),
    text text not null check (char_length(text) between 1 and 12000),
    tokens integer not null check (tokens between 1 and 8192),
    embedding extensions.vector(768) not null,
    embedding_model text not null,
    embedding_dim integer not null check (embedding_dim = 768),
    embedding_format_version text not null,
    metadata jsonb,
    created_at timestamptz not null default now(),
    unique (doc_id, chunk_index)
);

create index coach_kb_chunk_embed_idx
    on public.coach_kb_chunk using hnsw (embedding extensions.vector_cosine_ops)
    with (m = 16, ef_construction = 64);
create index coach_kb_chunk_text_idx
    on public.coach_kb_chunk using gin (text gin_trgm_ops);
create index coach_kb_chunk_model_format_idx
    on public.coach_kb_chunk (embedding_model, embedding_format_version, section);

alter table public.coach_kb_chunk enable row level security;
revoke all on table public.coach_kb_chunk from public, anon, authenticated;
grant select, insert, update, delete on table public.coach_kb_chunk to service_role;

comment on table public.coach_kb_doc is 'Service-role-only curated AI Coach documents.';
comment on table public.coach_kb_chunk is 'Service-role-only 768-dimensional AI Coach retrieval chunks.';

-- ── public.coach_user_note (cross-chat memory) ──────────────────────────────
create table public.coach_user_note (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    note text not null check (char_length(note) between 1 and 500),
    category text not null check (category in ('preference', 'restriction', 'goal_context', 'other')),
    source text not null check (source in ('coach', 'user')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index coach_user_note_user_updated_idx
    on public.coach_user_note (user_id, updated_at desc, id);

alter table public.coach_user_note enable row level security;
revoke all on table public.coach_user_note from public, anon, authenticated;
grant select on table public.coach_user_note to authenticated;
grant select, insert, update, delete on table public.coach_user_note to service_role;

create policy coach_user_note_select_own
    on public.coach_user_note for select to authenticated
    using ((select auth.uid()) = user_id);

comment on table public.coach_user_note is
    'AI Coach cross-chat memory. Authenticated clients can only select their own notes; writes use the backend.';

-- ── public.coach_summary (auto-compaction) ──────────────────────────────────
create table public.coach_summary (
    chat_id uuid not null references public.coach_chat(id) on delete cascade,
    up_to_message_id uuid not null references public.coach_message(id) on delete cascade,
    user_id uuid not null references auth.users(id) on delete cascade,
    summary text not null check (char_length(summary) between 1 and 5000),
    tokens integer not null check (tokens >= 0),
    created_at timestamptz not null default now(),
    primary key (chat_id, up_to_message_id),
    foreign key (chat_id, user_id) references public.coach_chat(id, user_id) on delete cascade
);
create index coach_summary_user_idx on public.coach_summary (user_id, created_at desc);
create index coach_summary_chat_user_fk_idx on public.coach_summary (chat_id, user_id);
create index coach_summary_message_fk_idx on public.coach_summary (up_to_message_id);

alter table public.coach_summary enable row level security;
revoke all on table public.coach_summary from public, anon, authenticated;
grant select on table public.coach_summary to authenticated;
grant select, insert, update, delete on table public.coach_summary to service_role;
create policy coach_summary_select_own on public.coach_summary for select to authenticated
    using ((select auth.uid()) = user_id);

-- ── public.coach_trace (PII-minimized audit) ────────────────────────────────
create table public.coach_trace (
    id uuid primary key default gen_random_uuid(),
    message_id uuid not null references public.coach_message(id) on delete cascade,
    user_id uuid not null references auth.users(id) on delete cascade,
    rag_query_hash text,
    retrieved jsonb,
    tool_calls jsonb,
    safety_events jsonb,
    duration_ms integer check (duration_ms >= 0),
    prompt_version integer not null,
    created_at timestamptz not null default now()
);
create index coach_trace_user_created_idx on public.coach_trace (user_id, created_at desc);
create index coach_trace_message_fk_idx on public.coach_trace (message_id);

alter table public.coach_trace enable row level security;
revoke all on table public.coach_trace from public, anon, authenticated;
grant select on table public.coach_trace to authenticated;
grant select, insert, update, delete on table public.coach_trace to service_role;
create policy coach_trace_select_own on public.coach_trace for select to authenticated
    using ((select auth.uid()) = user_id);

comment on table public.coach_trace is
    'PII-minimized AI Coach audit metadata. Raw user and assistant content is never stored here.';

-- ── public.coach_budget / coach_internal.coach_budget_reservation ───────────
create table public.coach_budget (
    user_id uuid not null references auth.users(id) on delete cascade,
    period_yyyymm integer not null check (period_yyyymm between 202001 and 299912),
    messages_used integer not null default 0 check (messages_used >= 0),
    input_tokens_used bigint not null default 0 check (input_tokens_used >= 0),
    output_tokens_used bigint not null default 0 check (output_tokens_used >= 0),
    cents_estimated integer not null default 0 check (cents_estimated >= 0),
    last_message_at timestamptz,
    primary key (user_id, period_yyyymm)
);

alter table public.coach_budget enable row level security;
revoke all on table public.coach_budget from public, anon, authenticated;
grant select on table public.coach_budget to authenticated;
grant select, insert, update, delete on table public.coach_budget to service_role;
create policy coach_budget_select_own on public.coach_budget for select to authenticated
    using ((select auth.uid()) = user_id);

create table coach_internal.coach_budget_reservation (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    period_yyyymm integer not null,
    request_id uuid not null,
    reserved_input integer not null check (reserved_input >= 0),
    reserved_output integer not null check (reserved_output >= 0),
    actual_input integer check (actual_input >= 0),
    actual_output integer check (actual_output >= 0),
    state text not null check (state in ('reserved', 'reconciled', 'released', 'exempt')),
    created_at timestamptz not null default now(),
    settled_at timestamptz,
    unique (user_id, request_id),
    foreign key (user_id, period_yyyymm)
        references public.coach_budget(user_id, period_yyyymm) on delete cascade
);
create index coach_budget_reservation_open_idx
    on coach_internal.coach_budget_reservation (created_at)
    where state = 'reserved';
create index coach_budget_reservation_budget_fk_idx
    on coach_internal.coach_budget_reservation (user_id, period_yyyymm);

alter table coach_internal.coach_budget_reservation enable row level security;
revoke all on table coach_internal.coach_budget_reservation from public, anon, authenticated;
grant select, insert, update, delete on table coach_internal.coach_budget_reservation to service_role;

-- ============================================================================
-- FUNCTIONS — coach_internal.* (privileged core; service_role only)
-- ============================================================================

-- ── RevenueCat webhook idempotency + reconcile ──────────────────────────────
create or replace function coach_internal.claim_revenuecat_event(
    p_event_id text,
    p_event_type text,
    p_payload jsonb,
    p_identity_candidates text[]
) returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_prior_state text;
begin
    if p_event_id is null or btrim(p_event_id) = '' then
        raise exception 'event_id must not be blank';
    end if;
    if p_event_type is null or btrim(p_event_type) = '' then
        raise exception 'event_type must not be blank';
    end if;
    if p_payload is null then
        raise exception 'payload must not be null';
    end if;

    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(p_event_id, 0));

    select event.state
      into v_prior_state
      from coach_internal.processed_revenuecat_event event
     where event.event_id = p_event_id
     for update;

    if v_prior_state is null then
        insert into coach_internal.processed_revenuecat_event (
            event_id, state, started_at, attempts, event_type, payload, identity_candidates
        ) values (
            p_event_id, 'processing', pg_catalog.now(), 1, p_event_type, p_payload,
            coalesce(p_identity_candidates, array[]::text[])
        );
        return 'inserted';
    elsif v_prior_state = 'failed' then
        update coach_internal.processed_revenuecat_event
           set state = 'processing',
               started_at = pg_catalog.now(),
               processed_at = null,
               attempts = attempts + 1,
               event_type = p_event_type,
               payload = p_payload,
               identity_candidates = coalesce(p_identity_candidates, array[]::text[]),
               last_error = null
         where event_id = p_event_id;
        return 'retry_failed';
    elsif v_prior_state = 'processing' then
        return 'already_processing';
    else
        return 'already_processed';
    end if;
end;
$$;

create or replace function coach_internal.mark_revenuecat_event_processed(
    p_event_id text,
    p_last_note text default null
) returns void
language sql
security definer
set search_path = ''
as $$
    update coach_internal.processed_revenuecat_event
       set state = 'processed',
           processed_at = pg_catalog.now(),
           last_error = p_last_note
     where event_id = p_event_id
       and state = 'processing';
$$;

create or replace function coach_internal.mark_revenuecat_event_failed(
    p_event_id text,
    p_error text
) returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    update coach_internal.processed_revenuecat_event
       set state = 'failed',
           last_error = left(coalesce(p_error, 'unknown error'), 2000)
     where event_id = p_event_id
       and state = 'processing';
end;
$$;

create or replace function coach_internal.claim_stale_revenuecat_events(
    p_older_than interval default interval '5 minutes',
    p_limit integer default 100
) returns table (
    event_id text,
    event_type text,
    payload jsonb,
    identity_candidates text[],
    attempts integer
)
language sql
security definer
set search_path = ''
as $$
    with stale as (
        select event.event_id
          from coach_internal.processed_revenuecat_event event
         where event.state = 'processing'
           and event.started_at < pg_catalog.now() - p_older_than
         order by event.started_at
         for update skip locked
         limit greatest(1, least(p_limit, 500))
    ), claimed as (
        update coach_internal.processed_revenuecat_event event
           set started_at = pg_catalog.now(),
               attempts = event.attempts + 1
          from stale
         where event.event_id = stale.event_id
         returning event.event_id, event.event_type, event.payload,
                   event.identity_candidates, event.attempts
    )
    select * from claimed;
$$;

create or replace function coach_internal.claim_recoverable_revenuecat_events(
    p_stale_after interval default interval '5 minutes',
    p_retry_after interval default interval '5 minutes',
    p_limit integer default 100,
    p_max_attempts integer default 10
) returns table (
    event_id text,
    event_type text,
    payload jsonb,
    identity_candidates text[],
    attempts integer
)
language sql
security definer
set search_path = ''
as $$
    with recoverable as (
        select event.event_id
          from coach_internal.processed_revenuecat_event event
         where event.attempts < greatest(1, p_max_attempts)
           and (
               (event.state = 'processing'
                and event.started_at < pg_catalog.now() - p_stale_after)
               or
               (event.state = 'failed'
                and event.started_at < pg_catalog.now() - p_retry_after)
           )
         order by event.started_at
         for update skip locked
         limit greatest(1, least(p_limit, 500))
    ), claimed as (
        update coach_internal.processed_revenuecat_event event
           set state = 'processing',
               started_at = pg_catalog.now(),
               attempts = event.attempts + 1,
               last_error = null
          from recoverable
         where event.event_id = recoverable.event_id
         returning event.event_id,
                   event.event_type,
                   event.payload,
                   event.identity_candidates,
                   event.attempts
    )
    select * from claimed;
$$;

-- Final form (source-scoped deactivate; manual grants survive RC sync).
create or replace function coach_internal.reconcile_user_entitlements(
    p_user_id uuid, p_revenuecat_app_user_id text, p_environment text, p_entitlements jsonb
) returns void
language plpgsql security definer set search_path to '' as $function$
begin
    if p_environment not in ('SANDBOX', 'PRODUCTION') then
        raise exception 'invalid RevenueCat environment';
    end if;
    if p_entitlements is null or pg_catalog.jsonb_typeof(p_entitlements) <> 'array' then
        raise exception 'entitlements must be a JSON array';
    end if;
    if exists (
        select 1
          from pg_catalog.jsonb_to_recordset(p_entitlements) as item(
              entitlement_id text,
              active boolean,
              expires_at timestamptz,
              grace_period_ends_at timestamptz,
              product_id text,
              store text
          )
         where item.entitlement_id is null
            or pg_catalog.btrim(item.entitlement_id) = ''
            or item.active is null
    ) then
        raise exception 'invalid entitlement item';
    end if;

    perform pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended(p_user_id::text, 0)
    );

    update public.user_entitlement existing
       set active = false,
           revenuecat_app_user_id = p_revenuecat_app_user_id,
           revenuecat_environment = p_environment,
           updated_at = pg_catalog.now()
     where existing.user_id = p_user_id
       and existing.source = 'revenuecat'
       and not exists (
           select 1
             from pg_catalog.jsonb_to_recordset(p_entitlements) as item(
                 entitlement_id text,
                 active boolean,
                 expires_at timestamptz,
                 grace_period_ends_at timestamptz,
                 product_id text,
                 store text
             )
            where item.entitlement_id = existing.entitlement_id
       );

    insert into public.user_entitlement (
        user_id,
        entitlement_id,
        active,
        expires_at,
        grace_period_ends_at,
        product_id,
        store,
        revenuecat_app_user_id,
        revenuecat_environment,
        source,
        updated_at
    )
    select p_user_id,
           item.entitlement_id,
           item.active,
           item.expires_at,
           item.grace_period_ends_at,
           item.product_id,
           item.store,
           p_revenuecat_app_user_id,
           p_environment,
           'revenuecat',
           pg_catalog.now()
      from pg_catalog.jsonb_to_recordset(p_entitlements) as item(
          entitlement_id text,
          active boolean,
          expires_at timestamptz,
          grace_period_ends_at timestamptz,
          product_id text,
          store text
      )
    on conflict (user_id, entitlement_id) do update
       set active = excluded.active,
           expires_at = excluded.expires_at,
           grace_period_ends_at = excluded.grace_period_ends_at,
           product_id = excluded.product_id,
           store = excluded.store,
           revenuecat_app_user_id = excluded.revenuecat_app_user_id,
           revenuecat_environment = excluded.revenuecat_environment,
           source = excluded.source,
           updated_at = excluded.updated_at;
end;
$function$;

-- ── Turn state machine ──────────────────────────────────────────────────────
create or replace function coach_internal.claim_turn(
    p_user_id uuid,
    p_chat_id uuid,
    p_request_id uuid,
    p_locale text,
    p_lease_seconds integer default 90
) returns table (
    status text,
    chat_id uuid,
    chat_created boolean,
    assistant_message_id uuid
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_turn coach_internal.coach_turn%rowtype;
    v_turn_found boolean := false;
    v_chat_id uuid;
    v_chat_created boolean := false;
    v_other_processing boolean := false;
    v_lease_seconds integer := greatest(30, least(p_lease_seconds, 300));
begin
    if p_user_id is null or p_request_id is null then
        raise exception 'user_id and request_id are required';
    end if;
    if p_locale is null or pg_catalog.char_length(pg_catalog.btrim(p_locale)) not between 2 and 32 then
        raise exception 'invalid locale';
    end if;

    perform pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended(p_user_id::text || ':' || p_request_id::text, 0)
    );

    select turn.*
      into v_turn
      from coach_internal.coach_turn turn
     where turn.user_id = p_user_id
       and turn.request_id = p_request_id
     for update;
    v_turn_found := found;

    if v_turn_found then
        if p_chat_id is not null and p_chat_id <> v_turn.chat_id then
            raise exception 'request_id belongs to a different chat';
        end if;
        v_chat_id := v_turn.chat_id;

        if v_turn.state = 'completed' then
            return query select 'completed'::text, v_chat_id, false, v_turn.assistant_message_id;
            return;
        end if;
        if v_turn.state = 'processing' and v_turn.lease_expires_at > pg_catalog.now() then
            return query select 'same_request'::text, v_chat_id, false, v_turn.assistant_message_id;
            return;
        end if;
    elsif p_chat_id is null then
        insert into public.coach_chat (user_id, locale)
        values (p_user_id, pg_catalog.btrim(p_locale))
        returning id into v_chat_id;
        v_chat_created := true;
    else
        v_chat_id := p_chat_id;
    end if;

    if not exists (
        select 1
          from public.coach_chat chat
         where chat.id = v_chat_id
           and chat.user_id = p_user_id
           and chat.archived_at is null
    ) then
        raise exception 'chat not found or archived' using errcode = 'P0002';
    end if;

    perform pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended(v_chat_id::text, 0)
    );

    select exists (
        select 1
          from coach_internal.coach_turn turn
         where turn.chat_id = v_chat_id
           and turn.state = 'processing'
           and turn.lease_expires_at > pg_catalog.now()
           and (turn.user_id, turn.request_id) <> (p_user_id, p_request_id)
    ) into v_other_processing;

    if v_other_processing then
        return query select 'in_flight'::text, v_chat_id, v_chat_created, null::uuid;
        return;
    end if;

    update coach_internal.coach_turn turn
       set state = 'failed',
           lease_expires_at = null,
           last_error = 'lease_expired',
           updated_at = pg_catalog.now()
     where turn.chat_id = v_chat_id
       and turn.state = 'processing'
       and turn.lease_expires_at <= pg_catalog.now()
       and (turn.user_id, turn.request_id) <> (p_user_id, p_request_id);

    insert into coach_internal.coach_turn (
        user_id,
        request_id,
        chat_id,
        state,
        lease_expires_at,
        attempts,
        last_error,
        updated_at
    ) values (
        p_user_id,
        p_request_id,
        v_chat_id,
        'processing',
        pg_catalog.now() + pg_catalog.make_interval(secs => v_lease_seconds),
        1,
        null,
        pg_catalog.now()
    )
    on conflict (user_id, request_id) do update
       set state = 'processing',
           lease_expires_at = excluded.lease_expires_at,
           attempts = coach_turn.attempts + 1,
           last_error = null,
           updated_at = pg_catalog.now();

    return query select 'claimed'::text, v_chat_id, v_chat_created, null::uuid;
end;
$$;

create or replace function coach_internal.complete_turn(
    p_user_id uuid,
    p_request_id uuid,
    p_user_message_id uuid,
    p_assistant_message_id uuid
) returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    update coach_internal.coach_turn
       set state = 'completed',
           lease_expires_at = null,
           user_message_id = p_user_message_id,
           assistant_message_id = p_assistant_message_id,
           last_error = null,
           updated_at = pg_catalog.now()
     where user_id = p_user_id
       and request_id = p_request_id
       and state = 'processing';

    if not found then
        raise exception 'processing turn not found';
    end if;
end;
$$;

create or replace function coach_internal.fail_turn(
    p_user_id uuid,
    p_request_id uuid,
    p_error text
) returns void
language sql
security definer
set search_path = ''
as $$
    update coach_internal.coach_turn
       set state = 'failed',
           lease_expires_at = null,
           last_error = pg_catalog.left(coalesce(p_error, 'unknown error'), 2000),
           updated_at = pg_catalog.now()
     where user_id = p_user_id
       and request_id = p_request_id
       and state = 'processing';
$$;

create or replace function coach_internal.persist_turn(
    p_user_id uuid,
    p_request_id uuid,
    p_user_content text,
    p_assistant_content text,
    p_input_tokens integer default null,
    p_output_tokens integer default null,
    p_cached_tokens integer default null,
    p_model_used text default null,
    p_escalated boolean default false,
    p_safety_action text default null,
    p_finish_reason text default null,
    p_citations jsonb default null
) returns table (
    user_message_id uuid,
    assistant_message_id uuid
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_turn coach_internal.coach_turn%rowtype;
    v_user_message_id uuid;
    v_assistant_message_id uuid;
    v_last_message_at timestamptz;
begin
    if p_user_content is null or pg_catalog.char_length(p_user_content) not between 1 and 2000 then
        raise exception 'invalid user content';
    end if;
    if p_assistant_content is null or pg_catalog.char_length(p_assistant_content) not between 1 and 20000 then
        raise exception 'invalid assistant content';
    end if;
    if p_input_tokens < 0 or p_output_tokens < 0 or p_cached_tokens < 0 then
        raise exception 'negative token count';
    end if;

    select turn.* into v_turn
      from coach_internal.coach_turn turn
     where turn.user_id = p_user_id and turn.request_id = p_request_id
     for update;

    if not found then
        raise exception 'turn not found' using errcode = 'P0002';
    end if;

    if v_turn.state = 'completed' then
        return query select v_turn.user_message_id, v_turn.assistant_message_id;
        return;
    end if;
    if v_turn.state <> 'processing' then
        raise exception 'turn is not processing';
    end if;

    insert into public.coach_message (
        chat_id, user_id, request_id, role, content
    ) values (
        v_turn.chat_id, p_user_id, p_request_id, 'user', p_user_content
    )
    on conflict (user_id, request_id, role) do nothing
    returning id into v_user_message_id;

    if v_user_message_id is null then
        select message.id into v_user_message_id
          from public.coach_message message
         where message.user_id = p_user_id
           and message.request_id = p_request_id
           and message.role = 'user'
           and message.chat_id = v_turn.chat_id
           and message.content = p_user_content;
        if not found then
            raise exception 'request_id was reused with different content';
        end if;
    end if;

    insert into public.coach_message (
        chat_id, user_id, request_id, role, content, citations,
        input_tokens, output_tokens, cached_tokens, model_used,
        escalated, safety_action, finish_reason
    ) values (
        v_turn.chat_id, p_user_id, p_request_id, 'assistant', p_assistant_content,
        p_citations, p_input_tokens, p_output_tokens, p_cached_tokens, p_model_used,
        p_escalated, p_safety_action, p_finish_reason
    )
    on conflict (user_id, request_id, role) do nothing
    returning id, created_at into v_assistant_message_id, v_last_message_at;

    if v_assistant_message_id is null then
        select message.id, message.created_at
          into v_assistant_message_id, v_last_message_at
          from public.coach_message message
         where message.user_id = p_user_id
           and message.request_id = p_request_id
           and message.role = 'assistant'
           and message.chat_id = v_turn.chat_id;
        if not found then
            raise exception 'assistant message conflict';
        end if;
    end if;

    update public.coach_chat chat
       set message_count = (
               select pg_catalog.count(*)::integer
                 from public.coach_message message
                where message.chat_id = chat.id
           ),
           last_message_at = v_last_message_at,
           updated_at = pg_catalog.now()
     where chat.id = v_turn.chat_id and chat.user_id = p_user_id;

    update coach_internal.coach_turn
       set state = 'completed', lease_expires_at = null,
           user_message_id = v_user_message_id,
           assistant_message_id = v_assistant_message_id,
           last_error = null, updated_at = pg_catalog.now()
     where user_id = p_user_id and request_id = p_request_id;

    return query select v_user_message_id, v_assistant_message_id;
end;
$$;

-- ── Notes ───────────────────────────────────────────────────────────────────
create or replace function coach_internal.write_user_note(
    p_user_id uuid,
    p_category text,
    p_note text,
    p_source text
) returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_id uuid;
begin
    insert into public.coach_user_note (user_id, category, note, source)
    values (p_user_id, p_category, pg_catalog.btrim(p_note), p_source)
    returning id into v_id;

    delete from public.coach_user_note note
     where note.user_id = p_user_id
       and note.id in (
           select old.id
             from public.coach_user_note old
            where old.user_id = p_user_id
            order by old.updated_at desc, old.id desc
            offset 50
       );

    return v_id;
end;
$$;

-- ── KB corpus ingest ────────────────────────────────────────────────────────
create or replace function coach_internal.replace_kb_doc(
    p_id text,
    p_title text,
    p_section text,
    p_content_md text,
    p_content_hash text,
    p_source_uri text,
    p_version integer,
    p_embedding_model text,
    p_embedding_format_version text,
    p_chunks jsonb
) returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_count integer;
begin
    if p_chunks is null or pg_catalog.jsonb_typeof(p_chunks) <> 'array' then
        raise exception 'chunks must be a JSON array';
    end if;

    insert into public.coach_kb_doc (
        id, title, section, locale, content_md, content_hash, source_uri, version, updated_at
    ) values (
        p_id, p_title, p_section, 'en', p_content_md, p_content_hash, p_source_uri, p_version, pg_catalog.now()
    )
    on conflict (id) do update set
        title = excluded.title,
        section = excluded.section,
        content_md = excluded.content_md,
        content_hash = excluded.content_hash,
        source_uri = excluded.source_uri,
        version = excluded.version,
        updated_at = pg_catalog.now();

    delete from public.coach_kb_chunk where doc_id = p_id;

    insert into public.coach_kb_chunk (
        doc_id, section, chunk_index, text, tokens, embedding,
        embedding_model, embedding_dim, embedding_format_version, metadata
    )
    select p_id,
           p_section,
           item.chunk_index,
           item.text,
           item.tokens,
           item.embedding::extensions.vector(768),
           p_embedding_model,
           768,
           p_embedding_format_version,
           item.metadata
      from pg_catalog.jsonb_to_recordset(p_chunks) as item(
          chunk_index integer,
          text text,
          tokens integer,
          embedding text,
          metadata jsonb
      );

    get diagnostics v_count = row_count;
    if v_count = 0 then
        raise exception 'at least one chunk is required';
    end if;
    return v_count;
end;
$$;

-- ── Budget reserve / reconcile / release / exempt / sweep (final forms) ─────
create or replace function coach_internal.budget_reserve(
    p_user_id uuid,
    p_request_id uuid,
    p_period integer,
    p_input_max integer,
    p_output_max integer,
    p_cap_messages integer,
    p_cap_tokens bigint
) returns table (allowed boolean, reason text, reservation_id uuid, status text)
language plpgsql security definer set search_path = '' as $$
declare
    v_existing coach_internal.coach_budget_reservation%rowtype;
    v_id uuid;
begin
    if p_input_max < 0 or p_output_max < 0 or p_cap_messages < 1 or p_cap_tokens < 1 then
        raise exception 'invalid budget arguments';
    end if;
    perform pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended(p_user_id::text || ':' || p_request_id::text, 0)
    );
    select reservation.* into v_existing
      from coach_internal.coach_budget_reservation reservation
     where reservation.user_id = p_user_id and reservation.request_id = p_request_id
     for update;
    if found then
        if v_existing.state = 'released' then
            delete from coach_internal.coach_budget_reservation where id = v_existing.id;
        else
            return query select true, null::text, v_existing.id, v_existing.state;
            return;
        end if;
    end if;
    insert into public.coach_budget (user_id, period_yyyymm)
    values (p_user_id, p_period) on conflict (user_id, period_yyyymm) do nothing;
    update public.coach_budget budget
       set messages_used = messages_used + 1,
           input_tokens_used = input_tokens_used + p_input_max,
           output_tokens_used = output_tokens_used + p_output_max,
           last_message_at = pg_catalog.now()
     where budget.user_id = p_user_id and budget.period_yyyymm = p_period
       and budget.messages_used + 1 <= p_cap_messages
       and budget.input_tokens_used + budget.output_tokens_used + p_input_max + p_output_max <= p_cap_tokens;
    if not found then
        return query select false,
            case when (select messages_used from public.coach_budget
                        where user_id = p_user_id and period_yyyymm = p_period) + 1 > p_cap_messages
                 then 'cap_messages' else 'cap_tokens' end,
            null::uuid, 'rejected'::text;
        return;
    end if;
    insert into coach_internal.coach_budget_reservation (
        user_id, period_yyyymm, request_id, reserved_input, reserved_output, state
    ) values (p_user_id, p_period, p_request_id, p_input_max, p_output_max, 'reserved')
    returning id into v_id;
    return query select true, null::text, v_id, 'reserved'::text;
end;
$$;

create or replace function coach_internal.budget_reconcile(
    p_reservation_id uuid,
    p_actual_input integer,
    p_actual_output integer
) returns void language plpgsql security definer set search_path = '' as $$
declare v_row coach_internal.coach_budget_reservation%rowtype;
begin
    if p_actual_input < 0 or p_actual_output < 0 then raise exception 'negative actuals'; end if;
    select * into v_row from coach_internal.coach_budget_reservation where id = p_reservation_id for update;
    if not found or v_row.state <> 'reserved' then return; end if;
    update public.coach_budget
       set input_tokens_used = greatest(0::bigint, input_tokens_used + p_actual_input - v_row.reserved_input),
           output_tokens_used = greatest(0::bigint, output_tokens_used + p_actual_output - v_row.reserved_output),
           last_message_at = pg_catalog.now()
     where user_id = v_row.user_id and period_yyyymm = v_row.period_yyyymm;
    update coach_internal.coach_budget_reservation
       set actual_input = p_actual_input, actual_output = p_actual_output,
           state = 'reconciled', settled_at = pg_catalog.now()
     where id = p_reservation_id;
end;
$$;

create or replace function coach_internal.budget_release(p_reservation_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare v_row coach_internal.coach_budget_reservation%rowtype;
begin
    select * into v_row from coach_internal.coach_budget_reservation where id = p_reservation_id for update;
    if not found or v_row.state <> 'reserved' then return; end if;
    update public.coach_budget
       set messages_used = greatest(0, messages_used - 1),
           input_tokens_used = greatest(0::bigint, input_tokens_used - v_row.reserved_input),
           output_tokens_used = greatest(0::bigint, output_tokens_used - v_row.reserved_output)
     where user_id = v_row.user_id and period_yyyymm = v_row.period_yyyymm;
    update coach_internal.coach_budget_reservation
       set state = 'released', settled_at = pg_catalog.now()
     where id = p_reservation_id;
end;
$$;

create or replace function coach_internal.budget_exempt(p_reservation_id uuid) returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_row coach_internal.coach_budget_reservation%rowtype;
begin
    select * into v_row
      from coach_internal.coach_budget_reservation
     where id = p_reservation_id
     for update;
    if not found or v_row.state <> 'reserved' then return; end if;

    update public.coach_budget
       set messages_used = greatest(0, messages_used - 1),
           input_tokens_used = greatest(0::bigint, input_tokens_used - v_row.reserved_input),
           output_tokens_used = greatest(0::bigint, output_tokens_used - v_row.reserved_output)
     where user_id = v_row.user_id
       and period_yyyymm = v_row.period_yyyymm;

    update coach_internal.coach_budget_reservation
       set state = 'exempt', settled_at = pg_catalog.now()
     where id = p_reservation_id;
end;
$$;

-- Reconciles completed turns to actuals, releases the rest (final form).
create or replace function coach_internal.release_stale_budget_reservations(
    p_older_than interval default interval '10 minutes',
    p_limit integer default 100
) returns integer
language plpgsql security definer set search_path = '' as $$
declare
    v_row record;
    v_count integer := 0;
begin
    for v_row in
        select reservation.id,
               turn.state as turn_state,
               coalesce(message.input_tokens, 0) as input_tokens,
               coalesce(message.output_tokens, 0) as output_tokens
          from coach_internal.coach_budget_reservation reservation
          left join coach_internal.coach_turn turn
            on turn.user_id = reservation.user_id and turn.request_id = reservation.request_id
          left join public.coach_message message on message.id = turn.assistant_message_id
         where reservation.state = 'reserved'
           and reservation.created_at < pg_catalog.now() - p_older_than
         order by reservation.created_at
         limit greatest(1, least(p_limit, 1000))
         for update of reservation skip locked
    loop
        if v_row.turn_state = 'completed' then
            perform coach_internal.budget_reconcile(v_row.id, v_row.input_tokens, v_row.output_tokens);
        else
            perform coach_internal.budget_release(v_row.id);
        end if;
        v_count := v_count + 1;
    end loop;
    return v_count;
end;
$$;

-- ── Retention sweep ─────────────────────────────────────────────────────────
create or replace function coach_internal.retention_sweep(
    p_before timestamptz,
    p_limit integer default 1000
) returns integer
language plpgsql security definer set search_path = '' as $$
declare v_count integer;
begin
    with doomed as (
        select chat.id
          from public.coach_chat chat
         where chat.updated_at < p_before
         order by chat.updated_at
         limit greatest(1, least(p_limit, 5000))
         for update skip locked
    )
    delete from public.coach_chat chat using doomed
     where chat.id = doomed.id;
    get diagnostics v_count = row_count;
    return v_count;
end;
$$;

-- Grants for coach_internal.* (service_role only)
revoke all on function coach_internal.claim_revenuecat_event(text, text, jsonb, text[]) from public, anon, authenticated;
revoke all on function coach_internal.mark_revenuecat_event_processed(text, text) from public, anon, authenticated;
revoke all on function coach_internal.mark_revenuecat_event_failed(text, text) from public, anon, authenticated;
revoke all on function coach_internal.claim_stale_revenuecat_events(interval, integer) from public, anon, authenticated;
revoke all on function coach_internal.claim_recoverable_revenuecat_events(interval, interval, integer, integer) from public, anon, authenticated;
revoke all on function coach_internal.reconcile_user_entitlements(uuid, text, text, jsonb) from public, anon, authenticated;
revoke all on function coach_internal.claim_turn(uuid, uuid, uuid, text, integer) from public, anon, authenticated;
revoke all on function coach_internal.complete_turn(uuid, uuid, uuid, uuid) from public, anon, authenticated;
revoke all on function coach_internal.fail_turn(uuid, uuid, text) from public, anon, authenticated;
revoke all on function coach_internal.persist_turn(uuid, uuid, text, text, integer, integer, integer, text, boolean, text, text, jsonb) from public, anon, authenticated;
revoke all on function coach_internal.write_user_note(uuid, text, text, text) from public, anon, authenticated;
revoke all on function coach_internal.replace_kb_doc(text, text, text, text, text, text, integer, text, text, jsonb) from public, anon, authenticated;
revoke all on function coach_internal.budget_reserve(uuid, uuid, integer, integer, integer, integer, bigint) from public, anon, authenticated;
revoke all on function coach_internal.budget_reconcile(uuid, integer, integer) from public, anon, authenticated;
revoke all on function coach_internal.budget_release(uuid) from public, anon, authenticated;
revoke all on function coach_internal.budget_exempt(uuid) from public, anon, authenticated;
revoke all on function coach_internal.release_stale_budget_reservations(interval, integer) from public, anon, authenticated;
revoke all on function coach_internal.retention_sweep(timestamptz, integer) from public, anon, authenticated;

grant execute on function coach_internal.claim_revenuecat_event(text, text, jsonb, text[]) to service_role;
grant execute on function coach_internal.mark_revenuecat_event_processed(text, text) to service_role;
grant execute on function coach_internal.mark_revenuecat_event_failed(text, text) to service_role;
grant execute on function coach_internal.claim_stale_revenuecat_events(interval, integer) to service_role;
grant execute on function coach_internal.claim_recoverable_revenuecat_events(interval, interval, integer, integer) to service_role;
grant execute on function coach_internal.reconcile_user_entitlements(uuid, text, text, jsonb) to service_role;
grant execute on function coach_internal.claim_turn(uuid, uuid, uuid, text, integer) to service_role;
grant execute on function coach_internal.complete_turn(uuid, uuid, uuid, uuid) to service_role;
grant execute on function coach_internal.fail_turn(uuid, uuid, text) to service_role;
grant execute on function coach_internal.persist_turn(uuid, uuid, text, text, integer, integer, integer, text, boolean, text, text, jsonb) to service_role;
grant execute on function coach_internal.write_user_note(uuid, text, text, text) to service_role;
grant execute on function coach_internal.replace_kb_doc(text, text, text, text, text, text, integer, text, text, jsonb) to service_role;
grant execute on function coach_internal.budget_reserve(uuid, uuid, integer, integer, integer, integer, bigint) to service_role;
grant execute on function coach_internal.budget_reconcile(uuid, integer, integer) to service_role;
grant execute on function coach_internal.budget_release(uuid) to service_role;
grant execute on function coach_internal.budget_exempt(uuid) to service_role;
grant execute on function coach_internal.release_stale_budget_reservations(interval, integer) to service_role;
grant execute on function coach_internal.retention_sweep(timestamptz, integer) to service_role;

-- ============================================================================
-- FUNCTIONS — public.* (thin PostgREST-reachable wrappers; service_role only)
-- ============================================================================

create or replace function public.coach_rc_claim_event(
    p_event_id text, p_event_type text, p_payload jsonb, p_identity_candidates text[]
) returns text language sql security invoker set search_path = '' as $$
    select coach_internal.claim_revenuecat_event(p_event_id, p_event_type, p_payload, p_identity_candidates);
$$;

create or replace function public.coach_rc_mark_event_processed(
    p_event_id text, p_last_note text default null
) returns void language sql security invoker set search_path = '' as $$
    select coach_internal.mark_revenuecat_event_processed(p_event_id, p_last_note);
$$;

create or replace function public.coach_rc_mark_event_failed(
    p_event_id text, p_error text
) returns void language sql security invoker set search_path = '' as $$
    select coach_internal.mark_revenuecat_event_failed(p_event_id, p_error);
$$;

create or replace function public.coach_rc_claim_stale_events(
    p_older_than interval default interval '5 minutes', p_limit integer default 100
) returns table (event_id text, event_type text, payload jsonb, identity_candidates text[], attempts integer)
language sql security invoker set search_path = '' as $$
    select * from coach_internal.claim_stale_revenuecat_events(p_older_than, p_limit);
$$;

create or replace function public.coach_rc_claim_recoverable_events(
    p_stale_after interval default interval '5 minutes',
    p_retry_after interval default interval '5 minutes',
    p_limit integer default 100, p_max_attempts integer default 10
) returns table (event_id text, event_type text, payload jsonb, identity_candidates text[], attempts integer)
language sql security invoker set search_path = '' as $$
    select * from coach_internal.claim_recoverable_revenuecat_events(p_stale_after, p_retry_after, p_limit, p_max_attempts);
$$;

create or replace function public.coach_reconcile_user_entitlements(
    p_user_id uuid, p_revenuecat_app_user_id text, p_environment text, p_entitlements jsonb
) returns void language sql security invoker set search_path = '' as $$
    select coach_internal.reconcile_user_entitlements(p_user_id, p_revenuecat_app_user_id, p_environment, p_entitlements);
$$;

create or replace function public.coach_claim_turn(
    p_user_id uuid, p_chat_id uuid, p_request_id uuid, p_locale text, p_lease_seconds integer default 90
) returns table (status text, chat_id uuid, chat_created boolean, assistant_message_id uuid)
language sql security invoker set search_path = '' as $$
    select * from coach_internal.claim_turn(p_user_id, p_chat_id, p_request_id, p_locale, p_lease_seconds);
$$;

create or replace function public.coach_complete_turn(
    p_user_id uuid, p_request_id uuid, p_user_message_id uuid, p_assistant_message_id uuid
) returns void language sql security invoker set search_path = '' as $$
    select coach_internal.complete_turn(p_user_id, p_request_id, p_user_message_id, p_assistant_message_id);
$$;

create or replace function public.coach_fail_turn(
    p_user_id uuid, p_request_id uuid, p_error text
) returns void language sql security invoker set search_path = '' as $$
    select coach_internal.fail_turn(p_user_id, p_request_id, p_error);
$$;

create or replace function public.coach_persist_turn(
    p_user_id uuid, p_request_id uuid, p_user_content text, p_assistant_content text,
    p_input_tokens integer default null, p_output_tokens integer default null,
    p_cached_tokens integer default null, p_model_used text default null,
    p_escalated boolean default false, p_safety_action text default null,
    p_finish_reason text default null, p_citations jsonb default null
) returns table (user_message_id uuid, assistant_message_id uuid)
language sql security invoker set search_path = '' as $$
    select * from coach_internal.persist_turn(
        p_user_id, p_request_id, p_user_content, p_assistant_content,
        p_input_tokens, p_output_tokens, p_cached_tokens, p_model_used,
        p_escalated, p_safety_action, p_finish_reason, p_citations
    );
$$;

create or replace function public.coach_write_user_note(
    p_user_id uuid, p_category text, p_note text, p_source text
) returns uuid language sql security invoker set search_path = '' as $$
    select coach_internal.write_user_note(p_user_id, p_category, p_note, p_source);
$$;

create or replace function public.coach_replace_kb_doc(
    p_id text, p_title text, p_section text, p_content_md text, p_content_hash text,
    p_source_uri text, p_version integer, p_embedding_model text,
    p_embedding_format_version text, p_chunks jsonb
) returns integer language sql security invoker set search_path = '' as $$
    select coach_internal.replace_kb_doc(
        p_id, p_title, p_section, p_content_md, p_content_hash, p_source_uri,
        p_version, p_embedding_model, p_embedding_format_version, p_chunks
    );
$$;

create or replace function public.coach_budget_reserve(
    p_user_id uuid, p_request_id uuid, p_period integer,
    p_input_max integer, p_output_max integer, p_cap_messages integer, p_cap_tokens bigint
) returns table (allowed boolean, reason text, reservation_id uuid, status text)
language sql security invoker set search_path = '' as $$
    select * from coach_internal.budget_reserve(
        p_user_id, p_request_id, p_period, p_input_max, p_output_max, p_cap_messages, p_cap_tokens
    );
$$;

create or replace function public.coach_budget_reconcile(
    p_reservation_id uuid, p_actual_input integer, p_actual_output integer
) returns void language sql security invoker set search_path = '' as $$
    select coach_internal.budget_reconcile(p_reservation_id, p_actual_input, p_actual_output);
$$;

create or replace function public.coach_budget_release(p_reservation_id uuid)
returns void language sql security invoker set search_path = '' as $$
    select coach_internal.budget_release(p_reservation_id);
$$;

create or replace function public.coach_budget_exempt(p_reservation_id uuid)
returns void language sql security invoker set search_path = '' as $$
    select coach_internal.budget_exempt(p_reservation_id);
$$;

create or replace function public.coach_release_stale_budget_reservations(p_limit integer default 100)
returns integer language sql security invoker set search_path = '' as $$
    select coach_internal.release_stale_budget_reservations(interval '10 minutes', p_limit);
$$;

create or replace function public.coach_retention_sweep(
    p_before timestamptz, p_limit integer default 1000
) returns integer language sql security invoker set search_path = '' as $$
    select coach_internal.retention_sweep(p_before, p_limit);
$$;

-- coach_search_kb: internal helper (NOT called by the shipping code; the code uses
-- search_coach_kb_hybrid below). Uses `extensions.similarity` for the DEV layout where
-- pg_trgm lives in `extensions`. On prod, pg_trgm lives in `public` → `public.similarity`.
create or replace function public.coach_search_kb(
    p_embedding extensions.vector(768),
    p_query text,
    p_sections text[] default null,
    p_embedding_model text default 'gemini-embedding-2',
    p_embedding_format_version text default 'v1',
    p_limit integer default 6
) returns table (
    chunk_id uuid,
    doc_id text,
    title text,
    section text,
    chunk_text text,
    metadata jsonb,
    vector_score double precision,
    lexical_score double precision,
    rrf_score double precision
)
language sql
security invoker
set search_path = ''
as $$
    with vector_ranked as materialized (
        select chunk.id,
               row_number() over (order by chunk.embedding OPERATOR(extensions.<=>) p_embedding) as rank,
               1.0 - (chunk.embedding OPERATOR(extensions.<=>) p_embedding) as score
          from public.coach_kb_chunk chunk
         where chunk.embedding_model = p_embedding_model
           and chunk.embedding_format_version = p_embedding_format_version
           and (p_sections is null or chunk.section = any(p_sections))
         order by chunk.embedding OPERATOR(extensions.<=>) p_embedding
         limit 12
    ), lexical_ranked as materialized (
        select chunk.id,
               row_number() over (order by extensions.similarity(chunk.text, p_query) desc) as rank,
               extensions.similarity(chunk.text, p_query) as score
          from public.coach_kb_chunk chunk
         where chunk.embedding_model = p_embedding_model
           and chunk.embedding_format_version = p_embedding_format_version
           and (p_sections is null or chunk.section = any(p_sections))
           and extensions.similarity(chunk.text, p_query) > 0.05
         order by extensions.similarity(chunk.text, p_query) desc
         limit 12
    ), fused as (
        select candidate.id,
               max(case when candidate.source = 'vector' then candidate.score end) as vector_score,
               max(case when candidate.source = 'lexical' then candidate.score end) as lexical_score,
               sum(1.0 / (60.0 + candidate.rank)) as rrf_score
          from (
              select id, rank, score, 'vector'::text as source from vector_ranked
              union all
              select id, rank, score, 'lexical'::text as source from lexical_ranked
          ) candidate
         group by candidate.id
    )
    select chunk.id,
           chunk.doc_id,
           doc.title,
           chunk.section,
           chunk.text,
           chunk.metadata,
           fused.vector_score,
           fused.lexical_score,
           fused.rrf_score * case
               when chunk.section = 'recipes' and p_query ~* '(recipe|meal|breakfast|lunch|dinner|snack)' then 1.25
               when chunk.section in ('nutrition', 'app') and p_query ~* '(cut|bulk|calorie|macro|protein|deficit|surplus)' then 1.10
               else 1.0
           end as rrf_score
      from fused
      join public.coach_kb_chunk chunk on chunk.id = fused.id
      join public.coach_kb_doc doc on doc.id = chunk.doc_id
     order by rrf_score desc, chunk.id
     limit greatest(1, least(p_limit, 12));
$$;

-- search_coach_kb_hybrid: the retriever the coach code calls. Portable across the
-- pg_trgm-schema difference via `SET search_path = extensions, public, pg_temp`.
create or replace function public.search_coach_kb_hybrid(
    p_query_embedding float4[],
    p_query_text      text,
    p_sections        text[]  default null,
    p_vector_k        int     default 12,
    p_trgm_k          int     default 12,
    p_top_n           int     default 6
) returns table (
    chunk_id    uuid,
    doc_id      text,
    section     text,
    chunk_index int,
    text        text,
    rrf_score   float8
)
language sql
security definer
set search_path = extensions, public, pg_temp
as $$
    with vector_ranked as (
        select
            c.id,
            c.doc_id,
            c.section,
            c.chunk_index,
            c.text,
            row_number() over (
                order by c.embedding <=> p_query_embedding::vector(768)
            ) as rank
        from public.coach_kb_chunk c
        where p_sections is null or c.section = any(p_sections)
        order by c.embedding <=> p_query_embedding::vector(768)
        limit p_vector_k
    ),
    trgm_ranked as (
        select
            c.id,
            c.doc_id,
            c.section,
            c.chunk_index,
            c.text,
            row_number() over (
                order by word_similarity(p_query_text, c.text) desc
            ) as rank
        from public.coach_kb_chunk c
        where (p_sections is null or c.section = any(p_sections))
          and word_similarity(p_query_text, c.text) > 0.1
        order by word_similarity(p_query_text, c.text) desc
        limit p_trgm_k
    ),
    fused as (
        select
            coalesce(v.id,          t.id)          as id,
            coalesce(v.doc_id,      t.doc_id)      as doc_id,
            coalesce(v.section,     t.section)     as section,
            coalesce(v.chunk_index, t.chunk_index) as chunk_index,
            coalesce(v.text,        t.text)        as text,
            coalesce(1.0 / (60.0 + v.rank), 0.0)
                + coalesce(1.0 / (60.0 + t.rank), 0.0) as rrf_score
        from vector_ranked v
        full outer join trgm_ranked t on v.id = t.id
    )
    select
        id          as chunk_id,
        doc_id,
        section,
        chunk_index,
        text,
        rrf_score
    from fused
    order by rrf_score desc
    limit p_top_n;
$$;

-- Grants for public.* wrappers (service_role only)
revoke all on function public.coach_rc_claim_event(text, text, jsonb, text[]) from public, anon, authenticated;
revoke all on function public.coach_rc_mark_event_processed(text, text) from public, anon, authenticated;
revoke all on function public.coach_rc_mark_event_failed(text, text) from public, anon, authenticated;
revoke all on function public.coach_rc_claim_stale_events(interval, integer) from public, anon, authenticated;
revoke all on function public.coach_rc_claim_recoverable_events(interval, interval, integer, integer) from public, anon, authenticated;
revoke all on function public.coach_reconcile_user_entitlements(uuid, text, text, jsonb) from public, anon, authenticated;
revoke all on function public.coach_claim_turn(uuid, uuid, uuid, text, integer) from public, anon, authenticated;
revoke all on function public.coach_complete_turn(uuid, uuid, uuid, uuid) from public, anon, authenticated;
revoke all on function public.coach_fail_turn(uuid, uuid, text) from public, anon, authenticated;
revoke all on function public.coach_persist_turn(uuid, uuid, text, text, integer, integer, integer, text, boolean, text, text, jsonb) from public, anon, authenticated;
revoke all on function public.coach_write_user_note(uuid, text, text, text) from public, anon, authenticated;
revoke all on function public.coach_replace_kb_doc(text, text, text, text, text, text, integer, text, text, jsonb) from public, anon, authenticated;
revoke all on function public.coach_budget_reserve(uuid, uuid, integer, integer, integer, integer, bigint) from public, anon, authenticated;
revoke all on function public.coach_budget_reconcile(uuid, integer, integer) from public, anon, authenticated;
revoke all on function public.coach_budget_release(uuid) from public, anon, authenticated;
revoke all on function public.coach_budget_exempt(uuid) from public, anon, authenticated;
revoke all on function public.coach_release_stale_budget_reservations(integer) from public, anon, authenticated;
revoke all on function public.coach_retention_sweep(timestamptz, integer) from public, anon, authenticated;
revoke all on function public.coach_search_kb(extensions.vector, text, text[], text, text, integer) from public, anon, authenticated;
revoke execute on function public.search_coach_kb_hybrid(float4[], text, text[], int, int, int) from public, anon, authenticated;

grant execute on function public.coach_rc_claim_event(text, text, jsonb, text[]) to service_role;
grant execute on function public.coach_rc_mark_event_processed(text, text) to service_role;
grant execute on function public.coach_rc_mark_event_failed(text, text) to service_role;
grant execute on function public.coach_rc_claim_stale_events(interval, integer) to service_role;
grant execute on function public.coach_rc_claim_recoverable_events(interval, interval, integer, integer) to service_role;
grant execute on function public.coach_reconcile_user_entitlements(uuid, text, text, jsonb) to service_role;
grant execute on function public.coach_claim_turn(uuid, uuid, uuid, text, integer) to service_role;
grant execute on function public.coach_complete_turn(uuid, uuid, uuid, uuid) to service_role;
grant execute on function public.coach_fail_turn(uuid, uuid, text) to service_role;
grant execute on function public.coach_persist_turn(uuid, uuid, text, text, integer, integer, integer, text, boolean, text, text, jsonb) to service_role;
grant execute on function public.coach_write_user_note(uuid, text, text, text) to service_role;
grant execute on function public.coach_replace_kb_doc(text, text, text, text, text, text, integer, text, text, jsonb) to service_role;
grant execute on function public.coach_budget_reserve(uuid, uuid, integer, integer, integer, integer, bigint) to service_role;
grant execute on function public.coach_budget_reconcile(uuid, integer, integer) to service_role;
grant execute on function public.coach_budget_release(uuid) to service_role;
grant execute on function public.coach_budget_exempt(uuid) to service_role;
grant execute on function public.coach_release_stale_budget_reservations(integer) to service_role;
grant execute on function public.coach_retention_sweep(timestamptz, integer) to service_role;
grant execute on function public.coach_search_kb(extensions.vector, text, text[], text, text, integer) to service_role;
grant execute on function public.search_coach_kb_hybrid(float4[], text, text[], int, int, int) to service_role;

-- ============================================================================
-- SHARED: public.delete_user_data (account hard-delete; includes coach cascade)
-- ============================================================================
-- Deployed final form: deletes all application + AI Coach rows for one auth user
-- (including coach_internal.coach_turn + coach_internal.coach_budget_reservation).
create or replace function public.delete_user_data(p_user_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    delete from public.food_item_serving         where user_id = p_user_id;
    delete from public.diary_entry_ingredient    where user_id = p_user_id;
    delete from public.my_meal_ingredient        where user_id = p_user_id;
    delete from public.recent_food               where user_id = p_user_id;

    delete from public.diary_entry               where user_id = p_user_id;
    delete from public.food_item                 where user_id = p_user_id;
    delete from public.my_meal                   where user_id = p_user_id;

    delete from public.ai_progress_ladder_rung   where user_id = p_user_id;
    delete from public.ai_progress_ladder        where user_id = p_user_id;

    delete from public.coach_trace               where user_id = p_user_id;
    delete from public.coach_summary             where user_id = p_user_id;
    delete from coach_internal.coach_turn        where user_id = p_user_id;
    delete from public.coach_message             where user_id = p_user_id;
    delete from public.coach_chat                where user_id = p_user_id;
    delete from public.coach_user_note           where user_id = p_user_id;
    delete from coach_internal.coach_budget_reservation where user_id = p_user_id;
    delete from public.coach_budget              where user_id = p_user_id;
    delete from public.user_entitlement           where user_id = p_user_id;

    delete from public.progress_photo            where user_id = p_user_id;
    delete from public.weight_entry              where user_id = p_user_id;
    delete from public.calorie_target_history    where user_id = p_user_id;
    delete from public.calorie_target            where user_id = p_user_id;
    delete from public.user_goal                 where user_id = p_user_id;
    delete from public.user_profile              where user_id = p_user_id;
end;
$$;

comment on function public.delete_user_data(uuid) is
    'Transactional hard-delete of all application and AI Coach rows for one auth user.';

revoke all on function public.delete_user_data(uuid) from public, anon, authenticated;
grant execute on function public.delete_user_data(uuid) to service_role;

-- ============================================================================
-- REALTIME PUBLICATION
-- ============================================================================
alter publication supabase_realtime add table public.coach_chat;
alter publication supabase_realtime add table public.coach_message;
