-- Finalize a manually onboarded provider in one audited admin action.
-- A provider login is not required at activation time because staff may onboard
-- a provider from information received by phone, WhatsApp, or in person.

create or replace function public.admin_finalize_provider(target_id uuid)
returns jsonb language plpgsql security definer set search_path=public as $$
declare
  p public.providers%rowtype;
  missing text[] := '{}';
  menu_record record;
  package_record record;
  expected_slots public.meal_slot[];
  slot public.meal_slot;
  covered integer;
begin
  perform public.require_staff(array['ADMIN']::public.app_role[]);
  select * into p from public.providers where id=target_id for update;
  if not found then raise exception 'Provider not found'; end if;

  if nullif(trim(p.display_name),'') is null then missing:=array_append(missing,'Provider name'); end if;
  if nullif(trim(p.contact_person_name),'') is null then missing:=array_append(missing,'Contact person'); end if;
  if nullif(trim(coalesce(p.support_phone,'')),'') is null then missing:=array_append(missing,'Mobile number'); end if;
  if nullif(trim(p.business_address_line),'') is null or nullif(trim(p.business_city),'') is null or
     nullif(trim(p.business_state),'') is null or p.business_pincode is null then
    missing:=array_append(missing,'Basic business address');
  end if;
  if not exists(select 1 from public.provider_service_areas where provider_id=target_id and status in ('PENDING','APPROVED')) then
    missing:=array_append(missing,'Serviceable pincode');
  end if;
  if not exists(select 1 from public.packages where provider_id=target_id) then
    missing:=array_append(missing,'Package and price');
  end if;
  if not exists(select 1 from public.provider_menus where provider_id=target_id and status in ('PENDING_REVIEW','APPROVED')) then
    missing:=array_append(missing,'Weekly menu');
  end if;
  if cardinality(missing)>0 then
    raise exception 'Complete these required fields before activation: %',to_jsonb(missing);
  end if;

  update public.provider_service_areas set status='APPROVED',approved_by=auth.uid(),approved_at=now(),
    effective_from=coalesce(effective_from,current_date) where provider_id=target_id and status='PENDING';
  update public.package_price_versions v set status='APPROVED',approved_by=auth.uid(),approved_at=now(),effective_from=now()
    from public.packages pk where v.package_id=pk.id and pk.provider_id=target_id and v.status='PENDING';
  update public.menu_items set status='APPROVED',reviewed_by=auth.uid(),reviewed_at=now(),rejection_reason=null
    where provider_id=target_id and status='PENDING_REVIEW';
  update public.provider_menus set status='APPROVED',reviewed_by=auth.uid(),reviewed_at=now(),rejection_reason=null
    where provider_id=target_id and status='PENDING_REVIEW';
  update public.packages set is_active=true,
    lunch_delivery_start=case when kind in ('LUNCH_ONLY','LUNCH_AND_DINNER') then coalesce(lunch_delivery_start,'12:00'::time) else null end,
    lunch_delivery_end=case when kind in ('LUNCH_ONLY','LUNCH_AND_DINNER') then coalesce(lunch_delivery_end,'14:00'::time) else null end,
    dinner_delivery_start=case when kind in ('DINNER_ONLY','LUNCH_AND_DINNER') then coalesce(dinner_delivery_start,'19:00'::time) else null end,
    dinner_delivery_end=case when kind in ('DINNER_ONLY','LUNCH_AND_DINNER') then coalesce(dinner_delivery_end,'21:00'::time) else null end
    where provider_id=target_id;

  -- Connect every active package to the current approved weekly menu.
  for package_record in select * from public.packages where provider_id=target_id and is_active loop
    for menu_record in select * from public.provider_menus where provider_id=target_id and status='APPROVED' loop
      insert into public.package_menus(package_id,menu_id,effective_from)
      values(package_record.id,menu_record.id,current_date) on conflict do nothing;
    end loop;
    expected_slots:=case package_record.kind
      when 'LUNCH_ONLY' then array['LUNCH'::public.meal_slot]
      when 'DINNER_ONLY' then array['DINNER'::public.meal_slot]
      else array['LUNCH'::public.meal_slot,'DINNER'::public.meal_slot] end;
    foreach slot in array expected_slots loop
      select count(distinct d.day_of_week) into covered from public.package_menus pm
      join public.provider_menus m on m.id=pm.menu_id and m.status='APPROVED'
      join public.menu_days d on d.menu_id=m.id and d.meal_slot=slot and d.is_available
      where pm.package_id=package_record.id and exists(select 1 from public.menu_day_choices c join public.menu_items i on i.id=c.menu_item_id where c.menu_day_id=d.id and i.status='APPROVED');
      if covered<>7 then raise exception 'Complete all 7 days for the % menu before activation',lower(slot::text); end if;
    end loop;
  end loop;

  update public.providers set status='ACTIVE',approved_by=auth.uid(),approved_at=now() where id=target_id;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data,metadata)
  select auth.uid(),'MANUAL_PROVIDER_FINALIZED','providers',id::text,to_jsonb(providers.*),
    jsonb_build_object('approved_related_records',true,'provider_account_required_later',true)
  from public.providers where id=target_id;
  return jsonb_build_object('provider_id',target_id,'ready',true,'status','ACTIVE');
end; $$;

create or replace function public.admin_review_provider(target_id uuid,decision text,review_reason text default null)
returns void language plpgsql security definer set search_path=public as $$
declare old_data jsonb; new_data jsonb; normalized text:=upper(decision);
begin
  perform public.require_staff(array['ADMIN','OPERATIONS']::public.app_role[]);
  if normalized not in ('APPROVED','REJECTED','SUSPENDED') then raise exception 'Invalid decision'; end if;
  if normalized in ('REJECTED','SUSPENDED') and nullif(trim(review_reason),'') is null then raise exception 'Reason is required'; end if;
  select to_jsonb(p) into old_data from public.providers p where id=target_id for update;
  if old_data is null then raise exception 'Provider not found'; end if;
  if normalized='APPROVED' then perform public.admin_finalize_provider(target_id);
  else update public.providers set status=case normalized when 'SUSPENDED' then 'SUSPENDED'::public.provider_status else 'DRAFT'::public.provider_status end,
    approved_by=null,approved_at=null where id=target_id; end if;
  select to_jsonb(p) into new_data from public.providers p where id=target_id;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,before_data,after_data,metadata)
  values(auth.uid(),'ADMIN_PROVIDER_'||normalized,'PROVIDER',target_id::text,old_data,new_data,jsonb_build_object('reason',review_reason));
end; $$;

revoke all on function public.admin_finalize_provider(uuid) from public;
grant execute on function public.admin_finalize_provider(uuid) to authenticated;
