-- Customer-confirmed provider changes take effect for the next meal whose
-- operational cutoff has not passed. The operation is atomic: subscription,
-- selected package/menu template and eligible future meals always agree.

create or replace function public.customer_switch_provider(
  target_subscription uuid,
  replacement_provider uuid,
  replacement_package uuid,
  target_weekly_menu jsonb
) returns jsonb
language plpgsql
security definer
set search_path=public
as $$
declare
  subscription_record public.customer_subscriptions;
  package_record public.packages;
  price_record public.package_price_versions;
  template_id uuid;
  selected_item uuid;
  selected_name text;
  day_index integer;
  slot_value public.meal_slot;
  local_now timestamp := now() at time zone 'Asia/Kolkata';
  switched_meals integer := 0;
  meal_record record;
  component_total bigint;
  daily_value bigint;
begin
  select * into subscription_record
  from public.customer_subscriptions
  where id=target_subscription and customer_id=auth.uid()
  for update;

  if subscription_record.id is null then raise exception 'Active subscription was not found'; end if;
  if subscription_record.status not in ('ACTIVE','PAUSED') then raise exception 'This subscription cannot change provider in its current status'; end if;
  if replacement_provider=subscription_record.provider_id then raise exception 'Choose a different service provider'; end if;

  select * into package_record from public.packages
  where id=replacement_package and provider_id=replacement_provider and is_active
  for share;
  if package_record.id is null then raise exception 'The selected replacement package is not active'; end if;
  if not exists(select 1 from public.providers where id=replacement_provider and status='ACTIVE') then
    raise exception 'The selected replacement provider is not active';
  end if;
  if not exists(
    select 1 from public.provider_service_areas area
    where area.provider_id=replacement_provider
      and area.pincode=coalesce(subscription_record.delivery_address->>'pincode','')
      and area.status='APPROVED'
      and (area.effective_from is null or area.effective_from<=current_date)
      and (area.effective_until is null or area.effective_until>=current_date)
  ) then raise exception 'The replacement provider does not serve your saved address'; end if;

  select * into price_record from public.package_price_versions
  where package_id=replacement_package and status='APPROVED'
    and effective_from<=now() and (effective_until is null or effective_until>now())
  order by effective_from desc,version desc limit 1;
  if price_record.id is null then raise exception 'An approved price was not found for the replacement package'; end if;

  update public.customer_weekly_menu_templates set is_active=false
  where customer_id=auth.uid() and is_active;
  insert into public.customer_weekly_menu_templates(customer_id,package_id,name,dietary_preference,is_active)
  values(auth.uid(),replacement_package,'Provider change weekly menu',package_record.dietary_type,true)
  returning id into template_id;

  for day_index in 1..7 loop
    foreach slot_value in array array['LUNCH'::public.meal_slot,'DINNER'::public.meal_slot] loop
      if (package_record.kind='LUNCH_ONLY' and slot_value='DINNER') or (package_record.kind='DINNER_ONLY' and slot_value='LUNCH') then continue; end if;
      selected_name := nullif(trim((target_weekly_menu->lower(slot_value::text)->>((day_index-1)::text))),'');
      selected_item := null;
      if selected_name is not null then
        select item.id into selected_item
        from public.provider_menus menu
        join public.menu_days menu_day on menu_day.menu_id=menu.id and menu_day.day_of_week=day_index and menu_day.meal_slot=slot_value and menu_day.is_available
        join public.menu_day_choices choice on choice.menu_day_id=menu_day.id and choice.choice_group='MAIN_COURSE'
        join public.menu_items item on item.id=choice.menu_item_id and item.status='APPROVED'
        where menu.provider_id=replacement_provider and menu.status='APPROVED'
          and lower(trim(item.name))=lower(selected_name)
          and menu.valid_from<=current_date and (menu.valid_until is null or menu.valid_until>=current_date)
        order by menu.valid_from desc limit 1;
      end if;
      selected_item := coalesce(selected_item,public.default_menu_item_for_day(replacement_provider,current_date+(day_index-extract(isodow from current_date)::integer),slot_value));
      if selected_item is null then raise exception 'No approved % main course exists for weekday %',lower(slot_value::text),day_index; end if;
      insert into public.customer_weekly_menu_selections(template_id,day_of_week,meal_slot,choice_group,menu_item_id)
      values(template_id,day_index,slot_value,'MAIN_COURSE',selected_item);
    end loop;
  end loop;

  update public.customer_subscriptions set
    provider_id=replacement_provider,
    package_id=replacement_package,
    price_version_id=price_record.id,
    package_price_paise=price_record.total_price_paise,
    lunch_component_paise=price_record.lunch_value_paise,
    dinner_component_paise=price_record.dinner_value_paise,
    commission_basis_points=public.current_provider_commission_basis_points(replacement_provider),
    updated_at=now()
  where id=target_subscription;

  for meal_record in
    select id,service_date,meal_slot,status from public.subscription_meals
    where subscription_id=target_subscription
      and status in ('SCHEDULED','PAUSED')
      and (
        service_date>local_now::date
        or (service_date=local_now::date and meal_slot='LUNCH' and local_now::time<time '07:00')
        or (service_date=local_now::date and meal_slot='DINNER' and local_now::time<time '16:00')
      )
    for update
  loop
    if (package_record.kind='LUNCH_ONLY' and meal_record.meal_slot='DINNER') or (package_record.kind='DINNER_ONLY' and meal_record.meal_slot='LUNCH') then
      update public.subscription_meals set status='CANCELLED',updated_at=now() where id=meal_record.id;
    else
      selected_item:=public.subscription_menu_item_for_day(auth.uid(),replacement_package,replacement_provider,meal_record.service_date,meal_record.meal_slot);
      component_total:=case meal_record.meal_slot when 'LUNCH' then price_record.lunch_value_paise else price_record.dinner_value_paise end;
      daily_value:=component_total/greatest(package_record.duration_days,1);
      update public.subscription_meals set provider_id=replacement_provider,selected_menu_item_id=selected_item,
        meal_value_paise=daily_value,delivery_personnel_id=null,updated_at=now()
      where id=meal_record.id;
      switched_meals:=switched_meals+1;
    end if;
  end loop;

  -- If the new package adds a slot that did not exist in the old package,
  -- create it for every remaining eligible service date.
  insert into public.subscription_meals(subscription_id,provider_id,customer_id,service_date,meal_slot,selected_menu_item_id,status,meal_value_paise,delivery_address)
  select target_subscription,replacement_provider,auth.uid(),service_day,slot_value,
    public.subscription_menu_item_for_day(auth.uid(),replacement_package,replacement_provider,service_day,slot_value),
    'SCHEDULED',
    (case slot_value when 'LUNCH' then price_record.lunch_value_paise else price_record.dinner_value_paise end)/greatest(package_record.duration_days,1),
    subscription_record.delivery_address
  from generate_series(greatest(subscription_record.start_date,local_now::date),subscription_record.end_date,'1 day'::interval) generated(service_stamp)
  cross join lateral (select generated.service_stamp::date as service_day) day_value
  cross join unnest(case package_record.kind when 'LUNCH_ONLY' then array['LUNCH'::public.meal_slot] when 'DINNER_ONLY' then array['DINNER'::public.meal_slot] else array['LUNCH'::public.meal_slot,'DINNER'::public.meal_slot] end) slot_value
  where (service_day>local_now::date or (slot_value='LUNCH' and local_now::time<time '07:00') or (slot_value='DINNER' and local_now::time<time '16:00'))
  on conflict(subscription_id,service_date,meal_slot) do nothing;

  update public.customer_subscription_change_requests set status='COMPLETED',review_note='Customer completed self-service provider change',reviewed_at=now(),updated_at=now()
  where subscription_id=target_subscription and request_type='CHANGE_PROVIDER' and status in('PENDING','CONTACTED','APPROVED');

  insert into public.audit_logs(actor_id,action,entity_type,entity_id,before_data,after_data)
  values(auth.uid(),'CUSTOMER_PROVIDER_SWITCHED','customer_subscription',target_subscription::text,
    jsonb_build_object('provider_id',subscription_record.provider_id,'package_id',subscription_record.package_id),
    jsonb_build_object('provider_id',replacement_provider,'package_id',replacement_package,'future_meals_switched',switched_meals,'settlement','WALLET_BALANCE'));

  return jsonb_build_object('subscription_id',target_subscription,'provider_id',replacement_provider,'package_id',replacement_package,
    'future_meals_switched',switched_meals,'effective_from','NEXT_ELIGIBLE_MEAL','settlement','WALLET_BALANCE');
end;
$$;

revoke all on function public.customer_switch_provider(uuid,uuid,uuid,jsonb) from public;
grant execute on function public.customer_switch_provider(uuid,uuid,uuid,jsonb) to authenticated;
notify pgrst,'reload schema';
