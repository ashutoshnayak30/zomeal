-- A rejected FCM token must be renewed on the handset, not re-enabled forever.
create or replace function public.register_customer_push_device(target_token text)
returns jsonb language plpgsql security definer set search_path=public as $$
begin
 if auth.uid() is null then raise exception 'Authentication required' using errcode='42501'; end if;
 if length(trim(coalesce(target_token,''))) not between 20 and 4096 then raise exception 'Invalid push token'; end if;
 if exists(select 1 from public.push_device_tokens d where d.token=trim(target_token)
   and (not d.enabled or exists(select 1 from public.meal_push_outbox o where o.device_id=d.id and o.completed_at is not null and o.last_error='NOT_FOUND'))) then
   return jsonb_build_object('registered',false,'refresh_required',true);
 end if;
 return public.register_push_device(target_token,'CUSTOMER','ANDROID');
end $$;
revoke all on function public.register_customer_push_device(text) from public,anon;
grant execute on function public.register_customer_push_device(text) to authenticated;
notify pgrst,'reload schema';
