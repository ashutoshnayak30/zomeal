-- Razorpay payment intents and immutable gateway event evidence.

create table if not exists public.payment_orders (
  id uuid primary key default gen_random_uuid(),
  customer_id uuid references public.profiles(id),
  provider_id uuid not null references public.providers(id),
  package_id uuid not null references public.packages(id),
  subscription_id uuid references public.customer_subscriptions(id),
  gateway text not null default 'RAZORPAY' check (gateway = 'RAZORPAY'),
  gateway_order_id text unique,
  gateway_payment_id text unique,
  receipt text not null unique,
  currency text not null default 'INR' check (currency = 'INR'),
  package_amount_paise bigint not null check (package_amount_paise >= 0),
  platform_fee_paise bigint not null default 0 check (platform_fee_paise >= 0),
  delivery_fee_paise bigint not null default 0 check (delivery_fee_paise >= 0),
  discount_paise bigint not null default 0 check (discount_paise >= 0),
  amount_paise bigint not null check (amount_paise > 0),
  status text not null default 'CREATING' check (status in (
    'CREATING','CREATED','AUTHORIZED','CAPTURED','FAILED','CANCELLED','REFUNDED','PARTIALLY_REFUNDED'
  )),
  test_mode boolean not null default true,
  checkout_payload jsonb not null default '{}'::jsonb,
  failure_code text,
  failure_description text,
  verified_at timestamptz,
  captured_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint payment_amount_components_match check (
    amount_paise = package_amount_paise + platform_fee_paise + delivery_fee_paise - discount_paise
  )
);

create index if not exists payment_orders_customer_created_idx
  on public.payment_orders(customer_id, created_at desc);
create index if not exists payment_orders_provider_created_idx
  on public.payment_orders(provider_id, created_at desc);
create index if not exists payment_orders_status_idx
  on public.payment_orders(status, created_at desc);

create table if not exists public.payment_gateway_events (
  id uuid primary key default gen_random_uuid(),
  payment_order_id uuid references public.payment_orders(id) on delete set null,
  gateway text not null default 'RAZORPAY' check (gateway = 'RAZORPAY'),
  gateway_event_id text unique,
  event_type text not null,
  signature_valid boolean not null default false,
  payload jsonb not null,
  processing_status text not null default 'RECEIVED' check (processing_status in ('RECEIVED','PROCESSED','IGNORED','FAILED')),
  processing_error text,
  received_at timestamptz not null default now(),
  processed_at timestamptz
);

alter table public.payment_orders enable row level security;
alter table public.payment_gateway_events enable row level security;

create policy payment_orders_customer_read on public.payment_orders
  for select to authenticated using (customer_id = auth.uid());
create policy payment_orders_admin_read on public.payment_orders
  for select to authenticated using (public.has_role('ADMIN') or public.has_role('FINANCE'));
create policy payment_gateway_events_admin_read on public.payment_gateway_events
  for select to authenticated using (public.has_role('ADMIN') or public.has_role('FINANCE'));

create trigger payment_orders_set_updated_at before update on public.payment_orders
for each row execute function public.set_updated_at();

comment on table public.payment_orders is
  'Server-priced Razorpay orders. Gateway secrets and raw card or UPI credentials are never stored.';
comment on column public.payment_orders.checkout_payload is
  'Non-sensitive checkout context such as address and chosen weekly menu, used only after verified payment.';

create or replace function public.finalize_captured_payment(target_payment_order uuid,target_customer uuid)
returns jsonb language plpgsql security definer set search_path=public as $$
declare
  payment_record public.payment_orders;
  package_duration integer;
  selected_start date;
  created_subscription uuid;
begin
  select * into payment_record from public.payment_orders where id=target_payment_order for update;
  if payment_record.id is null then raise exception 'Payment order was not found'; end if;
  if payment_record.status<>'CAPTURED' then raise exception 'Payment has not been captured'; end if;
  if payment_record.customer_id is distinct from target_customer then raise exception 'Payment customer does not match'; end if;
  if payment_record.subscription_id is not null then
    return jsonb_build_object('subscription_id',payment_record.subscription_id,'already_finalized',true);
  end if;
  select duration_days into package_duration from public.packages where id=payment_record.package_id;
  selected_start:=coalesce(nullif(payment_record.checkout_payload->>'start_date','')::date,(now() at time zone 'Asia/Kolkata')::date+1);
  insert into public.customer_subscriptions(
    customer_id,provider_id,package_id,status,start_date,end_date,delivery_address,total_paid_paise
  ) values(
    target_customer,payment_record.provider_id,payment_record.package_id,'ACTIVE',selected_start,
    selected_start+greatest(coalesce(package_duration,30)-1,0),
    coalesce(payment_record.checkout_payload->'delivery_address','{}'::jsonb),payment_record.amount_paise
  ) returning id into created_subscription;
  update public.payment_orders set subscription_id=created_subscription where id=payment_record.id;
  return jsonb_build_object('subscription_id',created_subscription,'already_finalized',false);
end; $$;

revoke all on function public.finalize_captured_payment(uuid,uuid) from public,anon,authenticated;
grant execute on function public.finalize_captured_payment(uuid,uuid) to service_role;
