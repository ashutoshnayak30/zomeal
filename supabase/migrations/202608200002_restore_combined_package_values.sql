-- Restore the provider combined-package split RPC and refresh PostgREST's schema cache.
-- The provider supplies the lunch value per day; dinner receives the remainder.

create or replace function public.provider_set_combined_package_values(
  target_lunch_daily_rupees numeric
) returns jsonb
language plpgsql
security definer
set search_path=public
as $$
declare
  target_provider uuid;
  target_package uuid;
  target_price uuid;
  duration integer;
  total bigint;
  lunch_value bigint;
  dinner_value bigint;
begin
  select pm.provider_id into target_provider
  from public.provider_members pm
  where pm.user_id=auth.uid() and pm.is_active
  order by pm.created_at desc limit 1;

  if target_provider is null then
    raise exception 'Provider account was not found';
  end if;
  if target_lunch_daily_rupees is null or target_lunch_daily_rupees<=0 then
    raise exception 'Lunch value per day must be greater than zero';
  end if;

  select p.id,p.duration_days,pv.id,pv.total_price_paise
  into target_package,duration,target_price,total
  from public.packages p
  join public.package_price_versions pv on pv.package_id=p.id
  where p.provider_id=target_provider
    and p.kind='LUNCH_AND_DINNER'
    and pv.status in ('PENDING','APPROVED')
  order by case when pv.status='PENDING' then 0 else 1 end,pv.created_at desc
  limit 1
  for update of pv;

  if target_price is null then
    raise exception 'Combined lunch and dinner package price was not found';
  end if;

  lunch_value:=round(target_lunch_daily_rupees*duration*100);
  dinner_value:=total-lunch_value;
  if lunch_value<=0 or dinner_value<=0 then
    raise exception 'Lunch value must be lower than the combined daily package value';
  end if;

  update public.package_price_versions
  set lunch_value_paise=lunch_value,dinner_value_paise=dinner_value
  where id=target_price;

  return jsonb_build_object(
    'package_id',target_package,
    'total_price_paise',total,
    'lunch_value_paise',lunch_value,
    'dinner_value_paise',dinner_value,
    'lunch_daily_paise',round(lunch_value::numeric/duration),
    'dinner_daily_paise',round(dinner_value::numeric/duration)
  );
end;
$$;

revoke all on function public.provider_set_combined_package_values(numeric) from public;
grant execute on function public.provider_set_combined_package_values(numeric) to authenticated;

notify pgrst,'reload schema';
