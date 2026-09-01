-- Production referral programme: immutable credits, one referral per customer,
-- rewards only after the referred customer's first captured subscription.

create table if not exists public.customer_wallets (
  customer_id uuid primary key references public.profiles(id) on delete cascade,
  balance_paise bigint not null default 0 check (balance_paise >= 0),
  lifetime_credit_paise bigint not null default 0 check (lifetime_credit_paise >= 0),
  updated_at timestamptz not null default now()
);

create table if not exists public.customer_wallet_entries (
  id uuid primary key default gen_random_uuid(),
  customer_id uuid not null references public.profiles(id) on delete cascade,
  entry_type text not null check (entry_type in ('REFERRER_REWARD','REFERRED_REWARD','REFUND','RECHARGE','SUBSCRIPTION_DEBIT','ADJUSTMENT')),
  amount_paise bigint not null check (amount_paise <> 0),
  description text not null,
  reference_type text not null,
  reference_id text not null,
  metadata jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  unique(customer_id,entry_type,reference_type,reference_id)
);

create table if not exists public.customer_referral_accounts (
  customer_id uuid primary key references public.profiles(id) on delete cascade,
  referral_code text not null unique check (referral_code ~ '^ZM[A-Z0-9]{8}$'),
  created_at timestamptz not null default now()
);

create table if not exists public.customer_referral_claims (
  id uuid primary key default gen_random_uuid(),
  referrer_id uuid not null references public.profiles(id) on delete cascade,
  referred_customer_id uuid not null unique references public.profiles(id) on delete cascade,
  referral_code text not null,
  status text not null default 'APPLIED' check (status in ('APPLIED','REWARDED','CANCELLED','FLAGGED')),
  qualifying_subscription_id uuid references public.customer_subscriptions(id),
  referrer_reward_paise bigint not null default 0,
  referred_reward_paise bigint not null default 0,
  applied_at timestamptz not null default now(),
  rewarded_at timestamptz,
  constraint referral_not_self check (referrer_id <> referred_customer_id)
);

create index if not exists wallet_entries_customer_created_idx on public.customer_wallet_entries(customer_id,created_at desc);
create index if not exists referral_claims_referrer_idx on public.customer_referral_claims(referrer_id,applied_at desc);

alter table public.customer_wallets enable row level security;
alter table public.customer_wallet_entries enable row level security;
alter table public.customer_referral_accounts enable row level security;
alter table public.customer_referral_claims enable row level security;

drop policy if exists customer_wallet_read_own on public.customer_wallets;
create policy customer_wallet_read_own on public.customer_wallets for select to authenticated using(customer_id=auth.uid() or public.has_role('ADMIN'));
drop policy if exists customer_wallet_entries_read_own on public.customer_wallet_entries;
create policy customer_wallet_entries_read_own on public.customer_wallet_entries for select to authenticated using(customer_id=auth.uid() or public.has_role('ADMIN'));
drop policy if exists referral_accounts_read_own on public.customer_referral_accounts;
create policy referral_accounts_read_own on public.customer_referral_accounts for select to authenticated using(customer_id=auth.uid() or public.has_role('ADMIN'));
drop policy if exists referral_claims_read_related on public.customer_referral_claims;
create policy referral_claims_read_related on public.customer_referral_claims for select to authenticated using(referrer_id=auth.uid() or referred_customer_id=auth.uid() or public.has_role('ADMIN'));

revoke all on public.customer_wallets,public.customer_wallet_entries,public.customer_referral_accounts,public.customer_referral_claims from anon,authenticated;
grant select on public.customer_wallets,public.customer_wallet_entries,public.customer_referral_accounts,public.customer_referral_claims to authenticated;

update public.platform_settings
set value=value||jsonb_build_object(
  'referrer_reward_paise',coalesce((value->>'first_subscription_reward_paise')::bigint,10000),
  'referred_reward_paise',coalesce((value->>'verified_install_reward_paise')::bigint,2500),
  'qualification','FIRST_CAPTURED_SUBSCRIPTION',
  'cycle_days',30,
  'terms_version','2026-09-01'
)
where setting_key='customer_referral_program' and effective_until is null;

create or replace function public.ensure_customer_referral_account(target_customer uuid default auth.uid())
returns text language plpgsql security definer set search_path=public as $$
declare generated_code text; existing_code text;
begin
  if target_customer is null or (target_customer<>auth.uid() and not public.has_role('ADMIN')) then raise exception 'Customer authentication is required'; end if;
  select referral_code into existing_code from public.customer_referral_accounts where customer_id=target_customer;
  if existing_code is not null then return existing_code; end if;
  generated_code:='ZM'||upper(substr(replace(target_customer::text,'-',''),1,8));
  insert into public.customer_referral_accounts(customer_id,referral_code) values(target_customer,generated_code)
    on conflict(customer_id) do update set customer_id=excluded.customer_id returning referral_code into existing_code;
  insert into public.customer_wallets(customer_id) values(target_customer) on conflict do nothing;
  return existing_code;
end; $$;

create or replace function public.customer_apply_referral(target_code text)
returns jsonb language plpgsql security definer set search_path=public as $$
declare code text:=upper(trim(target_code)); referrer uuid; claim_id uuid; programme jsonb;
begin
  if auth.uid() is null then raise exception 'Customer authentication is required'; end if;
  if code !~ '^ZM[A-Z0-9]{8}$' then raise exception 'Enter a valid Zomeal referral code'; end if;
  programme:=public.customer_referral_program();
  if not coalesce((programme->>'enabled')::boolean,false) then raise exception 'The referral programme is currently paused'; end if;
  select customer_id into referrer from public.customer_referral_accounts where referral_code=code;
  if referrer is null then raise exception 'Referral code was not found'; end if;
  if referrer=auth.uid() then raise exception 'You cannot use your own referral code'; end if;
  if exists(select 1 from public.customer_subscriptions where customer_id=auth.uid() and total_paid_paise>0) then raise exception 'Referral codes must be applied before the first paid subscription'; end if;
  insert into public.customer_referral_claims(referrer_id,referred_customer_id,referral_code)
    values(referrer,auth.uid(),code) returning id into claim_id;
  perform public.ensure_customer_referral_account(auth.uid());
  return jsonb_build_object('applied',true,'claim_id',claim_id,'message','Referral applied. Both rewards unlock after your first successful paid subscription.');
exception when unique_violation then raise exception 'A referral code has already been applied to this account';
end; $$;

create or replace function public.customer_referral_dashboard()
returns jsonb language plpgsql security definer set search_path=public as $$
declare code text; programme jsonb; wallet public.customer_wallets; joined integer; rewarded integer; earned bigint;
begin
  if auth.uid() is null then raise exception 'Customer authentication is required'; end if;
  code:=public.ensure_customer_referral_account(auth.uid());
  programme:=public.customer_referral_program();
  select * into wallet from public.customer_wallets where customer_id=auth.uid();
  select count(*),count(*) filter(where status='REWARDED'),coalesce(sum(referrer_reward_paise) filter(where status='REWARDED'),0)
    into joined,rewarded,earned from public.customer_referral_claims where referrer_id=auth.uid();
  return jsonb_build_object(
    'enabled',coalesce((programme->>'enabled')::boolean,false),'referral_code',code,
    'share_link','https://zomeal.in/?ref='||code,
    'referrer_reward_paise',coalesce((programme->>'referrer_reward_paise')::bigint,10000),
    'referred_reward_paise',coalesce((programme->>'referred_reward_paise')::bigint,2500),
    'cycle_cap_paise',coalesce((programme->>'cycle_cap_paise')::bigint,100000),
    'balance_paise',coalesce(wallet.balance_paise,0),'lifetime_referral_earned_paise',earned,
    'friends_joined',joined,'friends_rewarded',rewarded,
    'applied_referral',(select jsonb_build_object('code',referral_code,'status',status,'applied_at',applied_at) from public.customer_referral_claims where referred_customer_id=auth.uid()),
    'activity',coalesce((select jsonb_agg(jsonb_build_object('type',entry_type,'amount_paise',amount_paise,'description',description,'created_at',created_at) order by created_at desc) from (select * from public.customer_wallet_entries where customer_id=auth.uid() order by created_at desc limit 20)e),'[]'::jsonb)
  );
end; $$;

create or replace function public.process_customer_referral_reward()
returns trigger language plpgsql security definer set search_path=public as $$
declare claim public.customer_referral_claims; programme jsonb; referrer_amount bigint; referred_amount bigint; cap bigint; cycle_days integer; already_earned bigint;
begin
  if new.total_paid_paise<=0 or new.status not in ('ACTIVE','PAUSED') then return new; end if;
  select * into claim from public.customer_referral_claims where referred_customer_id=new.customer_id and status='APPLIED' for update;
  if claim.id is null then return new; end if;
  programme:=public.customer_referral_program();
  if not coalesce((programme->>'enabled')::boolean,false) then return new; end if;
  referrer_amount:=greatest(coalesce((programme->>'referrer_reward_paise')::bigint,0),0);
  referred_amount:=greatest(coalesce((programme->>'referred_reward_paise')::bigint,0),0);
  cap:=greatest(coalesce((programme->>'cycle_cap_paise')::bigint,0),0);
  cycle_days:=greatest(coalesce((programme->>'cycle_days')::integer,30),1);
  select coalesce(sum(amount_paise),0) into already_earned from public.customer_wallet_entries where customer_id=claim.referrer_id and entry_type='REFERRER_REWARD' and created_at>=now()-make_interval(days=>cycle_days);
  referrer_amount:=least(referrer_amount,greatest(cap-already_earned,0));
  insert into public.customer_wallets(customer_id) values(claim.referrer_id) on conflict do nothing;
  insert into public.customer_wallets(customer_id) values(claim.referred_customer_id) on conflict do nothing;
  if referrer_amount>0 then
    insert into public.customer_wallet_entries(customer_id,entry_type,amount_paise,description,reference_type,reference_id,metadata)
      values(claim.referrer_id,'REFERRER_REWARD',referrer_amount,'Friend completed their first Zomeal subscription','referral_claim',claim.id::text,jsonb_build_object('subscription_id',new.id));
    update public.customer_wallets set balance_paise=balance_paise+referrer_amount,lifetime_credit_paise=lifetime_credit_paise+referrer_amount,updated_at=now() where customer_id=claim.referrer_id;
  end if;
  if referred_amount>0 then
    insert into public.customer_wallet_entries(customer_id,entry_type,amount_paise,description,reference_type,reference_id,metadata)
      values(claim.referred_customer_id,'REFERRED_REWARD',referred_amount,'Welcome reward after first Zomeal subscription','referral_claim',claim.id::text,jsonb_build_object('subscription_id',new.id));
    update public.customer_wallets set balance_paise=balance_paise+referred_amount,lifetime_credit_paise=lifetime_credit_paise+referred_amount,updated_at=now() where customer_id=claim.referred_customer_id;
  end if;
  update public.customer_referral_claims set status='REWARDED',qualifying_subscription_id=new.id,referrer_reward_paise=referrer_amount,referred_reward_paise=referred_amount,rewarded_at=now() where id=claim.id;
  return new;
end; $$;

drop trigger if exists customer_subscription_referral_reward on public.customer_subscriptions;
create trigger customer_subscription_referral_reward after insert on public.customer_subscriptions for each row execute function public.process_customer_referral_reward();

create or replace function public.admin_referral_settings()
returns jsonb language plpgsql stable security definer set search_path=public as $$
declare programme jsonb;
begin
  perform public.require_staff(array['ADMIN']::public.app_role[]);
  programme:=public.customer_referral_program();
  return programme||jsonb_build_object(
    'total_claims',(select count(*) from public.customer_referral_claims),
    'rewarded_claims',(select count(*) from public.customer_referral_claims where status='REWARDED'),
    'total_rewarded_paise',(select coalesce(sum(amount_paise),0) from public.customer_wallet_entries where entry_type in ('REFERRER_REWARD','REFERRED_REWARD')),
    'is_super_admin',exists(select 1 from public.admin_staff_profiles where user_id=auth.uid() and staff_role='SUPER_ADMIN')
  );
end; $$;

create or replace function public.admin_update_referral_settings(target_enabled boolean,target_referrer_reward_paise bigint,target_referred_reward_paise bigint,target_cycle_cap_paise bigint,target_cycle_days integer)
returns jsonb language plpgsql security definer set search_path=public as $$
declare new_value jsonb;
begin
  perform public.require_staff(array['ADMIN']::public.app_role[]);
  if not exists(select 1 from public.admin_staff_profiles where user_id=auth.uid() and staff_role='SUPER_ADMIN') then raise exception 'Only the Super Administrator can change referral rewards'; end if;
  if target_referrer_reward_paise<0 or target_referred_reward_paise<0 or target_cycle_cap_paise<0 or target_cycle_days not between 1 and 365 then raise exception 'Enter valid non-negative rewards, cap and cycle days'; end if;
  new_value=jsonb_build_object('enabled',target_enabled,'referrer_reward_paise',target_referrer_reward_paise,'referred_reward_paise',target_referred_reward_paise,'cycle_cap_paise',target_cycle_cap_paise,'cycle_days',target_cycle_days,'qualification','FIRST_CAPTURED_SUBSCRIPTION','terms_version','2026-09-01');
  update public.platform_settings set effective_until=now() where setting_key='customer_referral_program' and effective_until is null;
  insert into public.platform_settings(setting_key,value,effective_from,created_by) values('customer_referral_program',new_value,now(),auth.uid());
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data) values(auth.uid(),'REFERRAL_PROGRAM_UPDATED','platform_setting','customer_referral_program',new_value);
  return new_value;
end; $$;

grant execute on function public.ensure_customer_referral_account(uuid),public.customer_apply_referral(text),public.customer_referral_dashboard() to authenticated;
grant execute on function public.admin_referral_settings(),public.admin_update_referral_settings(boolean,bigint,bigint,bigint,integer) to authenticated;
revoke all on function public.process_customer_referral_reward() from public,anon,authenticated;

-- Promote the founding/earliest administrator so the new controls have exactly
-- one owner without granting Super Administrator to every ADMIN account.
update public.admin_staff_profiles set staff_role='SUPER_ADMIN',updated_at=now()
where user_id=(select user_id from public.user_roles where role='ADMIN' order by granted_at limit 1)
  and not exists(select 1 from public.admin_staff_profiles where staff_role='SUPER_ADMIN');
