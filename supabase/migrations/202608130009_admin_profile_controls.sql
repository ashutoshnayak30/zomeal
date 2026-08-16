-- Admin profile controls: protect provider-owned identity/financial fields while
-- allowing staff to manage the complete operational catalogue.

create or replace function public.protect_provider_verified_phone()
returns trigger language plpgsql set search_path=public as $$
begin
  if old.support_phone is distinct from new.support_phone and
     (public.has_role('ADMIN') or public.has_role('OPERATIONS')) then
    raise exception 'Provider verified phone number cannot be changed by an administrator';
  end if;
  return new;
end; $$;
drop trigger if exists providers_protect_verified_phone on public.providers;
create trigger providers_protect_verified_phone before update of support_phone on public.providers
for each row execute function public.protect_provider_verified_phone();

-- Bank details remain provider/finance owned. If the payout table is introduced
-- later, its admin write policies must exclude ordinary ADMIN/OPERATIONS roles.

create or replace function public.admin_replace_provider_menu(target_provider_id uuid,menu_payload jsonb)
returns uuid language plpgsql security definer set search_path=public as $$
declare menu_id uuid; menu_row jsonb; menu_day_id uuid; item_id uuid;
begin
  perform public.require_staff(array['ADMIN','OPERATIONS']::public.app_role[]);
  if jsonb_array_length(coalesce(menu_payload,'[]'::jsonb))=0 then raise exception 'Weekly menu is required'; end if;
  delete from public.provider_menus where provider_id=target_provider_id and status in ('DRAFT','PENDING_REVIEW','REJECTED');
  insert into public.provider_menus(provider_id,name,status,valid_from,submitted_at,created_by)
  values(target_provider_id,'Standard weekly menu','PENDING_REVIEW',current_date,now(),auth.uid()) returning id into menu_id;
  for menu_row in select value from jsonb_array_elements(menu_payload) loop
    if nullif(trim(menu_row->>'main_course'),'') is null then
      raise exception 'Main course is required for day % %',menu_row->>'day',menu_row->>'meal_slot';
    end if;
    insert into public.menu_days(menu_id,day_of_week,meal_slot,is_available)
    values(menu_id,(menu_row->>'day')::smallint,(menu_row->>'meal_slot')::public.meal_slot,true) returning id into menu_day_id;
    insert into public.menu_items(provider_id,name,category,dietary_type,status,created_by,submitted_at)
    select target_provider_id,menu_row->>'main_course','MAIN_COURSE',p.dietary_type,'PENDING_REVIEW',auth.uid(),now() from public.providers p where p.id=target_provider_id
    on conflict(provider_id,name) do update set status='PENDING_REVIEW',submitted_at=now() returning id into item_id;
    insert into public.menu_day_choices(menu_day_id,menu_item_id,choice_group,is_default,is_required,is_changeable)
    values(menu_day_id,item_id,'MAIN_COURSE',true,true,true);
    if nullif(trim(menu_row->>'fixed_items'),'') is not null then
      insert into public.menu_items(provider_id,name,category,dietary_type,status,created_by,submitted_at)
      select target_provider_id,menu_row->>'fixed_items','SIDE',p.dietary_type,'PENDING_REVIEW',auth.uid(),now() from public.providers p where p.id=target_provider_id
      on conflict(provider_id,name) do update set status='PENDING_REVIEW',submitted_at=now() returning id into item_id;
      insert into public.menu_day_choices(menu_day_id,menu_item_id,choice_group,is_default,is_required,is_changeable)
      values(menu_day_id,item_id,'SIDE',true,true,false);
    end if;
  end loop;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data)
  values(auth.uid(),'WEEKLY_MENU_REPLACED_BY_ADMIN','provider_menus',menu_id::text,menu_payload);
  return menu_id;
end; $$;

revoke all on function public.admin_replace_provider_menu(uuid,jsonb) from public;
grant execute on function public.admin_replace_provider_menu(uuid,jsonb) to authenticated;

