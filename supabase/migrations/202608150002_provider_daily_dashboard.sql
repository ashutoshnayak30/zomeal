-- Daily provider operations: subscriptions, meal instances, choices and live dashboard.

create table if not exists public.customer_subscriptions (
  id uuid primary key default gen_random_uuid(),
  customer_id uuid not null references public.profiles(id),
  provider_id uuid not null references public.providers(id),
  package_id uuid not null references public.packages(id),
  status text not null default 'ACTIVE' check(status in ('PENDING','ACTIVE','PAUSED','CANCEL_PENDING','CANCELLED','COMPLETED')),
  start_date date not null,
  end_date date not null,
  delivery_address jsonb not null default '{}'::jsonb,
  total_paid_paise bigint not null default 0 check(total_paid_paise>=0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.subscription_meals (
  id uuid primary key default gen_random_uuid(),
  subscription_id uuid not null references public.customer_subscriptions(id) on delete cascade,
  provider_id uuid not null references public.providers(id),
  customer_id uuid not null references public.profiles(id),
  service_date date not null,
  meal_slot public.meal_slot not null,
  selected_menu_item_id uuid references public.menu_items(id),
  status text not null default 'SCHEDULED' check(status in ('SCHEDULED','PAUSED','CANCELLED','PREPARING','PACKING','READY','OUT_FOR_DELIVERY','DELIVERED')),
  meal_value_paise bigint not null default 0 check(meal_value_paise>=0),
  delivery_address jsonb not null default '{}'::jsonb,
  delivery_personnel_id uuid references public.provider_delivery_personnel(id),
  delivered_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique(subscription_id,service_date,meal_slot)
);

create index if not exists subscription_meals_provider_day_slot_idx
  on public.subscription_meals(provider_id,service_date,meal_slot,status);
create index if not exists subscription_meals_customer_idx
  on public.subscription_meals(customer_id,service_date desc);

alter table public.customer_subscriptions enable row level security;
alter table public.subscription_meals enable row level security;
create policy customer_subscriptions_owner_read on public.customer_subscriptions for select to authenticated using(customer_id=auth.uid());
create policy provider_subscriptions_member_read on public.customer_subscriptions for select to authenticated using(public.is_provider_member(provider_id));
create policy customer_meals_owner_read on public.subscription_meals for select to authenticated using(customer_id=auth.uid());
create policy provider_meals_member_read on public.subscription_meals for select to authenticated using(public.is_provider_member(provider_id));

create trigger customer_subscriptions_set_updated_at before update on public.customer_subscriptions
for each row execute function public.set_updated_at();
create trigger subscription_meals_set_updated_at before update on public.subscription_meals
for each row execute function public.set_updated_at();

create or replace function public.customer_select_daily_meal(target_meal_id uuid,target_item_id uuid)
returns jsonb language plpgsql security definer set search_path=public as $$
declare meal_record public.subscription_meals; cutoff_at timestamptz;
begin
  select * into meal_record from public.subscription_meals where id=target_meal_id and customer_id=auth.uid() for update;
  if meal_record.id is null then raise exception 'Meal was not found'; end if;
  cutoff_at := ((meal_record.service_date + case meal_record.meal_slot when 'LUNCH' then time '07:00' else time '16:00' end) at time zone 'Asia/Kolkata');
  if now()>=cutoff_at then
    raise exception '% changes closed at % IST',initcap(lower(meal_record.meal_slot::text)),case meal_record.meal_slot when 'LUNCH' then '7:00 AM' else '4:00 PM' end;
  end if;
  if not exists(select 1 from public.menu_items i where i.id=target_item_id and i.provider_id=meal_record.provider_id and i.status='APPROVED') then
    raise exception 'This menu item is not available';
  end if;
  update public.subscription_meals set selected_menu_item_id=target_item_id,updated_at=now() where id=target_meal_id;
  return jsonb_build_object('meal_id',target_meal_id,'selected_menu_item_id',target_item_id,'cutoff_at',cutoff_at);
end; $$;

create or replace function public.provider_daily_dashboard(target_slot public.meal_slot,target_date date default null)
returns jsonb language plpgsql stable security definer set search_path=public as $$
declare
  target_provider uuid; provider_name text; current_provider_status public.provider_status; selected_date date;
  cutoff_at timestamptz; capacity_total integer; active_total integer;
begin
  select p.id,p.display_name,p.status into target_provider,provider_name,current_provider_status
  from public.provider_members pm join public.providers p on p.id=pm.provider_id
  where pm.user_id=auth.uid() and pm.is_active
  order by pm.created_at desc limit 1;
  if target_provider is null then raise exception 'A provider account is required'; end if;
  selected_date:=coalesce(target_date,(now() at time zone 'Asia/Kolkata')::date);
  cutoff_at:=((selected_date + case target_slot when 'LUNCH' then time '07:00' else time '16:00' end) at time zone 'Asia/Kolkata');
  select coalesce(sum(capacity_limit),0) into capacity_total from public.provider_capacity
    where provider_id=target_provider and service_date=selected_date and meal_slot=target_slot and is_available;
  select count(*) into active_total from public.subscription_meals
    where provider_id=target_provider and service_date=selected_date and meal_slot=target_slot
      and status not in ('PAUSED','CANCELLED');

  return jsonb_build_object(
    'provider_id',target_provider,'provider_name',provider_name,'provider_status',current_provider_status,
    'preview_mode',current_provider_status<>'ACTIVE','date',selected_date,'slot',target_slot,
    'cutoff_at',cutoff_at,'is_final',now()>=cutoff_at,
    'metrics',jsonb_build_object(
      'active',active_total,'capacity',capacity_total,'remaining',greatest(capacity_total-active_total,0),
      'paused',(select count(*) from public.subscription_meals where provider_id=target_provider and service_date=selected_date and meal_slot=target_slot and status='PAUSED'),
      'cancelled',(select count(*) from public.subscription_meals where provider_id=target_provider and service_date=selected_date and meal_slot=target_slot and status='CANCELLED'),
      'preparing',(select count(*) from public.subscription_meals where provider_id=target_provider and service_date=selected_date and meal_slot=target_slot and status='PREPARING'),
      'packing',(select count(*) from public.subscription_meals where provider_id=target_provider and service_date=selected_date and meal_slot=target_slot and status='PACKING'),
      'ready',(select count(*) from public.subscription_meals where provider_id=target_provider and service_date=selected_date and meal_slot=target_slot and status='READY'),
      'out_for_delivery',(select count(*) from public.subscription_meals where provider_id=target_provider and service_date=selected_date and meal_slot=target_slot and status='OUT_FOR_DELIVERY'),
      'delivered',(select count(*) from public.subscription_meals where provider_id=target_provider and service_date=selected_date and meal_slot=target_slot and status='DELIVERED'),
      'unassigned_delivery',(select count(*) from public.subscription_meals where provider_id=target_provider and service_date=selected_date and meal_slot=target_slot and status not in ('PAUSED','CANCELLED','DELIVERED') and delivery_personnel_id is null),
      'areas',(select count(distinct delivery_address->>'pincode') from public.subscription_meals where provider_id=target_provider and service_date=selected_date and meal_slot=target_slot and status not in ('PAUSED','CANCELLED')),
      'gross_paise',(select coalesce(sum(meal_value_paise),0) from public.subscription_meals where provider_id=target_provider and service_date=selected_date and meal_slot=target_slot and status not in ('PAUSED','CANCELLED'))
    ),
    'package_breakdown',coalesce((
      select jsonb_agg(jsonb_build_object(
        'package_kind',breakdown.package_kind,
        'label',case
          when breakdown.package_kind='LUNCH_ONLY' then 'Lunch-only customers'
          when breakdown.package_kind='DINNER_ONLY' then 'Dinner-only customers'
          when target_slot='LUNCH' then 'Both package · lunch share'
          else 'Both package · dinner share' end,
        'customers',breakdown.customer_count,
        'average_value_paise',breakdown.average_value_paise,
        'gross_paise',breakdown.gross_paise
      ) order by breakdown.package_kind)
      from (
        select p.kind::text package_kind,count(sm.id)::integer customer_count,
          round(avg(sm.meal_value_paise))::bigint average_value_paise,
          coalesce(sum(sm.meal_value_paise),0)::bigint gross_paise
        from public.subscription_meals sm
        join public.customer_subscriptions cs on cs.id=sm.subscription_id
        join public.packages p on p.id=cs.package_id
        where sm.provider_id=target_provider and sm.service_date=selected_date and sm.meal_slot=target_slot
          and sm.status not in ('PAUSED','CANCELLED')
        group by p.kind
      ) breakdown
    ),'[]'::jsonb),
    'choices',coalesce((
      select jsonb_agg(jsonb_build_object('item_id',choice.item_id,'name',choice.name,'count',choice.selected_count) order by choice.selected_count desc,choice.name)
      from (
        select i.id item_id,i.name,count(sm.id)::integer selected_count
        from public.provider_menus m
        join public.menu_days md on md.menu_id=m.id and md.day_of_week=extract(isodow from selected_date)::smallint and md.meal_slot=target_slot
        join public.menu_day_choices mdc on mdc.menu_day_id=md.id and mdc.choice_group='MAIN_COURSE'
        join public.menu_items i on i.id=mdc.menu_item_id
        left join public.subscription_meals sm on sm.selected_menu_item_id=i.id and sm.provider_id=target_provider
          and sm.service_date=selected_date and sm.meal_slot=target_slot and sm.status not in ('PAUSED','CANCELLED')
        where m.provider_id=target_provider and m.status='APPROVED'
        group by i.id,i.name
      ) choice
    ),'[]'::jsonb),
    'manifest',case when now()>=cutoff_at then coalesce((select jsonb_agg(jsonb_build_object(
      'meal_id',sm.id,'customer_name',pr.full_name,'phone',pr.phone,'address',sm.delivery_address,
      'meal_type',sm.meal_slot,'main_course',mi.name,'status',sm.status,
      'delivery_person_id',dp.id,'delivery_person',dp.full_name,'delivery_person_phone',dp.phone) order by sm.delivery_address->>'pincode',sm.delivery_address->>'locality',pr.full_name)
      from public.subscription_meals sm join public.profiles pr on pr.id=sm.customer_id
      left join public.menu_items mi on mi.id=sm.selected_menu_item_id
      left join public.provider_delivery_personnel dp on dp.id=sm.delivery_personnel_id
      where sm.provider_id=target_provider and sm.service_date=selected_date and sm.meal_slot=target_slot
        and sm.status not in ('PAUSED','CANCELLED')),'[]'::jsonb) else '[]'::jsonb end,
    'delivery_people',coalesce((select jsonb_agg(jsonb_build_object('id',d.id,'name',d.full_name,'phone',d.phone,'assigned',
      (select count(*) from public.subscription_meals sm where sm.delivery_personnel_id=d.id and sm.service_date=selected_date and sm.meal_slot=target_slot and sm.status not in ('PAUSED','CANCELLED'))))
      from public.provider_delivery_personnel d where d.provider_id=target_provider and d.is_active),'[]'::jsonb),
    'commission',jsonb_build_object('rate_percent',14,'gross_paise',(select coalesce(sum(meal_value_paise),0) from public.subscription_meals where provider_id=target_provider and service_date=selected_date and meal_slot=target_slot and status not in ('PAUSED','CANCELLED')))
  );
end; $$;

create or replace function public.provider_set_combined_package_values(target_lunch_daily_rupees numeric)
returns jsonb language plpgsql security definer set search_path=public as $$
declare target_provider uuid; target_package uuid; target_price uuid; duration integer; total bigint; lunch_value bigint; dinner_value bigint;
begin
  select provider_id into target_provider from public.provider_members where user_id=auth.uid() and is_active order by created_at desc limit 1;
  select p.id,p.duration_days,pv.id,pv.total_price_paise into target_package,duration,target_price,total
  from public.packages p join public.package_price_versions pv on pv.package_id=p.id
  where p.provider_id=target_provider and p.kind='LUNCH_AND_DINNER' and pv.status='PENDING'
  order by pv.created_at desc limit 1 for update of pv;
  if target_price is null then raise exception 'Pending combined package price was not found'; end if;
  lunch_value:=round(target_lunch_daily_rupees*duration*100);
  dinner_value:=total-lunch_value;
  if lunch_value<=0 or dinner_value<=0 then raise exception 'Lunch and dinner values must both be greater than zero'; end if;
  update public.package_price_versions set lunch_value_paise=lunch_value,dinner_value_paise=dinner_value where id=target_price;
  return jsonb_build_object('total_price_paise',total,'lunch_value_paise',lunch_value,'dinner_value_paise',dinner_value,
    'lunch_daily_paise',round(lunch_value::numeric/duration),'dinner_daily_paise',round(dinner_value::numeric/duration));
end; $$;

create or replace function public.provider_update_daily_meal_status(
  target_slot public.meal_slot,target_date date,new_status text
) returns jsonb language plpgsql security definer set search_path=public as $$
declare target_provider uuid; changed integer; normalized text:=upper(trim(new_status));
begin
  select provider_id into target_provider from public.provider_members
  where user_id=auth.uid() and is_active order by created_at desc limit 1;
  if target_provider is null then raise exception 'Provider account was not found'; end if;
  if normalized not in ('PREPARING','PACKING','READY','OUT_FOR_DELIVERY','DELIVERED') then raise exception 'Unsupported meal status'; end if;
  update public.subscription_meals set status=normalized,
    delivered_at=case when normalized='DELIVERED' then now() else delivered_at end,updated_at=now()
  where provider_id=target_provider and service_date=coalesce(target_date,(now() at time zone 'Asia/Kolkata')::date)
    and meal_slot=target_slot and status not in ('PAUSED','CANCELLED','DELIVERED');
  get diagnostics changed=row_count;
  return jsonb_build_object('updated',changed,'status',normalized);
end; $$;

create or replace function public.provider_assign_delivery_batch(
  target_personnel_id uuid,target_slot public.meal_slot,target_date date,maximum_meals integer default 100
) returns jsonb language plpgsql security definer set search_path=public as $$
declare target_provider uuid; changed integer;
begin
  select provider_id into target_provider from public.provider_members where user_id=auth.uid() and is_active order by created_at desc limit 1;
  if not exists(select 1 from public.provider_delivery_personnel where id=target_personnel_id and provider_id=target_provider and is_active) then
    raise exception 'Active delivery person was not found';
  end if;
  if maximum_meals not between 1 and 100 then raise exception 'A delivery batch must contain between 1 and 100 meals'; end if;
  update public.subscription_meals set delivery_personnel_id=target_personnel_id,updated_at=now()
  where id in (select id from public.subscription_meals where provider_id=target_provider
    and service_date=coalesce(target_date,(now() at time zone 'Asia/Kolkata')::date) and meal_slot=target_slot
    and status not in ('PAUSED','CANCELLED','DELIVERED') and delivery_personnel_id is null
    order by created_at limit maximum_meals);
  get diagnostics changed=row_count;
  return jsonb_build_object('assigned',changed,'delivery_personnel_id',target_personnel_id);
end; $$;

create or replace function public.provider_auto_assign_routes(target_slot public.meal_slot,target_date date default null)
returns jsonb language plpgsql security definer set search_path=public as $$
declare
  target_provider uuid; selected_date date; cutoff_at timestamptz;
  riders uuid[]; rider_count integer; rider_index integer:=1; route record; changed integer:=0; route_changed integer;
begin
  select provider_id into target_provider from public.provider_members where user_id=auth.uid() and is_active order by created_at desc limit 1;
  if target_provider is null then raise exception 'Provider account was not found'; end if;
  selected_date:=coalesce(target_date,(now() at time zone 'Asia/Kolkata')::date);
  cutoff_at:=((selected_date + case target_slot when 'LUNCH' then time '07:00' else time '16:00' end) at time zone 'Asia/Kolkata');
  if now()<cutoff_at then raise exception 'Routes can be assigned only after the customer-change cutoff'; end if;
  select array_agg(id order by is_primary desc,created_at) into riders from public.provider_delivery_personnel where provider_id=target_provider and is_active;
  rider_count:=coalesce(array_length(riders,1),0);
  if rider_count=0 then raise exception 'Add at least one active delivery person'; end if;

  for route in
    select coalesce(delivery_address->>'pincode','') pincode,coalesce(delivery_address->>'locality','') locality,count(*) route_meals
    from public.subscription_meals where provider_id=target_provider and service_date=selected_date and meal_slot=target_slot
      and status not in ('PAUSED','CANCELLED','DELIVERED') and delivery_personnel_id is null
    group by coalesce(delivery_address->>'pincode',''),coalesce(delivery_address->>'locality','')
    order by count(*) desc,pincode,locality
  loop
    update public.subscription_meals set delivery_personnel_id=riders[rider_index],updated_at=now()
    where id in (select id from public.subscription_meals where provider_id=target_provider and service_date=selected_date and meal_slot=target_slot
      and status not in ('PAUSED','CANCELLED','DELIVERED') and delivery_personnel_id is null
      and coalesce(delivery_address->>'pincode','')=route.pincode and coalesce(delivery_address->>'locality','')=route.locality
      order by created_at limit 100);
    get diagnostics route_changed=row_count; changed:=changed+route_changed;
    rider_index:=case when rider_index>=rider_count then 1 else rider_index+1 end;
  end loop;
  return jsonb_build_object('assigned',changed,'delivery_people',rider_count,'strategy','AREA_PINCODE_ROUND_ROBIN');
end; $$;

revoke all on function public.customer_select_daily_meal(uuid,uuid),public.provider_daily_dashboard(public.meal_slot,date),public.provider_update_daily_meal_status(public.meal_slot,date,text),public.provider_assign_delivery_batch(uuid,public.meal_slot,date,integer),public.provider_auto_assign_routes(public.meal_slot,date),public.provider_set_combined_package_values(numeric) from public;
grant execute on function public.customer_select_daily_meal(uuid,uuid),public.provider_daily_dashboard(public.meal_slot,date),public.provider_update_daily_meal_status(public.meal_slot,date,text),public.provider_assign_delivery_batch(uuid,public.meal_slot,date,integer),public.provider_auto_assign_routes(public.meal_slot,date),public.provider_set_combined_package_values(numeric) to authenticated;
