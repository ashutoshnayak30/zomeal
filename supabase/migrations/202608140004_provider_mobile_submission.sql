-- Provider mobile onboarding: transactional submission, status and moderated media.

create or replace function public.submit_provider_mobile_application(payload jsonb)
returns jsonb language plpgsql security definer set search_path=public as $$
#variable_conflict use_column
<<mobile_submit>>
declare
  provider_id uuid;
  existing_status public.provider_status;
  pin jsonb; package_row jsonb; day_row jsonb; dish jsonb;
  package_id uuid; menu_id uuid; menu_day_id uuid; item_id uuid;
  slot public.meal_slot; dishes jsonb; fixed_name text; first_item boolean;
  price_paise bigint; lunch_paise bigint; dinner_paise bigint;
  required_lunch boolean := coalesce((payload->>'lunchEnabled')::boolean,false) or coalesce((payload->>'bothEnabled')::boolean,false);
  required_dinner boolean := coalesce((payload->>'dinnerEnabled')::boolean,false) or coalesce((payload->>'bothEnabled')::boolean,false);
  covered integer;
begin
  if auth.uid() is null then raise exception 'Authentication required'; end if;
  if nullif(trim(payload->>'businessName'),'') is null then raise exception 'Business name is required'; end if;
  if nullif(trim(payload->>'contactName'),'') is null then raise exception 'Contact person is required'; end if;
  if nullif(trim(payload->>'address'),'') is null then raise exception 'Business address is required'; end if;
  if nullif(trim(payload->>'ownerPhone'),'') is null then raise exception 'Provider phone number is required'; end if;
  if nullif(trim(payload->>'deliveryPhone'),'') is null then raise exception 'Delivery phone number is required'; end if;
  if not (required_lunch or required_dinner) then raise exception 'Select at least one package'; end if;

  select p.id,p.status into provider_id,existing_status
  from public.provider_members pm join public.providers p on p.id=pm.provider_id
  where pm.user_id=auth.uid() and pm.is_active order by pm.created_at desc limit 1 for update of p;

  if provider_id is null then
    insert into public.providers(legal_name,display_name,slug,status,dietary_type,contact_person_name,support_phone,
      business_address_line,business_city,business_state,business_pincode)
    values(payload->>'businessName',payload->>'businessName',regexp_replace(lower(payload->>'businessName'),'[^a-z0-9]+','-','g')||'-'||substr(gen_random_uuid()::text,1,6),
      'DRAFT',case upper(payload->>'category') when 'VEG' then 'VEG'::public.dietary_type when 'NON-VEG' then 'NON_VEG'::public.dietary_type else 'BOTH'::public.dietary_type end,
      payload->>'contactName',payload->>'ownerPhone',payload->>'address',coalesce(nullif(payload->>'city',''),'Bhubaneswar'),
      coalesce(nullif(payload->>'state',''),'Odisha'),nullif(payload->>'pincode','')) returning id into provider_id;
    insert into public.provider_members(provider_id,user_id,member_role,is_active) values(provider_id,auth.uid(),'OWNER',true);
    insert into public.user_roles(user_id,role) values(auth.uid(),'PROVIDER') on conflict do nothing;
  elsif existing_status='ACTIVE' then
    raise exception 'Active providers must submit changes through change requests';
  end if;

  update public.providers set legal_name=payload->>'businessName',display_name=payload->>'businessName',
    dietary_type=case upper(payload->>'category') when 'VEG' then 'VEG'::public.dietary_type when 'NON-VEG' then 'NON_VEG'::public.dietary_type else 'BOTH'::public.dietary_type end,
    contact_person_name=payload->>'contactName',support_phone=payload->>'ownerPhone',business_address_line=payload->>'address',
    business_city=payload->>'city',business_state=payload->>'state',business_pincode=nullif(payload->>'pincode',''),
    status='PENDING_APPROVAL',approved_by=null,approved_at=null where id=provider_id;

  delete from public.provider_service_areas where provider_id=mobile_submit.provider_id and status<>'APPROVED';
  for pin in select value from jsonb_array_elements(coalesce(payload->'servicePincodes','[]'::jsonb)) loop
    if coalesce((pin->>'verified')::boolean,false) and (pin->>'value') ~ '^[1-9][0-9]{5}$' then
      insert into public.pincodes(code,locality,city,state) values(pin->>'value',nullif(pin->>'areaName',''),coalesce(nullif(payload->>'city',''),'Bhubaneswar'),coalesce(nullif(payload->>'state',''),'Odisha'))
      on conflict(code) do update set locality=coalesce(excluded.locality,pincodes.locality);
      insert into public.provider_service_areas(provider_id,pincode,status,delivery_radius_km,requested_by,effective_from)
      values(provider_id,pin->>'value','PENDING',nullif(payload->>'radius','')::numeric,auth.uid(),current_date)
      on conflict(provider_id,pincode) do update set status='PENDING',delivery_radius_km=excluded.delivery_radius_km,requested_by=auth.uid();
      insert into public.provider_capacity(provider_id,pincode,service_date,meal_slot,capacity_limit,updated_by)
      select provider_id,pin->>'value',d::date,s.slot,case s.slot when 'LUNCH' then coalesce(nullif(payload->>'lunchCapacity','')::integer,0) else coalesce(nullif(payload->>'dinnerCapacity','')::integer,0) end,auth.uid()
      from generate_series(current_date,current_date+29,interval '1 day') d cross join (values('LUNCH'::public.meal_slot),('DINNER'::public.meal_slot)) s(slot)
      on conflict(provider_id,pincode,service_date,meal_slot) do update set capacity_limit=excluded.capacity_limit,updated_by=auth.uid();
    end if;
  end loop;
  if not exists(select 1 from public.provider_service_areas where provider_id=mobile_submit.provider_id) then raise exception 'Add and verify at least one pincode'; end if;

  delete from public.package_price_versions where package_id in (select id from public.packages where provider_id=mobile_submit.provider_id) and status<>'APPROVED';
  delete from public.packages where provider_id=mobile_submit.provider_id and not is_active;
  for package_row in select value from jsonb_array_elements(jsonb_build_array(
    jsonb_build_object('enabled',payload->'lunchEnabled','kind','LUNCH_ONLY','name','Lunch Only','price',payload->>'lunchPrice'),
    jsonb_build_object('enabled',payload->'dinnerEnabled','kind','DINNER_ONLY','name','Dinner Only','price',payload->>'dinnerPrice'),
    jsonb_build_object('enabled',payload->'bothEnabled','kind','LUNCH_AND_DINNER','name','Lunch + Dinner','price',payload->>'bothPrice'))) loop
    if coalesce((package_row->>'enabled')::boolean,false) then
      if nullif(package_row->>'price','') is null then raise exception 'Price required for %',package_row->>'name'; end if;
      price_paise:=round((package_row->>'price')::numeric*100);
      if package_row->>'kind'='LUNCH_ONLY' then lunch_paise:=price_paise; dinner_paise:=0;
      elsif package_row->>'kind'='DINNER_ONLY' then lunch_paise:=0; dinner_paise:=price_paise;
      else lunch_paise:=price_paise/2; dinner_paise:=price_paise-lunch_paise; end if;
      insert into public.packages(provider_id,name,kind,dietary_type,duration_days,is_active,lunch_delivery_start,lunch_delivery_end,dinner_delivery_start,dinner_delivery_end)
      select provider_id,package_row->>'name',(package_row->>'kind')::public.package_kind,p.dietary_type,30,false,
        case when package_row->>'kind'<>'DINNER_ONLY' then '12:00'::time end,case when package_row->>'kind'<>'DINNER_ONLY' then '14:00'::time end,
        case when package_row->>'kind'<>'LUNCH_ONLY' then '19:00'::time end,case when package_row->>'kind'<>'LUNCH_ONLY' then '21:00'::time end from public.providers p where p.id=provider_id
      returning id into package_id;
      insert into public.package_price_versions(package_id,version,total_price_paise,lunch_value_paise,dinner_value_paise,status,requested_by)
      values(package_id,1,price_paise,lunch_paise,dinner_paise,'PENDING',auth.uid());
    end if;
  end loop;

  delete from public.provider_menus where provider_id=mobile_submit.provider_id and status<>'APPROVED';
  insert into public.provider_menus(provider_id,name,status,valid_from,submitted_at,created_by)
  values(provider_id,'Standard weekly menu','PENDING_REVIEW',current_date,now(),auth.uid()) returning id into menu_id;
  for day_row in select value from jsonb_array_elements(coalesce(payload->'menus','[]'::jsonb)) loop
    foreach slot in array array['LUNCH'::public.meal_slot,'DINNER'::public.meal_slot] loop
      if (slot='LUNCH' and required_lunch) or (slot='DINNER' and required_dinner) then
        dishes:=case slot when 'LUNCH' then coalesce(day_row->'lunch','[]'::jsonb) else coalesce(day_row->'dinner','[]'::jsonb) end;
        if not exists(select 1 from jsonb_array_elements(dishes) x where nullif(trim(x->>'name'),'') is not null) then
          raise exception '% menu is incomplete for %',initcap(lower(slot::text)),day_row->>'day';
        end if;
        insert into public.menu_days(menu_id,day_of_week,meal_slot,is_available)
        values(menu_id,(select ordinality::smallint from unnest(array['Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday']) with ordinality d(name,ordinality) where name=day_row->>'day'),slot,true)
        returning id into menu_day_id;
        first_item:=true;
        for dish in select value from jsonb_array_elements(dishes) loop
          if nullif(trim(dish->>'name'),'') is not null then
            insert into public.menu_items(provider_id,name,description,category,dietary_type,status,created_by,submitted_at)
            select provider_id,dish->>'name',nullif(dish->>'description',''),'MAIN_COURSE',p.dietary_type,'PENDING_REVIEW',auth.uid(),now() from public.providers p where p.id=provider_id
            on conflict(provider_id,name) do update set description=excluded.description,status='PENDING_REVIEW',submitted_at=now() returning id into item_id;
            insert into public.menu_day_choices(menu_day_id,menu_item_id,choice_group,is_default,is_required,is_changeable)
            values(menu_day_id,item_id,'MAIN_COURSE',first_item,true,true); first_item:=false;
          end if;
        end loop;
        fixed_name:=case slot when 'LUNCH' then day_row->>'lunchFixed' else day_row->>'dinnerFixed' end;
        if nullif(trim(fixed_name),'') is not null then
          insert into public.menu_items(provider_id,name,category,dietary_type,status,created_by,submitted_at)
          select provider_id,fixed_name,'SIDE',p.dietary_type,'PENDING_REVIEW',auth.uid(),now() from public.providers p where p.id=provider_id
          on conflict(provider_id,name) do update set status='PENDING_REVIEW',submitted_at=now() returning id into item_id;
          insert into public.menu_day_choices(menu_day_id,menu_item_id,choice_group,is_default,is_required,is_changeable)
          values(menu_day_id,item_id,'SIDE',true,true,false);
        end if;
      end if;
    end loop;
  end loop;
  select count(distinct day_of_week) into covered from public.menu_days where menu_id=mobile_submit.menu_id;
  if covered<>7 then raise exception 'Complete menus for all 7 days'; end if;

  delete from public.provider_delivery_personnel where provider_id=mobile_submit.provider_id;
  insert into public.provider_delivery_personnel(provider_id,full_name,phone,is_primary,is_active,created_by)
  values(provider_id,nullif(payload->>'deliveryName',''),payload->>'deliveryPhone',true,true,auth.uid());

  update public.provider_form_drafts set status='SUBMITTED',updated_at=now()
  where owner_user_id=auth.uid() and form_scope='provider_mobile_onboarding' and status='IN_PROGRESS';
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data,metadata)
  values(auth.uid(),'PROVIDER_APPLICATION_SUBMITTED','providers',provider_id::text,payload,jsonb_build_object('source','PROVIDER_ANDROID'));
  return jsonb_build_object('provider_id',provider_id,'status','PENDING_APPROVAL');
end; $$;

create or replace function public.provider_register_uploaded_media(target_provider_id uuid,target_menu_item_name text,media_kind public.media_type,
  object_path text,mime text,bytes bigint,alt_text_value text)
returns uuid language plpgsql security definer set search_path=public as $$
declare media_id uuid; item_id uuid;
begin
  if not public.is_provider_member(target_provider_id) then raise exception 'Not allowed'; end if;
  if target_menu_item_name is not null then select id into item_id from public.menu_items where provider_id=target_provider_id and name=target_menu_item_name; end if;
  if media_kind='MENU_ITEM' and item_id is null then raise exception 'Saved dish not found'; end if;
  insert into public.provider_media(provider_id,media_type,status,storage_bucket,storage_path,mime_type,size_bytes,alt_text,menu_item_id,is_primary,uploaded_by,submitted_at)
  values(target_provider_id,media_kind,'PENDING_REVIEW','provider-media',object_path,mime,bytes,alt_text_value,item_id,false,auth.uid(),now()) returning id into media_id;
  return media_id;
end; $$;

create or replace function public.provider_application_status()
returns jsonb language sql stable security definer set search_path=public as $$
  select coalesce((select jsonb_build_object('provider_id',p.id,'display_name',p.display_name,'status',p.status,
    'change_requests',coalesce((select jsonb_agg(jsonb_build_object('id',r.id,'type',r.request_type,'status',r.status,'reason',r.review_note,'requested_at',r.requested_at) order by r.requested_at desc)
      from public.provider_change_requests r where r.provider_id=p.id),'[]'::jsonb))
    from public.provider_members pm join public.providers p on p.id=pm.provider_id where pm.user_id=auth.uid() and pm.is_active order by pm.created_at desc limit 1),'{}'::jsonb);
$$;

create or replace function public.provider_resume_application()
returns jsonb language plpgsql security definer set search_path=public as $$
declare result jsonb; current_status public.provider_status;
begin
  select p.status into current_status from public.provider_members pm join public.providers p on p.id=pm.provider_id
  where pm.user_id=auth.uid() and pm.is_active order by pm.created_at desc limit 1;
  if current_status is distinct from 'DRAFT'::public.provider_status then raise exception 'Application is not open for editing'; end if;
  update public.provider_form_drafts set status='IN_PROGRESS',updated_at=now()
  where id=(select id from public.provider_form_drafts where owner_user_id=auth.uid() and form_scope='provider_mobile_onboarding' order by updated_at desc limit 1)
  returning payload into result;
  return coalesce(result,'{}'::jsonb);
end; $$;

revoke all on function public.submit_provider_mobile_application(jsonb),public.provider_register_uploaded_media(uuid,text,public.media_type,text,text,bigint,text),public.provider_application_status(),public.provider_resume_application() from public;
grant execute on function public.submit_provider_mobile_application(jsonb),public.provider_register_uploaded_media(uuid,text,public.media_type,text,text,bigint,text),public.provider_application_status(),public.provider_resume_application() to authenticated;
