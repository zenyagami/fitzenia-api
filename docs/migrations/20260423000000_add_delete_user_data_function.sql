-- Wipe every public.* row for a given auth user in a single transaction.
-- Invoked from the backend's SupabaseAdminGateway with the service-role key.
-- RLS does not apply to service_role, but we still scope every DELETE by user_id
-- as a defense-in-depth guardrail.
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

    -- Independent user-owned rows
    delete from public.progress_photo            where user_id = p_user_id;
    delete from public.weight_entry              where user_id = p_user_id;
    delete from public.calorie_target_history    where user_id = p_user_id;
    delete from public.calorie_target            where user_id = p_user_id;
    delete from public.user_goal                 where user_id = p_user_id;
    delete from public.user_profile              where user_id = p_user_id;
end;
$$;

-- Only the service role can invoke this function.
revoke all on function public.delete_user_data(uuid) from public, anon, authenticated;
grant execute on function public.delete_user_data(uuid) to service_role;
