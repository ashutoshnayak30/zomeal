-- Persist actual meal preparation stages; never fake a delivery from a local checkbox.
create or replace function public.provider_advance_kitchen_stage(
  target_slot public.meal_slot, target_date date, new_stage text
) returns jsonb language plpgsql security definer set search_path=public as $$
declare
  kitchen uuid;
  stage text := upper(trim(new_stage));
  dashboard jsonb;
  changed integer;
begin
  select provider_id into kitchen from public.provider_members
  where user_id=auth.uid() and is_active order by created_at desc limit 1;
  if kitchen is null then raise exception 'Provider account required'; end if;
  if target_date is distinct from (now() at time zone 'Asia/Kolkata')::date then
    raise exception 'Only today''s meals can be updated';
  end if;
  if stage is null or stage not in ('PREPARING','PACKING','OUT_FOR_DELIVERY') then
    raise exception 'Invalid cooking stage';
  end if;
  perform pg_advisory_xact_lock(hashtextextended(kitchen::text || target_date::text || target_slot::text,0));
  perform 1 from public.subscription_meals
    where provider_id=kitchen and service_date=target_date and meal_slot=target_slot for update;
  dashboard := public.provider_daily_dashboard(target_slot,target_date);
  if coalesce((dashboard->>'preview_mode')::boolean,true) or not coalesce((dashboard->>'is_final')::boolean,false) then
    raise exception 'Wait until the active kitchen''s meal list is final';
  end if;
  if stage in ('PACKING','OUT_FOR_DELIVERY') and exists(
    select 1 from public.subscription_meals where provider_id=kitchen and service_date=target_date and meal_slot=target_slot and status='SCHEDULED'
  ) then raise exception 'Start cooking all scheduled meals first'; end if;
  if stage='OUT_FOR_DELIVERY' then
    if exists(select 1 from public.subscription_meals where provider_id=kitchen and service_date=target_date and meal_slot=target_slot and status='PREPARING') then
      raise exception 'Start packaging before dispatch';
    end if;
    if exists(select 1 from public.subscription_meals meal
      where meal.provider_id=kitchen and meal.service_date=target_date and meal.meal_slot=target_slot
      and meal.status in ('PACKING','READY') and not exists(
        select 1 from public.provider_delivery_personnel rider
        where rider.id=meal.delivery_personnel_id and rider.provider_id=kitchen and rider.is_active
      )) then raise exception 'Assign an active rider to every packed meal before dispatch'; end if;
  end if;
  update public.subscription_meals set status=stage,updated_at=now()
  where provider_id=kitchen and service_date=target_date and meal_slot=target_slot and
    ((stage='PREPARING' and status='SCHEDULED') or
     (stage='PACKING' and status='PREPARING') or
     (stage='OUT_FOR_DELIVERY' and status in ('PACKING','READY')));
  get diagnostics changed=row_count;
  if changed=0 then raise exception 'No eligible meals. Refresh the dashboard'; end if;
  return jsonb_build_object('updated',changed,'status',stage);
end;
$$;
revoke all on function public.provider_advance_kitchen_stage(public.meal_slot,date,text) from public;
grant execute on function public.provider_advance_kitchen_stage(public.meal_slot,date,text) to authenticated;
