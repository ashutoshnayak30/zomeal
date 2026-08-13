-- Zomeal admin operations: audited, role-checked marketplace actions.

create or replace function public.require_staff(allowed_roles public.app_role[] default array['ADMIN'::public.app_role])
returns void language plpgsql stable security definer set search_path=public as $$
begin
  if auth.uid() is null or not exists(
    select 1 from public.user_roles where user_id=auth.uid() and role=any(allowed_roles)
  ) then raise exception 'Not authorized'; end if;
end; $$;

create or replace function public.admin_create_provider_draft(payload jsonb)
returns uuid language plpgsql security definer set search_path=public as $$
declare new_id uuid; slug_base text; before_data jsonb;
begin
  perform public.require_staff(array['ADMIN','OPERATIONS']::public.app_role[]);
  if nullif(trim(payload->>'display_name'),'') is null then raise exception 'Provider name is required'; end if;
  slug_base := regexp_replace(lower(payload->>'display_name'),'[^a-z0-9]+','-','g')||'-'||substr(gen_random_uuid()::text,1,6);
  insert into public.providers(legal_name,display_name,slug,status,dietary_type,contact_person_name,support_phone,
    business_address_line,business_city,business_state,business_pincode,description)
  values(coalesce(nullif(payload->>'legal_name',''),payload->>'display_name'),payload->>'display_name',slug_base,'DRAFT',
    coalesce((payload->>'dietary_type')::public.dietary_type,'BOTH'),payload->>'contact_person_name',payload->>'support_phone',
    payload->>'business_address_line',payload->>'business_city',payload->>'business_state',nullif(payload->>'business_pincode',''),payload->>'description')
  returning id into new_id;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data,metadata)
  values(auth.uid(),'PROVIDER_DRAFT_CREATED','providers',new_id::text,payload,jsonb_build_object('source','ADMIN_MANUAL'));
  return new_id;
end; $$;

create or replace function public.admin_update_provider(target_id uuid, payload jsonb, change_reason text)
returns void language plpgsql security definer set search_path=public as $$
declare old_data jsonb; new_data jsonb;
begin
  perform public.require_staff(array['ADMIN','OPERATIONS']::public.app_role[]);
  if nullif(trim(change_reason),'') is null then raise exception 'Change reason is required'; end if;
  select to_jsonb(p) into old_data from public.providers p where id=target_id for update;
  if old_data is null then raise exception 'Provider not found'; end if;
  update public.providers set
    legal_name=coalesce(payload->>'legal_name',legal_name), display_name=coalesce(payload->>'display_name',display_name),
    dietary_type=coalesce((payload->>'dietary_type')::public.dietary_type,dietary_type),
    contact_person_name=coalesce(payload->>'contact_person_name',contact_person_name), support_phone=coalesce(payload->>'support_phone',support_phone),
    business_address_line=coalesce(payload->>'business_address_line',business_address_line), business_city=coalesce(payload->>'business_city',business_city),
    business_state=coalesce(payload->>'business_state',business_state), business_pincode=coalesce(payload->>'business_pincode',business_pincode),
    description=coalesce(payload->>'description',description), status=case when status='ACTIVE' then 'PENDING_APPROVAL' else status end
  where id=target_id returning to_jsonb(providers.*) into new_data;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,before_data,after_data,metadata)
  values(auth.uid(),'PROVIDER_UPDATED_BY_ADMIN','providers',target_id::text,old_data,new_data,jsonb_build_object('reason',change_reason));
end; $$;

create or replace function public.admin_review_request(entity_type text, target_id uuid, decision text, review_reason text default null)
returns void language plpgsql security definer set search_path=public as $$
declare old_data jsonb; new_data jsonb; normalized text:=upper(decision);
begin
  perform public.require_staff(array['ADMIN','OPERATIONS']::public.app_role[]);
  if normalized not in ('APPROVED','REJECTED','SUSPENDED') then raise exception 'Invalid decision'; end if;
  if normalized in ('REJECTED','SUSPENDED') and nullif(trim(review_reason),'') is null then raise exception 'Reason is required'; end if;
  case entity_type
    when 'PRICE' then
      if normalized='SUSPENDED' then raise exception 'Prices cannot be suspended'; end if;
      select to_jsonb(x) into old_data from public.package_price_versions x where id=target_id for update;
      update public.package_price_versions set status=normalized::public.approval_status,approved_by=auth.uid(),approved_at=now() where id=target_id returning to_jsonb(package_price_versions.*) into new_data;
    when 'SERVICE_AREA' then
      if normalized='SUSPENDED' then raise exception 'Service areas cannot be suspended'; end if;
      select to_jsonb(x) into old_data from public.provider_service_areas x where id=target_id for update;
      update public.provider_service_areas set status=normalized::public.approval_status,approved_by=auth.uid(),approved_at=now() where id=target_id returning to_jsonb(provider_service_areas.*) into new_data;
    when 'MENU' then
      select to_jsonb(x) into old_data from public.provider_menus x where id=target_id for update;
      update public.provider_menus set status=normalized::public.catalogue_status,reviewed_by=auth.uid(),reviewed_at=now(),rejection_reason=case when normalized='REJECTED' then review_reason else null end where id=target_id returning to_jsonb(provider_menus.*) into new_data;
    when 'MENU_ITEM' then
      select to_jsonb(x) into old_data from public.menu_items x where id=target_id for update;
      update public.menu_items set status=normalized::public.catalogue_status,reviewed_by=auth.uid(),reviewed_at=now(),rejection_reason=case when normalized='REJECTED' then review_reason else null end where id=target_id returning to_jsonb(menu_items.*) into new_data;
    when 'MEDIA' then
      select to_jsonb(x) into old_data from public.provider_media x where id=target_id for update;
      update public.provider_media set status=normalized::public.media_status,reviewed_by=auth.uid(),reviewed_at=now(),rejection_reason=case when normalized='REJECTED' then review_reason else null end where id=target_id returning to_jsonb(provider_media.*) into new_data;
    else raise exception 'Unsupported entity type';
  end case;
  if old_data is null then raise exception 'Record not found'; end if;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,before_data,after_data,metadata)
  values(auth.uid(),'ADMIN_'||normalized,entity_type,target_id::text,old_data,new_data,jsonb_build_object('reason',review_reason));
end; $$;

create or replace view public.admin_approval_queue with (security_invoker=true) as
select 'PROVIDER'::text entity_type,p.id,p.display_name provider_name,p.status::text status,p.created_at submitted_at,
 jsonb_build_object('dietary_type',p.dietary_type,'phone',p.support_phone,'city',p.business_city) summary from public.providers p where p.status in ('DRAFT','PENDING_APPROVAL')
union all select 'PRICE',v.id,p.display_name,v.status::text,v.created_at,jsonb_build_object('package',pk.name,'price_paise',v.total_price_paise)
 from public.package_price_versions v join public.packages pk on pk.id=v.package_id join public.providers p on p.id=pk.provider_id where v.status='PENDING'
union all select 'SERVICE_AREA',a.id,p.display_name,a.status::text,a.created_at,jsonb_build_object('pincode',a.pincode,'radius_km',a.delivery_radius_km)
 from public.provider_service_areas a join public.providers p on p.id=a.provider_id where a.status='PENDING'
union all select 'MENU',m.id,p.display_name,m.status::text,m.created_at,jsonb_build_object('menu',m.name,'valid_from',m.valid_from)
 from public.provider_menus m join public.providers p on p.id=m.provider_id where m.status='PENDING_REVIEW'
union all select 'MEDIA',m.id,p.display_name,m.status::text,m.created_at,jsonb_build_object('media_type',m.media_type,'path',m.storage_path)
 from public.provider_media m join public.providers p on p.id=m.provider_id where m.status='PENDING_REVIEW';

grant select on public.admin_approval_queue to authenticated;

create policy staff_pending_providers_read on public.providers for select using (public.has_role('OPERATIONS'));
create policy staff_pending_prices_read on public.package_price_versions for select using (public.has_role('OPERATIONS'));
create policy staff_pending_areas_read on public.provider_service_areas for select using (public.has_role('OPERATIONS'));
revoke all on function public.admin_create_provider_draft(jsonb),public.admin_update_provider(uuid,jsonb,text),public.admin_review_request(text,uuid,text,text) from public;
grant execute on function public.admin_create_provider_draft(jsonb),public.admin_update_provider(uuid,jsonb,text),public.admin_review_request(text,uuid,text,text) to authenticated;
