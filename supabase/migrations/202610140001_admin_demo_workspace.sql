-- Demo credits are real spendable wallet adjustments, never captured payments.
-- Provider samples are isolated: no auth accounts, orders, pushes or earnings.
create table public.admin_demo_credits (
 id uuid primary key, customer_id uuid not null references public.profiles(id),
 amount_paise bigint not null check(amount_paise between 100 and 5000000),
 reason text not null, created_by uuid not null references public.profiles(id),
 created_at timestamptz not null default now(), reversed_at timestamptz
);
create table public.provider_demo_sessions (
 provider_id uuid primary key references public.providers(id) on delete cascade,
 sample_count integer not null check(sample_count between 1 and 100),
 lunch_paise integer not null check(lunch_paise between 100 and 100000),
 dinner_paise integer not null check(dinner_paise between 100 and 100000),
 expires_at timestamptz not null, enabled boolean not null default true,
 created_by uuid not null references public.profiles(id), updated_at timestamptz not null default now()
);
alter table public.admin_demo_credits enable row level security;
alter table public.provider_demo_sessions enable row level security;
revoke all on public.admin_demo_credits,public.provider_demo_sessions from anon,authenticated;

create or replace function public.admin_demo_workspace(payload jsonb)
returns jsonb language plpgsql security definer set search_path=public as $$
declare
 act text:=payload->>'action'; phone_key text; target uuid; matches integer; amount bigint;
 request_id uuid; credit public.admin_demo_credits%rowtype; result jsonb; balance bigint;
begin
 if not public.is_super_administrator() then raise exception 'Super Administrator access required' using errcode='42501'; end if;
 if act='list' then
  return jsonb_build_object('credits',coalesce((select jsonb_agg(to_jsonb(x)) from (
   select c.*,p.full_name,p.phone,w.balance_paise from admin_demo_credits c join profiles p on p.id=c.customer_id left join customer_wallets w on w.customer_id=c.customer_id order by c.created_at desc limit 100) x),'[]'::jsonb),
   'providers',coalesce((select jsonb_agg(to_jsonb(x)) from(select s.*,p.display_name from provider_demo_sessions s join providers p on p.id=s.provider_id order by s.updated_at desc limit 100)x),'[]'::jsonb));
 end if;
 if act in('lookup','credit') then
  phone_key:=regexp_replace(coalesce(payload->>'phone',''),'[^0-9]','','g');
  if length(phone_key)=12 and left(phone_key,2)='91' then phone_key:=substr(phone_key,3); end if;
  if phone_key !~ '^[6-9][0-9]{9}$' then raise exception 'Enter a complete Indian mobile number'; end if;
  select count(*),(array_agg(p.id))[1] into matches,target from profiles p where right(regexp_replace(p.phone,'[^0-9]','','g'),10)=phone_key;
  if matches<>1 then raise exception 'Expected exactly one account; found %',matches; end if;
  perform 1 from profiles where id=target and is_active;
  if not found then raise exception 'Account is disabled'; end if;
  if exists(select 1 from provider_members where user_id=target) or exists(select 1 from user_roles where user_id=target and role<>'CUSTOMER') then raise exception 'Choose a customer account, not provider or staff'; end if;
  if act='lookup' then
   return (select jsonb_build_object('id',p.id,'name',p.full_name,'phone',p.phone,'balance_paise',coalesce(w.balance_paise,0)) from profiles p left join customer_wallets w on w.customer_id=p.id where p.id=target);
  end if;
  if (payload->>'customer_id')::uuid is distinct from target then raise exception 'Account changed; look up the phone again'; end if;
  amount:=(payload->>'amount_paise')::bigint; request_id:=(payload->>'request_id')::uuid;
  if amount is null or amount not between 100 and 5000000 or request_id is null then raise exception 'Credit must be ₹1–₹50,000 with a request ID'; end if;
  if length(trim(coalesce(payload->>'reason','')))<10 then raise exception 'Enter an internal reason of at least 10 characters'; end if;
  perform pg_advisory_xact_lock(hashtextextended(request_id::text,0));
  select * into credit from admin_demo_credits where id=request_id;
  if found then
   if credit.customer_id<>target or credit.amount_paise<>amount then raise exception 'Request ID already used for another credit'; end if;
   return jsonb_build_object('id',credit.id,'already_applied',true,'reversed',credit.reversed_at is not null);
  end if;
  insert into customer_wallets(customer_id) values(target) on conflict do nothing;
  perform 1 from customer_wallets where customer_id=target for update;
  insert into admin_demo_credits(id,customer_id,amount_paise,reason,created_by) values(request_id,target,amount,trim(payload->>'reason'),auth.uid());
  insert into customer_wallet_entries(customer_id,entry_type,amount_paise,description,reference_type,reference_id,metadata)
   values(target,'ADJUSTMENT',amount,'Wallet credit','DEMO_TEST_CREDIT',request_id::text,jsonb_build_object('is_test',true,'demo',true,'cash_payment',false,'cleanup_required',true,'actor_id',auth.uid()));
  update customer_wallets set balance_paise=balance_paise+amount,lifetime_credit_paise=lifetime_credit_paise+amount,updated_at=now() where customer_id=target returning balance_paise into balance;
  result:=jsonb_build_object('id',request_id,'customer_id',target,'amount_paise',amount,'balance_paise',balance);
 elsif act='reverse' then
  request_id:=(payload->>'id')::uuid;
  select * into credit from admin_demo_credits where id=request_id for update;
  if not found then raise exception 'Credit not found'; end if;
  if credit.reversed_at is not null then return jsonb_build_object('already_reversed',true); end if;
  select balance_paise into balance from customer_wallets where customer_id=credit.customer_id for update;
  if balance is null or balance<credit.amount_paise then raise exception 'Balance is too low to reverse the full credit. Reconcile spent credits manually; no debt or deletion applied.'; end if;
  insert into customer_wallet_entries(customer_id,entry_type,amount_paise,description,reference_type,reference_id,metadata)
   values(credit.customer_id,'ADJUSTMENT',-credit.amount_paise,'Wallet adjustment','DEMO_TEST_REVERSAL',request_id::text,jsonb_build_object('is_test',true,'demo',true,'original_credit',request_id,'actor_id',auth.uid()));
  update customer_wallets set balance_paise=balance_paise-credit.amount_paise,updated_at=now() where customer_id=credit.customer_id returning balance_paise into balance;
  update admin_demo_credits set reversed_at=now() where id=request_id;
  result:=jsonb_build_object('id',request_id,'balance_paise',balance);
 elsif act in('provider_enable','provider_disable') then
  target:=(payload->>'provider_id')::uuid;
  if not exists(select 1 from providers where id=target) then raise exception 'Provider not found'; end if;
  if act='provider_enable' then
   insert into provider_demo_sessions(provider_id,sample_count,lunch_paise,dinner_paise,expires_at,enabled,created_by)
   values(target,(payload->>'sample_count')::integer,(payload->>'lunch_paise')::integer,(payload->>'dinner_paise')::integer,now()+interval '7 days',true,auth.uid())
   on conflict(provider_id) do update set sample_count=excluded.sample_count,lunch_paise=excluded.lunch_paise,dinner_paise=excluded.dinner_paise,expires_at=excluded.expires_at,enabled=true,created_by=auth.uid(),updated_at=now();
  else update provider_demo_sessions set enabled=false,updated_at=now() where provider_id=target; end if;
  result:=jsonb_build_object('provider_id',target,'enabled',act='provider_enable');
 else raise exception 'Unsupported demo action'; end if;
 insert into audit_logs(actor_id,action,entity_type,entity_id,after_data,metadata)
 values(auth.uid(),'ADMIN_DEMO_'||upper(act),'demo_workspace',coalesce(request_id,target)::text,result,jsonb_build_object('is_test',true));
 return result;
end $$;
revoke all on function public.admin_demo_workspace(jsonb) from public,anon;
grant execute on function public.admin_demo_workspace(jsonb) to authenticated;

create or replace function public.provider_demo_preview()
returns jsonb language plpgsql stable security definer set search_path=public as $$
declare s public.provider_demo_sessions%rowtype; provider_name text;
begin
 if auth.uid() is null or not exists(select 1 from profiles where id=auth.uid() and is_active) then raise exception 'Provider access required' using errcode='42501'; end if;
 select d.* into s from provider_demo_sessions d join provider_members m on m.provider_id=d.provider_id
 where m.user_id=auth.uid() and m.is_active and upper(m.member_role) in('OWNER','MANAGER') and d.enabled and d.expires_at>now() order by d.updated_at desc limit 1;
 if not found then return jsonb_build_object('enabled',false); end if;
 select display_name into provider_name from providers where id=s.provider_id;
 return jsonb_build_object('enabled',true,'provider_name',provider_name,'expires_at',s.expires_at,'sample_count',s.sample_count,
 'lunch_paise',s.lunch_paise,'dinner_paise',s.dinner_paise,
 'customers',(select jsonb_agg(jsonb_build_object('id',n,'name','Sample customer '||lpad(n::text,3,'0'),'address','Demo building · Room '||n,'diet',case when n%3=0 then 'NON_VEG' else 'VEG' end)) from generate_series(1,s.sample_count)n));
end $$;
revoke all on function public.provider_demo_preview() from public,anon;
grant execute on function public.provider_demo_preview() to authenticated;
