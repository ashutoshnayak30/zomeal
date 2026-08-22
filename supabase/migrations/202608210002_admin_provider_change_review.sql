-- One audited review workflow for active-provider profile, package, menu and photo edits.

create or replace function public.admin_provider_change_queue(status_filter text default 'PENDING',search_text text default null)
returns jsonb language plpgsql stable security definer set search_path=public as $$
declare result jsonb;
begin
  if not (public.has_role('ADMIN') or public.has_role('OPERATIONS')) then raise exception 'Admin access is required'; end if;
  select coalesce(jsonb_agg(jsonb_build_object(
    'id',request.id,'provider_id',request.provider_id,'provider_name',provider.display_name,'status',request.status,
    'requested_at',request.requested_at,'reviewed_at',request.reviewed_at,'review_note',request.review_note,
    'scope',request.requested_payload->>'scope','photo_count',(select count(*) from public.provider_media media where media.change_request_id=request.id),
    'menu_item_count',(select count(*) from public.menu_day_choices choice join public.menu_days day on day.id=choice.menu_day_id join public.provider_menus menu on menu.id=day.menu_id where menu.change_request_id=request.id),
    'price_count',(select count(*) from public.package_price_versions price where price.change_request_id=request.id)
  ) order by request.requested_at asc),'[]'::jsonb) into result
  from public.provider_change_requests request join public.providers provider on provider.id=request.provider_id
  where request.requested_payload->>'scope'='FULL_BUSINESS_UPDATE'
    and (status_filter is null or status_filter='' or request.status::text=upper(status_filter))
    and (search_text is null or search_text='' or provider.display_name ilike '%'||search_text||'%');
  return result;
end; $$;

create or replace function public.admin_provider_change_detail(target_request uuid)
returns jsonb language plpgsql stable security definer set search_path=public as $$
declare result jsonb;
begin
  if not (public.has_role('ADMIN') or public.has_role('OPERATIONS')) then raise exception 'Admin access is required'; end if;
  select jsonb_build_object(
    'request',jsonb_build_object('id',request.id,'provider_id',request.provider_id,'status',request.status,'requested_at',request.requested_at,
      'reviewed_at',request.reviewed_at,'review_note',request.review_note,'payload',request.requested_payload->'payload'),
    'before',jsonb_build_object(
      'profile',jsonb_build_object('businessName',provider.display_name,'contactName',provider.contact_person_name,'address',provider.business_address_line,
        'city',provider.business_city,'state',provider.business_state,'pincode',provider.business_pincode,'dietaryType',provider.dietary_type),
      'packages',coalesce((select jsonb_agg(jsonb_build_object('id',package.id,'kind',package.kind,'name',package.name,'enabled',package.is_active,
        'price',price.total_price_paise/100.0,'lunchValue',price.lunch_value_paise/100.0,'dinnerValue',price.dinner_value_paise/100.0) order by package.kind)
        from public.packages package left join lateral(select * from public.package_price_versions version where version.package_id=package.id and version.status='APPROVED' and version.effective_until is null order by version.version desc limit 1) price on true
        where package.provider_id=provider.id),'[]'::jsonb),
      'menu',coalesce((select jsonb_agg(jsonb_build_object('day',day.day_of_week,'slot',day.meal_slot,'items',
        (select coalesce(jsonb_agg(jsonb_build_object('name',item.name,'description',item.description,'foodType',item.dietary_type,'category',item.category) order by choice.display_order,item.name),'[]'::jsonb)
         from public.menu_day_choices choice join public.menu_items item on item.id=choice.menu_item_id where choice.menu_day_id=day.id)) order by day.day_of_week,day.meal_slot)
        from public.menu_days day join public.provider_menus menu on menu.id=day.menu_id where menu.provider_id=provider.id and menu.status='APPROVED'),'[]'::jsonb),
      'photos',coalesce((select jsonb_agg(jsonb_build_object('id',media.id,'media_type',media.media_type,'storage_path',media.storage_path,'alt_text',media.alt_text,'menu_item_id',media.menu_item_id))
        from public.provider_media media where media.provider_id=provider.id and media.status='APPROVED'),'[]'::jsonb)
    ),
    'pending_photos',coalesce((select jsonb_agg(jsonb_build_object('id',media.id,'media_type',media.media_type,'storage_path',media.storage_path,
      'alt_text',media.alt_text,'menu_item_id',media.menu_item_id,'dish_name',item.name,'status',media.status) order by media.created_at)
      from public.provider_media media left join public.menu_items item on item.id=media.menu_item_id where media.change_request_id=request.id),'[]'::jsonb)
  ) into result
  from public.provider_change_requests request join public.providers provider on provider.id=request.provider_id where request.id=target_request;
  if result is null then raise exception 'Provider change request was not found'; end if;
  return result;
end; $$;

create or replace function public.admin_save_provider_change_request(target_request uuid,revised_payload jsonb,target_note text default null)
returns jsonb language plpgsql security definer set search_path=public as $$
declare provider_id_value uuid;
begin
  if not (public.has_role('ADMIN') or public.has_role('OPERATIONS')) then raise exception 'Admin access is required'; end if;
  if jsonb_typeof(revised_payload)<>'object' then raise exception 'A complete provider payload is required'; end if;
  update public.provider_change_requests set requested_payload=jsonb_build_object('scope','FULL_BUSINESS_UPDATE','payload',revised_payload),
    review_note=nullif(trim(target_note),''),reviewed_by=null,reviewed_at=null
  where id=target_request and status='PENDING' returning provider_id into provider_id_value;
  if provider_id_value is null then raise exception 'Only pending requests can be edited'; end if;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data,metadata)
  values(auth.uid(),'PROVIDER_CHANGE_ADMIN_EDIT','provider_change_requests',target_request::text,revised_payload,jsonb_build_object('provider_id',provider_id_value,'note',target_note));
  return jsonb_build_object('id',target_request,'status','PENDING','saved',true);
end; $$;

create or replace function public.admin_review_provider_business_update(target_request uuid,target_decision text,target_note text default null,revised_payload jsonb default null)
returns jsonb language plpgsql security definer set search_path=public as $$
declare request_row public.provider_change_requests; payload jsonb; decision text:=upper(trim(target_decision)); package_row jsonb; enabled_kinds text[]:=array[]::text[];
  day_row jsonb; slot_value public.meal_slot; dish_row record; photo_edit jsonb; target_day smallint; staged_item uuid; revised_total bigint; revised_lunch bigint;
begin
  if not (public.has_role('ADMIN') or public.has_role('OPERATIONS')) then raise exception 'Admin access is required'; end if;
  if decision not in ('APPROVED','REJECTED') then raise exception 'Decision must be APPROVED or REJECTED'; end if;
  if decision='REJECTED' and nullif(trim(target_note),'') is null then raise exception 'A rejection reason is required'; end if;
  select * into request_row from public.provider_change_requests where id=target_request for update;
  if request_row.id is null or request_row.status<>'PENDING' then raise exception 'This request is no longer pending'; end if;
  payload:=coalesce(revised_payload,request_row.requested_payload->'payload');

  if decision='REJECTED' then
    update public.package_price_versions set status='REJECTED' where change_request_id=target_request and status='PENDING';
    update public.provider_menus set status='REJECTED',reviewed_by=auth.uid(),reviewed_at=now(),rejection_reason=trim(target_note) where change_request_id=target_request and status='PENDING_REVIEW';
    update public.menu_items item set status='REJECTED',reviewed_by=auth.uid(),reviewed_at=now(),rejection_reason=trim(target_note)
      where item.status='PENDING_REVIEW' and exists(select 1 from public.menu_day_choices choice join public.menu_days day on day.id=choice.menu_day_id join public.provider_menus menu on menu.id=day.menu_id where choice.menu_item_id=item.id and menu.change_request_id=target_request);
    update public.provider_media set status='REJECTED',reviewed_by=auth.uid(),reviewed_at=now(),rejection_reason=trim(target_note) where change_request_id=target_request and status='PENDING_REVIEW';
  else
    update public.providers set display_name=trim(payload->>'businessName'),legal_name=trim(payload->>'businessName'),
      contact_person_name=trim(payload->>'contactName'),business_address_line=trim(payload->>'address'),
      business_city=coalesce(nullif(trim(payload->>'city'),''),business_city),business_state=coalesce(nullif(trim(payload->>'state'),''),business_state),
      business_pincode=coalesce(nullif(trim(payload->>'pincode'),''),business_pincode),
      dietary_type=case upper(replace(coalesce(payload->>'dietaryType',dietary_type::text),'-','_')) when 'NON_VEG' then 'NON_VEG'::public.dietary_type when 'VEGAN' then 'VEGAN'::public.dietary_type when 'BOTH' then 'BOTH'::public.dietary_type else 'VEG'::public.dietary_type end,
      updated_at=now() where id=request_row.provider_id;

    -- Recalculate staged prices from any admin-edited payload values.
    update public.package_price_versions price set total_price_paise=round((payload->>'lunchPrice')::numeric*100),
      lunch_value_paise=round((payload->>'lunchPrice')::numeric*100),dinner_value_paise=0
    from public.packages package where price.package_id=package.id and price.change_request_id=target_request and package.kind='LUNCH_ONLY' and nullif(payload->>'lunchPrice','') is not null;
    update public.package_price_versions price set total_price_paise=round((payload->>'dinnerPrice')::numeric*100),
      lunch_value_paise=0,dinner_value_paise=round((payload->>'dinnerPrice')::numeric*100)
    from public.packages package where price.package_id=package.id and price.change_request_id=target_request and package.kind='DINNER_ONLY' and nullif(payload->>'dinnerPrice','') is not null;
    revised_total:=round(coalesce(nullif(payload->>'bothPrice','')::numeric,0)*100);
    revised_lunch:=round(coalesce(nullif(payload->>'bothLunchDailyPrice','')::numeric,revised_total::numeric/6000)*3000);
    update public.package_price_versions price set total_price_paise=revised_total,lunch_value_paise=revised_lunch,dinner_value_paise=revised_total-revised_lunch
    from public.packages package where price.package_id=package.id and price.change_request_id=target_request and package.kind='LUNCH_AND_DINNER' and revised_total>0;

    -- Apply admin edits to the exact staged dishes before publishing the menu.
    for day_row in select value from jsonb_array_elements(coalesce(payload->'menus','[]'::jsonb)) loop
      select ordinality::smallint into target_day from unnest(array['Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday']) with ordinality weekday(name,ordinality) where weekday.name=day_row->>'day';
      foreach slot_value in array array['LUNCH'::public.meal_slot,'DINNER'::public.meal_slot] loop
        for dish_row in select value,ordinality from jsonb_array_elements(case slot_value when 'LUNCH' then coalesce(day_row->'lunch','[]'::jsonb) else coalesce(day_row->'dinner','[]'::jsonb) end) with ordinality loop
          select item.id into staged_item from public.provider_menus menu join public.menu_days menu_day on menu_day.menu_id=menu.id
          join public.menu_day_choices choice on choice.menu_day_id=menu_day.id join public.menu_items item on item.id=choice.menu_item_id
          where menu.change_request_id=target_request and menu_day.day_of_week=target_day and menu_day.meal_slot=slot_value and choice.choice_group='MAIN_COURSE'
          order by choice.display_order,item.created_at limit 1 offset greatest(dish_row.ordinality::integer-1,0);
          if staged_item is not null and nullif(trim(dish_row.value->>'name'),'') is not null then
            update public.menu_items set name=trim(dish_row.value->>'name'),description=nullif(trim(dish_row.value->>'description'),''),
              dietary_type=case upper(replace(coalesce(dish_row.value->>'foodType','VEG'),'-','_')) when 'NON_VEG' then 'NON_VEG'::public.dietary_type when 'VEGAN' then 'VEGAN'::public.dietary_type else 'VEG'::public.dietary_type end,updated_at=now()
            where id=staged_item;
          end if;
        end loop;
      end loop;
    end loop;

    for package_row in select * from jsonb_array_elements(jsonb_build_array(
      jsonb_build_object('enabled',payload->'lunchEnabled','kind','LUNCH_ONLY'),jsonb_build_object('enabled',payload->'dinnerEnabled','kind','DINNER_ONLY'),jsonb_build_object('enabled',payload->'bothEnabled','kind','LUNCH_AND_DINNER'))) loop
      if coalesce((package_row->>'enabled')::boolean,false) then enabled_kinds:=array_append(enabled_kinds,package_row->>'kind'); end if;
    end loop;
    update public.packages set is_active=(kind::text=any(enabled_kinds)),updated_at=now() where provider_id=request_row.provider_id;
    update public.package_price_versions current_price set effective_until=now()
      where current_price.status='APPROVED' and current_price.effective_until is null and exists(select 1 from public.package_price_versions pending where pending.change_request_id=target_request and pending.package_id=current_price.package_id);
    update public.package_price_versions set status='APPROVED',approved_by=auth.uid(),approved_at=now(),effective_from=now(),effective_until=null
      where change_request_id=target_request and status='PENDING';

    update public.provider_menus set status='ARCHIVED',valid_until=current_date,updated_at=now()
      where provider_id=request_row.provider_id and status='APPROVED' and id not in(select id from public.provider_menus where change_request_id=target_request);
    update public.provider_menus set status='APPROVED',reviewed_by=auth.uid(),reviewed_at=now(),rejection_reason=null,updated_at=now()
      where change_request_id=target_request;
    update public.menu_items item set status='APPROVED',reviewed_by=auth.uid(),reviewed_at=now(),rejection_reason=null,updated_at=now()
      where exists(select 1 from public.menu_day_choices choice join public.menu_days day on day.id=choice.menu_day_id join public.provider_menus menu on menu.id=day.menu_id where choice.menu_item_id=item.id and menu.change_request_id=target_request);
    for photo_edit in select value from jsonb_array_elements(coalesce(payload->'photoEdits','[]'::jsonb)) loop
      update public.provider_media set alt_text=nullif(trim(photo_edit->>'altText'),''),
        status=case when coalesce((photo_edit->>'publish')::boolean,true) then status else 'REJECTED'::public.media_status end,
        reviewed_by=case when coalesce((photo_edit->>'publish')::boolean,true) then reviewed_by else auth.uid() end,
        reviewed_at=case when coalesce((photo_edit->>'publish')::boolean,true) then reviewed_at else now() end,
        rejection_reason=case when coalesce((photo_edit->>'publish')::boolean,true) then rejection_reason else 'Excluded by Zomeal during change review' end,updated_at=now()
      where id=(photo_edit->>'id')::uuid and change_request_id=target_request;
    end loop;
    update public.provider_media old_media set status='ARCHIVED',updated_at=now()
      where old_media.provider_id=request_row.provider_id and old_media.status='APPROVED' and exists(select 1 from public.provider_media replacement
        where replacement.change_request_id=target_request and replacement.media_type=old_media.media_type and replacement.menu_item_id is not distinct from old_media.menu_item_id);
    update public.provider_media set status='APPROVED',reviewed_by=auth.uid(),reviewed_at=now(),rejection_reason=null,updated_at=now()
      where change_request_id=target_request and status='PENDING_REVIEW';
  end if;

  update public.provider_change_requests set status=decision::public.approval_status,requested_payload=jsonb_build_object('scope','FULL_BUSINESS_UPDATE','payload',payload),
    reviewed_by=auth.uid(),reviewed_at=now(),review_note=nullif(trim(target_note),'') where id=target_request;
  perform public.notify_provider_members(request_row.provider_id,'OPERATIONS',case when decision='APPROVED' then 'Your business changes are live' else 'Your business changes need attention' end,
    case when decision='APPROVED' then 'Zomeal approved your profile, package, menu and photo changes. Customers can now see the updated listing.' else trim(target_note) end,
    'PROFILE','PROVIDER_CHANGE_REQUEST',target_request::text);
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,before_data,after_data,metadata)
  values(auth.uid(),'PROVIDER_BUSINESS_UPDATE_'||decision,'provider_change_requests',target_request::text,request_row.requested_payload,
    jsonb_build_object('decision',decision,'payload',payload),jsonb_build_object('provider_id',request_row.provider_id,'review_note',target_note));
  return jsonb_build_object('id',target_request,'provider_id',request_row.provider_id,'status',decision,'customer_listing_updated',decision='APPROVED');
end; $$;

revoke all on function public.admin_provider_change_queue(text,text),public.admin_provider_change_detail(uuid),public.admin_save_provider_change_request(uuid,jsonb,text),public.admin_review_provider_business_update(uuid,text,text,jsonb) from public;
grant execute on function public.admin_provider_change_queue(text,text),public.admin_provider_change_detail(uuid),public.admin_save_provider_change_request(uuid,jsonb,text),public.admin_review_provider_business_update(uuid,text,text,jsonb) to authenticated;
