-- Zomeal Phase 2 follow-up: minimum provider activation checklist.
-- Optional profile enrichment never blocks activation.

alter table public.providers
  add column contact_person_name text,
  add column business_address_line text,
  add column business_city text,
  add column business_state text,
  add column business_pincode text references public.pincodes(code);

alter table public.packages
  add column lunch_delivery_start time,
  add column lunch_delivery_end time,
  add column dinner_delivery_start time,
  add column dinner_delivery_end time,
  add constraint package_lunch_window_valid check (
    (lunch_delivery_start is null and lunch_delivery_end is null) or
    (lunch_delivery_start is not null and lunch_delivery_end is not null and lunch_delivery_end > lunch_delivery_start)
  ),
  add constraint package_dinner_window_valid check (
    (dinner_delivery_start is null and dinner_delivery_end is null) or
    (dinner_delivery_start is not null and dinner_delivery_end is not null and dinner_delivery_end > dinner_delivery_start)
  );

create or replace function public.provider_activation_check(target_provider_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = public
as $$
declare
  provider_record public.providers%rowtype;
  missing_requirements text[] := '{}';
  provider_package record;
  expected_slots public.meal_slot[];
  expected_slot public.meal_slot;
  covered_days integer;
begin
  if not (
    public.is_provider_member(target_provider_id) or
    public.has_role('ADMIN') or
    public.has_role('OPERATIONS')
  ) then
    raise exception 'Not authorized to inspect provider activation readiness';
  end if;

  select * into provider_record from public.providers where id = target_provider_id;
  if not found then
    raise exception 'Provider not found';
  end if;

  if nullif(trim(provider_record.display_name), '') is null then
    missing_requirements := array_append(missing_requirements, 'Provider display name');
  end if;
  if nullif(trim(provider_record.contact_person_name), '') is null then
    missing_requirements := array_append(missing_requirements, 'Contact person name');
  end if;
  if nullif(trim(coalesce(provider_record.support_phone, '')), '') is null then
    missing_requirements := array_append(missing_requirements, 'Contact mobile number');
  end if;
  if nullif(trim(provider_record.business_address_line), '') is null or
     nullif(trim(provider_record.business_city), '') is null or
     nullif(trim(provider_record.business_state), '') is null or
     provider_record.business_pincode is null then
    missing_requirements := array_append(missing_requirements, 'Basic business address');
  end if;
  if not exists (
    select 1 from public.provider_members
    where provider_id = target_provider_id and is_active
  ) then
    missing_requirements := array_append(missing_requirements, 'Authorized provider account');
  end if;
  if not exists (
    select 1 from public.provider_service_areas
    where provider_id = target_provider_id and status = 'APPROVED'
      and (effective_from is null or effective_from <= current_date)
      and (effective_until is null or effective_until >= current_date)
  ) then
    missing_requirements := array_append(missing_requirements, 'At least one approved serviceable pincode');
  end if;
  if not exists (select 1 from public.packages where provider_id = target_provider_id and is_active) then
    missing_requirements := array_append(missing_requirements, 'At least one active package');
  end if;

  for provider_package in
    select p.* from public.packages p where p.provider_id = target_provider_id and p.is_active
  loop
    if not exists (
      select 1 from public.package_price_versions ppv
      where ppv.package_id = provider_package.id and ppv.status = 'APPROVED'
        and ppv.effective_from <= now()
        and (ppv.effective_until is null or ppv.effective_until > now())
    ) then
      missing_requirements := array_append(missing_requirements, format('Approved current price for package: %s', provider_package.name));
    end if;

    if provider_package.kind = 'LUNCH_ONLY' then
      expected_slots := array['LUNCH'::public.meal_slot];
      if provider_package.lunch_delivery_start is null then
        missing_requirements := array_append(missing_requirements, format('Lunch delivery timing for package: %s', provider_package.name));
      end if;
    elsif provider_package.kind = 'DINNER_ONLY' then
      expected_slots := array['DINNER'::public.meal_slot];
      if provider_package.dinner_delivery_start is null then
        missing_requirements := array_append(missing_requirements, format('Dinner delivery timing for package: %s', provider_package.name));
      end if;
    else
      expected_slots := array['LUNCH'::public.meal_slot, 'DINNER'::public.meal_slot];
      if provider_package.lunch_delivery_start is null or provider_package.dinner_delivery_start is null then
        missing_requirements := array_append(missing_requirements, format('Lunch and dinner delivery timings for package: %s', provider_package.name));
      end if;
    end if;

    if not exists (
      select 1 from public.package_menus pm
      join public.provider_menus m on m.id = pm.menu_id
      where pm.package_id = provider_package.id and m.status = 'APPROVED'
        and pm.effective_from <= current_date
        and (pm.effective_until is null or pm.effective_until >= current_date)
        and m.valid_from <= current_date
        and (m.valid_until is null or m.valid_until >= current_date)
    ) then
      missing_requirements := array_append(missing_requirements, format('Approved current weekly menu for package: %s', provider_package.name));
      continue;
    end if;

    foreach expected_slot in array expected_slots loop
      select count(distinct md.day_of_week) into covered_days
      from public.package_menus pm
      join public.provider_menus m on m.id = pm.menu_id and m.status = 'APPROVED'
      join public.menu_days md on md.menu_id = m.id and md.meal_slot = expected_slot and md.is_available
      where pm.package_id = provider_package.id
        and pm.effective_from <= current_date
        and (pm.effective_until is null or pm.effective_until >= current_date)
        and m.valid_from <= current_date
        and (m.valid_until is null or m.valid_until >= current_date)
        and exists (
          select 1 from public.menu_day_choices mdc
          join public.menu_items mi on mi.id = mdc.menu_item_id
          where mdc.menu_day_id = md.id and mi.status = 'APPROVED'
        );

      if covered_days <> 7 then
        missing_requirements := array_append(
          missing_requirements,
          format('Complete Monday-Sunday %s menu for package: %s', lower(expected_slot::text), provider_package.name)
        );
      end if;
    end loop;
  end loop;

  return jsonb_build_object(
    'provider_id', target_provider_id,
    'ready', cardinality(missing_requirements) = 0,
    'missing_requirements', to_jsonb(missing_requirements)
  );
end;
$$;

create or replace function public.activate_provider(target_provider_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  readiness jsonb;
  previous_provider jsonb;
begin
  if not public.has_role('ADMIN') then
    raise exception 'Only a Zomeal administrator can activate a provider';
  end if;

  readiness := public.provider_activation_check(target_provider_id);
  if not coalesce((readiness ->> 'ready')::boolean, false) then
    raise exception 'Provider is not ready for activation: %', readiness -> 'missing_requirements';
  end if;

  select to_jsonb(p) into previous_provider from public.providers p where p.id = target_provider_id for update;
  update public.providers
  set status = 'ACTIVE', approved_by = auth.uid(), approved_at = now()
  where id = target_provider_id;

  insert into public.audit_logs(actor_id, action, entity_type, entity_id, before_data, after_data)
  select auth.uid(), 'PROVIDER_ACTIVATED', 'providers', p.id::text, previous_provider, to_jsonb(p)
  from public.providers p where p.id = target_provider_id;

  return readiness;
end;
$$;

revoke all on function public.provider_activation_check(uuid) from public;
revoke all on function public.activate_provider(uuid) from public;
grant execute on function public.provider_activation_check(uuid) to authenticated;
grant execute on function public.activate_provider(uuid) to authenticated;

create or replace view public.provider_profile_completion
with (security_invoker = true)
as
select
  p.id as provider_id,
  (
    (case when nullif(trim(coalesce(p.description, '')), '') is not null then 15 else 0 end) +
    (case when nullif(trim(coalesce(p.fssai_number, '')), '') is not null then 15 else 0 end) +
    (case when exists(select 1 from public.provider_media m where m.provider_id = p.id and m.status = 'APPROVED' and m.media_type = 'PROVIDER_LOGO') then 15 else 0 end) +
    (case when exists(select 1 from public.provider_media m where m.provider_id = p.id and m.status = 'APPROVED' and m.media_type in ('MEAL','MENU_ITEM','PACKAGE_COVER')) then 20 else 0 end) +
    (case when exists(select 1 from public.provider_media m where m.provider_id = p.id and m.status = 'APPROVED' and m.media_type in ('KITCHEN','PACKAGING')) then 10 else 0 end) +
    (case when exists(select 1 from public.menu_item_nutrition n join public.menu_items i on i.id = n.menu_item_id where i.provider_id = p.id) then 15 else 0 end) +
    (case when exists(select 1 from public.menu_item_allergens a join public.menu_items i on i.id = a.menu_item_id where i.provider_id = p.id) then 10 else 0 end)
  )::integer as completion_percent,
  not exists(select 1 from public.provider_media m where m.provider_id = p.id and m.status = 'APPROVED' and m.media_type = 'PROVIDER_LOGO') as needs_logo,
  not exists(select 1 from public.provider_media m where m.provider_id = p.id and m.status = 'APPROVED' and m.media_type in ('MEAL','MENU_ITEM','PACKAGE_COVER')) as needs_meal_photos,
  not exists(select 1 from public.menu_item_nutrition n join public.menu_items i on i.id = n.menu_item_id where i.provider_id = p.id) as needs_nutrition,
  not exists(select 1 from public.menu_item_allergens a join public.menu_items i on i.id = a.menu_item_id where i.provider_id = p.id) as needs_allergens
from public.providers p;

grant select on public.provider_profile_completion to authenticated;

comment on view public.provider_profile_completion is
'Optional enrichment score only. It does not determine whether a provider can be activated.';
