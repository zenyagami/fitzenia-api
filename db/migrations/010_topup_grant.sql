-- 010_topup_grant.sql
-- Top-up credit grants from RevenueCat consumables (docs/AI_COACH.md).
--
-- Pack decided by the owner: product `coach_credits_5m` (~EUR 5) -> 5,000,000 credits.
-- The Kotlin sync service maps product_id -> credits and calls grant_credit_topups with
-- every known consumable purchase from the RC subscriber snapshot. Idempotency rides on
-- the UNIQUE(rc_transaction_id) constraint (ON CONFLICT DO NOTHING), so webhook replays
-- and sweeper re-syncs can never double-grant.
--
-- 12-month expiry: expires_at = purchased_at + 12 months, enforced solely by the draw
-- filter in budget_reserve/budget_reconcile (007) — no sweeper needed.
--
-- Also (re)creates the coach usage RPC budget_usage / public.coach_usage with
-- credits/pro/messages/topup_remaining + topup_granted so the usage endpoint can show
-- "purchased credits used / left". DROP-then-CREATE is used because the return type may
-- change across migration replays; the drop is a defensive no-op on a fresh DB.
--
-- Applied to: dev (tpslgveyjldykkkhnifs) and prod (anqvtpesmddllplyhkrc).

create or replace function coach_internal.grant_credit_topups(
    p_user_id uuid,
    p_environment text,
    p_grants jsonb
) returns integer
language plpgsql security definer set search_path = '' as $$
declare
    v_inserted integer := 0;
begin
    if p_environment not in ('SANDBOX', 'PRODUCTION') then
        raise exception 'invalid RevenueCat environment';
    end if;
    if p_grants is null or pg_catalog.jsonb_typeof(p_grants) <> 'array' then
        raise exception 'grants must be a JSON array';
    end if;
    if exists (
        select 1
          from pg_catalog.jsonb_to_recordset(p_grants) as grant_item(
              rc_transaction_id text,
              product_id text,
              store text,
              credits bigint,
              purchased_at timestamptz
          )
         where grant_item.rc_transaction_id is null
            or pg_catalog.btrim(grant_item.rc_transaction_id) = ''
            or grant_item.product_id is null
            or grant_item.credits is null
            or grant_item.credits <= 0
    ) then
        raise exception 'invalid grant item';
    end if;

    insert into public.coach_credit_topup (
        user_id, rc_transaction_id, product_id, store, environment,
        credits_granted, credits_remaining, created_at, expires_at
    )
    select p_user_id,
           grant_item.rc_transaction_id,
           grant_item.product_id,
           grant_item.store,
           p_environment,
           grant_item.credits,
           grant_item.credits,
           coalesce(grant_item.purchased_at, pg_catalog.now()),
           coalesce(grant_item.purchased_at, pg_catalog.now()) + interval '12 months'
      from pg_catalog.jsonb_to_recordset(p_grants) as grant_item(
          rc_transaction_id text,
          product_id text,
          store text,
          credits bigint,
          purchased_at timestamptz
      )
    on conflict (rc_transaction_id) do nothing;

    get diagnostics v_inserted = row_count;
    return v_inserted;
end;
$$;

create or replace function public.coach_grant_credit_topups(
    p_user_id uuid, p_environment text, p_grants jsonb
) returns integer language sql security invoker set search_path = '' as $$
    select coach_internal.grant_credit_topups(p_user_id, p_environment, p_grants);
$$;

-- budget_usage: add topup_granted (non-expired packs; drained-but-valid packs count so the
-- client can render used = granted - remaining).
drop function if exists public.coach_usage(uuid, integer);
drop function if exists coach_internal.budget_usage(uuid, integer);

create function coach_internal.budget_usage(
    p_user_id uuid,
    p_period integer
) returns table (
    credits_used bigint,
    pro_credits_used bigint,
    messages_used integer,
    topup_remaining bigint,
    topup_granted bigint
)
language sql stable security definer set search_path = '' as $$
    select coalesce(b.credits_used, 0),
           coalesce(b.pro_credits_used, 0),
           coalesce(b.messages_used, 0),
           coalesce((
               select sum(t.credits_remaining)
                 from public.coach_credit_topup t
                where t.user_id = p_user_id
                  and (t.expires_at is null or t.expires_at > pg_catalog.now())
           ), 0),
           coalesce((
               select sum(t.credits_granted)
                 from public.coach_credit_topup t
                where t.user_id = p_user_id
                  and (t.expires_at is null or t.expires_at > pg_catalog.now())
           ), 0)
      from (select 1) as one
      left join public.coach_budget b
        on b.user_id = p_user_id and b.period_yyyymm = p_period;
$$;

create function public.coach_usage(p_user_id uuid, p_period integer)
returns table (
    credits_used bigint,
    pro_credits_used bigint,
    messages_used integer,
    topup_remaining bigint,
    topup_granted bigint
)
language sql security invoker set search_path = '' as $$
    select * from coach_internal.budget_usage(p_user_id, p_period);
$$;

-- Grants (service_role only, incl. the internal functions the invoker wrappers call).
revoke all on function public.coach_grant_credit_topups(uuid, text, jsonb) from public, anon, authenticated;
grant execute on function public.coach_grant_credit_topups(uuid, text, jsonb) to service_role;
revoke all on function coach_internal.grant_credit_topups(uuid, text, jsonb) from public, anon, authenticated;
grant execute on function coach_internal.grant_credit_topups(uuid, text, jsonb) to service_role;
revoke all on function public.coach_usage(uuid, integer) from public, anon, authenticated;
grant execute on function public.coach_usage(uuid, integer) to service_role;
revoke all on function coach_internal.budget_usage(uuid, integer) from public, anon, authenticated;
grant execute on function coach_internal.budget_usage(uuid, integer) to service_role;
