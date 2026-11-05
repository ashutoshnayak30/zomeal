-- Reconcile from the reviewed payload, not unstable ordinal positions in rows
-- left behind by the legacy duplicate-name merge trigger.
create function public.reconcile_request_main_courses(target_request uuid) returns integer
language plpgsql security definer set search_path=public as $$
declare r provider_change_requests; m uuid; day_row jsonb; course record; slot meal_slot;
 weekday integer; md uuid; item menu_items; item_id uuid; diet dietary_type;
 expected uuid[]; restored integer:=0; affected integer; dish_name text;
begin
 select * into r from provider_change_requests where id=target_request;
 if r.status not in ('PENDING','APPROVED') or r.id is null then raise exception 'Only pending or approved requests can reconcile menu choices'; end if;
 perform 1 from providers where id=r.provider_id for update;
 select id into m from provider_menus where change_request_id=r.id and status in ('PENDING_REVIEW','APPROVED','ARCHIVED') order by created_at desc,id limit 1;
 if m is null then raise exception 'This request has no saved menu. Open the latest complete request.'; end if;
 for day_row in select value from jsonb_array_elements(coalesce(r.requested_payload->'payload'->'menus','[]')) loop
  weekday:=array_position(array['Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday'],day_row->>'day');
  if weekday is null then raise exception 'Invalid weekday in saved request'; end if;
  foreach slot in array array['LUNCH','DINNER']::meal_slot[] loop
   select id into md from menu_days where menu_id=m and day_of_week=weekday and meal_slot=slot;
   if md is null then continue; end if;
   expected:=array[]::uuid[];
   for course in select value,ordinality from jsonb_array_elements(coalesce(day_row->lower(slot::text),'[]')) with ordinality loop
    dish_name:=nullif(trim(course.value->>'name'),'');
    if dish_name is null then continue; end if;
    diet:=upper(replace(coalesce(course.value->>'foodType','VEG'),'-','_'))::dietary_type;
    if diet='BOTH' then raise exception 'Main courses need an explicit dietary category'; end if;
    insert into menu_items(provider_id,name,description,category,dietary_type,status,created_by,submitted_at)
     values(r.provider_id,dish_name,nullif(trim(course.value->>'description'),''),'MAIN_COURSE',diet,'PENDING_REVIEW',r.requested_by,r.requested_at)
     on conflict(provider_id,name) do nothing;
    select * into item from menu_items where provider_id=r.provider_id and name=dish_name;
    if item.category<>'MAIN_COURSE' or item.dietary_type<>diet then
     raise exception 'Dish % conflicts with its saved dietary category. Use a distinct dish name; historical dishes cannot be relabelled.',dish_name;
    end if;
    if item.status='REJECTED' then raise exception 'Dish % was rejected. Submit a reviewed replacement.',dish_name; end if;
    item_id:=item.id;
    expected:=array_append(expected,item_id);
    insert into menu_day_choices(menu_day_id,menu_item_id,choice_group,is_default,is_required,is_changeable,display_order)
     values(md,item_id,'MAIN_COURSE',course.ordinality=1,true,true,course.ordinality-1)
     on conflict(menu_day_id,menu_item_id) do nothing;
    get diagnostics affected=row_count;restored:=restored+affected;
    update menu_day_choices set display_order=course.ordinality-1,is_default=course.ordinality=1 where menu_day_id=md and menu_item_id=item_id;
    if r.status='APPROVED' then
     update menu_items set status='APPROVED',reviewed_by=r.reviewed_by,reviewed_at=r.reviewed_at,updated_at=now() where id=item_id and status<>'APPROVED';
    end if;
    -- Exact reviewed dish labels identify photographs miswired by the old merge.
    -- Never publish rejected/excluded photos, or infer a match from a file path.
    update provider_media set menu_item_id=item_id,updated_at=now(),
     status=case when r.status='APPROVED' and status='ARCHIVED' then 'APPROVED'::media_status else status end
     where change_request_id=r.id and provider_id=r.provider_id and media_type='MENU_ITEM'
      and trim(alt_text)=dish_name and status in ('PENDING_REVIEW','APPROVED','ARCHIVED')
      and not exists(select 1 from jsonb_array_elements(coalesce(r.requested_payload->'payload'->'photoEdits','[]')) e
       where e->>'id'=provider_media.id::text and e->>'publish'='false');
   end loop;
   if r.status='PENDING' then
    delete from menu_day_choices where menu_day_id=md and choice_group='MAIN_COURSE' and not(menu_item_id=any(expected));
   elsif exists(select 1 from menu_day_choices where menu_day_id=md and choice_group='MAIN_COURSE' and not(menu_item_id=any(expected))) then
    raise exception 'Approved menu has unexpected dishes. Review the saved payload before repair.';
   end if;
  end loop;
 end loop;
 perform checked_request_menu(target_request,r.status='APPROVED');
 return restored;
end $$;
revoke all on function public.reconcile_request_main_courses(uuid) from public,anon,authenticated;

alter function public.admin_review_provider_business_update(uuid,text,text,jsonb) rename to admin_review_provider_business_update_before_choice_reconcile;
revoke all on function public.admin_review_provider_business_update_before_choice_reconcile(uuid,text,text,jsonb) from public,anon,authenticated;
create function public.admin_review_provider_business_update(target_request uuid,target_decision text,target_note text default null,revised_payload jsonb default null)
returns jsonb language plpgsql security definer set search_path=public as $$
declare r provider_change_requests;
begin
 if not(has_role('ADMIN') or has_role('OPERATIONS')) then raise exception 'Admin access required'; end if;
 select * into r from provider_change_requests where id=target_request;
 perform 1 from providers where id=r.provider_id for update;
 perform 1 from provider_change_requests where id=target_request for update;
 if r.status<>'PENDING' then raise exception 'This request is no longer pending'; end if;
 if upper(trim(target_decision))='APPROVED' then
  if revised_payload is not null then update provider_change_requests set requested_payload=jsonb_build_object('scope','FULL_BUSINESS_UPDATE','payload',revised_payload) where id=target_request; end if;
  perform reconcile_request_main_courses(target_request);
 end if;
 return admin_review_provider_business_update_before_choice_reconcile(target_request,target_decision,target_note,revised_payload);
end $$;
revoke all on function public.admin_review_provider_business_update(uuid,text,text,jsonb) from public,anon;
grant execute on function public.admin_review_provider_business_update(uuid,text,text,jsonb) to authenticated;

alter function public.admin_menu_sync(uuid,boolean) rename to admin_menu_sync_before_choice_reconcile;
revoke all on function public.admin_menu_sync_before_choice_reconcile(uuid,boolean) from public,anon,authenticated;
create function public.admin_menu_sync(target_request uuid,apply_changes boolean default false) returns jsonb
language plpgsql security definer set search_path=public as $$
declare result jsonb; restored integer;
begin
 if not(has_role('ADMIN') or has_role('OPERATIONS')) then raise exception 'Admin access required'; end if;
 if apply_changes then
  if not exists(select 1 from provider_change_requests where id=target_request and status='APPROVED') then raise exception 'Approve the request before syncing'; end if;
  restored:=reconcile_request_main_courses(target_request);
  result:=admin_menu_sync_before_choice_reconcile(target_request,true);
 else
  begin
   restored:=reconcile_request_main_courses(target_request);
   result:=admin_menu_sync_before_choice_reconcile(target_request,false);
   raise sqlstate 'ZX002' using message='Rollback preview-only choice reconciliation';
  exception when sqlstate 'ZX002' then null;
  end;
 end if;
 return result||jsonb_build_object('restored_alternatives',restored);
end $$;
revoke all on function public.admin_menu_sync(uuid,boolean) from public,anon;
grant execute on function public.admin_menu_sync(uuid,boolean) to authenticated;
notify pgrst,'reload schema';
