-- The customer wallet is the single source of truth for prepaid money.
-- Meal charges run in the database (not on the handset), are idempotent, and
-- use the per-meal price snapshotted when the subscription was created.

alter table public.customer_subscriptions
  add column if not exists pause_reason text;

alter table public.subscription_meals
  add column if not exists wallet_charged_at timestamptz;

-- Enabling the ledger must never retroactively charge already-served meals.
update public.subscription_meals
set wallet_charged_at=now()
where wallet_charged_at is null
  and now()>=((service_date+case meal_slot when 'LUNCH' then time '14:00' else time '22:00' end) at time zone 'Asia/Kolkata');

create table if not exists public.customer_notifications (
  id uuid primary key default gen_random_uuid(),
  customer_id uuid not null references public.profiles(id) on delete cascade,
  category text not null,
  title text not null,
  message text not null,
  destination text not null default 'home',
  dedupe_key text not null,
  read_at timestamptz,
  created_at timestamptz not null default now(),
  unique(customer_id,dedupe_key)
);

create index if not exists customer_notifications_feed_idx
  on public.customer_notifications(customer_id,created_at desc);
alter table public.customer_notifications enable row level security;
drop policy if exists customer_notifications_read_own on public.customer_notifications;
create policy customer_notifications_read_own on public.customer_notifications
  for select to authenticated using(customer_id=auth.uid());
drop policy if exists customer_notifications_update_own on public.customer_notifications;
create policy customer_notifications_update_own on public.customer_notifications
  for update to authenticated using(customer_id=auth.uid()) with check(customer_id=auth.uid());
revoke all on public.customer_notifications from anon,authenticated;
grant select,update on public.customer_notifications to authenticated;

create or replace function public.customer_notification_feed(target_limit integer default 50)
returns jsonb language sql stable security definer set search_path=public as $$
  select jsonb_build_object('items',coalesce(jsonb_agg(jsonb_build_object(
    'id',id,'category',category,'title',title,'message',message,
    'destination',destination,'created_at',created_at,'read',read_at is not null
  ) order by created_at desc),'[]'::jsonb))
  from (select * from public.customer_notifications where customer_id=auth.uid()
        order by created_at desc limit least(greatest(target_limit,1),100)) n;
$$;

create or replace function public.customer_mark_notifications_read(target_notification uuid default null,target_read boolean default true)
returns integer language plpgsql security definer set search_path=public as $$
declare changed integer;
begin
  update public.customer_notifications
  set read_at=case when target_read then coalesce(read_at,now()) else null end
  where customer_id=auth.uid() and (target_notification is null or id=target_notification);
  get diagnostics changed=row_count;
  return changed;
end; $$;

grant execute on function public.customer_notification_feed(integer),public.customer_mark_notifications_read(uuid,boolean) to authenticated;

create or replace function public.customer_select_daily_meal(target_meal_id uuid,target_item_id uuid)
returns jsonb language plpgsql security definer set search_path=public as $$
declare meal_record public.subscription_meals; cutoff_at timestamptz;
begin
  select * into meal_record from public.subscription_meals where id=target_meal_id and customer_id=auth.uid() for update;
  if meal_record.id is null then raise exception 'Meal was not found'; end if;
  cutoff_at:=((meal_record.service_date+case meal_record.meal_slot when 'LUNCH' then time '08:00' else time '16:00' end) at time zone 'Asia/Kolkata');
  if now()>=cutoff_at then
    raise exception '% changes closed at % IST',initcap(lower(meal_record.meal_slot::text)),case meal_record.meal_slot when 'LUNCH' then '8:00 AM' else '4:00 PM' end;
  end if;
  if not exists(select 1 from public.menu_items i where i.id=target_item_id and i.provider_id=meal_record.provider_id and i.status='APPROVED') then raise exception 'This menu item is not available'; end if;
  update public.subscription_meals set selected_menu_item_id=target_item_id,updated_at=now() where id=target_meal_id;
  return jsonb_build_object('meal_id',target_meal_id,'selected_menu_item_id',target_item_id,'cutoff_at',cutoff_at);
end; $$;

create or replace function public.customer_pause_subscription_meals(target_subscription uuid,target_dates date[],target_slot text)
returns jsonb language plpgsql security definer set search_path=public as $$
declare changed integer; normalized text:=upper(trim(target_slot));
begin
  if normalized not in ('LUNCH','DINNER','BOTH') then raise exception 'Choose lunch, dinner or both'; end if;
  if coalesce(array_length(target_dates,1),0)=0 or array_length(target_dates,1)>14 then raise exception 'Choose between 1 and 14 dates'; end if;
  update public.subscription_meals meal set status='PAUSED',updated_at=now()
  where meal.subscription_id=target_subscription and meal.customer_id=auth.uid() and meal.service_date=any(target_dates)
    and(normalized='BOTH' or meal.meal_slot::text=normalized) and meal.status='SCHEDULED'
    and now()<((meal.service_date+case meal.meal_slot when 'LUNCH' then time '08:00' else time '16:00' end) at time zone 'Asia/Kolkata');
  get diagnostics changed=row_count;
  return jsonb_build_object('updated_meals',changed,'status','PAUSED','lunch_cutoff','08:00','dinner_cutoff','16:00');
end; $$;

-- One helper credits any captured Razorpay payment to the wallet exactly once.
create or replace function public.credit_captured_payment_to_wallet(target_payment_order uuid,target_customer uuid,target_description text)
returns bigint language plpgsql security definer set search_path=public as $$
declare pay public.payment_orders; wallet_balance bigint;
begin
  select * into pay from public.payment_orders where id=target_payment_order for update;
  if pay.id is null or pay.customer_id is distinct from target_customer or pay.status<>'CAPTURED' then
    raise exception 'Captured customer payment was not found';
  end if;
  insert into public.customer_wallets(customer_id) values(target_customer) on conflict do nothing;
  insert into public.customer_wallet_entries(customer_id,entry_type,amount_paise,description,reference_type,reference_id,metadata)
  values(target_customer,'RECHARGE',pay.amount_paise,target_description,'payment_order',pay.id::text,
    jsonb_build_object('gateway_payment_id',pay.gateway_payment_id,'receipt',pay.receipt,
      'purpose',coalesce(pay.checkout_payload->>'purpose','SUBSCRIPTION_ADVANCE')))
  on conflict(customer_id,entry_type,reference_type,reference_id) do nothing;
  if found then
    update public.customer_wallets set balance_paise=balance_paise+pay.amount_paise,
      lifetime_credit_paise=lifetime_credit_paise+pay.amount_paise,updated_at=now()
    where customer_id=target_customer;
  end if;
  select balance_paise into wallet_balance from public.customer_wallets where customer_id=target_customer;

  -- Only subscriptions paused automatically for insufficient funds resume.
  update public.customer_subscriptions set status='ACTIVE',pause_reason=null,updated_at=now()
  where customer_id=target_customer and status='PAUSED' and pause_reason='INSUFFICIENT_WALLET'
    and end_date>=(now() at time zone 'Asia/Kolkata')::date;
  update public.subscription_meals meal set status='SCHEDULED',updated_at=now()
  from public.customer_subscriptions sub
  where meal.subscription_id=sub.id and sub.customer_id=target_customer and sub.status='ACTIVE'
    and meal.status='PAUSED' and meal.wallet_charged_at is null
    and (meal.service_date>(now() at time zone 'Asia/Kolkata')::date
      or (meal.service_date=(now() at time zone 'Asia/Kolkata')::date
        and (now() at time zone 'Asia/Kolkata')::time<case meal.meal_slot when 'LUNCH' then time '08:00' else time '16:00' end));
  return wallet_balance;
end; $$;

revoke all on function public.credit_captured_payment_to_wallet(uuid,uuid,text) from public,anon,authenticated;
grant execute on function public.credit_captured_payment_to_wallet(uuid,uuid,text) to service_role;

-- Apply captured payments once. Plan advances and later payments are wallet
-- credits; the subscription stores the captured total for audit/referrals.
create or replace function public.apply_captured_payment(target_payment_order uuid,target_customer uuid)
returns jsonb language plpgsql security definer set search_path=public as $$
declare pay public.payment_orders; sub public.customer_subscriptions; result jsonb; wallet_balance bigint;
begin
  select * into pay from public.payment_orders where id=target_payment_order for update;
  if pay.id is null then raise exception 'Payment order was not found'; end if;
  if pay.customer_id is distinct from target_customer then raise exception 'Payment customer does not match'; end if;
  if pay.status<>'CAPTURED' then raise exception 'Payment has not been captured'; end if;
  if pay.capture_applied_at is not null then
    select balance_paise into wallet_balance from public.customer_wallets where customer_id=target_customer;
    return jsonb_build_object('subscription_id',pay.subscription_id,'wallet_balance_paise',coalesce(wallet_balance,0),'already_applied',true);
  end if;

  if coalesce(pay.checkout_payload->>'purpose','')='WALLET_RECHARGE' then
    if pay.provider_id is not null or pay.package_id is not null or pay.subscription_id is not null or pay.amount_paise not between 500 and 1000000 then
      raise exception 'Invalid wallet recharge payment';
    end if;
    wallet_balance:=public.credit_captured_payment_to_wallet(pay.id,target_customer,'Money added securely through Razorpay');
    update public.payment_orders set capture_applied_at=now() where id=pay.id;
    return jsonb_build_object('wallet_balance_paise',wallet_balance,'already_applied',false,'purpose','WALLET_RECHARGE');
  end if;

  if coalesce(pay.checkout_payload->>'purpose','')='PLAN_BALANCE' then
    select * into sub from public.customer_subscriptions where id=(pay.checkout_payload->>'subscription_id')::uuid for update;
    if sub.id is null or sub.customer_id is distinct from target_customer then raise exception 'Subscription payment does not match'; end if;
    update public.customer_subscriptions set total_paid_paise=total_paid_paise+pay.amount_paise,updated_at=now() where id=sub.id;
    update public.payment_orders set subscription_id=sub.id where id=pay.id;
    wallet_balance:=public.credit_captured_payment_to_wallet(pay.id,target_customer,'Subscription payment added to wallet');
    update public.payment_orders set capture_applied_at=now() where id=pay.id;
    return jsonb_build_object('subscription_id',sub.id,'wallet_balance_paise',wallet_balance,'already_applied',false);
  end if;

  result:=public.finalize_captured_payment(pay.id,target_customer);
  wallet_balance:=public.credit_captured_payment_to_wallet(pay.id,target_customer,'Subscription advance added to wallet');
  update public.payment_orders set capture_applied_at=now() where id=pay.id;
  return result||jsonb_build_object('wallet_balance_paise',wallet_balance,'already_applied',false);
end; $$;
revoke all on function public.apply_captured_payment(uuid,uuid) from public,anon,authenticated;
grant execute on function public.apply_captured_payment(uuid,uuid) to service_role;

create or replace function public.process_due_customer_meal_charges(target_now timestamptz default now())
returns jsonb language plpgsql security definer set search_path=public as $$
declare meal record; wallet_balance bigint; charged integer:=0; paused integer:=0; low_alerts integer:=0;
begin
  for meal in
    select sm.*,p.display_name provider_name
    from public.subscription_meals sm
    join public.customer_subscriptions cs on cs.id=sm.subscription_id
    join public.providers p on p.id=sm.provider_id
    where cs.status='ACTIVE' and sm.status not in ('PAUSED','CANCELLED') and sm.wallet_charged_at is null
      and target_now>=((sm.service_date+case sm.meal_slot when 'LUNCH' then time '14:00' else time '22:00' end) at time zone 'Asia/Kolkata')
    order by sm.service_date,sm.meal_slot,sm.created_at
    for update of sm skip locked
  loop
    insert into public.customer_wallets(customer_id) values(meal.customer_id) on conflict do nothing;
    select balance_paise into wallet_balance from public.customer_wallets where customer_id=meal.customer_id for update;
    if wallet_balance>=meal.meal_value_paise then
      insert into public.customer_wallet_entries(customer_id,entry_type,amount_paise,description,reference_type,reference_id,metadata)
      values(meal.customer_id,'SUBSCRIPTION_DEBIT',-meal.meal_value_paise,
        initcap(lower(meal.meal_slot::text))||' on '||to_char(meal.service_date,'DD Mon YYYY')||' · '||meal.provider_name,
        'subscription_meal',meal.id::text,jsonb_build_object('subscription_id',meal.subscription_id,'provider_id',meal.provider_id,
          'service_date',meal.service_date,'meal_slot',meal.meal_slot,'price_paise',meal.meal_value_paise))
      on conflict(customer_id,entry_type,reference_type,reference_id) do nothing;
      if found then
        update public.customer_wallets set balance_paise=balance_paise-meal.meal_value_paise,updated_at=target_now
        where customer_id=meal.customer_id returning balance_paise into wallet_balance;
        charged:=charged+1;
      end if;
      update public.subscription_meals set wallet_charged_at=target_now,updated_at=target_now where id=meal.id;
      if wallet_balance<50000 then
        insert into public.customer_notifications(customer_id,category,title,message,destination,dedupe_key)
        values(meal.customer_id,'Payment','Wallet balance is low',
          'Your wallet balance is '||to_char(wallet_balance/100.0,'FM₹999999990.00')||'. Recharge soon to avoid pausing your subscription.',
          'wallet','LOW_WALLET_'||meal.service_date::text)
        on conflict(customer_id,dedupe_key) do nothing;
        if found then low_alerts:=low_alerts+1; end if;
      end if;
    else
      update public.customer_subscriptions set status='PAUSED',pause_reason='INSUFFICIENT_WALLET',updated_at=target_now
      where id=meal.subscription_id and status='ACTIVE';
      update public.subscription_meals set status='PAUSED',updated_at=target_now
      where subscription_id=meal.subscription_id and wallet_charged_at is null and status not in('PAUSED','CANCELLED','DELIVERED')
        and service_date>=meal.service_date;
      insert into public.customer_notifications(customer_id,category,title,message,destination,dedupe_key)
      values(meal.customer_id,'Payment','Subscription paused — recharge wallet',
        'Your wallet does not have enough balance for this meal. Recharge it to resume upcoming deliveries.',
        'wallet','WALLET_PAUSED_'||meal.subscription_id::text)
      on conflict(customer_id,dedupe_key) do update set message=excluded.message,created_at=target_now,read_at=null;
      paused:=paused+1;
    end if;
  end loop;
  return jsonb_build_object('charged',charged,'subscriptions_paused',paused,'low_balance_alerts',low_alerts,'processed_at',target_now);
end; $$;

revoke all on function public.process_due_customer_meal_charges(timestamptz) from public,anon,authenticated;
grant execute on function public.process_due_customer_meal_charges(timestamptz) to service_role;

-- Hosted Supabase runs the ledger every five minutes. The function itself
-- decides whether the 2 PM / 10 PM IST charge time has arrived.
create extension if not exists pg_cron with schema extensions;
do $$
declare existing_job bigint;
begin
  select jobid into existing_job from cron.job where jobname='zomeal-meal-wallet-ledger';
  if existing_job is not null then perform cron.unschedule(existing_job); end if;
  perform cron.schedule('zomeal-meal-wallet-ledger','*/5 * * * *','select public.process_due_customer_meal_charges();');
end $$;
