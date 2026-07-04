-- 009_entitlement_trial.sql
-- Trial detection for the coach credit budget (docs/AI_COACH.md).
--
-- RevenueCat's subscriber snapshot reports period_type == "trial" while a subscription
-- is inside its free-trial window. The sync service maps that onto user_entitlement.is_trial
-- so the coach can serve a reduced credit cap (CAP_CREDITS_TRIAL) to trial users.
-- The reconcile function keeps its public wrapper signature (jsonb pass-through) —
-- only the recordset shapes + column lists change.
--
-- Applied to: dev (tpslgveyjldykkkhnifs) and prod (anqvtpesmddllplyhkrc).

alter table public.user_entitlement
    add column if not exists is_trial boolean not null default false;

comment on column public.user_entitlement.is_trial is
    'True while the RevenueCat subscription backing this entitlement is in its free-trial period (period_type == "trial"). Drives the reduced coach credit cap.';

-- Same body as 005 plus is_trial in the recordset shapes, insert, and on-conflict update.
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
              store text,
              is_trial boolean
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
                 store text,
                 is_trial boolean
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
        is_trial,
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
           coalesce(item.is_trial, false),
           pg_catalog.now()
      from pg_catalog.jsonb_to_recordset(p_entitlements) as item(
          entitlement_id text,
          active boolean,
          expires_at timestamptz,
          grace_period_ends_at timestamptz,
          product_id text,
          store text,
          is_trial boolean
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
           is_trial = excluded.is_trial,
           updated_at = excluded.updated_at;
end;
$function$;
