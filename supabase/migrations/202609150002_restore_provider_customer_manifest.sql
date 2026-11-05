-- The commission base no longer emits a manifest. Construct it directly instead
-- of enriching an absent array. Preserve the existing final-list cutoff.
create or replace function public.provider_daily_dashboard(target_slot public.meal_slot,target_date date default null)
returns jsonb language plpgsql stable security definer set search_path=public as $$
declare result jsonb; kitchen uuid; service_day date; customers jsonb;
begin
  result := public.provider_daily_dashboard_order_base(target_slot,target_date);
  kitchen := (result->>'provider_id')::uuid;
  service_day := (result->>'date')::date;
  if kitchen is null or not exists(select 1 from public.provider_members where provider_id=kitchen and user_id=auth.uid() and is_active) then
    raise exception 'Active provider membership required';
  end if;
  customers := '[]'::jsonb;
  if coalesce((result->>'is_final')::boolean,false) and not coalesce((result->>'preview_mode')::boolean,true) then
    select coalesce(jsonb_agg(jsonb_build_object(
      'meal_id',meal.id,'customer_name',coalesce(profile.full_name,''),
      'phone',case when length(phone.digits)=10 then left(phone.digits,2)||'xxxxxx'||right(phone.digits,2) else '' end,
      'address',meal.delivery_address,'meal_type',meal.meal_slot,
      'main_course',coalesce(item.name,''),'status',meal.status,
      'delivery_person_id',coalesce(rider.id::text,''),'delivery_person',coalesce(rider.full_name,''),
      'delivery_person_phone',coalesce(rider.phone,''),'service_date',meal.service_date,
      'package_id',package.id,'package_name',coalesce(package.name,''),'package_kind',package.kind,
      'meal_value_paise',meal.meal_value_paise
    ) order by meal.delivery_address->>'pincode',meal.delivery_address->>'locality',profile.full_name,meal.id),'[]'::jsonb)
    into customers
    from public.subscription_meals meal
    join public.profiles profile on profile.id=meal.customer_id
    join public.customer_subscriptions subscription on subscription.id=meal.subscription_id
    left join public.packages package on package.id=subscription.package_id
    left join public.menu_items item on item.id=meal.selected_menu_item_id
    left join public.provider_delivery_personnel rider on rider.id=meal.delivery_personnel_id and rider.provider_id=kitchen and rider.is_active
    cross join lateral (select right(regexp_replace(coalesce(profile.phone,''),'[^0-9]','','g'),10) digits) phone
    where meal.provider_id=kitchen and meal.service_date=service_day and meal.meal_slot=target_slot
      and meal.status not in ('PAUSED','CANCELLED');
  end if;
  return jsonb_set(result,'{manifest}',customers,true);
end;
$$;
revoke all on function public.provider_daily_dashboard(public.meal_slot,date) from public;
grant execute on function public.provider_daily_dashboard(public.meal_slot,date) to authenticated;
