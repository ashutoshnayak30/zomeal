create or replace function public.admin_update_website_lead(
  target_lead uuid,
  target_status text
) returns public.website_leads
language plpgsql
security definer
set search_path=public
as $$
declare
  updated_lead public.website_leads;
  normalized_status text:=upper(trim(coalesce(target_status,'')));
begin
  if not public.has_role('ADMIN') then raise exception 'Admin access is required'; end if;
  if normalized_status not in('NEW','CONTACTED','CONVERTED','CLOSED') then raise exception 'Invalid lead status'; end if;
  update public.website_leads set status=normalized_status,updated_at=now()
  where id=target_lead returning * into updated_lead;
  if updated_lead.id is null then raise exception 'Website lead was not found'; end if;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data)
  values(auth.uid(),'WEBSITE_LEAD_STATUS_UPDATED','website_lead',target_lead::text,jsonb_build_object('status',normalized_status));
  return updated_lead;
end;
$$;

revoke all on function public.admin_update_website_lead(uuid,text) from public;
grant execute on function public.admin_update_website_lead(uuid,text) to authenticated;
notify pgrst,'reload schema';
