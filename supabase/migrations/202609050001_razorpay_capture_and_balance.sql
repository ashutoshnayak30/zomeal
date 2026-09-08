-- Idempotent Razorpay capture application and subsequent plan-balance payments.
alter table public.payment_orders add column capture_applied_at timestamptz;
create unique index payment_one_open_balance_order_idx on public.payment_orders(subscription_id)
where status in ('CREATING','CREATED','AUTHORIZED') and checkout_payload->>'purpose'='PLAN_BALANCE';

create or replace function public.apply_captured_payment(target_payment_order uuid,target_customer uuid)
returns jsonb language plpgsql security definer set search_path=public as $$
declare pay public.payment_orders; sub public.customer_subscriptions; initial_total bigint; remaining bigint; result jsonb;
begin
  select * into pay from public.payment_orders where id=target_payment_order for update;
  if pay.id is null then raise exception 'Payment order was not found'; end if;
  if pay.customer_id is distinct from target_customer then raise exception 'Payment customer does not match'; end if;
  if pay.status<>'CAPTURED' then raise exception 'Payment has not been captured'; end if;
  if pay.capture_applied_at is not null then
    return jsonb_build_object('subscription_id',pay.subscription_id,'already_applied',true);
  end if;
  if coalesce(pay.checkout_payload->>'purpose','')='PLAN_BALANCE' then
    select * into sub from public.customer_subscriptions where id=(pay.checkout_payload->>'subscription_id')::uuid for update;
    if sub.id is null or sub.customer_id is distinct from target_customer or sub.provider_id<>pay.provider_id or sub.package_id<>pay.package_id then
      raise exception 'Plan balance payment does not match the subscription';
    end if;
    select max(plan_total_paise) into initial_total from public.payment_orders where subscription_id=sub.id and status='CAPTURED';
    remaining:=greatest(coalesce(initial_total,0)-sub.total_paid_paise,0);
    if remaining<=0 or pay.amount_paise>remaining then raise exception 'Payment exceeds the remaining plan balance'; end if;
    update public.customer_subscriptions set total_paid_paise=total_paid_paise+pay.amount_paise where id=sub.id;
    update public.payment_orders set subscription_id=sub.id,capture_applied_at=now() where id=pay.id;
    perform public.post_finance_journal('payment_order',pay.id::text,'BALANCE_PAYMENT_CAPTURED',coalesce(pay.captured_at,now()),
      'Customer plan balance payment captured',pay.gateway_payment_id,
      jsonb_build_array(jsonb_build_object('account_code','1000','debit_paise',pay.amount_paise,'subscription_id',sub.id),
        jsonb_build_object('account_code','2000','credit_paise',pay.amount_paise,'subscription_id',sub.id)),
      jsonb_build_object('provider_id',sub.provider_id,'remaining_paise',remaining-pay.amount_paise));
    insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data,metadata)
      values(target_customer,'PLAN_BALANCE_PAYMENT_CAPTURED','customer_subscription',sub.id::text,
        jsonb_build_object('paid_paise',pay.amount_paise,'remaining_paise',remaining-pay.amount_paise),jsonb_build_object('payment_order_id',pay.id));
    return jsonb_build_object('subscription_id',sub.id,'already_applied',false,'remaining_paise',remaining-pay.amount_paise);
  end if;
  result:=public.finalize_captured_payment(pay.id,target_customer);
  update public.payment_orders set capture_applied_at=now() where id=pay.id;
  return result||jsonb_build_object('already_applied',false);
end; $$;
revoke all on function public.apply_captured_payment(uuid,uuid) from public,anon,authenticated;
grant execute on function public.apply_captured_payment(uuid,uuid) to service_role;

create or replace function public.customer_plan_balance() returns jsonb language sql stable security definer set search_path=public as $$
  select jsonb_build_object('subscription_id',s.id,'provider_id',s.provider_id,'package_id',s.package_id,
    'plan_total_paise',coalesce(max(p.plan_total_paise),s.total_paid_paise),'paid_paise',s.total_paid_paise,
    'remaining_paise',greatest(coalesce(max(p.plan_total_paise),s.total_paid_paise)-s.total_paid_paise,0))
  from public.customer_subscriptions s left join public.payment_orders p on p.subscription_id=s.id and p.status='CAPTURED'
  where s.customer_id=auth.uid() and s.status in ('ACTIVE','PAUSED','CANCEL_PENDING')
  group by s.id order by s.created_at desc limit 1;
$$;
revoke all on function public.customer_plan_balance() from public,anon;
grant execute on function public.customer_plan_balance() to authenticated;
