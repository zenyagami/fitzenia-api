-- Make weight_entry's primary key user-scoped: (id) -> (user_id, id).
--
-- Health Connect weights were keyed with a deterministic, NON-user-scoped id
-- ("hc_<date>", identical across users). Under the old global PRIMARY KEY (id) the
-- second user to sync a given date collided on another user's row, which RLS hides,
-- so PostgREST rejected the upsert with
--   new row violates row-level security policy (USING expression) for table "weight_entry"
-- and that user's weight silently stopped syncing.
--
-- Fix: primary key (user_id, id). "hc_<date>" is then unique only within a user, so
-- each user's upsert conflicts only against their own (RLS-visible) row. The client
-- upserts weight_entry with no explicit on_conflict, so PostgREST uses this PK.
--
-- Idempotent: no-op if the composite PK is already in place. This change was first
-- applied to prod + dev out-of-band on 2026-07-25; this migration records it so the
-- schema is reproducible from migrations (cf. 20260504000001_fix_ai_progress_ladder_missing_indexes).
--
-- Preconditions (asserted below; all held on prod when first applied): user_id NOT
-- NULL, (user_id, id) already unique, no inbound FKs.

do $$
begin
    if (
        select pg_get_constraintdef(oid)
        from pg_constraint
        where conrelid = 'public.weight_entry'::regclass and contype = 'p'
    ) is distinct from 'PRIMARY KEY (user_id, id)' then

        if exists (select 1 from public.weight_entry where user_id is null) then
            raise exception 'weight_entry has NULL user_id rows; composite PK impossible';
        end if;
        if exists (select 1 from public.weight_entry group by user_id, id having count(*) > 1) then
            raise exception 'duplicate (user_id, id) pairs exist; resolve before re-keying PK';
        end if;
        if exists (
            select 1 from pg_constraint
            where confrelid = 'public.weight_entry'::regclass and contype = 'f'
        ) then
            raise exception 'an inbound FK references weight_entry; review before dropping PK';
        end if;

        alter table public.weight_entry drop constraint weight_entry_pkey;
        alter table public.weight_entry add  constraint weight_entry_pkey primary key (user_id, id);
    end if;
end $$;
