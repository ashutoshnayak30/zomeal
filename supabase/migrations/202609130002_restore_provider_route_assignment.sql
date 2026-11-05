-- Restore the missing route RPC used by the provider Orders screen.
create or replace function public.provider_auto_assign_routes(target_slot public.meal_slot,target_date date default null)
returns jsonb language plpgsql security definer set search_path=public as $$
declare
  kitchen uuid; service_day date := coalesce(target_date,(now() at time zone 'Asia/Kolkata')::date);
  dashboard jsonb; riders uuid[]; rider_index integer := 1; meal record; changed integer := 0;
begin
  select provider_id into kitchen from public.provider_members
    where user_id=auth.uid() and is_active order by created_at desc limit 1;
  if kitchen is null then raise exception 'Provider account required'; end if;
  dashboard := public.provider_daily_dashboard(target_slot,service_day);
  if coalesce((dashboard->>'preview_mode')::boolean,true) or not coalesce((dashboard->>'is_final')::boolean,false) then
    raise exception 'Routes can be assigned only after the active kitchen meal cutoff';
  end if;
  perform pg_advisory_xact_lock(hashtextextended(kitchen::text || service_day::text || target_slot::text,0));
  select array_agg(id order by is_primary desc,created_at,id) into riders
    from public.provider_delivery_personnel where provider_id=kitchen and is_active;
  if coalesce(array_length(riders,1),0)=0 then raise exception 'Add at least one active delivery person'; end if;
  for meal in select id from public.subscription_meals
    where provider_id=kitchen and service_date=service_day and meal_slot=target_slot
      and status in ('SCHEDULED','PREPARING','PACKING','READY') and delivery_personnel_id is null
    order by delivery_address->>'pincode',delivery_address->>'locality',created_at,id for update
  loop
    -- Keep nearby meals together in batches of at most 100; never replace existing assignments.
    rider_index := (changed / 100) % array_length(riders,1) + 1;
    update public.subscription_meals set delivery_personnel_id=riders[rider_index],updated_at=now() where id=meal.id;
    changed := changed+1;
  end loop;
  return jsonb_build_object('assigned',changed,'delivery_people',array_length(riders,1));
end;
$$;
revoke all on function public.provider_auto_assign_routes(public.meal_slot,date) from public;
grant execute on function public.provider_auto_assign_routes(public.meal_slot,date) to authenticated;
