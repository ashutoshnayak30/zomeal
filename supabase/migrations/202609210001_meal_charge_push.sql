-- Notify only new, committed meal debits. Never backfill old wallet entries.
do $$
declare definition text;
begin
  select pg_get_functiondef('public.process_due_customer_meal_charges(timestamptz)'::regprocedure) into definition;
  if position('time ''14:00''' in definition)=0 then
    raise exception 'Unexpected meal charge function; review schedule before applying';
  end if;
  execute replace(definition,'time ''14:00''','time ''14:30''');
end $$;

create table public.meal_push_dispatch_config (
  singleton boolean primary key default true check(singleton),
  secret text not null default (gen_random_uuid()::text || gen_random_uuid()::text)
);
insert into public.meal_push_dispatch_config default values;
alter table public.meal_push_dispatch_config enable row level security;
revoke all on public.meal_push_dispatch_config from public,anon,authenticated;

create table public.meal_push_outbox (
  id uuid primary key default gen_random_uuid(),
  notification_id uuid not null references public.customer_notifications(id) on delete cascade,
  device_id uuid not null references public.push_device_tokens(id) on delete cascade,
  attempts integer not null default 0,
  available_at timestamptz not null default now(),
  lease_id uuid,
  completed_at timestamptz,
  last_error text,
  unique(notification_id,device_id)
);
alter table public.meal_push_outbox enable row level security;
revoke all on public.meal_push_outbox from public,anon,authenticated;
create index meal_push_pending_idx on public.meal_push_outbox(available_at) where completed_at is null;

create function public.notify_customer_meal_debit() returns trigger
language plpgsql security definer set search_path=public as $$
declare meal public.subscription_meals; balance bigint; notification uuid; slot text;
begin
  if new.entry_type <> 'SUBSCRIPTION_DEBIT' or new.reference_type <> 'subscription_meal' or new.amount_paise >= 0 then return new; end if;
  select * into meal from public.subscription_meals where id::text=new.reference_id and customer_id=new.customer_id;
  if not found then return new; end if;
  -- The debit processor holds this wallet lock and applies the balance update
  -- immediately after inserting the ledger entry, in this same transaction.
  select balance_paise+new.amount_paise into balance from public.customer_wallets where customer_id=new.customer_id for update;
  slot:=initcap(lower(meal.meal_slot::text));
  insert into public.customer_notifications(customer_id,category,title,message,destination,dedupe_key)
  values(new.customer_id,'Payment',slot||' payment deducted',
    to_char(-new.amount_paise/100.0,'FM₹999999990.00')||' deducted for '||lower(slot)||' on '||to_char(meal.service_date,'DD Mon YYYY')||'. Wallet balance: '||to_char(balance/100.0,'FM₹999999990.00')||'.',
    'wallet','MEAL_DEBIT_'||meal.id::text)
  on conflict(customer_id,dedupe_key) do nothing returning id into notification;
  if notification is not null then
    insert into public.meal_push_outbox(notification_id,device_id)
    select notification,id from public.push_device_tokens where user_id=new.customer_id and app_kind='CUSTOMER' and enabled
    on conflict do nothing;
  end if;
  return new;
end $$;
revoke all on function public.notify_customer_meal_debit() from public,anon,authenticated;
create trigger meal_debit_notification after insert on public.customer_wallet_entries for each row execute function public.notify_customer_meal_debit();

create function public.authorize_meal_push_dispatch(dispatch_secret text) returns boolean
language sql security definer set search_path=public as $$
 select exists(select 1 from public.meal_push_dispatch_config where secret=dispatch_secret);
$$;
create function public.claim_meal_push_batch() returns jsonb
language sql security definer set search_path=public as $$
 with pending as (
   select id from public.meal_push_outbox where completed_at is null and attempts<8 and available_at<=now()
   order by available_at limit 50 for update skip locked
 ), claimed as (
   update public.meal_push_outbox o set attempts=attempts+1,lease_id=gen_random_uuid(),available_at=now()+interval '5 minutes'
   from pending p where o.id=p.id returning o.*
 ) select coalesce(jsonb_agg(jsonb_build_object('id',c.id,'lease_id',c.lease_id,'notification_id',n.id,
   'token',case when d.enabled and d.user_id=n.customer_id and d.app_kind='CUSTOMER' then d.token else null end,
   'title',n.title,'body',n.message,'destination',n.destination)),'[]'::jsonb)
 from claimed c join public.customer_notifications n on n.id=c.notification_id join public.push_device_tokens d on d.id=c.device_id;
$$;
create function public.finish_meal_push(target_id uuid,target_lease uuid,delivered boolean,invalid_token boolean default false,error_code text default null) returns void
language plpgsql security definer set search_path=public as $$
declare device uuid;
begin
 update public.meal_push_outbox set completed_at=case when delivered or invalid_token then now() else null end,
 last_error=left(error_code,120),available_at=now()+make_interval(secs=>least(3600,30*(2^attempts)::integer)),lease_id=null
 where id=target_id and lease_id=target_lease returning device_id into device;
 if invalid_token and device is not null then update public.push_device_tokens set enabled=false where id=device; end if;
end $$;
revoke all on function public.authorize_meal_push_dispatch(text),public.claim_meal_push_batch(),public.finish_meal_push(uuid,uuid,boolean,boolean,text) from public,anon,authenticated;
grant execute on function public.authorize_meal_push_dispatch(text),public.claim_meal_push_batch(),public.finish_meal_push(uuid,uuid,boolean,boolean,text) to service_role;

-- Existing five-minute ledger schedule includes 09:00 UTC (14:30 IST)
-- and 16:30 UTC (22:00 IST), and catches up after transient downtime.
-- HOSTED DISPATCH START (external network/scheduler, excluded by local tests)
create extension if not exists pg_net with schema extensions;
create or replace function public.dispatch_meal_push() returns void
language plpgsql security definer set search_path=public as $$
begin
 if exists(select 1 from public.meal_push_outbox where completed_at is null and attempts<8 and available_at<=now()) then
   perform net.http_post(
     url:='https://tojgwcxfvicrenfabgml.supabase.co/functions/v1/dispatch-meal-push',
     headers:=jsonb_build_object('Content-Type','application/json','x-meal-dispatch-secret',(select secret from public.meal_push_dispatch_config)),
     body:='{}'::jsonb,timeout_milliseconds:=10000);
 end if;
end $$;
revoke all on function public.dispatch_meal_push() from public,anon,authenticated;
select cron.schedule('zomeal-meal-push-dispatch','* * * * *','select public.dispatch_meal_push();');
-- HOSTED DISPATCH END
notify pgrst,'reload schema';
