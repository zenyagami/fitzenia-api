-- 011_budget_cleanup.sql
-- Post-migration cleanup for cost-weighted budgeting (docs/AI_COACH.md).
--
-- ⚠️ ORDERING: apply this ONLY AFTER the new backend (credit-weighted RPC signatures) is
-- live in every environment it runs in. It DROPS the legacy budget RPC overloads that the
-- pre-M1 backend depended on — running it while old code is still serving traffic would
-- break that code's reserve/reconcile calls. Safe once no process calls the old signatures.
--
-- Drops:
--   - legacy public.coach_budget_reserve / coach_internal.budget_reserve   (7-arg, p_cap_tokens)
--   - legacy public.coach_budget_reconcile / coach_internal.budget_reconcile (3-arg)
--   - coach_budget.cents_estimated (never populated; the credit counters superseded it)
-- The new 8-arg reserve / 6-arg reconcile overloads are unaffected (distinct signatures).
--
-- Before dropping the 3-arg reconcile, the stale-reservation sweeper is re-pointed: its
-- legacy branch (for pre-migration reservations with reserved_credits IS NULL) now calls the
-- new 6-arg reconcile with tokens-as-lite (pro/cached = 0). The 6-arg reconcile's own
-- reserved_credits-IS-NULL branch does the identical raw-token delta math, so behavior is
-- unchanged and the sweeper no longer depends on the dropped 3-arg function.
--
-- Applied to: dev (tpslgveyjldykkkhnifs) and prod (anqvtpesmddllplyhkrc), 2026-07-04.

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
                -- Legacy reservation → 6-arg reconcile, tokens as lite (its null branch = old math).
                perform coach_internal.budget_reconcile(
                    v_row.id, v_row.input_tokens, v_row.output_tokens, 0, 0, 0);
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

drop function if exists public.coach_budget_reserve(uuid, uuid, integer, integer, integer, integer, bigint);
drop function if exists coach_internal.budget_reserve(uuid, uuid, integer, integer, integer, integer, bigint);

drop function if exists public.coach_budget_reconcile(uuid, integer, integer);
drop function if exists coach_internal.budget_reconcile(uuid, integer, integer);

alter table public.coach_budget drop column if exists cents_estimated;
