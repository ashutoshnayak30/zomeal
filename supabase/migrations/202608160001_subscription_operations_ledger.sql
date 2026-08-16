-- Subscription operations backbone.
-- Converts a confirmed payment into dated meals, records tracking history,
-- and accrues provider earnings exactly once after delivery.

alter table public.customer_subscriptions
  add column if not exists price_version_id uuid references public.package_price_versions(id),
  add column if not exists payment_reference text,
  add column if not exists package_price_paise bigint not null default 0 check(package_price_paise>=0),
  add column if not exists lunch_component_paise bigint not null default 0 check(lunch_component_paise>=0),
  add column if not exists dinner_component_paise bigint not null default 0 check(dinner_component_paise>=0),
  add column if not exists commission_basis_points integer not null default 1400 check(commission_basis_points between 0 and 10000),
  add column if not exists activated_at timestamptz;

create unique index if not exists customer_subscriptions_payment_reference_unique
  on public.customer_subscriptions(payment_reference) where payment_reference is not null;

create table if not exists public.meal_status_events (
  id bigint generated always as identity primary key,
  meal_id uuid not null references public.subscription_meals(id) on delete cascade,
  subscription_id uuid not null references public.customer_subscriptions(id) on delete cascade,
  provider_id uuid not null references public.providers(id),
  customer_id uuid not null references public.profiles(id),
  old_status text,
  new_status text not null,
  changed_by uuid references public.profiles(id),
  occurred_at timestamptz not null default now(),
  metadata jsonb not null default '{}'::jsonb
);

create index if not exists meal_status_events_customer_time_idx on public.meal_status_events(customer_id,occurred_at desc);
create index if not exists meal_status_events_provider_time_idx on public.meal_status_events(provider_id,occurred_at desc);

create table if not exists public.provider_financial_ledger (
  id uuid primary key default gen_random_uuid(),
  provider_id uuid not null references public.providers(id),
  subscription_id uuid references public.customer_subscriptions(id),
  meal_id uuid references public.subscription_meals(id),
  entry_type text not null check(entry_type in ('MEAL_EARNING','REVERSAL','PAYOUT')),
  meal_slot public.meal_slot,
  service_date date,
  package_kind public.package_kind,
  gross_paise bigint not null,
  commission_basis_points integer not null check(commission_basis_points between 0 and 10000),
  commission_paise bigint not null,
  provider_net_paise bigint not null,
  available_at timestamptz not null,
  reference_entry_id uuid references public.provider_financial_ledger(id),
  external_reference text,
  created_by uuid references public.profiles(id),
  created_at timestamptz not null default now(),
  metadata jsonb not null default '{}'::jsonb,
  constraint provider_ledger_amounts_match check(provider_net_paise=gross_paise-commission_paise),
  constraint provider_ledger_reversal_reference check(entry_type<>'REVERSAL' or reference_entry_id is not null)
);

create unique index if not exists provider_ledger_one_meal_earning on public.provider_financial_ledger(meal_id) where entry_type='MEAL_EARNING';
create index if not exists provider_ledger_provider_available_idx on public.provider_financial_ledger(provider_id,available_at,created_at desc);

alter table public.meal_status_events enable row level security;
alter table public.provider_financial_ledger enable row level security;
create policy meal_status_customer_read on public.meal_status_events for select to authenticated using(customer_id=auth.uid());
create policy meal_status_provider_read on public.meal_status_events for select to authenticated using(public.is_provider_member(provider_id));
create policy meal_status_admin_read on public.meal_status_events for select to authenticated using(public.has_role('ADMIN') or public.has_role('OPERATIONS'));
create policy provider_ledger_member_read on public.provider_financial_ledger for select to authenticated using(public.is_provider_member(provider_id));
create policy provider_ledger_finance_read on public.provider_financial_ledger for select to authenticated using(public.has_role('ADMIN') or public.has_role('FINANCE'));

create or replace function public.current_provider_commission_basis_points()
returns integer language sql stable security definer set search_path=public as $$
  select coalesce((select (value->>'basis_points')::integer from public.platform_settings
    where setting_key='provider_commission' and effective_from<=now()
      and (effective_until is null or effective_until>now()) order by effective_from desc limit 1),1400);
$$;

create or replace function public.default_menu_item_for_day(target_provider uuid,target_date date,target_slot public.meal_slot)
returns uuid language sql stable security definer set search_path=public as $$
  select i.id from public.provider_menus m
  join public.menu_days md on md.menu_id=m.id
  join public.menu_day_choices choice on choice.menu_day_id=md.id and choice.choice_group='MAIN_COURSE'
  join public.menu_items i on i.id=choice.menu_item_id
  where m.provider_id=target_provider and m.status='APPROVED'
    and m.valid_from<=target_date and (m.valid_until is null or m.valid_until>=target_date)
    and md.day_of_week=extract(isodow from target_date)::smallint and md.meal_slot=target_slot
    and md.is_available and i.status='APPROVED'
  order by choice.is_default desc,choice.display_order,i.name limit 1;
$$;

create or replace function public.subscription_menu_item_for_day(
  target_customer uuid,target_package uuid,target_provider uuid,target_date date,target_slot public.meal_slot
) returns uuid language plpgsql stable security definer set search_path=public as $$
declare selected_item uuid;
begin
  select selection.menu_item_id into selected_item
  from public.customer_weekly_menu_templates template
  join public.customer_weekly_menu_selections selection on selection.template_id=template.id
  join public.menu_items item on item.id=selection.menu_item_id
  where template.customer_id=target_customer and template.package_id=target_package and template.is_active
    and selection.day_of_week=extract(isodow from target_date)::smallint
    and selection.meal_slot=target_slot and selection.choice_group='MAIN_COURSE'
    and item.provider_id=target_provider and item.status='APPROVED'
  order by template.updated_at desc,selection.created_at limit 1;
  return coalesce(selected_item,public.default_menu_item_for_day(target_provider,target_date,target_slot));
end; $$;

create or replace function public.admin_confirm_subscription_payment(
  target_customer uuid,target_package uuid,target_start_date date,target_delivery_address jsonb,
  target_total_paid_paise bigint,target_payment_reference text
) returns jsonb language plpgsql security definer set search_path=public as $$
declare
  package_record public.packages; price_record public.package_price_versions; subscription_id uuid;
  target_end_date date; commission_bps integer; service_day date; day_number integer;
  lunch_base bigint; lunch_remainder bigint; dinner_base bigint; dinner_remainder bigint;
  meal_value bigint; meals_created integer:=0;
begin
  if not (public.has_role('ADMIN') or public.has_role('FINANCE') or public.has_role('OPERATIONS')) then
    raise exception 'Only an authorized payment operation can activate a subscription';
  end if;
  if target_payment_reference is null or trim(target_payment_reference)='' then raise exception 'Payment reference is required'; end if;
  select * into package_record from public.packages where id=target_package and is_active for share;
  if package_record.id is null then raise exception 'Active package was not found'; end if;
  if not exists(select 1 from public.providers where id=package_record.provider_id and status='ACTIVE') then raise exception 'Provider is not active'; end if;
  if not exists(select 1 from public.provider_service_areas where provider_id=package_record.provider_id
    and pincode=target_delivery_address->>'pincode' and status='APPROVED'
    and (effective_from is null or effective_from<=target_start_date)
    and (effective_until is null or effective_until>=target_start_date)) then
    raise exception 'Delivery pincode is not approved for this provider';
  end if;
  select * into price_record from public.package_price_versions where package_id=target_package and status='APPROVED'
    and effective_from<=now() and (effective_until is null or effective_until>now()) order by effective_from desc limit 1;
  if price_record.id is null then raise exception 'Approved package price was not found'; end if;
  select id into subscription_id from public.customer_subscriptions where payment_reference=target_payment_reference;
  if subscription_id is not null then return jsonb_build_object('subscription_id',subscription_id,'idempotent',true,'meals_created',0); end if;

  target_end_date:=target_start_date+(package_record.duration_days-1); commission_bps:=public.current_provider_commission_basis_points();
  insert into public.customer_subscriptions(customer_id,provider_id,package_id,price_version_id,status,start_date,end_date,
    delivery_address,total_paid_paise,payment_reference,package_price_paise,lunch_component_paise,dinner_component_paise,
    commission_basis_points,activated_at)
  values(target_customer,package_record.provider_id,target_package,price_record.id,'ACTIVE',target_start_date,target_end_date,
    target_delivery_address,target_total_paid_paise,target_payment_reference,price_record.total_price_paise,
    price_record.lunch_value_paise,price_record.dinner_value_paise,commission_bps,now()) returning id into subscription_id;

  lunch_base:=price_record.lunch_value_paise/package_record.duration_days;
  lunch_remainder:=price_record.lunch_value_paise%package_record.duration_days;
  dinner_base:=price_record.dinner_value_paise/package_record.duration_days;
  dinner_remainder:=price_record.dinner_value_paise%package_record.duration_days;
  for service_day in select generate_series(target_start_date,target_end_date,'1 day'::interval)::date loop
    day_number:=(service_day-target_start_date)+1;
    if package_record.kind in ('LUNCH_ONLY','LUNCH_AND_DINNER') then
      meal_value:=lunch_base+case when day_number<=lunch_remainder then 1 else 0 end;
      insert into public.subscription_meals(subscription_id,provider_id,customer_id,service_date,meal_slot,selected_menu_item_id,meal_value_paise,delivery_address)
      values(subscription_id,package_record.provider_id,target_customer,service_day,'LUNCH',
        public.subscription_menu_item_for_day(target_customer,target_package,package_record.provider_id,service_day,'LUNCH'),meal_value,target_delivery_address);
      meals_created:=meals_created+1;
    end if;
    if package_record.kind in ('DINNER_ONLY','LUNCH_AND_DINNER') then
      meal_value:=dinner_base+case when day_number<=dinner_remainder then 1 else 0 end;
      insert into public.subscription_meals(subscription_id,provider_id,customer_id,service_date,meal_slot,selected_menu_item_id,meal_value_paise,delivery_address)
      values(subscription_id,package_record.provider_id,target_customer,service_day,'DINNER',
        public.subscription_menu_item_for_day(target_customer,target_package,package_record.provider_id,service_day,'DINNER'),meal_value,target_delivery_address);
      meals_created:=meals_created+1;
    end if;
  end loop;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data,metadata)
  values(auth.uid(),'SUBSCRIPTION_ACTIVATED','customer_subscription',subscription_id::text,
    jsonb_build_object('package_id',target_package,'start_date',target_start_date,'end_date',target_end_date,'meals_created',meals_created),
    jsonb_build_object('payment_reference',target_payment_reference));
  return jsonb_build_object('subscription_id',subscription_id,'idempotent',false,'meals_created',meals_created,
    'start_date',target_start_date,'end_date',target_end_date);
end; $$;

create or replace function public.record_meal_status_and_earning()
returns trigger language plpgsql security definer set search_path=public as $$
declare subscription_record public.customer_subscriptions; package_type public.package_kind; commission bigint;
begin
  if old.status is distinct from new.status then
    insert into public.meal_status_events(meal_id,subscription_id,provider_id,customer_id,old_status,new_status,changed_by,metadata)
    values(new.id,new.subscription_id,new.provider_id,new.customer_id,old.status,new.status,auth.uid(),
      jsonb_build_object('delivery_personnel_id',new.delivery_personnel_id));
  end if;
  if new.status='DELIVERED' and old.status is distinct from 'DELIVERED' then
    select * into subscription_record from public.customer_subscriptions where id=new.subscription_id;
    select kind into package_type from public.packages where id=subscription_record.package_id;
    commission:=round(new.meal_value_paise::numeric*subscription_record.commission_basis_points/10000);
    insert into public.provider_financial_ledger(provider_id,subscription_id,meal_id,entry_type,meal_slot,service_date,
      package_kind,gross_paise,commission_basis_points,commission_paise,provider_net_paise,available_at,created_by,metadata)
    values(new.provider_id,new.subscription_id,new.id,'MEAL_EARNING',new.meal_slot,new.service_date,package_type,
      new.meal_value_paise,subscription_record.commission_basis_points,commission,new.meal_value_paise-commission,
      coalesce(new.delivered_at,now())+interval '48 hours',auth.uid(),jsonb_build_object('payment_reference',subscription_record.payment_reference))
    on conflict (meal_id) where entry_type='MEAL_EARNING' do nothing;
  end if;
  return new;
end; $$;

drop trigger if exists subscription_meals_status_ledger on public.subscription_meals;
create trigger subscription_meals_status_ledger after update of status on public.subscription_meals
for each row execute function public.record_meal_status_and_earning();

create or replace function public.provider_earnings_summary()
returns jsonb language plpgsql stable security definer set search_path=public as $$
declare target_provider uuid;
begin
  select provider_id into target_provider from public.provider_members where user_id=auth.uid() and is_active order by created_at desc limit 1;
  if target_provider is null then raise exception 'Provider account was not found'; end if;
  return jsonb_build_object(
    'provider_id',target_provider,
    'gross_paise',coalesce((select sum(gross_paise) from public.provider_financial_ledger where provider_id=target_provider),0),
    'commission_paise',coalesce((select sum(commission_paise) from public.provider_financial_ledger where provider_id=target_provider),0),
    'provider_net_paise',coalesce((select sum(provider_net_paise) from public.provider_financial_ledger where provider_id=target_provider),0),
    'available_paise',coalesce((select sum(provider_net_paise) from public.provider_financial_ledger where provider_id=target_provider and available_at<=now()),0),
    'pending_paise',coalesce((select sum(provider_net_paise) from public.provider_financial_ledger where provider_id=target_provider and available_at>now()),0),
    'by_slot',coalesce((select jsonb_agg(jsonb_build_object('slot',meal_slot,'gross_paise',gross,'commission_paise',commission,'net_paise',net))
      from (select meal_slot,sum(gross_paise) gross,sum(commission_paise) commission,sum(provider_net_paise) net
        from public.provider_financial_ledger where provider_id=target_provider and entry_type in ('MEAL_EARNING','REVERSAL') group by meal_slot) slot_totals),'[]'::jsonb),
    'recent_entries',coalesce((select jsonb_agg(row_data order by created_at desc) from (
      select jsonb_build_object('id',id,'entry_type',entry_type,'meal_slot',meal_slot,'service_date',service_date,
        'gross_paise',gross_paise,'commission_paise',commission_paise,'provider_net_paise',provider_net_paise,
        'available_at',available_at,'created_at',created_at) row_data,created_at
      from public.provider_financial_ledger where provider_id=target_provider order by created_at desc limit 50) recent),'[]'::jsonb)
  );
end; $$;

create or replace function public.customer_subscription_timeline(target_subscription uuid)
returns jsonb language plpgsql stable security definer set search_path=public as $$
begin
  if not exists(select 1 from public.customer_subscriptions where id=target_subscription and customer_id=auth.uid()) then raise exception 'Subscription was not found'; end if;
  return jsonb_build_object(
    'subscription',(select to_jsonb(cs) from public.customer_subscriptions cs where cs.id=target_subscription),
    'meals',coalesce((select jsonb_agg(jsonb_build_object('meal_id',sm.id,'service_date',sm.service_date,
      'meal_slot',sm.meal_slot,'status',sm.status,'main_course',mi.name,'delivery_person',dp.full_name,'updated_at',sm.updated_at)
      order by sm.service_date,sm.meal_slot) from public.subscription_meals sm
      left join public.menu_items mi on mi.id=sm.selected_menu_item_id
      left join public.provider_delivery_personnel dp on dp.id=sm.delivery_personnel_id
      where sm.subscription_id=target_subscription),'[]'::jsonb),
    'events',coalesce((select jsonb_agg(jsonb_build_object('meal_id',e.meal_id,'old_status',e.old_status,
      'new_status',e.new_status,'occurred_at',e.occurred_at) order by e.occurred_at desc)
      from public.meal_status_events e where e.subscription_id=target_subscription),'[]'::jsonb)
  );
end; $$;

revoke all on function public.current_provider_commission_basis_points(),public.default_menu_item_for_day(uuid,date,public.meal_slot),
  public.subscription_menu_item_for_day(uuid,uuid,uuid,date,public.meal_slot),
  public.admin_confirm_subscription_payment(uuid,uuid,date,jsonb,bigint,text),public.provider_earnings_summary(),
  public.customer_subscription_timeline(uuid) from public;
grant execute on function public.admin_confirm_subscription_payment(uuid,uuid,date,jsonb,bigint,text),
  public.provider_earnings_summary(),public.customer_subscription_timeline(uuid) to authenticated;
