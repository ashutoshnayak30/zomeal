-- User requested Manju fixtures only. Keep both actual provider accounts,
-- Yogesh fixtures and the separately funded 6370648872 test account.
begin;
do $cleanup$
declare
  run constant text := 'zomeal-kitchen-demo-20260913';
  kitchen constant uuid := '9726ff16-eead-4e63-ba58-e797e8cb97ed';
  demo_users uuid[]; subscriptions uuid[]; meal_count integer; removed integer;
begin
  select array_agg(u.id) into demo_users from auth.users u
  where u.raw_app_meta_data->>'zomeal_demo_run'=run
    and exists(select 1 from public.customer_subscriptions s where s.customer_id=u.id and s.provider_id=kitchen
      and s.payment_reference='DEMO:'||run||':'||s.id::text);
  if coalesce(cardinality(demo_users),0)<>12 then raise exception 'Expected exactly 12 Manju demo identities; review before deletion'; end if;
  perform 1 from auth.users where id=any(demo_users) for update;
  perform 1 from public.customer_subscriptions where customer_id=any(demo_users) for update;
  select array_agg(id) into subscriptions from public.customer_subscriptions where customer_id=any(demo_users);
  if cardinality(subscriptions)<>12
    or exists(select 1 from public.customer_subscriptions where customer_id=any(demo_users)
      and (provider_id<>kitchen or payment_reference is distinct from 'DEMO:'||run||':'||id::text or total_paid_paise<>0))
    or exists(select 1 from public.subscription_meals where subscription_id=any(subscriptions)
      and (provider_id<>kitchen or delivery_address->>'demo_run_id' is distinct from run or meal_value_paise<>0))
    or exists(select 1 from public.provider_financial_ledger where subscription_id=any(subscriptions)
      and (gross_paise<>0 or provider_net_paise<>0 or commission_paise<>0))
    or exists(select 1 from public.payment_orders where customer_id=any(demo_users))
    or exists(select 1 from public.customer_wallet_entries where customer_id=any(demo_users))
    or exists(select 1 from public.customer_wallets where customer_id=any(demo_users) and balance_paise<>0)
    or exists(select 1 from public.finance_journal_lines where subscription_id=any(subscriptions))
    or exists(select 1 from public.provider_members where user_id=any(demo_users)) then
    raise exception 'Unexpected non-demo/financial records; no deletion performed';
  end if;
  select count(*) into meal_count from public.subscription_meals where subscription_id=any(subscriptions);
  delete from public.provider_financial_ledger where subscription_id=any(subscriptions);
  delete from public.customer_subscriptions where id=any(subscriptions);
  delete from auth.users where id=any(demo_users) and raw_app_meta_data->>'zomeal_demo_run'=run;
  get diagnostics removed=row_count;
  insert into public.audit_logs(action,entity_type,entity_id,after_data,metadata)
  values('DEMO_MANJU_DATA_CLEARED','demo_run',run,
    jsonb_build_object('removed_demo_identities',removed,'removed_subscriptions',cardinality(subscriptions),'removed_meals',meal_count),
    jsonb_build_object('provider_id',kitchen,'customer_ids',demo_users,'subscription_ids',subscriptions,'user_requested',true));
end;
$cleanup$;
commit;
select p.display_name,count(distinct u.id) demo_users,count(m.id) demo_meals
from public.providers p left join public.customer_subscriptions s on s.provider_id=p.id
left join auth.users u on u.id=s.customer_id and u.raw_app_meta_data->>'zomeal_demo_run'='zomeal-kitchen-demo-20260913'
left join public.subscription_meals m on m.subscription_id=s.id and u.id is not null
where p.id in ('9726ff16-eead-4e63-ba58-e797e8cb97ed','07eeb007-ed44-47ad-92c7-b82d9f51b6c1') group by p.id,p.display_name;
