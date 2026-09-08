-- Keep every captured wallet/plan payment in the immutable finance journal.
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
    perform public.post_finance_journal('payment_order',pay.id::text,'WALLET_RECHARGE_CAPTURED',coalesce(pay.captured_at,now()),
      'Customer wallet recharge captured',pay.gateway_payment_id,
      jsonb_build_array(jsonb_build_object('account_code','1000','debit_paise',pay.amount_paise),
        jsonb_build_object('account_code','2210','credit_paise',pay.amount_paise)),
      jsonb_build_object('customer_id',target_customer));
    insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data,metadata)
    values(target_customer,'WALLET_RECHARGE_CAPTURED','customer_wallet',target_customer::text,
      jsonb_build_object('amount_paise',pay.amount_paise,'balance_paise',wallet_balance),jsonb_build_object('payment_order_id',pay.id));
    update public.payment_orders set capture_applied_at=now() where id=pay.id;
    return jsonb_build_object('wallet_balance_paise',wallet_balance,'already_applied',false,'purpose','WALLET_RECHARGE');
  end if;

  if coalesce(pay.checkout_payload->>'purpose','')='PLAN_BALANCE' then
    select * into sub from public.customer_subscriptions where id=(pay.checkout_payload->>'subscription_id')::uuid for update;
    if sub.id is null or sub.customer_id is distinct from target_customer then raise exception 'Subscription payment does not match'; end if;
    update public.customer_subscriptions set total_paid_paise=total_paid_paise+pay.amount_paise,updated_at=now() where id=sub.id;
    update public.payment_orders set subscription_id=sub.id where id=pay.id;
    wallet_balance:=public.credit_captured_payment_to_wallet(pay.id,target_customer,'Subscription payment added to wallet');
    perform public.post_finance_journal('payment_order',pay.id::text,'PLAN_BALANCE_PAYMENT_CAPTURED',coalesce(pay.captured_at,now()),
      'Customer plan payment credited to wallet',pay.gateway_payment_id,
      jsonb_build_array(jsonb_build_object('account_code','1000','debit_paise',pay.amount_paise,'subscription_id',sub.id),
        jsonb_build_object('account_code','2210','credit_paise',pay.amount_paise,'subscription_id',sub.id)),
      jsonb_build_object('provider_id',sub.provider_id,'customer_id',target_customer));
    insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data,metadata)
    values(target_customer,'PLAN_BALANCE_PAYMENT_CAPTURED','customer_subscription',sub.id::text,
      jsonb_build_object('paid_paise',pay.amount_paise,'wallet_balance_paise',wallet_balance),jsonb_build_object('payment_order_id',pay.id));
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
