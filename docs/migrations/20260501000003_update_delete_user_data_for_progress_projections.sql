-- Extend delete_user_data to wipe AI progress projection rows in dependency order.
-- Storage blobs in progress-photos and ai-progress-ladders are wiped separately by
-- AccountService.deleteAccount via SupabaseAdminGateway before this RPC is invoked.
--
-- AccountService.deleteAccount wipes the ai-progress-ladders bucket prefix BEFORE
-- invoking this RPC, so by the time these row deletes run the storage is already clean.

create or replace function public.delete_user_data(p_user_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
    -- Children / leaf rows first
    delete from public.food_item_serving         where user_id = p_user_id;
    delete from public.diary_entry_ingredient    where user_id = p_user_id;
    delete from public.my_meal_ingredient        where user_id = p_user_id;
    delete from public.recent_food               where user_id = p_user_id;

    -- Parent rows
    delete from public.diary_entry               where user_id = p_user_id;
    delete from public.food_item                 where user_id = p_user_id;
    delete from public.my_meal                   where user_id = p_user_id;

    -- AI progress projections — rungs cascade with ladders, ladders cascade with photo,
    -- but be explicit (defense-in-depth, also handles orphan ladders).
    delete from public.ai_progress_ladder_rung   where user_id = p_user_id;
    delete from public.ai_progress_ladder        where user_id = p_user_id;

    -- Independent user-owned rows
    delete from public.progress_photo            where user_id = p_user_id;
    delete from public.weight_entry              where user_id = p_user_id;
    delete from public.calorie_target_history    where user_id = p_user_id;
    delete from public.calorie_target            where user_id = p_user_id;
    delete from public.user_goal                 where user_id = p_user_id;
    delete from public.user_profile              where user_id = p_user_id;
end;
$$;

revoke all on function public.delete_user_data(uuid) from public, anon, authenticated;
grant execute on function public.delete_user_data(uuid) to service_role;
