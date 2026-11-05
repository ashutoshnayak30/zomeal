-- Full customer phones are fetched only for a confirmed assigned-rider export.
-- The ordinary provider dashboard continues to return masked phones.
create or replace function public.provider_delivery_share(target_slot public.meal_slot, target_date date, target_rider uuid)
returns jsonb language plpgsql stable security definer set search_path=public as $$
declare dashboard jsonb; kitchen uuid; rider_phone text; customers jsonb;
begin
  if target_date is distinct from (now() at time zone 'Asia/Kolkata')::date then
    raise exception 'Only today''s assigned deliveries can be shared';
  end if;
  dashboard := public.provider_daily_dashboard(target_slot,target_date);
  kitchen := (dashboard->>'provider_id')::uuid;
  if not coalesce((dashboard->>'is_final')::boolean,false) or coalesce((dashboard->>'preview_mode')::boolean,true) then
    raise exception 'Delivery list is available after cutoff';
  end if;
  select phone into rider_phone from public.provider_delivery_personnel
    where id=target_rider and provider_id=kitchen and is_active;
  if rider_phone is null then raise exception 'Active assigned rider required'; end if;
  select coalesce(jsonb_agg(jsonb_build_object(
    'customer_name',entry->>'customer_name','address',entry->'address',
    'main_course',entry->>'main_course',
    'phone',case when length(digits)=10 then '+91'||digits else '' end
  ) order by entry->'address'->>'pincode',entry->>'customer_name'),'[]'::jsonb) into customers
  from jsonb_array_elements(dashboard->'manifest') entry
  join public.subscription_meals meal on meal.id::text=entry->>'meal_id'
  join public.profiles profile on profile.id=meal.customer_id
  cross join lateral (select right(regexp_replace(coalesce(profile.phone,''),'[^0-9]','','g'),10) digits) phone
  where meal.provider_id=kitchen and meal.delivery_personnel_id=target_rider
    and meal.service_date=target_date and meal.meal_slot=target_slot
    and meal.status in ('SCHEDULED','PREPARING','PACKING','READY','OUT_FOR_DELIVERY');
  return jsonb_build_object('rider_phone',rider_phone,'customers',customers);
end;
$$;
revoke all on function public.provider_delivery_share(public.meal_slot,date,uuid) from public;
grant execute on function public.provider_delivery_share(public.meal_slot,date,uuid) to authenticated;
