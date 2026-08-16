-- Complete, transactional provider onboarding and admin workspace reads.

create or replace function public.admin_save_provider_workspace(
  target_id uuid,
  payload jsonb,
  change_reason text default 'Admin provider workspace save'
) returns uuid language plpgsql security definer set search_path=public as $$
declare
  provider_id uuid := target_id;
  provider_before jsonb;
  provider_after jsonb;
  pin jsonb;
  package_row jsonb;
  menu_row jsonb;
  package_id uuid;
  menu_id uuid;
  menu_day_id uuid;
  item_id uuid;
  price_paise bigint;
  lunch_paise bigint;
  dinner_paise bigint;
begin
  perform public.require_staff(array['ADMIN','OPERATIONS']::public.app_role[]);
  if nullif(trim(payload->>'display_name'),'') is null then raise exception 'Provider name is required'; end if;
  if jsonb_array_length(coalesce(payload->'pincodes','[]'::jsonb)) = 0 then raise exception 'At least one pincode is required'; end if;

  if provider_id is null then
    insert into public.providers(
      legal_name,display_name,slug,status,dietary_type,contact_person_name,support_phone,
      business_address_line,business_city,business_state,business_pincode,description
    ) values (
      coalesce(nullif(payload->>'legal_name',''),payload->>'display_name'), payload->>'display_name',
      regexp_replace(lower(payload->>'display_name'),'[^a-z0-9]+','-','g')||'-'||substr(gen_random_uuid()::text,1,6),
      'PENDING_APPROVAL', coalesce((payload->>'dietary_type')::public.dietary_type,'BOTH'),
      payload->>'contact_person_name',payload->>'support_phone',payload->>'business_address_line',
      coalesce(payload->>'business_city','Bhubaneswar'),coalesce(payload->>'business_state','Odisha'),
      payload->>'business_pincode',payload->>'description'
    ) returning id into provider_id;
  else
    select to_jsonb(p) into provider_before from public.providers p where p.id=provider_id for update;
    if provider_before is null then raise exception 'Provider not found'; end if;
    update public.providers set legal_name=coalesce(nullif(payload->>'legal_name',''),payload->>'display_name'),
      display_name=payload->>'display_name',dietary_type=coalesce((payload->>'dietary_type')::public.dietary_type,dietary_type),
      contact_person_name=payload->>'contact_person_name',support_phone=payload->>'support_phone',
      business_address_line=payload->>'business_address_line',business_city=coalesce(payload->>'business_city',business_city),
      business_state=coalesce(payload->>'business_state',business_state),business_pincode=payload->>'business_pincode',
      description=payload->>'description',status=case when status='ACTIVE' then 'PENDING_APPROVAL' else status end
    where id=provider_id;
  end if;

  -- Replace the editable operational draft in the same transaction.
  delete from public.provider_service_areas where provider_id=admin_save_provider_workspace.provider_id and status in ('DRAFT','PENDING','REJECTED');
  for pin in select value from jsonb_array_elements(payload->'pincodes') loop
    insert into public.pincodes(code,city,state) values(pin->>'code',coalesce(pin->>'city',payload->>'business_city','Bhubaneswar'),coalesce(pin->>'state',payload->>'business_state','Odisha'))
    on conflict(code) do update set city=excluded.city,state=excluded.state;
    insert into public.provider_service_areas(provider_id,pincode,status,delivery_radius_km,requested_by,effective_from)
    values(provider_id,pin->>'code','PENDING',nullif(pin->>'radius_km','')::numeric,auth.uid(),current_date)
    on conflict(provider_id,pincode) do update set status='PENDING',delivery_radius_km=excluded.delivery_radius_km,requested_by=auth.uid(),approved_by=null,approved_at=null;
    insert into public.provider_capacity(provider_id,pincode,service_date,meal_slot,capacity_limit,updated_by)
    select provider_id,pin->>'code',d::date,s.slot,
      case s.slot when 'LUNCH'::public.meal_slot then coalesce((payload#>>'{capacities,lunch}')::integer,0) else coalesce((payload#>>'{capacities,dinner}')::integer,0) end,auth.uid()
    from generate_series(current_date,current_date+29,interval '1 day') d
    cross join (values('LUNCH'::public.meal_slot),('DINNER'::public.meal_slot)) s(slot)
    on conflict(provider_id,pincode,service_date,meal_slot) do update set capacity_limit=excluded.capacity_limit,updated_by=auth.uid();
  end loop;

  for package_row in select value from jsonb_array_elements(coalesce(payload->'packages','[]'::jsonb)) loop
    if coalesce((package_row->>'enabled')::boolean,true) then
      price_paise := round((package_row->>'price_rupees')::numeric*100);
      if package_row->>'kind'='LUNCH_ONLY' then lunch_paise:=price_paise; dinner_paise:=0;
      elsif package_row->>'kind'='DINNER_ONLY' then lunch_paise:=0; dinner_paise:=price_paise;
      else lunch_paise:=price_paise/2; dinner_paise:=price_paise-lunch_paise; end if;
      insert into public.packages(provider_id,name,description,kind,dietary_type,duration_days,is_active)
      values(provider_id,package_row->>'name',package_row->>'delivery_time',(package_row->>'kind')::public.package_kind,
        coalesce((payload->>'dietary_type')::public.dietary_type,'BOTH'),coalesce((package_row->>'duration_days')::integer,30),false)
      on conflict(provider_id,name) do update set description=excluded.description,kind=excluded.kind,
        dietary_type=excluded.dietary_type,duration_days=excluded.duration_days,is_active=false
      returning id into package_id;
      insert into public.package_price_versions(package_id,version,total_price_paise,lunch_value_paise,dinner_value_paise,status,requested_by)
      values(package_id,coalesce((select max(version)+1 from public.package_price_versions where package_id=admin_save_provider_workspace.package_id),1),price_paise,lunch_paise,dinner_paise,'PENDING',auth.uid());
    end if;
  end loop;

  delete from public.provider_menus where provider_id=admin_save_provider_workspace.provider_id and status in ('DRAFT','PENDING_REVIEW','REJECTED');
  insert into public.provider_menus(provider_id,name,status,valid_from,submitted_at,created_by)
  values(provider_id,'Standard weekly menu','PENDING_REVIEW',current_date,now(),auth.uid()) returning id into menu_id;
  for menu_row in select value from jsonb_array_elements(coalesce(payload->'menu','[]'::jsonb)) loop
    insert into public.menu_days(menu_id,day_of_week,meal_slot,is_available)
    values(menu_id,(menu_row->>'day')::smallint,coalesce((menu_row->>'meal_slot')::public.meal_slot,'LUNCH'),true)
    returning id into menu_day_id;
    insert into public.menu_items(provider_id,name,category,dietary_type,status,created_by,submitted_at)
    values(provider_id,menu_row->>'main_course','MAIN_COURSE',coalesce((payload->>'dietary_type')::public.dietary_type,'BOTH'),'PENDING_REVIEW',auth.uid(),now())
    on conflict(provider_id,name) do update set status='PENDING_REVIEW',submitted_at=now() returning id into item_id;
    insert into public.menu_day_choices(menu_day_id,menu_item_id,choice_group,is_default,is_required,is_changeable)
    values(menu_day_id,item_id,'MAIN_COURSE',true,true,true);
    if nullif(trim(menu_row->>'fixed_items'),'') is not null then
      insert into public.menu_items(provider_id,name,category,dietary_type,status,created_by,submitted_at)
      values(provider_id,menu_row->>'fixed_items','SIDE',coalesce((payload->>'dietary_type')::public.dietary_type,'BOTH'),'PENDING_REVIEW',auth.uid(),now())
      on conflict(provider_id,name) do update set status='PENDING_REVIEW',submitted_at=now() returning id into item_id;
      insert into public.menu_day_choices(menu_day_id,menu_item_id,choice_group,is_default,is_required,is_changeable)
      values(menu_day_id,item_id,'SIDE',true,true,false);
    end if;
  end loop;

  select to_jsonb(p) into provider_after from public.providers p where p.id=provider_id;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,before_data,after_data,metadata)
  values(auth.uid(),case when target_id is null then 'PROVIDER_WORKSPACE_CREATED' else 'PROVIDER_WORKSPACE_UPDATED' end,
    'providers',provider_id::text,provider_before,provider_after,jsonb_build_object('reason',change_reason,'source','ADMIN_MANUAL'));
  return provider_id;
end; $$;

create or replace function public.admin_review_provider(target_id uuid, decision text, review_reason text default null)
returns void language plpgsql security definer set search_path=public as $$
declare old_data jsonb; new_data jsonb; normalized text:=upper(decision);
begin
  perform public.require_staff(array['ADMIN','OPERATIONS']::public.app_role[]);
  if normalized not in ('APPROVED','REJECTED','SUSPENDED') then raise exception 'Invalid decision'; end if;
  if normalized in ('REJECTED','SUSPENDED') and nullif(trim(review_reason),'') is null then raise exception 'Reason is required'; end if;
  select to_jsonb(p) into old_data from public.providers p where id=target_id for update;
  if old_data is null then raise exception 'Provider not found'; end if;
  if normalized='APPROVED' then
    perform public.admin_activate_provider(target_id);
  else
    update public.providers set status=case normalized when 'SUSPENDED' then 'SUSPENDED'::public.provider_status else 'DRAFT'::public.provider_status end,
      approved_by=null,approved_at=null where id=target_id;
  end if;
  select to_jsonb(p) into new_data from public.providers p where id=target_id;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,before_data,after_data,metadata)
  values(auth.uid(),'ADMIN_PROVIDER_'||normalized,'PROVIDER',target_id::text,old_data,new_data,jsonb_build_object('reason',review_reason));
end; $$;

create or replace function public.admin_provider_workspace(target_id uuid)
returns jsonb language plpgsql stable security definer set search_path=public as $$
begin
  perform public.require_staff(array['ADMIN','OPERATIONS']::public.app_role[]);
  return (select jsonb_build_object(
    'provider',to_jsonb(p),
    'service_areas',coalesce((select jsonb_agg(to_jsonb(a) order by a.pincode) from public.provider_service_areas a where a.provider_id=p.id),'[]'::jsonb),
    'packages',coalesce((select jsonb_agg(jsonb_build_object('id',pk.id,'name',pk.name,'kind',pk.kind,'duration_days',pk.duration_days,'delivery_time',pk.description,'is_active',pk.is_active,'prices',
      coalesce((select jsonb_agg(to_jsonb(v) order by v.version desc) from public.package_price_versions v where v.package_id=pk.id),'[]'::jsonb))) from public.packages pk where pk.provider_id=p.id),'[]'::jsonb),
    'menus',coalesce((select jsonb_agg(jsonb_build_object('id',m.id,'name',m.name,'status',m.status,'days',
      coalesce((select jsonb_agg(jsonb_build_object('id',d.id,'day',d.day_of_week,'meal_slot',d.meal_slot,'choices',
        coalesce((select jsonb_agg(jsonb_build_object('id',c.id,'name',i.name,'category',c.choice_group,'changeable',c.is_changeable)) from public.menu_day_choices c join public.menu_items i on i.id=c.menu_item_id where c.menu_day_id=d.id),'[]'::jsonb)) order by d.day_of_week,d.meal_slot) from public.menu_days d where d.menu_id=m.id),'[]'::jsonb))) from public.provider_menus m where m.provider_id=p.id),'[]'::jsonb),
    'media',coalesce((select jsonb_agg(to_jsonb(pm) order by pm.created_at desc) from public.provider_media pm where pm.provider_id=p.id),'[]'::jsonb)
  ) from public.providers p where p.id=target_id);
end; $$;

revoke all on function public.admin_save_provider_workspace(uuid,jsonb,text),public.admin_provider_workspace(uuid),public.admin_review_provider(uuid,text,text) from public;
grant execute on function public.admin_save_provider_workspace(uuid,jsonb,text),public.admin_provider_workspace(uuid),public.admin_review_provider(uuid,text,text) to authenticated;
