-- Activated-provider self-service catalogue management.
-- Approved customer-facing data remains live while replacement prices, menus
-- and photographs wait for Zomeal review.

alter table public.package_price_versions
  add column if not exists change_request_id uuid references public.provider_change_requests(id) on delete set null;
alter table public.provider_menus
  add column if not exists change_request_id uuid references public.provider_change_requests(id) on delete set null;
alter table public.provider_media
  add column if not exists change_request_id uuid references public.provider_change_requests(id) on delete set null;
create index if not exists package_price_change_request_idx on public.package_price_versions(change_request_id);
create index if not exists provider_menu_change_request_idx on public.provider_menus(change_request_id);
create index if not exists provider_media_change_request_idx on public.provider_media(change_request_id);

create or replace function public.provider_submit_business_update(payload jsonb)
returns jsonb language plpgsql security definer set search_path=public as $$
#variable_conflict use_column
<<business_update>>
declare
  target_provider_id uuid;
  change_id uuid;
  proposed_menu_id uuid;
  proposed_day_id uuid;
  saved_item_id uuid;
  saved_package_id uuid;
  package_version integer;
  package_row jsonb;
  day_row jsonb;
  dish_row jsonb;
  selected_slot public.meal_slot;
  selected_dishes jsonb;
  fixed_name text;
  first_choice boolean;
  total_paise bigint;
  lunch_paise bigint;
  dinner_paise bigint;
  required_lunch boolean := coalesce((payload->>'lunchEnabled')::boolean,false) or coalesce((payload->>'bothEnabled')::boolean,false);
  required_dinner boolean := coalesce((payload->>'dinnerEnabled')::boolean,false) or coalesce((payload->>'bothEnabled')::boolean,false);
begin
  select provider.id into target_provider_id
  from public.provider_members member
  join public.providers provider on provider.id=member.provider_id
  where member.user_id=auth.uid() and member.is_active and provider.status='ACTIVE'
  order by member.created_at desc limit 1;

  if target_provider_id is null then raise exception 'An active provider account is required'; end if;
  if nullif(trim(payload->>'businessName'),'') is null then raise exception 'Business name is required'; end if;
  if nullif(trim(payload->>'contactName'),'') is null then raise exception 'Contact person is required'; end if;
  if not (required_lunch or required_dinner) then raise exception 'Select at least one package'; end if;

  update public.provider_form_drafts
  set payload=$1,provider_id=target_provider_id,status='SUBMITTED',updated_at=now()
  where owner_user_id=auth.uid() and form_scope='provider_mobile_onboarding';

  -- A new full submission replaces the provider's older unreviewed draft so
  -- the admin never has two competing versions of the same listing.
  update public.provider_change_requests set status='CANCELLED',review_note='Superseded by a newer provider submission'
  where provider_id=target_provider_id and status='PENDING' and requested_payload->>'scope'='FULL_BUSINESS_UPDATE';
  update public.provider_media set status='ARCHIVED'
  where provider_id=target_provider_id and status='PENDING_REVIEW' and change_request_id in
    (select id from public.provider_change_requests where provider_id=target_provider_id and status='CANCELLED' and requested_payload->>'scope'='FULL_BUSINESS_UPDATE');

  insert into public.provider_change_requests(provider_id,request_type,status,requested_payload,reason,requested_by)
  values(target_provider_id,'PRICE_CHANGE','PENDING',
    jsonb_build_object('scope','FULL_BUSINESS_UPDATE','payload',payload),
    'Provider submitted profile, package, menu or photo changes from the partner app',auth.uid())
  returning id into change_id;

  -- Stage revised prices without ending the currently approved price.
  for package_row in select value from jsonb_array_elements(jsonb_build_array(
    jsonb_build_object('enabled',payload->'lunchEnabled','kind','LUNCH_ONLY','name','Lunch Only','price',payload->>'lunchPrice'),
    jsonb_build_object('enabled',payload->'dinnerEnabled','kind','DINNER_ONLY','name','Dinner Only','price',payload->>'dinnerPrice'),
    jsonb_build_object('enabled',payload->'bothEnabled','kind','LUNCH_AND_DINNER','name','Lunch + Dinner','price',payload->>'bothPrice'))) loop
    if coalesce((package_row->>'enabled')::boolean,false) then
      if nullif(package_row->>'price','') is null then raise exception 'Price required for %',package_row->>'name'; end if;
      select package.id into saved_package_id from public.packages package
      where package.provider_id=target_provider_id and package.kind=(package_row->>'kind')::public.package_kind
      order by package.is_active desc,package.created_at desc limit 1;
      if saved_package_id is null then
        insert into public.packages(provider_id,name,kind,dietary_type,duration_days,is_active)
        select target_provider_id,package_row->>'name',(package_row->>'kind')::public.package_kind,provider.dietary_type,30,false
        from public.providers provider where provider.id=target_provider_id returning id into saved_package_id;
      end if;
      total_paise:=round((package_row->>'price')::numeric*100);
      if package_row->>'kind'='LUNCH_ONLY' then lunch_paise:=total_paise; dinner_paise:=0;
      elsif package_row->>'kind'='DINNER_ONLY' then lunch_paise:=0; dinner_paise:=total_paise;
      else
        lunch_paise:=round(coalesce(nullif(payload->>'bothLunchDailyPrice','')::numeric,total_paise::numeric/6000)*3000);
        dinner_paise:=total_paise-lunch_paise;
      end if;
      select coalesce(max(version),0)+1 into package_version from public.package_price_versions where package_id=saved_package_id;
      delete from public.package_price_versions where package_id=saved_package_id and status='PENDING';
      insert into public.package_price_versions(package_id,version,total_price_paise,lunch_value_paise,dinner_value_paise,status,requested_by,change_request_id)
      values(saved_package_id,package_version,total_paise,lunch_paise,dinner_paise,'PENDING',auth.uid(),change_id);
      saved_package_id:=null;
    end if;
  end loop;

  -- Replace only an older unapproved proposal. The approved weekly menu remains live.
  delete from public.provider_menus
  where provider_id=target_provider_id and status in ('DRAFT','PENDING_REVIEW','REJECTED');
  insert into public.provider_menus(provider_id,name,description,status,valid_from,submitted_at,created_by,change_request_id)
  values(target_provider_id,'Proposed weekly menu '||to_char(clock_timestamp(),'YYYYMMDDHH24MISS'),
    'Provider app catalogue update','PENDING_REVIEW',current_date,now(),auth.uid(),change_id)
  returning id into proposed_menu_id;

  for day_row in select value from jsonb_array_elements(coalesce(payload->'menus','[]'::jsonb)) loop
    foreach selected_slot in array array['LUNCH'::public.meal_slot,'DINNER'::public.meal_slot] loop
      if (selected_slot='LUNCH' and required_lunch) or (selected_slot='DINNER' and required_dinner) then
        selected_dishes:=case selected_slot when 'LUNCH' then coalesce(day_row->'lunch','[]'::jsonb) else coalesce(day_row->'dinner','[]'::jsonb) end;
        if not exists(select 1 from jsonb_array_elements(selected_dishes) course where nullif(trim(course->>'name'),'') is not null) then
          raise exception '% menu is incomplete for %',initcap(lower(selected_slot::text)),day_row->>'day';
        end if;
        insert into public.menu_days(menu_id,day_of_week,meal_slot,is_available)
        values(proposed_menu_id,(select ordinality::smallint from unnest(array['Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday']) with ordinality weekday(name,ordinality) where weekday.name=day_row->>'day'),selected_slot,true)
        returning id into proposed_day_id;
        first_choice:=true;
        for dish_row in select value from jsonb_array_elements(selected_dishes) loop
          if nullif(trim(dish_row->>'name'),'') is not null then
            insert into public.menu_items(provider_id,name,description,category,dietary_type,status,created_by,submitted_at)
            values(target_provider_id,trim(dish_row->>'name'),nullif(trim(dish_row->>'description'),''),'MAIN_COURSE',
              case upper(replace(coalesce(dish_row->>'foodType','VEG'),'-','_')) when 'NON_VEG' then 'NON_VEG'::public.dietary_type when 'VEGAN' then 'VEGAN'::public.dietary_type else 'VEG'::public.dietary_type end,
              'PENDING_REVIEW',auth.uid(),now())
            -- Reuse an approved item without mutating its live details. Requested
            -- description/type remain in the request payload until admin approval.
            on conflict(provider_id,name) do update set updated_at=menu_items.updated_at
            returning id into saved_item_id;
            insert into public.menu_day_choices(menu_day_id,menu_item_id,choice_group,is_default,is_required,is_changeable)
            values(proposed_day_id,saved_item_id,'MAIN_COURSE',first_choice,true,true);
            first_choice:=false;
          end if;
        end loop;
        fixed_name:=case selected_slot when 'LUNCH' then day_row->>'lunchFixed' else day_row->>'dinnerFixed' end;
        if nullif(trim(fixed_name),'') is not null then
          insert into public.menu_items(provider_id,name,category,dietary_type,status,created_by,submitted_at)
          select target_provider_id,trim(fixed_name),'SIDE',provider.dietary_type,'PENDING_REVIEW',auth.uid(),now()
          from public.providers provider where provider.id=target_provider_id
          on conflict(provider_id,name) do update set updated_at=menu_items.updated_at
          returning id into saved_item_id;
          insert into public.menu_day_choices(menu_day_id,menu_item_id,choice_group,is_default,is_required,is_changeable)
          values(proposed_day_id,saved_item_id,'SIDE',true,true,false);
        end if;
      end if;
    end loop;
  end loop;

  -- Delivery contacts are operational data and can safely take effect immediately.
  if nullif(trim(payload->>'deliveryPhone'),'') is not null then
    update public.provider_delivery_personnel set is_primary=false
    where provider_id=target_provider_id and is_active;
    insert into public.provider_delivery_personnel(provider_id,full_name,phone,is_primary,is_active,created_by)
    values(target_provider_id,nullif(trim(payload->>'deliveryName'),''),trim(payload->>'deliveryPhone'),true,true,auth.uid())
    on conflict do nothing;
  end if;

  insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data,metadata)
  values(auth.uid(),'PROVIDER_BUSINESS_UPDATE_SUBMITTED','providers',target_provider_id::text,payload,
    jsonb_build_object('change_request_id',change_id,'source','PROVIDER_ANDROID'));
  return jsonb_build_object('provider_id',target_provider_id,'change_request_id',change_id,'status','PENDING_REVIEW');
end;
$$;

revoke all on function public.provider_submit_business_update(jsonb) from public;
grant execute on function public.provider_submit_business_update(jsonb) to authenticated;

create or replace function public.provider_register_change_media(target_change_request_id uuid,target_provider_id uuid,target_menu_item_name text,
  media_kind public.media_type,object_path text,mime text,bytes bigint,alt_text_value text)
returns uuid language plpgsql security definer set search_path=public as $$
declare saved_id uuid; saved_item uuid;
begin
  if not exists(select 1 from public.provider_change_requests request
    join public.provider_members member on member.provider_id=request.provider_id
    where request.id=target_change_request_id and request.provider_id=target_provider_id and request.status='PENDING'
      and member.user_id=auth.uid() and member.is_active) then
    raise exception 'This pending change request is not available to the signed-in provider';
  end if;
  if media_kind='MENU_ITEM' then
    select item.id into saved_item from public.menu_items item
    join public.menu_day_choices choice on choice.menu_item_id=item.id
    join public.menu_days day on day.id=choice.menu_day_id
    join public.provider_menus menu on menu.id=day.menu_id
    where menu.change_request_id=target_change_request_id and lower(item.name)=lower(trim(target_menu_item_name))
    order by item.created_at desc limit 1;
    if saved_item is null then raise exception 'Save the requested dish before uploading its photo'; end if;
  end if;
  insert into public.provider_media(provider_id,media_type,status,storage_bucket,storage_path,mime_type,size_bytes,alt_text,menu_item_id,
    is_primary,uploaded_by,submitted_at,change_request_id)
  values(target_provider_id,media_kind,'PENDING_REVIEW','provider-media',object_path,mime,bytes,nullif(trim(alt_text_value),''),saved_item,
    true,auth.uid(),now(),target_change_request_id) returning id into saved_id;
  return saved_id;
end;
$$;
revoke all on function public.provider_register_change_media(uuid,uuid,text,public.media_type,text,text,bigint,text) from public;
grant execute on function public.provider_register_change_media(uuid,uuid,text,public.media_type,text,text,bigint,text) to authenticated;
