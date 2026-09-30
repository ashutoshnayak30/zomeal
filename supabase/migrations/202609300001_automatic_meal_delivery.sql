-- Explicit business policy: time-based delivery completion, not rider confirmation.
alter table public.subscription_meals add column auto_delivered_at timestamptz;

create function public.process_automatic_meal_delivery(target_now timestamptz default now())
returns integer language plpgsql security definer set search_path=public as $$
declare meal record; changed integer:=0; local_now timestamp:=target_now at time zone 'Asia/Kolkata';
begin
 for meal in
  select sm.id,sm.status from subscription_meals sm
  join customer_subscriptions cs on cs.id=sm.subscription_id
  left join customer_wallets w on w.customer_id=sm.customer_id
  where cs.status='ACTIVE' and sm.service_date=local_now::date
   and sm.service_date between cs.start_date and cs.end_date
   and sm.status in ('SCHEDULED','PREPARING','PACKING','READY','OUT_FOR_DELIVERY')
   and (sm.wallet_charged_at is not null or coalesce(w.balance_paise,0)>=sm.meal_value_paise)
   and local_now>=sm.service_date+case sm.meal_slot when 'LUNCH' then time '14:30' else time '21:50' end
  order by sm.id for update of sm skip locked
 loop
  update subscription_meals set status='DELIVERED',delivered_at=target_now,
   auto_delivered_at=target_now,updated_at=target_now where id=meal.id;
  insert into audit_logs(action,entity_type,entity_id,after_data,metadata)
  values('MEAL_AUTO_DELIVERED','subscription_meal',meal.id::text,
   jsonb_build_object('status','DELIVERED','previous_status',meal.status),
   jsonb_build_object('source','TIME_BASED_POLICY','processed_at',target_now));
  changed:=changed+1;
 end loop;
 return changed;
end $$;
revoke all on function public.process_automatic_meal_delivery(timestamptz) from public,anon,authenticated;
-- HOSTED DISPATCH START
select cron.schedule('zomeal-automatic-meal-delivery','* * * * *','select public.process_automatic_meal_delivery();');
-- HOSTED DISPATCH END
