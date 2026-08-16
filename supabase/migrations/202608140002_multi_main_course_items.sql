-- Store multiple main-course alternatives as independent menu items.
create or replace function public.admin_replace_provider_menu(target_provider_id uuid,menu_payload jsonb)
returns uuid language plpgsql security definer set search_path=public as $$
declare menu_id uuid; menu_row jsonb; course jsonb; new_day_id uuid; item_id uuid; courses jsonb; first_course boolean;
begin
  perform public.require_staff(array['ADMIN','OPERATIONS']::public.app_role[]);
  if jsonb_array_length(coalesce(menu_payload,'[]'::jsonb))=0 then raise exception 'Weekly menu is required'; end if;
  delete from public.provider_menus where provider_id=target_provider_id and status in ('DRAFT','PENDING_REVIEW','REJECTED');
  insert into public.provider_menus(provider_id,name,status,valid_from,submitted_at,created_by)
  values(target_provider_id,'Standard weekly menu','PENDING_REVIEW',current_date,now(),auth.uid()) returning id into menu_id;
  for menu_row in select value from jsonb_array_elements(menu_payload) loop
    courses:=coalesce(menu_row->'main_courses',case when nullif(trim(menu_row->>'main_course'),'') is not null then jsonb_build_array(jsonb_build_object('name',menu_row->>'main_course')) else '[]'::jsonb end);
    if jsonb_array_length(courses)=0 then raise exception 'A main course is required for day % %',menu_row->>'day',menu_row->>'meal_slot'; end if;
    insert into public.menu_days(menu_id,day_of_week,meal_slot,is_available)
    values(menu_id,(menu_row->>'day')::smallint,(menu_row->>'meal_slot')::public.meal_slot,true) returning id into new_day_id;
    first_course:=true;
    for course in select value from jsonb_array_elements(courses) loop
      if nullif(trim(course->>'name'),'') is null then raise exception 'Main-course name cannot be empty'; end if;
      insert into public.menu_items(provider_id,name,category,dietary_type,status,created_by,submitted_at)
      select target_provider_id,course->>'name','MAIN_COURSE',p.dietary_type,'PENDING_REVIEW',auth.uid(),now() from public.providers p where p.id=target_provider_id
      on conflict(provider_id,name) do update set status='PENDING_REVIEW',submitted_at=now() returning id into item_id;
      insert into public.menu_day_choices(menu_day_id,menu_item_id,choice_group,is_default,is_required,is_changeable)
      values(new_day_id,item_id,'MAIN_COURSE',first_course,true,true);
      first_course:=false;
    end loop;
    if nullif(trim(menu_row->>'fixed_items'),'') is not null then
      insert into public.menu_items(provider_id,name,category,dietary_type,status,created_by,submitted_at)
      select target_provider_id,menu_row->>'fixed_items','SIDE',p.dietary_type,'PENDING_REVIEW',auth.uid(),now() from public.providers p where p.id=target_provider_id
      on conflict(provider_id,name) do update set status='PENDING_REVIEW',submitted_at=now() returning id into item_id;
      insert into public.menu_day_choices(menu_day_id,menu_item_id,choice_group,is_default,is_required,is_changeable)
      values(new_day_id,item_id,'SIDE',true,true,false);
    end if;
  end loop;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data)
  values(auth.uid(),'MULTI_ITEM_WEEKLY_MENU_REPLACED','provider_menus',menu_id::text,menu_payload);
  return menu_id;
end; $$;
revoke all on function public.admin_replace_provider_menu(uuid,jsonb) from public;
grant execute on function public.admin_replace_provider_menu(uuid,jsonb) to authenticated;
