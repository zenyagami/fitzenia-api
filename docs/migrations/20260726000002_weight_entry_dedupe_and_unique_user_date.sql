-- Enforce one weight row per (user, date): dedupe existing duplicates, then add
-- UNIQUE (user_id, date). Complements 20260726000001 (which fixed the CROSS-user
-- collision) by fixing the same-user duplicate/flap: rows that share (user_id, date)
-- under different ids otherwise fight each other on every pull.
--
-- ⚠️ ORDERING: apply this ONLY AFTER the client release that (a) mints random-UUID
-- ids for new Health Connect weights and (b) isolates each table's push in its own
-- try/catch is live in every environment. An older client upserts on the PK and
-- lacks that isolation, so a post-dedupe id mismatch would raise 23505 on this new
-- unique index and abort that client's entire sync run. Safe once that client is
-- adopted. (A later client release should also set on_conflict=user_id,date on the
-- weight upserts so same-date re-logs UPDATE cleanly instead of erroring.)
--
-- Idempotent: dedupe is safe to re-run; the constraint is added only if absent.

-- 1) Dedupe: keep one row per (user_id, date) -- live over tombstoned, then most
--    recently written, then richer (has body fat), then stable id. Delete the rest
--    by ctid (id is NOT globally unique under the composite PK, so deleting by id
--    alone could remove another user's same-id row).
with ranked as (
    select ctid as row_ctid,
           row_number() over (
               partition by user_id, date
               order by is_deleted asc,
                        created_at desc,
                        (body_fat_percent is not null) desc,
                        id asc
           ) as rn
    from public.weight_entry
)
delete from public.weight_entry
where ctid in (select row_ctid from ranked where rn > 1);

-- 2) One row per (user, date) going forward.
do $$
begin
    if not exists (
        select 1 from pg_constraint
        where conrelid = 'public.weight_entry'::regclass
          and contype = 'u'
          and conname = 'weight_entry_user_date_key'
    ) then
        alter table public.weight_entry
            add constraint weight_entry_user_date_key unique (user_id, date);
    end if;
end $$;
