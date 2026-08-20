-- Persist the complete customer checkout only after a verified captured payment.
-- The payment order is the immutable source of checkout intent; finalization is
-- idempotent and creates address, recurring choices, subscription and daily meals.

create table if not exists public.customer_addresses(
  id uuid primary key default gen_random_uuid(),
  customer_id uuid not null references public.profiles(id) on delete cascade,
  label text not null default 'Home',
  house text not null,
  street text not null,
  locality text not null,
  landmark text,
  pincode text not null references public.pincodes(code),
  city text not null default 'Bhubaneswar',
  state text not null default 'Odisha',
  is_default boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create unique index if not exists one_default_customer_address
on public.customer_addresses(customer_id) where is_default;
create index if not exists customer_addresses_owner_idx on public.customer_addresses(customer_id,updated_at desc);
alter table public.customer_addresses enable row level security;
create policy customer_addresses_owner_read on public.customer_addresses for select to authenticated using(customer_id=auth.uid());
create policy customer_addresses_owner_insert on public.customer_addresses for insert to authenticated with check(customer_id=auth.uid());
create policy customer_addresses_owner_update on public.customer_addresses for update to authenticated using(customer_id=auth.uid()) with check(customer_id=auth.uid());
create trigger customer_addresses_updated_at before update on public.customer_addresses
for each row execute function public.set_updated_at();

create or replace function public.finalize_captured_payment(target_payment_order uuid,target_customer uuid)
returns jsonb language plpgsql security definer set search_path=public as $$
declare
  payment_record public.payment_orders;
  package_record public.packages;
  price_record public.package_price_versions;
  selected_start date;
  selected_end date;
  first_slot text;
  address_payload jsonb;
  weekly_payload jsonb;
  preference public.dietary_type;
  template_id uuid;
  created_subscription uuid;
  selected_name text;
  selected_item uuid;
  slot_value public.meal_slot;
  day_index integer;
  service_day date;
  day_number integer;
  meal_value bigint;
  lunch_base bigint;
  lunch_remainder bigint;
  dinner_base bigint;
  dinner_remainder bigint;
  commission_bps integer;
  meals_created integer:=0;
begin
  select * into payment_record from public.payment_orders where id=target_payment_order for update;
  if payment_record.id is null then raise exception 'Payment order was not found'; end if;
  if payment_record.status<>'CAPTURED' then raise exception 'Payment has not been captured'; end if;
  if payment_record.customer_id is distinct from target_customer then raise exception 'Payment customer does not match'; end if;
  if payment_record.subscription_id is not null then
    return jsonb_build_object('subscription_id',payment_record.subscription_id,'already_finalized',true);
  end if;

  select * into package_record from public.packages where id=payment_record.package_id and is_active for share;
  if package_record.id is null then raise exception 'Selected package is no longer active'; end if;
  select * into price_record from public.package_price_versions
  where package_id=package_record.id and status='APPROVED' and effective_from<=payment_record.created_at
    and (effective_until is null or effective_until>payment_record.created_at)
  order by effective_from desc,created_at desc limit 1;
  if price_record.id is null then raise exception 'Approved package price was not found'; end if;

  address_payload:=coalesce(payment_record.checkout_payload->'delivery_address','{}'::jsonb);
  weekly_payload:=coalesce(payment_record.checkout_payload->'weekly_menu','{}'::jsonb);
  if nullif(trim(address_payload->>'house'),'') is null or nullif(trim(address_payload->>'street'),'') is null
     or nullif(trim(address_payload->>'locality'),'') is null or (address_payload->>'pincode') !~ '^[1-9][0-9]{5}$' then
    raise exception 'A complete delivery address is required';
  end if;
  if not exists(select 1 from public.provider_service_areas where provider_id=payment_record.provider_id
    and pincode=address_payload->>'pincode' and status='APPROVED'
    and (effective_from is null or effective_from<=current_date)
    and (effective_until is null or effective_until>=current_date)) then
    raise exception 'The selected provider does not serve this delivery address';
  end if;

  update public.customer_addresses set is_default=false where customer_id=target_customer and is_default;
  insert into public.customer_addresses(customer_id,label,house,street,locality,landmark,pincode,city,state,is_default)
  values(target_customer,'Home',trim(address_payload->>'house'),trim(address_payload->>'street'),trim(address_payload->>'locality'),
    nullif(trim(address_payload->>'landmark'),''),address_payload->>'pincode',coalesce(nullif(address_payload->>'city',''),'Bhubaneswar'),
    coalesce(nullif(address_payload->>'state',''),'Odisha'),true);

  preference:=case upper(coalesce(weekly_payload->>'preference',''))
    when 'VEG' then 'VEG'::public.dietary_type when 'VEGAN' then 'VEGAN'::public.dietary_type
    when 'NON_VEG' then 'NON_VEG'::public.dietary_type else package_record.dietary_type end;
  update public.customer_weekly_menu_templates set is_active=false
  where customer_id=target_customer and package_id=package_record.id and is_active;
  insert into public.customer_weekly_menu_templates(customer_id,package_id,name,dietary_preference,is_active)
  values(target_customer,package_record.id,'Subscription weekly menu',preference,true) returning id into template_id;

  for day_index in 1..7 loop
    foreach slot_value in array array['LUNCH'::public.meal_slot,'DINNER'::public.meal_slot] loop
      if (package_record.kind='LUNCH_ONLY' and slot_value='DINNER') or (package_record.kind='DINNER_ONLY' and slot_value='LUNCH') then continue; end if;
      selected_name:=nullif(trim(weekly_payload->lower(slot_value::text)->>(day_index-1)::text),'');
      if selected_name is not null then
        select item.id into selected_item from public.provider_menus menu
        join public.menu_days menu_day on menu_day.menu_id=menu.id and menu_day.day_of_week=day_index and menu_day.meal_slot=slot_value
        join public.menu_day_choices choice on choice.menu_day_id=menu_day.id and choice.choice_group='MAIN_COURSE'
        join public.menu_items item on item.id=choice.menu_item_id and item.status='APPROVED'
        where menu.provider_id=payment_record.provider_id and menu.status='APPROVED'
          and lower(trim(item.name))=lower(selected_name)
          and menu.valid_from<=current_date and (menu.valid_until is null or menu.valid_until>=current_date)
        order by menu.valid_from desc limit 1;
      else selected_item:=null; end if;
      selected_item:=coalesce(selected_item,public.default_menu_item_for_day(payment_record.provider_id,current_date+(day_index-extract(isodow from current_date)::integer),slot_value));
      if selected_item is null then raise exception 'No approved % main course exists for weekday %',lower(slot_value::text),day_index; end if;
      insert into public.customer_weekly_menu_selections(template_id,day_of_week,meal_slot,choice_group,menu_item_id)
      values(template_id,day_index,slot_value,'MAIN_COURSE',selected_item);
    end loop;
  end loop;

  selected_start:=coalesce(nullif(payment_record.checkout_payload->>'start_date','')::date,(now() at time zone 'Asia/Kolkata')::date+1);
  selected_end:=selected_start+greatest(package_record.duration_days-1,0);
  first_slot:=upper(coalesce(nullif(payment_record.checkout_payload->>'first_meal',''),case when package_record.kind='DINNER_ONLY' then 'DINNER' else 'LUNCH' end));
  commission_bps:=public.current_provider_commission_basis_points(payment_record.provider_id);
  insert into public.customer_subscriptions(customer_id,provider_id,package_id,price_version_id,status,start_date,end_date,
    delivery_address,total_paid_paise,payment_reference,package_price_paise,lunch_component_paise,dinner_component_paise,
    commission_basis_points,activated_at)
  values(target_customer,payment_record.provider_id,package_record.id,price_record.id,'ACTIVE',selected_start,selected_end,
    address_payload,payment_record.amount_paise,payment_record.gateway_payment_id,price_record.total_price_paise,
    price_record.lunch_value_paise,price_record.dinner_value_paise,commission_bps,now()) returning id into created_subscription;

  lunch_base:=price_record.lunch_value_paise/package_record.duration_days;
  lunch_remainder:=price_record.lunch_value_paise%package_record.duration_days;
  dinner_base:=price_record.dinner_value_paise/package_record.duration_days;
  dinner_remainder:=price_record.dinner_value_paise%package_record.duration_days;
  for service_day in select generate_series(selected_start,selected_end,'1 day'::interval)::date loop
    day_number:=(service_day-selected_start)+1;
    if package_record.kind in ('LUNCH_ONLY','LUNCH_AND_DINNER') and not(service_day=selected_start and first_slot='DINNER') then
      meal_value:=lunch_base+case when day_number<=lunch_remainder then 1 else 0 end;
      insert into public.subscription_meals(subscription_id,provider_id,customer_id,service_date,meal_slot,selected_menu_item_id,meal_value_paise,delivery_address)
      values(created_subscription,payment_record.provider_id,target_customer,service_day,'LUNCH',
        public.subscription_menu_item_for_day(target_customer,package_record.id,payment_record.provider_id,service_day,'LUNCH'),meal_value,address_payload);
      meals_created:=meals_created+1;
    end if;
    if package_record.kind in ('DINNER_ONLY','LUNCH_AND_DINNER') then
      meal_value:=dinner_base+case when day_number<=dinner_remainder then 1 else 0 end;
      insert into public.subscription_meals(subscription_id,provider_id,customer_id,service_date,meal_slot,selected_menu_item_id,meal_value_paise,delivery_address)
      values(created_subscription,payment_record.provider_id,target_customer,service_day,'DINNER',
        public.subscription_menu_item_for_day(target_customer,package_record.id,payment_record.provider_id,service_day,'DINNER'),meal_value,address_payload);
      meals_created:=meals_created+1;
    end if;
  end loop;
  update public.payment_orders set subscription_id=created_subscription where id=payment_record.id;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data,metadata)
  values(target_customer,'CUSTOMER_CHECKOUT_FINALIZED','customer_subscription',created_subscription::text,
    jsonb_build_object('provider_id',payment_record.provider_id,'package_id',package_record.id,'start_date',selected_start,'end_date',selected_end,'meals_created',meals_created),
    jsonb_build_object('payment_order_id',payment_record.id,'gateway_payment_id',payment_record.gateway_payment_id));
  return jsonb_build_object('subscription_id',created_subscription,'already_finalized',false,'meals_created',meals_created,'start_date',selected_start,'end_date',selected_end);
end; $$;

create or replace function public.customer_active_subscription_state()
returns jsonb language plpgsql stable security definer set search_path=public as $$
declare target_subscription uuid;
begin
  select id into target_subscription from public.customer_subscriptions
  where customer_id=auth.uid() and status in('ACTIVE','PAUSED','CANCEL_PENDING')
  order by activated_at desc nulls last,created_at desc limit 1;
  if target_subscription is null then return jsonb_build_object('has_active_subscription',false); end if;
  return jsonb_build_object(
    'has_active_subscription',true,
    'profile',(select jsonb_build_object('id',p.id,'full_name',p.full_name,'phone',p.phone,'avatar_url',p.avatar_url) from public.profiles p where p.id=auth.uid()),
    'address',(select to_jsonb(a) from public.customer_addresses a where a.customer_id=auth.uid() and a.is_default limit 1),
    'subscription',(select jsonb_build_object('id',s.id,'status',s.status,'start_date',s.start_date,'end_date',s.end_date,'total_paid_paise',s.total_paid_paise,
      'provider_id',s.provider_id,'provider_name',pr.display_name,'provider_dietary_type',pr.dietary_type,
      'package_id',s.package_id,'package_name',pk.name,'package_kind',pk.kind,'duration_days',pk.duration_days,
      'payment_reference',s.payment_reference)
      from public.customer_subscriptions s join public.providers pr on pr.id=s.provider_id join public.packages pk on pk.id=s.package_id where s.id=target_subscription),
    'weekly_menu',coalesce((select jsonb_agg(jsonb_build_object('day_of_week',selection.day_of_week,'meal_slot',selection.meal_slot,'item_id',item.id,'item_name',item.name,'dietary_type',item.dietary_type)
      order by selection.day_of_week,selection.meal_slot) from public.customer_weekly_menu_templates template
      join public.customer_weekly_menu_selections selection on selection.template_id=template.id
      join public.menu_items item on item.id=selection.menu_item_id
      where template.customer_id=auth.uid() and template.package_id=(select package_id from public.customer_subscriptions where id=target_subscription) and template.is_active),'[]'::jsonb),
    'daily_meals',coalesce((select jsonb_agg(jsonb_build_object('id',meal.id,'service_date',meal.service_date,'meal_slot',meal.meal_slot,'status',meal.status,
      'item_id',item.id,'item_name',item.name,'dietary_type',item.dietary_type,'description',item.description,'delivery_address',meal.delivery_address)
      order by meal.service_date,meal.meal_slot) from public.subscription_meals meal left join public.menu_items item on item.id=meal.selected_menu_item_id
      where meal.subscription_id=target_subscription and meal.service_date between current_date and current_date+6),'[]'::jsonb),
    'payment',(select jsonb_build_object('id',payment.id,'gateway',payment.gateway,'gateway_order_id',payment.gateway_order_id,'gateway_payment_id',payment.gateway_payment_id,
      'status',payment.status,'amount_paise',payment.amount_paise,'captured_at',payment.captured_at) from public.payment_orders payment where payment.subscription_id=target_subscription order by payment.created_at desc limit 1)
  );
end; $$;

revoke all on function public.customer_active_subscription_state() from public;
grant execute on function public.customer_active_subscription_state() to authenticated;
revoke all on function public.finalize_captured_payment(uuid,uuid) from public,anon,authenticated;
grant execute on function public.finalize_captured_payment(uuid,uuid) to service_role;
