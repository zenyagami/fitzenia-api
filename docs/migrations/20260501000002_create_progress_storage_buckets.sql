-- Private bucket for AI-generated progress projection images.
-- progress-photos already exists from 20260412205626 with full RLS — do not touch it.
--
-- Path layout: {userId}/ladders/{ladderId}/{stepIndex}.jpg
-- The first folder segment is auth.uid(), matching the storage RLS pattern used by
-- progress-photos.

insert into storage.buckets (id, name, public)
values ('ai-progress-ladders', 'ai-progress-ladders', false)
on conflict (id) do nothing;


-- Storage RLS: a user can read their own generated images. Writes happen exclusively
-- via the backend service-role key (bypasses RLS), so no INSERT/UPDATE/DELETE policy
-- for authenticated users — the sweeper is the only writer/deleter and it uses
-- service_role.

drop policy if exists ai_progress_ladders_select_own on storage.objects;
create policy ai_progress_ladders_select_own
    on storage.objects
    for select
    to authenticated
    using (
        bucket_id = 'ai-progress-ladders'
        and (storage.foldername(name))[1] = auth.uid()::text
    );
