-- Guest portions are real meal instances: existing kitchen counts, routing,
-- delivery events, ledger and commission all operate on each portion.
alter table public.subscription_meals add column guest_number smallint not null default 0 check(guest_number between 0 and 3);
alter table public.subscription_meals add column guest_surcharge_paise bigint not null default 0 check(guest_surcharge_paise in (0,1000));
alter table public.subscription_meals add constraint guest_surcharge_valid check((guest_number=0 and guest_surcharge_paise=0) or (guest_number>0 and guest_surcharge_paise=1000));
alter table public.subscription_meals drop constraint subscription_meals_subscription_id_service_date_meal_slot_key;
alter table public.subscription_meals add unique(subscription_id,service_date,meal_slot,guest_number);

-- Keep regular meal generation idempotent (its guest_number defaults to zero).
do $$ declare r record; definition text; begin
 for r in select p.oid from pg_proc p join pg_namespace n on n.oid=p.pronamespace
 where n.nspname='public' and p.prokind='f' and p.prosrc ~* 'on conflict\s*\(subscription_id,\s*service_date,\s*meal_slot\)' loop
  definition:=pg_get_functiondef(r.oid);
  execute regexp_replace(definition,'on conflict\s*\(subscription_id,\s*service_date,\s*meal_slot\)',
   'on conflict(subscription_id,service_date,meal_slot,guest_number)','gi');
 end loop;
end $$;

-- Regular home cards must never pick a guest's different dish.
alter function public.customer_active_subscription_state() rename to customer_active_subscription_state_before_guests;
revoke all on function public.customer_active_subscription_state_before_guests() from public,anon,authenticated;
create function public.customer_active_subscription_state() returns jsonb language plpgsql stable security definer set search_path=public as $$
declare result jsonb; begin
 result:=customer_active_subscription_state_before_guests();
 if result ? 'daily_meals' then
 result:=jsonb_set(result,'{daily_meals}',coalesce((select jsonb_agg(x) from jsonb_array_elements(result->'daily_meals') x
 where exists(select 1 from subscription_meals m where m.id=(x->>'id')::uuid and m.guest_number=0)),'[]'::jsonb));
 end if;
 return result;
end $$;

create function public.customer_guest_menu(target_subscription uuid) returns jsonb language plpgsql stable security definer set search_path=public as $$
declare s customer_subscriptions; begin
 select * into s from customer_subscriptions where id=target_subscription and customer_id=auth.uid();
 if s.id is null then raise exception 'Subscription not found'; end if;
 return jsonb_build_object('surcharge_paise',1000,'maximum_guests',3,'items',coalesce((
 select jsonb_agg(jsonb_build_object('meal_id',m.id,'date',m.service_date,'slot',m.meal_slot,
 'price_paise',m.meal_value_paise+1000,'editable',now()<((m.service_date+case m.meal_slot when 'LUNCH' then time '08:00' else time '18:00' end) at time zone 'Asia/Kolkata'),
 'choices',coalesce((select jsonb_agg(jsonb_build_object('id',i.id,'name',i.name,'diet',i.dietary_type) order by c.display_order,i.name)
 from menu_days d join menu_day_choices c on c.menu_day_id=d.id join menu_items i on i.id=coalesce((select dc.new_item from dated_menu_changes dc where dc.provider_id=m.provider_id and dc.old_item=c.menu_item_id and dc.meal_slot=m.meal_slot and m.service_date=any(dc.service_dates) and dc.status='APPROVED' order by dc.reviewed_at desc limit 1),c.menu_item_id)
 where d.menu_id=current_side_menu(m.provider_id,m.service_date) and d.day_of_week=extract(isodow from m.service_date)
 and d.meal_slot=m.meal_slot and d.is_available and c.choice_group='MAIN_COURSE' and i.status='APPROVED'),'[]'::jsonb),
 'guests',coalesce((select jsonb_agg(jsonb_build_object('number',g.guest_number,'item_id',g.selected_menu_item_id,'status',g.status) order by g.guest_number)
 from subscription_meals g where g.subscription_id=m.subscription_id and g.service_date=m.service_date and g.meal_slot=m.meal_slot and g.guest_number>0 and g.status<>'CANCELLED'),'[]'::jsonb)) order by m.service_date,m.meal_slot)
 from subscription_meals m where m.subscription_id=s.id and m.guest_number=0 and m.service_date>=(now() at time zone 'Asia/Kolkata')::date and m.service_date<=s.end_date),'[]'::jsonb));
end $$;

-- Explicit full replacement of guest selections for each chosen date/slot.
-- Repeating the same request updates those seats; it never adds duplicates.
create function public.customer_save_guest_meals(target_subscription uuid,selections jsonb) returns jsonb
language plpgsql security definer set search_path=public as $$
declare s customer_subscriptions; row jsonb; base subscription_meals; chosen uuid; seat integer; count_guests integer; total bigint:=0; cap integer; used integer;
begin
 select * into s from customer_subscriptions where id=target_subscription and customer_id=auth.uid() and status='ACTIVE' for update;
 if s.id is null then raise exception 'An active subscription is required'; end if;
 if jsonb_typeof(selections)<>'array' or jsonb_array_length(selections) not between 1 and 62 then raise exception 'Choose valid meal dates'; end if;
 perform 1 from providers where id=s.provider_id for update;
 for row in select value from jsonb_array_elements(selections) loop
  select * into base from subscription_meals where subscription_id=s.id and guest_number=0 and id=(row->>'meal_id')::uuid for update;
  if base.id is null or base.service_date not between s.start_date and s.end_date or base.provider_id<>s.provider_id then raise exception 'Meal does not belong to this plan'; end if;
  if now()>=((base.service_date+case base.meal_slot when 'LUNCH' then time '08:00' else time '18:00' end) at time zone 'Asia/Kolkata') then raise exception 'Guest booking cutoff has passed'; end if;
  if jsonb_typeof(row->'items')<>'array' then raise exception 'Select guest dishes'; end if;
  count_guests:=jsonb_array_length(row->'items');
  if count_guests not between 0 and 3 then raise exception 'Maximum 3 guest meals per lunch or dinner'; end if;
  if count_guests>0 and (row->>'expected_price_paise')::bigint is distinct from base.meal_value_paise+1000 then raise exception 'Meal price changed. Refresh and confirm the new total'; end if;
  if exists(select 1 from subscription_meals where subscription_id=s.id and service_date=base.service_date and meal_slot=base.meal_slot and guest_number>0 and (wallet_charged_at is not null or status not in ('SCHEDULED','PAUSED','CANCELLED'))) then raise exception 'Guest meals are already being prepared'; end if;
  select coalesce(sum(capacity_limit),0) into cap from provider_capacity where provider_id=s.provider_id and meal_slot=base.meal_slot and service_date=base.service_date and pincode=base.delivery_address->>'pincode' and is_available;
  select count(*) into used from subscription_meals where provider_id=s.provider_id and service_date=base.service_date and meal_slot=base.meal_slot and delivery_address->>'pincode'=base.delivery_address->>'pincode' and status not in('PAUSED','CANCELLED') and not(subscription_id=s.id and guest_number>0);
  if count_guests>0 and used+count_guests>cap then raise exception 'Kitchen capacity is full for this meal'; end if;
  update subscription_meals set status='CANCELLED',updated_at=now() where subscription_id=s.id and service_date=base.service_date and meal_slot=base.meal_slot and guest_number>count_guests;
  for seat in 1..count_guests loop
   chosen:=(row->'items'->>(seat-1))::uuid;
   if not exists(select 1 from menu_days d join menu_day_choices c on c.menu_day_id=d.id join menu_items i on i.id=coalesce((select dc.new_item from dated_menu_changes dc where dc.provider_id=base.provider_id and dc.old_item=c.menu_item_id and dc.meal_slot=base.meal_slot and base.service_date=any(dc.service_dates) and dc.status='APPROVED' order by dc.reviewed_at desc limit 1),c.menu_item_id)
    where d.menu_id=current_side_menu(base.provider_id,base.service_date) and d.day_of_week=extract(isodow from base.service_date)
    and d.meal_slot=base.meal_slot and d.is_available and c.choice_group='MAIN_COURSE' and i.id=chosen and i.status='APPROVED') then raise exception 'Guest dish is not available for this date'; end if;
   insert into subscription_meals(subscription_id,provider_id,customer_id,service_date,meal_slot,selected_menu_item_id,meal_value_paise,delivery_address,guest_number,guest_surcharge_paise,side_dishes)
   values(s.id,base.provider_id,s.customer_id,base.service_date,base.meal_slot,chosen,base.meal_value_paise+1000,base.delivery_address,seat,1000,base.side_dishes)
   on conflict(subscription_id,service_date,meal_slot,guest_number) do update set selected_menu_item_id=excluded.selected_menu_item_id,status='SCHEDULED',meal_value_paise=excluded.meal_value_paise,delivery_address=excluded.delivery_address,side_dishes=excluded.side_dishes,updated_at=now();
   total:=total+base.meal_value_paise+1000;
  end loop;
 end loop;
 insert into audit_logs(actor_id,action,entity_type,entity_id,after_data) values(auth.uid(),'GUEST_MEALS_SAVED','customer_subscription',s.id::text,selections);
 return jsonb_build_object('saved',true,'total_paise',total,'charged_now',0);
end $$;

-- Require an explicit scope when guests are present; old APKs cannot silently pause them.
alter function public.customer_pause_subscription_meals(uuid,date[],text) rename to customer_pause_subscription_meals_before_guests;
revoke all on function public.customer_pause_subscription_meals_before_guests(uuid,date[],text) from public,anon,authenticated;
create function public.customer_pause_meal_group(target_subscription uuid,target_dates date[],target_slot text,target_group text)
returns jsonb language plpgsql security definer set search_path=public as $$
declare changed integer; begin
 if upper(target_slot) not in('LUNCH','DINNER','BOTH') or target_group not in('OWN','GUESTS','BOTH') then raise exception 'Choose meals to pause'; end if;
 if coalesce(array_length(target_dates,1),0) not between 1 and 14 then raise exception 'Choose 1–14 dates'; end if;
 perform 1 from customer_subscriptions where id=target_subscription and customer_id=auth.uid() for update;
 if not found then raise exception 'Subscription not found'; end if;
 update subscription_meals m set status='PAUSED',updated_at=now()
 where m.subscription_id=target_subscription and m.customer_id=auth.uid() and m.service_date=any(target_dates)
 and (upper(target_slot)='BOTH' or m.meal_slot::text=upper(target_slot)) and m.status='SCHEDULED' and m.wallet_charged_at is null
 and (target_group='BOTH' or (target_group='OWN' and m.guest_number=0) or (target_group='GUESTS' and m.guest_number>0))
 and now()<((m.service_date+case m.meal_slot when 'LUNCH' then time '08:00' else time '18:00' end) at time zone 'Asia/Kolkata');
 get diagnostics changed=row_count;
 return jsonb_build_object('updated_meals',changed);
end $$;
create function public.customer_pause_subscription_meals(target_subscription uuid,target_dates date[],target_slot text)
returns jsonb language plpgsql security definer set search_path=public as $$ begin
 if exists(select 1 from subscription_meals where subscription_id=target_subscription and customer_id=auth.uid() and guest_number>0 and service_date=any(target_dates) and status='SCHEDULED') then
 raise exception 'Guest meals are active. Choose your meals, guest meals or both in the updated app'; end if;
 return customer_pause_meal_group(target_subscription,target_dates,target_slot,'OWN');
end $$;

-- Never move booked guests to a different kitchen/price silently during a switch.
create function public.guard_guest_meal_provider() returns trigger language plpgsql set search_path=public as $$ begin
 if old.guest_number>0 and new.provider_id<>old.provider_id then raise exception 'Cancel upcoming guest meals before changing provider'; end if;
 if new.guest_number>0 and new.status='DELIVERED' and new.wallet_charged_at is null then raise exception 'Guest payment is pending; delivery completion waits for the daily wallet debit'; end if;
 return new;
end $$;
create trigger guard_guest_meal_provider before update of provider_id,status on public.subscription_meals for each row execute function public.guard_guest_meal_provider();

-- The existing charge processor supplies exact-once debits and push notifications.
-- A guest with insufficient funds is skipped, not allowed to pause the whole plan.
do $$ declare definition text; begin
 definition:=pg_get_functiondef('public.process_due_customer_meal_charges(timestamptz)'::regprocedure);
 if position('else'||chr(10)||'      update public.customer_subscriptions' in definition)=0 then raise exception 'Review guest ledger integration: unexpected charge processor'; end if;
 definition:=replace(definition,'else'||chr(10)||'      update public.customer_subscriptions',
 'elsif meal.guest_number>0 then
      update public.subscription_meals set status=''PAUSED'',updated_at=target_now where id=meal.id;
      insert into public.customer_notifications(customer_id,category,title,message,destination,dedupe_key)
      values(meal.customer_id,''Payment'',''Guest meal skipped — low balance'',''Your guest meal could not be charged. Guest meals include ₹10 extra per portion. Recharge for future meals; this skipped meal stays paused.'',''wallet'',''GUEST_LOW_''||meal.id::text)
      on conflict(customer_id,dedupe_key) do nothing;
      insert into public.meal_push_outbox(notification_id,device_id)
      select n.id,d.id from public.customer_notifications n join public.push_device_tokens d on d.user_id=n.customer_id and d.app_kind=''CUSTOMER'' and d.enabled
      where n.customer_id=meal.customer_id and n.dedupe_key=''GUEST_LOW_''||meal.id::text on conflict do nothing;
    else
      update public.customer_subscriptions');
 definition:=replace(definition,'order by sm.service_date,sm.meal_slot,sm.created_at','order by sm.service_date,sm.meal_slot,sm.guest_number,sm.created_at');
 execute definition;
end $$;

-- Guest earnings must not be created by the timer before a successful debit.
do $$ declare definition text; begin
 definition:=pg_get_functiondef('public.process_automatic_meal_delivery(timestamptz)'::regprocedure);
 if position('where cs.status=''ACTIVE''' in definition)=0 then raise exception 'Unexpected automatic delivery implementation'; end if;
 execute replace(definition,'where cs.status=''ACTIVE''','where (sm.guest_number=0 or sm.wallet_charged_at is not null) and cs.status=''ACTIVE''');
end $$;

-- Do not silently resume intentionally paused guests on a wallet recharge.
-- Standard meal reminders continue to describe the customer's own dish.
do $$ declare definition text; begin
 definition:=pg_get_functiondef('public.credit_captured_payment_to_wallet(uuid,uuid,text)'::regprocedure);
 execute replace(definition,'and meal.status=''PAUSED''','and meal.guest_number=0 and meal.status=''PAUSED''');
 definition:=pg_get_functiondef('public.process_personal_meal_reminders(timestamptz)'::regprocedure);
 execute replace(definition,'where sm.service_date=local_now::date','where sm.guest_number=0 and sm.service_date=local_now::date');
 definition:=pg_get_functiondef('public.notify_customer_meal_debit()'::regprocedure);
 execute replace(definition,'slot:=initcap(lower(meal.meal_slot::text));','slot:=case when meal.guest_number>0 then ''Guest ''||meal.guest_number||'' '' else '''' end||initcap(lower(meal.meal_slot::text));');
end $$;

alter function public.provider_daily_dashboard(public.meal_slot,date) rename to provider_daily_dashboard_before_guests;
revoke all on function public.provider_daily_dashboard_before_guests(public.meal_slot,date) from public,anon,authenticated;
create function public.provider_daily_dashboard(target_slot public.meal_slot,target_date date default null)
returns jsonb language plpgsql security definer set search_path=public as $$
declare result jsonb; begin
 result:=provider_daily_dashboard_before_guests(target_slot,target_date);
 if jsonb_typeof(result->'manifest')='array' then
  result:=jsonb_set(result,'{manifest}',coalesce((select jsonb_agg(entry||jsonb_build_object(
   'guest_number',m.guest_number,'customer_name',coalesce(entry->>'customer_name','Customer')||case when m.guest_number>0 then ' · Guest '||m.guest_number else '' end))
   from jsonb_array_elements(result->'manifest') entry join subscription_meals m on m.id::text=entry->>'meal_id'),'[]'::jsonb));
 end if;
 return result;
end $$;
revoke all on function public.provider_daily_dashboard(public.meal_slot,date) from public,anon;
grant execute on function public.provider_daily_dashboard(public.meal_slot,date) to authenticated;

revoke all on function public.customer_active_subscription_state(),public.customer_guest_menu(uuid),public.customer_save_guest_meals(uuid,jsonb),public.customer_pause_meal_group(uuid,date[],text,text),public.customer_pause_subscription_meals(uuid,date[],text) from public,anon;
grant execute on function public.customer_active_subscription_state(),public.customer_guest_menu(uuid),public.customer_save_guest_meals(uuid,jsonb),public.customer_pause_meal_group(uuid,date[],text,text),public.customer_pause_subscription_meals(uuid,date[],text) to authenticated;
