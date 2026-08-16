-- Admin review and metadata corrections for provider-uploaded photographs.

create or replace function public.admin_update_provider_media(
  target_media_id uuid,
  new_alt_text text,
  new_status public.media_status,
  new_media_type public.media_type default null,
  new_menu_item_id uuid default null
) returns void language plpgsql security definer set search_path=public as $$
declare old_data jsonb; new_data jsonb;
begin
  perform public.require_staff(array['ADMIN','OPERATIONS']::public.app_role[]);
  if new_status not in ('APPROVED','REJECTED','SUSPENDED','ARCHIVED') then raise exception 'Invalid moderation status'; end if;
  select to_jsonb(m) into old_data from public.provider_media m where id=target_media_id for update;
  if old_data is null then raise exception 'Photo not found'; end if;
  update public.provider_media set alt_text=nullif(trim(new_alt_text),''),status=new_status,
    media_type=coalesce(new_media_type,media_type),menu_item_id=new_menu_item_id,
    reviewed_by=auth.uid(),reviewed_at=now(),rejection_reason=case when new_status='REJECTED' then 'Rejected by administrator' else null end,
    updated_at=now() where id=target_media_id returning to_jsonb(provider_media.*) into new_data;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,before_data,after_data)
  values(auth.uid(),'PROVIDER_MEDIA_UPDATED_BY_ADMIN','provider_media',target_media_id::text,old_data,new_data);
end; $$;

revoke all on function public.admin_update_provider_media(uuid,text,public.media_status,public.media_type,uuid) from public;
grant execute on function public.admin_update_provider_media(uuid,text,public.media_status,public.media_type,uuid) to authenticated;

