-- A request being approved is not proof that its menu is still the live menu.
-- Validate the replacement before the old approval workflow archives anything.
create function public.checked_request_menu(target_request uuid,approved_items_only boolean default false)
returns uuid language plpgsql stable security definer set search_path=public as $$
declare r provider_change_requests; m uuid; payload jsonb; slot meal_slot; needs boolean; covered integer;
begin
 select * into r from provider_change_requests where id=target_request;
 if r.id is null then raise exception 'Change request not found'; end if;
 if exists(select 1 from provider_change_requests newer where newer.provider_id=r.provider_id
  and newer.status='APPROVED' and (newer.requested_at,newer.id)>(r.requested_at,r.id)
  and exists(select 1 from provider_menus pm join menu_days d on d.menu_id=pm.id join menu_day_choices c on c.menu_day_id=d.id where pm.change_request_id=newer.id)) then
  raise exception 'This is an older request. Open the latest approved menu for this kitchen.';
 end if;
 select id into m from provider_menus where change_request_id=r.id and status in ('PENDING_REVIEW','APPROVED','ARCHIVED') order by created_at desc,id limit 1;
 if m is null then raise exception 'This request has no saved menu. Ask the provider to resubmit the complete seven-day menu. The live menu was not changed.'; end if;
 payload:=r.requested_payload->'payload';
 if not(coalesce((payload->>'lunchEnabled')::boolean,false) or coalesce((payload->>'dinnerEnabled')::boolean,false) or coalesce((payload->>'bothEnabled')::boolean,false)) then
  raise exception 'Select at least one meal package before publishing';
 end if;
 foreach slot in array array['LUNCH','DINNER']::meal_slot[] loop
  needs:=coalesce((payload->>'bothEnabled')::boolean,false) or coalesce((payload->>(lower(slot::text)||'Enabled'))::boolean,false);
  if needs then
   select count(distinct d.day_of_week) into covered from menu_days d where d.menu_id=m and d.meal_slot=slot and d.is_available
   and exists(select 1 from menu_day_choices c join menu_items i on i.id=c.menu_item_id where c.menu_day_id=d.id
    and c.choice_group='MAIN_COURSE' and i.provider_id=r.provider_id and nullif(trim(i.name),'') is not null
    and (i.status='APPROVED' or (not approved_items_only and i.status='PENDING_REVIEW')));
   if covered<>7 then raise exception '% menu has % of 7 complete days. Resubmit a complete menu; the live menu was not changed.',slot,covered; end if;
  end if;
 end loop;
 return m;
end $$;
revoke all on function public.checked_request_menu(uuid,boolean) from public,anon,authenticated;

alter function public.admin_review_provider_business_update(uuid,text,text,jsonb) rename to admin_review_provider_business_update_before_publication_guard;
revoke all on function public.admin_review_provider_business_update_before_publication_guard(uuid,text,text,jsonb) from public,anon,authenticated;
create function public.admin_review_provider_business_update(target_request uuid,target_decision text,target_note text default null,revised_payload jsonb default null)
returns jsonb language plpgsql security definer set search_path=public as $$
declare result jsonb; r provider_change_requests; m uuid;
begin
 if not(has_role('ADMIN') or has_role('OPERATIONS')) then raise exception 'Admin access required'; end if;
 select * into r from provider_change_requests where id=target_request;
 perform 1 from providers where id=r.provider_id for update;
 perform 1 from provider_change_requests where id=target_request for update;
 if upper(trim(target_decision))='APPROVED' then
  -- Validation uses the same final package selections as the review below.
  if revised_payload is not null and r.status='PENDING' then
   update provider_change_requests set requested_payload=jsonb_build_object('scope','FULL_BUSINESS_UPDATE','payload',revised_payload) where id=target_request;
  end if;
  m:=checked_request_menu(target_request,false);
 end if;
 result:=admin_review_provider_business_update_before_publication_guard(target_request,target_decision,target_note,revised_payload);
 if upper(trim(target_decision))='APPROVED' then
  perform checked_request_menu(target_request,true);
  if not exists(select 1 from provider_menus where id=m and status='APPROVED' and valid_from<=current_date and (valid_until is null or valid_until>=current_date)) then
   raise exception 'Publication failed: approved menu is not live. No changes were saved.';
  end if;
 end if;
 return result||jsonb_build_object('menu_live',upper(trim(target_decision))='APPROVED');
end $$;
revoke all on function public.admin_review_provider_business_update(uuid,text,text,jsonb) from public,anon;
grant execute on function public.admin_review_provider_business_update(uuid,text,text,jsonb) to authenticated;

-- Explicit, audited repair of a previously approved request; never approve a
-- pending request or roll back to an older revision through the repair button.
create or replace function public.admin_menu_sync(target_request uuid,apply_changes boolean default false) returns jsonb
language plpgsql security definer set search_path=public as $$
declare r provider_change_requests; m provider_menus; target uuid; result jsonb; repair boolean;
begin
 if not(has_role('ADMIN') or has_role('OPERATIONS')) then raise exception 'Admin access required'; end if;
 select * into r from provider_change_requests where id=target_request;
 if r.id is null then raise exception 'Request not found'; end if;
 perform 1 from providers where id=r.provider_id for update;
 if apply_changes and r.status<>'APPROVED' then raise exception 'Approve the request before syncing'; end if;
 target:=checked_request_menu(target_request,r.status='APPROVED');
 select * into m from provider_menus where id=target for update;
 repair:=r.status='APPROVED' and (m.status<>'APPROVED' or m.valid_until is not null);
 if m.valid_from>current_date then raise exception 'Future menus cannot be published early'; end if;
 if repair and not apply_changes then
  return jsonb_build_object('requires_repair',true,'customers',null,'changes','[]'::jsonb,'conflicts','[]'::jsonb,
   'message','This approved menu is not continuously live. Publish & sync will restore it and update eligible customer meals atomically.');
 end if;
 if apply_changes then
  update provider_menus set status='ARCHIVED',valid_until=greatest(valid_from,current_date),updated_at=now()
   where provider_id=r.provider_id and id<>target and status='APPROVED';
  update provider_menus set status='APPROVED',valid_until=null,updated_at=now() where id=target;
 end if;
 result:=sync_customer_menu(target,target_request::text,apply_changes);
 if apply_changes then
  insert into audit_logs(actor_id,action,entity_type,entity_id,before_data,after_data)
   values(auth.uid(),'APPROVED_MENU_PUBLISHED_AND_SYNCED','provider_menus',target::text,to_jsonb(m),result);
 end if;
 return result||jsonb_build_object('menu_live',apply_changes or (m.status='APPROVED' and not repair),'repaired',repair and apply_changes);
end $$;
revoke all on function public.admin_menu_sync(uuid,boolean) from public,anon;
grant execute on function public.admin_menu_sync(uuid,boolean) to authenticated;

alter function public.admin_provider_change_detail(uuid) rename to admin_provider_change_detail_before_publication_state;
revoke all on function public.admin_provider_change_detail_before_publication_state(uuid) from public,anon,authenticated;
create function public.admin_provider_change_detail(target_request uuid) returns jsonb
language plpgsql stable security definer set search_path=public as $$
declare result jsonb; m uuid; issue text; live boolean:=false;
begin
 result:=admin_provider_change_detail_before_publication_state(target_request);
 begin
  m:=checked_request_menu(target_request,result->'request'->>'status'='APPROVED');
  select status='APPROVED' and valid_from<=current_date and (valid_until is null or valid_until>=current_date) into live from provider_menus where id=m;
 exception when others then issue:=sqlerrm;
 end;
 return result||jsonb_build_object('publication',jsonb_build_object('menu_id',m,'live',live,'issue',issue,'can_sync',issue is null and result->'request'->>'status'='APPROVED'));
end $$;
revoke all on function public.admin_provider_change_detail(uuid) from public,anon;
grant execute on function public.admin_provider_change_detail(uuid) to authenticated;
notify pgrst,'reload schema';
-- A single provider-facing read model for the Profile page. Only the signed-in
-- provider's approved customer-visible catalogue is returned.
create or replace function public.provider_profile_hub()
returns jsonb language plpgsql stable security definer set search_path=public as $$
declare target_provider uuid; result jsonb;
begin
  select member.provider_id into target_provider from public.provider_members member
  where member.user_id=auth.uid() and member.is_active order by member.created_at desc limit 1;
  if target_provider is null then raise exception 'Provider membership was not found'; end if;

  select jsonb_build_object(
    'provider_id',p.id,'provider_name',p.display_name,'contact_name',p.contact_person_name,
    'status',p.status,'category',p.dietary_type,'description',p.description,
    'city',p.business_city,'state',p.business_state,'pincode',p.business_pincode,'address',p.business_address_line,
    'profile_photo_path',coalesce((select m.storage_path from public.provider_media m where m.provider_id=p.id and m.media_type='OWNER_PROFILE' and m.status='APPROVED' order by m.is_primary desc,m.reviewed_at desc nulls last limit 1),''),
    'kitchen_photo_path',coalesce((select m.storage_path from public.provider_media m where m.provider_id=p.id and m.media_type='KITCHEN' and m.status='APPROVED' order by m.is_primary desc,m.reviewed_at desc nulls last limit 1),''),
    'meal_photo_path',coalesce((select m.storage_path from public.provider_media m where m.provider_id=p.id and m.media_type='MEAL' and m.status='APPROVED' order by m.is_primary desc,m.reviewed_at desc nulls last limit 1),''),
    'active_subscribers',(select count(*) from public.customer_subscriptions s where s.provider_id=p.id and s.status in('ACTIVE','PAUSED','CANCEL_PENDING') and s.end_date>=current_date),
    'active_packages',(select count(*) from public.packages package where package.provider_id=p.id and package.is_active),
    'serviceable_pincodes',(select count(*) from public.provider_service_areas area where area.provider_id=p.id and area.status='APPROVED'),
    'pending_change_requests',(select count(*) from public.provider_change_requests r where r.provider_id=p.id and r.status='PENDING' and r.requested_payload->>'scope'='FULL_BUSINESS_UPDATE'),
    'packages',coalesce((select jsonb_agg(jsonb_build_object('id',package.id,'name',package.name,'kind',package.kind,'description',package.description,'price_paise',price.total_price_paise,'duration_days',package.duration_days) order by package.kind)
      from public.packages package join lateral(select v.total_price_paise from public.package_price_versions v where v.package_id=package.id and v.status='APPROVED' and v.effective_until is null order by v.version desc limit 1) price on true
      where package.provider_id=p.id and package.is_active),'[]'::jsonb),
    'weekly_menu',coalesce((select jsonb_agg(jsonb_build_object('day_of_week',rows.day_of_week,'meal_slot',rows.meal_slot,'items',rows.items) order by rows.day_of_week,rows.meal_slot)
      from(select day.day_of_week,day.meal_slot::text,jsonb_agg(jsonb_build_object('id',item.id,'name',item.name,'category',item.category,'dietary_type',item.dietary_type,'description',item.description,'photo_path',coalesce(media.storage_path,'')) order by choice.display_order,item.name) items
        from public.provider_menus menu join public.menu_days day on day.menu_id=menu.id and day.is_available
        join public.menu_day_choices choice on choice.menu_day_id=day.id join public.menu_items item on item.id=choice.menu_item_id and item.status='APPROVED'
        left join lateral(select m.storage_path from public.provider_media m where m.menu_item_id=item.id and m.media_type='MENU_ITEM' and m.status='APPROVED' order by m.is_primary desc,m.reviewed_at desc nulls last limit 1) media on true
        where menu.provider_id=p.id and menu.id=current_side_menu(p.id,current_date) group by day.day_of_week,day.meal_slot) rows),'[]'::jsonb)
  ) into result from public.providers p where p.id=target_provider;
  return coalesce(result,'{}'::jsonb);
end; $$;

revoke all on function public.provider_profile_hub() from public;
grant execute on function public.provider_profile_hub() to authenticated;
notify pgrst,'reload schema';
