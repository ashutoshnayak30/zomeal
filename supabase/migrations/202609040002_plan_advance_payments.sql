-- Preserve actual captured cash separately from the full plan obligation.
alter table public.payment_orders add column plan_total_paise bigint;
update public.payment_orders set plan_total_paise = amount_paise;
alter table public.payment_orders alter column plan_total_paise set not null;
alter table public.payment_orders add constraint payment_plan_total_valid check(plan_total_paise >= amount_paise);
-- Compatibility for older clients/functions while deployments roll out.
create function public.default_payment_plan_total() returns trigger language plpgsql set search_path=public as $$
begin
  new.plan_total_paise := coalesce(new.plan_total_paise,new.amount_paise);
  return new;
end; $$;
create trigger payment_plan_total_default before insert on public.payment_orders
for each row execute function public.default_payment_plan_total();

create function public.customer_plan_balance() returns jsonb language sql stable security definer set search_path=public as $$
  select jsonb_build_object('plan_total_paise',p.plan_total_paise,'paid_paise',s.total_paid_paise,
    'remaining_paise',greatest(p.plan_total_paise-s.total_paid_paise,0))
  from public.customer_subscriptions s join public.payment_orders p on p.subscription_id=s.id
  where s.customer_id=auth.uid() and s.status in ('ACTIVE','PAUSED','CANCEL_PENDING') and p.status='CAPTURED'
  order by s.created_at desc,p.created_at asc limit 1;
$$;
revoke all on function public.customer_plan_balance() from public,anon;
grant execute on function public.customer_plan_balance() to authenticated;
comment on column public.payment_orders.plan_total_paise is 'Full server-calculated plan quote; amount_paise is only the payment collected. Difference remains due, not wallet credit or a discount.';

alter function public.customer_active_subscription_state() rename to customer_active_subscription_state_before_advances;
revoke all on function public.customer_active_subscription_state_before_advances() from public,anon,authenticated;
create function public.customer_active_subscription_state() returns jsonb language plpgsql stable security definer set search_path=public as $$
declare result jsonb; balance jsonb; package_price bigint;
begin
  result := public.customer_active_subscription_state_before_advances();
  if coalesce((result->>'has_active_subscription')::boolean,false) then
    balance := public.customer_plan_balance();
    select package_price_paise into package_price from public.customer_subscriptions
      where id=(result->'subscription'->>'id')::uuid and customer_id=auth.uid();
    result := jsonb_set(result,'{payment}',coalesce(nullif(result->'payment','null'::jsonb),'{}'::jsonb)
      || coalesce(balance,'{}'::jsonb) || jsonb_build_object('plan_package_paise',package_price));
  end if;
  return result;
end; $$;
revoke all on function public.customer_active_subscription_state() from public,anon;
grant execute on function public.customer_active_subscription_state() to authenticated;
