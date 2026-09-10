-- Active providers can propose additional delivery pincodes from Manage Business.
-- Existing approved areas stay live while the complete change request is pending.

create or replace function public.provider_approved_service_areas()
returns jsonb
language sql
stable
security definer
set search_path=public
as $$
  select jsonb_build_object('servicePincodes',coalesce(jsonb_agg(jsonb_build_object(
    'value',area.pincode,
    'areaName',coalesce(pin.locality,pin.city),
    'verified',true,
    'status',area.status
  ) order by area.pincode),'[]'::jsonb))
  from public.provider_members member
  join public.provider_service_areas area on area.provider_id=member.provider_id and area.status='APPROVED'
  join public.pincodes pin on pin.code=area.pincode
  where member.user_id=auth.uid() and member.is_active;
$$;

create or replace function public.admin_provider_change_service_areas(target_request uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path=public
as $$
declare result jsonb;
begin
  if not (public.has_role('ADMIN') or public.has_role('OPERATIONS')) then
    raise exception 'Admin access is required';
  end if;
  select jsonb_build_object('service_areas',coalesce(jsonb_agg(jsonb_build_object(
    'pincode',area.pincode,
    'area_name',coalesce(pin.locality,pin.city),
    'status',area.status
  ) order by area.pincode),'[]'::jsonb))
  into result
  from public.provider_change_requests request
  join public.provider_service_areas area on area.provider_id=request.provider_id and area.status='APPROVED'
  join public.pincodes pin on pin.code=area.pincode
  where request.id=target_request;
  return result;
end;
$$;

create or replace function public.apply_approved_provider_service_area_change()
returns trigger
language plpgsql
security definer
set search_path=public
as $$
declare
  payload jsonb;
  pin jsonb;
  requested_count integer := 0;
  radius_value numeric;
  lunch_capacity integer;
  dinner_capacity integer;
begin
  if new.status <> 'APPROVED' or old.status = 'APPROVED'
     or new.requested_payload->>'scope' <> 'FULL_BUSINESS_UPDATE' then
    return new;
  end if;

  payload := new.requested_payload->'payload';
  if jsonb_typeof(payload->'servicePincodes') is distinct from 'array' then return new; end if;
  radius_value := greatest(coalesce(nullif(payload->>'radius','')::numeric,5),0.1);
  lunch_capacity := greatest(coalesce(nullif(payload->>'lunchCapacity','')::integer,0),0);
  dinner_capacity := greatest(coalesce(nullif(payload->>'dinnerCapacity','')::integer,0),0);

  for pin in select value from jsonb_array_elements(payload->'servicePincodes') loop
    if coalesce((pin->>'verified')::boolean,false) and (pin->>'value') ~ '^[1-9][0-9]{5}$' then
      requested_count := requested_count + 1;
      insert into public.pincodes(code,locality,city,state)
      values(
        pin->>'value',
        nullif(pin->>'areaName',''),
        coalesce(nullif(payload->>'city',''),'Bhubaneswar'),
        coalesce(nullif(payload->>'state',''),'Odisha')
      )
      on conflict(code) do update set locality=coalesce(excluded.locality,pincodes.locality);

      insert into public.provider_service_areas(
        provider_id,pincode,status,delivery_radius_km,requested_by,approved_by,approved_at,effective_from,effective_until
      ) values(
        new.provider_id,pin->>'value','APPROVED',radius_value,new.requested_by,new.reviewed_by,coalesce(new.reviewed_at,now()),current_date,null
      )
      on conflict(provider_id,pincode) do update set
        status='APPROVED',delivery_radius_km=excluded.delivery_radius_km,
        requested_by=excluded.requested_by,approved_by=excluded.approved_by,
        approved_at=excluded.approved_at,effective_from=current_date,effective_until=null,updated_at=now();

      insert into public.provider_capacity(provider_id,pincode,service_date,meal_slot,capacity_limit,updated_by)
      select new.provider_id,pin->>'value',day_value::date,slot_value.slot,
        case slot_value.slot when 'LUNCH' then lunch_capacity else dinner_capacity end,
        coalesce(new.reviewed_by,new.requested_by)
      from generate_series(current_date,current_date+29,interval '1 day') day_value
      cross join (values('LUNCH'::public.meal_slot),('DINNER'::public.meal_slot)) slot_value(slot)
      on conflict(provider_id,pincode,service_date,meal_slot) do update set
        capacity_limit=greatest(excluded.capacity_limit,provider_capacity.reserved_count+provider_capacity.confirmed_count),
        updated_by=excluded.updated_by,updated_at=now();
    end if;
  end loop;

  if requested_count = 0 then
    raise exception 'At least one verified serviceable pincode is required';
  end if;
  return new;
end;
$$;

drop trigger if exists provider_change_request_apply_service_areas on public.provider_change_requests;
create trigger provider_change_request_apply_service_areas
after update of status on public.provider_change_requests
for each row execute function public.apply_approved_provider_service_area_change();

revoke all on function public.provider_approved_service_areas(),public.admin_provider_change_service_areas(uuid) from public;
grant execute on function public.provider_approved_service_areas() to authenticated;
grant execute on function public.admin_provider_change_service_areas(uuid) to authenticated;

notify pgrst,'reload schema';
