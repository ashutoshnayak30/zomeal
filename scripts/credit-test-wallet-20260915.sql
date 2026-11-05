-- One-off user-authorized test credit, not a payment, revenue or referral reward.
begin;
do $test$
declare
  customer constant uuid := '50cb9594-bb66-430d-a279-54791829fcd5';
  run constant text := 'zomeal-test-wallet-6370648872-20260915';
  before_wallet jsonb; entry uuid; resumed uuid[] := '{}'; meals uuid[] := '{}'; sub record;
begin
  if (select count(*) from public.profiles where right(regexp_replace(phone,'[^0-9]','','g'),10)='6370648872')<>1
     or not exists(select 1 from public.profiles where id=customer and phone='6370648872' and is_active) then
    raise exception 'Test account identity changed';
  end if;
  perform 1 from public.customer_wallets where customer_id=customer for update;
  if not found then raise exception 'Expected wallet is missing'; end if;
  if exists(select 1 from public.customer_wallet_entries where customer_id=customer and reference_type='DEMO_TEST_CREDIT' and reference_id=run) then
    raise exception 'Test credit already applied';
  end if;
  select to_jsonb(w) into before_wallet from public.customer_wallets w where customer_id=customer;
  insert into public.customer_wallet_entries(customer_id,entry_type,amount_paise,description,reference_type,reference_id,metadata)
  values(customer,'ADJUSTMENT',300000,'DEMO TEST CREDIT ₹3,000 — not a real payment; remove after testing',
    'DEMO_TEST_CREDIT',run,jsonb_build_object('demo',true,'cleanup_required',true,'cash_payment',false,'before_wallet',before_wallet)) returning id into entry;
  update public.customer_wallets set balance_paise=balance_paise+300000,lifetime_credit_paise=lifetime_credit_paise+300000,updated_at=now() where customer_id=customer;
  for sub in select * from public.customer_subscriptions where customer_id=customer and status='PAUSED'
    and pause_reason='INSUFFICIENT_WALLET' and end_date>=(now() at time zone 'Asia/Kolkata')::date for update
  loop
    -- Only restore meals stamped by the same insufficient-wallet pause operation.
    -- Preserve unrelated/manual pauses, cancellations, processed meals and closed slots.
    with restored as (
      update public.subscription_meals m set status='SCHEDULED',updated_at=now()
      where m.subscription_id=sub.id and m.status='PAUSED' and m.wallet_charged_at is null
        and m.updated_at=sub.updated_at
        and now()<((m.service_date+case m.meal_slot when 'LUNCH' then time '08:00' else time '16:00' end) at time zone 'Asia/Kolkata')
      returning m.id
    ) select meals || coalesce(array_agg(id),'{}'::uuid[]) into meals from restored;
    update public.customer_subscriptions set status='ACTIVE',pause_reason=null,updated_at=now() where id=sub.id;
    resumed := array_append(resumed,sub.id);
  end loop;
  update public.customer_wallet_entries set metadata=metadata || jsonb_build_object('resumed_subscriptions',resumed,'restored_meals',meals)
    where id=entry;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data,metadata)
  values(customer,'DEMO_WALLET_CREDIT','customer_wallet',customer::text,
    jsonb_build_object('credit_paise',300000,'entry_id',entry),jsonb_build_object('run',run,'before_wallet',before_wallet,'resumed_subscriptions',resumed,'restored_meals',meals));
end;
$test$;
commit;
select w.balance_paise,e.id credit_entry,e.metadata->'resumed_subscriptions' resumed_subscriptions,
  jsonb_array_length(e.metadata->'restored_meals') restored_meal_count
from public.customer_wallets w join public.customer_wallet_entries e on e.customer_id=w.customer_id
where w.customer_id='50cb9594-bb66-430d-a279-54791829fcd5' and e.reference_type='DEMO_TEST_CREDIT'
and e.reference_id='zomeal-test-wallet-6370648872-20260915';
