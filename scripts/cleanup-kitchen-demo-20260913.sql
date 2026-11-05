-- Run ONLY after the user finishes testing. Exact run marker; never provider-wide.
begin;
do $cleanup$
declare run constant text := 'zomeal-kitchen-demo-20260913'; demo_user_ids uuid[]; subscriptions uuid[]; removed integer;
begin
  select array_agg(id) into demo_user_ids from auth.users where raw_app_meta_data->>'zomeal_demo_run'=run;
  if coalesce(cardinality(demo_user_ids),0)=0 then raise exception 'No identities for this exact demo run'; end if;
  select array_agg(id) into subscriptions from public.customer_subscriptions where customer_id=any(demo_user_ids) and payment_reference='DEMO:'||run||':'||id::text;
  if exists(select 1 from public.customer_subscriptions where customer_id=any(demo_user_ids) and (payment_reference is distinct from 'DEMO:'||run||':'||id::text or total_paid_paise<>0))
    or exists(select 1 from public.subscription_meals where subscription_id=any(subscriptions) and (delivery_address->>'demo_run_id' is distinct from run or meal_value_paise<>0))
    or exists(select 1 from public.provider_financial_ledger where subscription_id=any(subscriptions) and (gross_paise<>0 or provider_net_paise<>0 or commission_paise<>0))
    or exists(select 1 from public.payment_orders where customer_id=any(demo_user_ids))
    or exists(select 1 from public.customer_wallet_entries where customer_id=any(demo_user_ids) and amount_paise<>0)
    or exists(select 1 from public.customer_wallets where customer_id=any(demo_user_ids) and balance_paise<>0)
    or exists(select 1 from public.finance_journal_lines where subscription_id=any(subscriptions)) then
    raise exception 'Unexpected financial or non-demo data linked to test identities. No deletion performed.';
  end if;
  -- Zero-value delivered-meal audit entries may be created while operating the demo.
  delete from public.provider_financial_ledger where subscription_id=any(subscriptions);
  delete from public.customer_subscriptions where id=any(subscriptions);
  -- Auth deletion cascades only to these tagged profiles/roles and their own children.
  delete from auth.users where id=any(demo_user_ids) and raw_app_meta_data->>'zomeal_demo_run'=run;
  get diagnostics removed=row_count;
  insert into public.audit_logs(action,entity_type,entity_id,after_data)
  values('DEMO_KITCHEN_DATA_CLEARED','demo_run',run,jsonb_build_object('removed_demo_identities',removed,'financial_values',0));
end
$cleanup$;
commit;
