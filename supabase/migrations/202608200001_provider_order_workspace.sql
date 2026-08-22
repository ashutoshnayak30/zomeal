-- Enrich the finalized provider manifest with package and per-meal commercial data.
-- Customer-identifying fields remain protected by the existing slot cut-off logic.

alter function public.provider_daily_dashboard(public.meal_slot,date)
rename to provider_daily_dashboard_order_base;

create function public.provider_daily_dashboard(
  target_slot public.meal_slot,
  target_date date default null
) returns jsonb
language plpgsql
stable
security definer
set search_path=public
as $$
declare
  result jsonb;
  enriched_manifest jsonb;
begin
  result:=public.provider_daily_dashboard_order_base(target_slot,target_date);

  if coalesce(jsonb_array_length(result->'manifest'),0)>0 then
    select coalesce(jsonb_agg(
      manifest_row.entry || jsonb_build_object(
        'service_date',meal.service_date,
        'package_id',package.id,
        'package_name',package.name,
        'package_kind',package.kind,
        'meal_value_paise',meal.meal_value_paise
      ) order by meal.delivery_address->>'pincode',meal.delivery_address->>'locality',manifest_row.entry->>'customer_name'
    ),'[]'::jsonb)
    into enriched_manifest
    from jsonb_array_elements(result->'manifest') manifest_row(entry)
    join public.subscription_meals meal on meal.id=(manifest_row.entry->>'meal_id')::uuid
    join public.customer_subscriptions subscription on subscription.id=meal.subscription_id
    join public.packages package on package.id=subscription.package_id;

    result:=jsonb_set(result,'{manifest}',enriched_manifest,true);
  end if;

  return result;
end;
$$;

revoke all on function public.provider_daily_dashboard(public.meal_slot,date) from public;
grant execute on function public.provider_daily_dashboard(public.meal_slot,date) to authenticated;

comment on function public.provider_daily_dashboard(public.meal_slot,date) is
'Provider date-and-slot order workspace. Customer manifest is released only after the configured lunch or dinner cut-off.';

create or replace function public.customer_save_registration_profile(
  target_full_name text,
  target_phone text
) returns jsonb
language plpgsql
security definer
set search_path=public
as $$
declare
  normalized_phone text:=right(regexp_replace(coalesce(target_phone,''),'\D','','g'),10);
  saved_profile public.profiles;
begin
  if auth.uid() is null then raise exception 'Customer authentication is required'; end if;
  if normalized_phone !~ '^[0-9]{10}$' then raise exception 'A valid 10-digit mobile number is required'; end if;

  -- Development authentication creates disposable anonymous users. Allow a verified
  -- test number to move to the current test identity without weakening production OTP.
  if coalesce((select raw_user_meta_data->>'account_type' from auth.users where id=auth.uid()),'')='CUSTOMER_TEST' then
    update public.profiles set phone=null where phone=normalized_phone and id<>auth.uid();
  end if;

  update public.profiles
  set full_name=coalesce(nullif(trim(target_full_name),''),nullif(full_name,''),'Customer'),
      phone=normalized_phone,
      updated_at=now()
  where id=auth.uid()
  returning * into saved_profile;

  if saved_profile.id is null then raise exception 'Customer profile was not found'; end if;
  return jsonb_build_object('id',saved_profile.id,'full_name',saved_profile.full_name,'phone',saved_profile.phone);
end;
$$;

revoke all on function public.customer_save_registration_profile(text,text) from public;
grant execute on function public.customer_save_registration_profile(text,text) to authenticated;
