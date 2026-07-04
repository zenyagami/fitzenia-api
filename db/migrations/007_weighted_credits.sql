-- 007_weighted_credits.sql
-- Cost-weighted credit budgeting for the AI Coach (docs/AI_COACH.md).
--
-- 1 credit = 1 Lite input token ($0.25/1M). Weights are exact price ratios:
--   Lite input x1 | Lite output x6 | Pro input x6 | Pro output x36 | cached Lite input x0.25
-- The authoritative weighting is coach_internal.budget_credits(); Kotlin only mirrors the
-- weights for the reserve-time estimate (corrected at reconcile).
--
-- Zero-downtime strategy: budget_reserve/budget_reconcile get NEW overloads (8-arg / 6-arg);
-- the old signatures stay callable until the 011 cleanup so a running old backend keeps
-- working during deploy. release/exempt/sweeper keep their signatures but branch on
-- reservation.reserved_credits IS NULL (legacy) vs NOT NULL (credit-weighted).
--
-- Concurrency: every budget/top-up mutation happens while holding the user's
-- coach_budget row lock (SELECT ... FOR UPDATE), serializing per (user, period).
--
-- Applied to: dev (tpslgveyjldykkkhnifs) and prod (anqvtpesmddllplyhkrc).

-- ── coach_budget: weighted-credit counters ──────────────────────────────────
alter table public.coach_budget
    add column if not exists credits_used bigint not null default 0 check (credits_used >= 0),
    add column if not exists pro_credits_used bigint not null default 0 check (pro_credits_used >= 0);

comment on column public.coach_budget.credits_used is
    'Cost-weighted credits consumed from the MONTHLY pot this period (1 credit = 1 Lite input token). Top-up draws are tracked on coach_credit_topup, not here.';
comment on column public.coach_budget.pro_credits_used is
    'Monotonic display counter: credits attributable to the Pro model this period (never refunded). For the usage endpoint''s "Pro" bar.';

-- ── coach_budget_reservation: credit bookkeeping ────────────────────────────
alter table coach_internal.coach_budget_reservation
    add column if not exists reserved_credits bigint check (reserved_credits >= 0),
    add column if not exists actual_credits bigint check (actual_credits >= 0),
    add column if not exists monthly_credits bigint check (monthly_credits >= 0),
    add column if not exists topup_draws jsonb not null default '[]'::jsonb,
    add column if not exists cap_credits bigint check (cap_credits >= 1);

comment on column coach_internal.coach_budget_reservation.reserved_credits is
    'Credit estimate drawn at reserve. NULL = legacy reservation (raw-token semantics).';
comment on column coach_internal.coach_budget_reservation.monthly_credits is
    'Share of the current draw taken from the monthly pot (rest came from top-ups).';
comment on column coach_internal.coach_budget_reservation.topup_draws is
    'Exact top-up draws [{"topup_id": uuid, "credits": n}] so release/reconcile can refund precisely.';
comment on column coach_internal.coach_budget_reservation.cap_credits is
    'Monthly credit cap in force at reserve time (needed by reconcile''s re-draw).';

-- ── coach_credit_topup: purchased credit packs (filled by M6, drawable now) ─
create table if not exists public.coach_credit_topup (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    rc_transaction_id text not null unique,
    product_id text not null,
    store text,
    environment text,
    credits_granted bigint not null check (credits_granted > 0),
    credits_remaining bigint not null check (credits_remaining >= 0 and credits_remaining <= credits_granted),
    created_at timestamptz not null default now(),
    expires_at timestamptz
);

comment on table public.coach_credit_topup is
    'RevenueCat consumable credit packs. Drawn only after the monthly pot is exhausted, oldest first; 12-month expiry enforced solely by the draw filter.';

create index if not exists coach_credit_topup_draw_idx
    on public.coach_credit_topup (user_id, created_at)
    where credits_remaining > 0;

alter table public.coach_credit_topup enable row level security;
revoke all on table public.coach_credit_topup from public, anon, authenticated;
grant select on table public.coach_credit_topup to authenticated;
grant select, insert, update, delete on table public.coach_credit_topup to service_role;
drop policy if exists coach_credit_topup_select_own on public.coach_credit_topup;
create policy coach_credit_topup_select_own on public.coach_credit_topup
    for select to authenticated using (user_id = (select auth.uid()));

-- ── Authoritative credit weighting ──────────────────────────────────────────
-- p_lite_input EXCLUDES cached tokens; pass the cached share in p_cached_input
-- (quarter weight, integer floor). Callers without cached counts pass 0.
create or replace function coach_internal.budget_credits(
    p_lite_input bigint,
    p_lite_output bigint,
    p_pro_input bigint,
    p_pro_output bigint,
    p_cached_input bigint default 0
) returns bigint
language sql immutable set search_path = '' as $$
    select greatest(0, p_lite_input)
         + 6  * greatest(0, p_lite_output)
         + 6  * greatest(0, p_pro_input)
         + 36 * greatest(0, p_pro_output)
         + greatest(0, p_cached_input) / 4;
$$;

-- ── budget_reserve: NEW credit-weighted overload (old 7-arg version untouched)
create or replace function coach_internal.budget_reserve(
    p_user_id uuid,
    p_request_id uuid,
    p_period integer,
    p_input_max integer,
    p_output_max integer,
    p_credits_max bigint,
    p_cap_messages integer,
    p_cap_credits bigint
) returns table (allowed boolean, reason text, reservation_id uuid, status text)
language plpgsql security definer set search_path = '' as $$
declare
    v_existing coach_internal.coach_budget_reservation%rowtype;
    v_budget public.coach_budget%rowtype;
    v_topup_avail bigint;
    v_from_monthly bigint;
    v_remaining bigint;
    v_take bigint;
    v_draws jsonb := '[]'::jsonb;
    v_t record;
    v_id uuid;
begin
    if p_input_max < 0 or p_output_max < 0 or p_credits_max < 0
       or p_cap_messages < 1 or p_cap_credits < 1 then
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

    -- Row lock serializes all budget + top-up mutations for this user/period.
    select budget.* into v_budget
      from public.coach_budget budget
     where budget.user_id = p_user_id and budget.period_yyyymm = p_period
     for update;

    if v_budget.messages_used + 1 > p_cap_messages then
        return query select false, 'cap_messages'::text, null::uuid, 'rejected'::text;
        return;
    end if;

    v_from_monthly := least(p_credits_max, greatest(0, p_cap_credits - v_budget.credits_used));
    v_remaining := p_credits_max - v_from_monthly;

    if v_remaining > 0 then
        -- Pre-check availability so a rejection mutates nothing.
        select coalesce(sum(topup.credits_remaining), 0) into v_topup_avail
          from public.coach_credit_topup topup
         where topup.user_id = p_user_id
           and topup.credits_remaining > 0
           and (topup.expires_at is null or topup.expires_at > pg_catalog.now());
        if v_topup_avail < v_remaining then
            return query select false, 'cap_credits'::text, null::uuid, 'rejected'::text;
            return;
        end if;
        for v_t in
            select topup.id, topup.credits_remaining
              from public.coach_credit_topup topup
             where topup.user_id = p_user_id
               and topup.credits_remaining > 0
               and (topup.expires_at is null or topup.expires_at > pg_catalog.now())
             order by topup.created_at
             for update
        loop
            v_take := least(v_t.credits_remaining, v_remaining);
            update public.coach_credit_topup
               set credits_remaining = credits_remaining - v_take
             where id = v_t.id;
            v_draws := v_draws || pg_catalog.jsonb_build_object('topup_id', v_t.id, 'credits', v_take);
            v_remaining := v_remaining - v_take;
            exit when v_remaining <= 0;
        end loop;
    end if;

    update public.coach_budget
       set messages_used = messages_used + 1,
           credits_used = credits_used + v_from_monthly,
           last_message_at = pg_catalog.now()
     where user_id = p_user_id and period_yyyymm = p_period;

    insert into coach_internal.coach_budget_reservation (
        user_id, period_yyyymm, request_id, reserved_input, reserved_output,
        reserved_credits, monthly_credits, topup_draws, cap_credits, state
    ) values (
        p_user_id, p_period, p_request_id, p_input_max, p_output_max,
        p_credits_max, v_from_monthly, v_draws, p_cap_credits, 'reserved'
    ) returning id into v_id;

    return query select true, null::text, v_id, 'reserved'::text;
end;
$$;

-- ── budget_reconcile: NEW per-segment overload (old 3-arg version untouched) ─
-- Refund-then-redraw: reverses the reserve draws entirely, then draws the actual
-- weighted cost with the same monthly-first policy. NEVER rejects — if top-ups
-- expired in between, the monthly pot absorbs the remainder even past the cap.
create or replace function coach_internal.budget_reconcile(
    p_reservation_id uuid,
    p_lite_input integer,
    p_lite_output integer,
    p_pro_input integer,
    p_pro_output integer,
    p_cached_input integer
) returns void language plpgsql security definer set search_path = '' as $$
declare
    v_row coach_internal.coach_budget_reservation%rowtype;
    v_budget public.coach_budget%rowtype;
    v_actual bigint;
    v_pro_credits bigint;
    v_credits_now bigint;
    v_from_monthly bigint;
    v_remaining bigint;
    v_take bigint;
    v_draws jsonb := '[]'::jsonb;
    v_d record;
    v_t record;
begin
    if p_lite_input < 0 or p_lite_output < 0 or p_pro_input < 0
       or p_pro_output < 0 or p_cached_input < 0 then
        raise exception 'negative actuals';
    end if;
    select * into v_row from coach_internal.coach_budget_reservation
     where id = p_reservation_id for update;
    if not found or v_row.state <> 'reserved' then return; end if;

    if v_row.reserved_credits is null then
        -- Legacy reservation: old raw-token delta semantics.
        update public.coach_budget
           set input_tokens_used = greatest(0::bigint, input_tokens_used
                   + (p_lite_input + p_pro_input + p_cached_input) - v_row.reserved_input),
               output_tokens_used = greatest(0::bigint, output_tokens_used
                   + (p_lite_output + p_pro_output) - v_row.reserved_output),
               last_message_at = pg_catalog.now()
         where user_id = v_row.user_id and period_yyyymm = v_row.period_yyyymm;
        update coach_internal.coach_budget_reservation
           set actual_input = p_lite_input + p_pro_input + p_cached_input,
               actual_output = p_lite_output + p_pro_output,
               state = 'reconciled', settled_at = pg_catalog.now()
         where id = p_reservation_id;
        return;
    end if;

    v_actual := coach_internal.budget_credits(
        p_lite_input::bigint, p_lite_output::bigint,
        p_pro_input::bigint, p_pro_output::bigint, p_cached_input::bigint);
    v_pro_credits := coach_internal.budget_credits(
        0, 0, p_pro_input::bigint, p_pro_output::bigint, 0);

    select budget.* into v_budget
      from public.coach_budget budget
     where budget.user_id = v_row.user_id and budget.period_yyyymm = v_row.period_yyyymm
     for update;

    -- Refund the reserve draws (monthly + each top-up slice).
    v_credits_now := greatest(0, v_budget.credits_used - coalesce(v_row.monthly_credits, 0));
    for v_d in
        select * from pg_catalog.jsonb_to_recordset(v_row.topup_draws)
            as draw(topup_id uuid, credits bigint)
    loop
        update public.coach_credit_topup
           set credits_remaining = least(credits_granted, credits_remaining + v_d.credits)
         where id = v_d.topup_id;
    end loop;

    -- Re-draw the actual cost: monthly pot first, then top-ups oldest-first.
    v_from_monthly := least(v_actual, greatest(0, v_row.cap_credits - v_credits_now));
    v_remaining := v_actual - v_from_monthly;
    if v_remaining > 0 then
        for v_t in
            select topup.id, topup.credits_remaining
              from public.coach_credit_topup topup
             where topup.user_id = v_row.user_id
               and topup.credits_remaining > 0
               and (topup.expires_at is null or topup.expires_at > pg_catalog.now())
             order by topup.created_at
             for update
        loop
            v_take := least(v_t.credits_remaining, v_remaining);
            update public.coach_credit_topup
               set credits_remaining = credits_remaining - v_take
             where id = v_t.id;
            v_draws := v_draws || pg_catalog.jsonb_build_object('topup_id', v_t.id, 'credits', v_take);
            v_remaining := v_remaining - v_take;
            exit when v_remaining <= 0;
        end loop;
        if v_remaining > 0 then
            -- Reconcile never fails: the monthly pot absorbs the shortfall, past the cap if needed.
            v_from_monthly := v_from_monthly + v_remaining;
            v_remaining := 0;
        end if;
    end if;

    update public.coach_budget
       set credits_used = v_credits_now + v_from_monthly,
           pro_credits_used = pro_credits_used + v_pro_credits,
           input_tokens_used = input_tokens_used + p_lite_input + p_pro_input + p_cached_input,
           output_tokens_used = output_tokens_used + p_lite_output + p_pro_output,
           last_message_at = pg_catalog.now()
     where user_id = v_row.user_id and period_yyyymm = v_row.period_yyyymm;

    update coach_internal.coach_budget_reservation
       set actual_input = p_lite_input + p_pro_input + p_cached_input,
           actual_output = p_lite_output + p_pro_output,
           actual_credits = v_actual,
           monthly_credits = v_from_monthly,
           topup_draws = v_draws,
           state = 'reconciled', settled_at = pg_catalog.now()
     where id = p_reservation_id;
end;
$$;

-- ── budget_release / budget_exempt: same signatures, legacy-vs-credit branch ─
create or replace function coach_internal.budget_release(p_reservation_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare
    v_row coach_internal.coach_budget_reservation%rowtype;
    v_d record;
begin
    select * into v_row from coach_internal.coach_budget_reservation
     where id = p_reservation_id for update;
    if not found or v_row.state <> 'reserved' then return; end if;
    if v_row.reserved_credits is null then
        update public.coach_budget
           set messages_used = greatest(0, messages_used - 1),
               input_tokens_used = greatest(0::bigint, input_tokens_used - v_row.reserved_input),
               output_tokens_used = greatest(0::bigint, output_tokens_used - v_row.reserved_output)
         where user_id = v_row.user_id and period_yyyymm = v_row.period_yyyymm;
    else
        -- Exact refund of the recorded draw split.
        update public.coach_budget
           set messages_used = greatest(0, messages_used - 1),
               credits_used = greatest(0::bigint, credits_used - coalesce(v_row.monthly_credits, 0))
         where user_id = v_row.user_id and period_yyyymm = v_row.period_yyyymm;
        for v_d in
            select * from pg_catalog.jsonb_to_recordset(v_row.topup_draws)
                as draw(topup_id uuid, credits bigint)
        loop
            update public.coach_credit_topup
               set credits_remaining = least(credits_granted, credits_remaining + v_d.credits)
             where id = v_d.topup_id;
        end loop;
    end if;
    update coach_internal.coach_budget_reservation
       set state = 'released', settled_at = pg_catalog.now()
     where id = p_reservation_id;
end;
$$;

create or replace function coach_internal.budget_exempt(p_reservation_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare
    v_row coach_internal.coach_budget_reservation%rowtype;
    v_d record;
begin
    select * into v_row from coach_internal.coach_budget_reservation
     where id = p_reservation_id for update;
    if not found or v_row.state <> 'reserved' then return; end if;
    if v_row.reserved_credits is null then
        update public.coach_budget
           set messages_used = greatest(0, messages_used - 1),
               input_tokens_used = greatest(0::bigint, input_tokens_used - v_row.reserved_input),
               output_tokens_used = greatest(0::bigint, output_tokens_used - v_row.reserved_output)
         where user_id = v_row.user_id and period_yyyymm = v_row.period_yyyymm;
    else
        update public.coach_budget
           set messages_used = greatest(0, messages_used - 1),
               credits_used = greatest(0::bigint, credits_used - coalesce(v_row.monthly_credits, 0))
         where user_id = v_row.user_id and period_yyyymm = v_row.period_yyyymm;
        for v_d in
            select * from pg_catalog.jsonb_to_recordset(v_row.topup_draws)
                as draw(topup_id uuid, credits bigint)
        loop
            update public.coach_credit_topup
               set credits_remaining = least(credits_granted, credits_remaining + v_d.credits)
             where id = v_d.topup_id;
        end loop;
    end if;
    update coach_internal.coach_budget_reservation
       set state = 'exempt', settled_at = pg_catalog.now()
     where id = p_reservation_id;
end;
$$;

-- ── Stale-reservation sweeper: derive segments from coach_message ────────────
-- Legacy escalated rows (null pro_* columns) are charged as all-Pro — conservative
-- and self-limiting (only affects reservations from before this migration).
create or replace function coach_internal.release_stale_budget_reservations(
    p_older_than interval default interval '10 minutes',
    p_limit integer default 100
) returns integer
language plpgsql security definer set search_path = '' as $$
declare
    v_row record;
    v_count integer := 0;
    v_pro_in integer;
    v_pro_out integer;
begin
    for v_row in
        select reservation.id,
               reservation.reserved_credits,
               turn.state as turn_state,
               coalesce(message.input_tokens, 0) as input_tokens,
               coalesce(message.output_tokens, 0) as output_tokens,
               message.pro_input_tokens,
               message.pro_output_tokens,
               coalesce(message.escalated, false) as escalated
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
            if v_row.reserved_credits is null then
                perform coach_internal.budget_reconcile(v_row.id, v_row.input_tokens, v_row.output_tokens);
            else
                v_pro_in := coalesce(v_row.pro_input_tokens,
                    case when v_row.escalated then v_row.input_tokens else 0 end);
                v_pro_out := coalesce(v_row.pro_output_tokens,
                    case when v_row.escalated then v_row.output_tokens else 0 end);
                perform coach_internal.budget_reconcile(
                    v_row.id,
                    greatest(0, v_row.input_tokens - v_pro_in),
                    greatest(0, v_row.output_tokens - v_pro_out),
                    v_pro_in, v_pro_out, 0);
            end if;
        else
            perform coach_internal.budget_release(v_row.id);
        end if;
        v_count := v_count + 1;
    end loop;
    return v_count;
end;
$$;

-- ── Public wrappers (new overloads) ──────────────────────────────────────────
create or replace function public.coach_budget_reserve(
    p_user_id uuid, p_request_id uuid, p_period integer,
    p_input_max integer, p_output_max integer,
    p_credits_max bigint, p_cap_messages integer, p_cap_credits bigint
) returns table (allowed boolean, reason text, reservation_id uuid, status text)
language sql security invoker set search_path = '' as $$
    select * from coach_internal.budget_reserve(
        p_user_id, p_request_id, p_period, p_input_max, p_output_max,
        p_credits_max, p_cap_messages, p_cap_credits
    );
$$;

create or replace function public.coach_budget_reconcile(
    p_reservation_id uuid, p_lite_input integer, p_lite_output integer,
    p_pro_input integer, p_pro_output integer, p_cached_input integer
) returns void language sql security invoker set search_path = '' as $$
    select coach_internal.budget_reconcile(
        p_reservation_id, p_lite_input, p_lite_output, p_pro_input, p_pro_output, p_cached_input
    );
$$;

-- ── Grants (mirror 005: service_role only) ──────────────────────────────────
revoke all on function public.coach_budget_reserve(uuid, uuid, integer, integer, integer, bigint, integer, bigint) from public, anon, authenticated;
grant execute on function public.coach_budget_reserve(uuid, uuid, integer, integer, integer, bigint, integer, bigint) to service_role;
revoke all on function public.coach_budget_reconcile(uuid, integer, integer, integer, integer, integer) from public, anon, authenticated;
grant execute on function public.coach_budget_reconcile(uuid, integer, integer, integer, integer, integer) to service_role;
revoke all on function coach_internal.budget_reserve(uuid, uuid, integer, integer, integer, bigint, integer, bigint) from public, anon, authenticated;
revoke all on function coach_internal.budget_reconcile(uuid, integer, integer, integer, integer, integer) from public, anon, authenticated;
revoke all on function coach_internal.budget_credits(bigint, bigint, bigint, bigint, bigint) from public, anon, authenticated;

-- The security-invoker public wrappers run as service_role, so service_role needs
-- EXECUTE on the internal overloads too (mirrors the 005 grant block).
grant execute on function coach_internal.budget_reserve(uuid, uuid, integer, integer, integer, bigint, integer, bigint) to service_role;
grant execute on function coach_internal.budget_reconcile(uuid, integer, integer, integer, integer, integer) to service_role;
grant execute on function coach_internal.budget_credits(bigint, bigint, bigint, bigint, bigint) to service_role;
