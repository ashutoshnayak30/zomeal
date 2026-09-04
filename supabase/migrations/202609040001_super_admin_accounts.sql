-- Account inspection and guarded deletion. Never bypass accounting immutability.
create or replace function public.is_super_administrator()
returns boolean language sql stable security definer set search_path=public as $$
  select exists(select 1 from public.profiles p
    join public.user_roles r on r.user_id=p.id and r.role='ADMIN'
    join public.admin_staff_profiles s on s.user_id=p.id and s.staff_role='SUPER_ADMIN'
    where p.id=auth.uid() and p.is_active);
$$;
revoke all on function public.is_super_administrator() from public;
grant execute on function public.is_super_administrator() to authenticated;

-- Account management is shared by the two administrator titles only.
-- Keep is_super_administrator strict for other super-admin-only capabilities.
create or replace function public.can_manage_accounts()
returns boolean language sql stable security definer set search_path=public as $$
  select exists(select 1 from public.profiles p
    join public.user_roles r on r.user_id=p.id and r.role='ADMIN'
    join public.admin_staff_profiles s on s.user_id=p.id
    where p.id=auth.uid() and p.is_active
      and s.staff_role in ('SUPER_ADMIN','ADMINISTRATOR'));
$$;
revoke all on function public.can_manage_accounts() from public;
grant execute on function public.can_manage_accounts() to authenticated;

-- A durable outbox: database deletion commits first; Storage API cleanup is retryable.
create table public.admin_account_deletions (
  id uuid primary key default gen_random_uuid(),
  actor_id uuid not null references auth.users(id),
  target_kind text not null check(target_kind in ('user','provider')),
  target_id uuid not null,
  reason text not null,
  objects jsonb not null default '[]',
  cleanup_status text not null default 'PENDING' check(cleanup_status in ('PENDING','COMPLETE')),
  created_at timestamptz not null default now(),
  completed_at timestamptz
);
alter table public.admin_account_deletions enable row level security;
revoke all on public.admin_account_deletions from anon,authenticated;
grant all on public.admin_account_deletions to service_role;

create or replace function public.super_admin_account_search(search_text text, page_number integer default 0)
returns jsonb language plpgsql security definer set search_path=public as $$
declare q text:=trim(coalesce(search_text,'')); digits text; result jsonb;
begin
  if not public.can_manage_accounts() then raise exception 'Administrator access required' using errcode='42501'; end if;
  if page_number is null or page_number<0 or page_number>10000 then raise exception 'Invalid page'; end if;
  digits:=regexp_replace(q,'[^0-9]','','g');
  if q !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
    if q !~ '^[+0-9 ()-]+$' or not(length(digits)=10 or (length(digits)=12 and left(digits,2)='91')) then
      raise exception 'Enter a complete 10-digit Indian phone number or full user/provider UUID';
    end if;
    digits:=right(digits,10);
  else digits:=null;
  end if;
  with matched_users as (
    select p.id,p.full_name,coalesce(nullif(p.phone,''),u.phone) phone,p.is_active
    from public.profiles p join auth.users u on u.id=p.id
    where p.id::text=lower(q) or (digits is not null and
      (right(regexp_replace(coalesce(p.phone,''),'[^0-9]','','g'),10)=digits
       or right(regexp_replace(coalesce(u.phone,''),'[^0-9]','','g'),10)=digits))
  ), matches as (
    select 'user'::text kind,id,full_name name,phone,case when is_active then 'ACTIVE' else 'INACTIVE' end status from matched_users
    union all
    select 'provider',p.id,p.display_name,p.support_phone,p.status::text from public.providers p
    where p.id::text=lower(q) or (digits is not null and right(regexp_replace(coalesce(p.support_phone,''),'[^0-9]','','g'),10)=digits)
      or exists(select 1 from public.provider_members m join matched_users u on u.id=m.user_id where m.provider_id=p.id)
  ) select jsonb_build_object('total',(select count(*) from matches),'page',page_number,
    'results',coalesce((select jsonb_agg(to_jsonb(x)) from (select * from matches order by kind,id limit 20 offset page_number*20)x),'[]')) into result;
  insert into public.audit_logs(actor_id,action,entity_type,metadata)
    values(auth.uid(),'ACCOUNT_SEARCH','account',jsonb_build_object('result_count',result->'total')); -- No phone in audit metadata.
  return result;
end; $$;

create or replace function public.super_admin_account_detail(target_kind text,target_id uuid,page_number integer default 0)
returns jsonb language plpgsql security definer set search_path=public as $$
declare result jsonb; available bigint; reserved bigint;
begin
  if not public.can_manage_accounts() then raise exception 'Administrator access required' using errcode='42501'; end if;
  if page_number is null or page_number<0 or page_number>10000 then raise exception 'Invalid page'; end if;
  if target_kind='user' then
    select jsonb_build_object('kind','user','profile',to_jsonb(p),
      'auth',jsonb_build_object('email',u.email,'phone',u.phone,'created_at',u.created_at,'last_sign_in_at',u.last_sign_in_at,'phone_confirmed_at',u.phone_confirmed_at),
      'roles',coalesce((select jsonb_agg(r.role) from public.user_roles r where r.user_id=p.id),'[]'),
      'wallet',coalesce((select to_jsonb(w) from public.customer_wallets w where w.customer_id=p.id),'{"balance_paise":0,"lifetime_credit_paise":0}'),
      'addresses',coalesce((select jsonb_agg(to_jsonb(a)) from public.customer_addresses a where a.customer_id=p.id),'[]'),
      'providers',coalesce((select jsonb_agg(jsonb_build_object('id',v.id,'name',v.display_name,'member_role',m.member_role)) from public.provider_members m join public.providers v on v.id=m.provider_id where m.user_id=p.id),'[]'),
      'referral', (select to_jsonb(r) from public.customer_referral_accounts r where r.customer_id=p.id)
    ) into result from public.profiles p join auth.users u on u.id=p.id where p.id=target_id;
  elsif target_kind='provider' then
    select coalesce(sum(provider_net_paise),0) into available from public.provider_financial_ledger where provider_id=target_id and available_at<=now();
    select coalesce(sum(amount_paise),0) into reserved from public.provider_payout_requests where provider_id=target_id and status in ('PENDING','APPROVED','PROCESSING');
    select jsonb_build_object('kind','provider','profile',to_jsonb(p),
      'members',coalesce((select jsonb_agg(jsonb_build_object('id',u.id,'name',u.full_name,'phone',u.phone,'member_role',m.member_role,'is_active',m.is_active)) from public.provider_members m join public.profiles u on u.id=m.user_id where m.provider_id=p.id),'[]'),
      'wallet',jsonb_build_object('available_paise',greatest(available-reserved,0),'reserved_paise',reserved,'ledger_available_paise',available,
        'pending_paise',coalesce((select sum(provider_net_paise) from public.provider_financial_ledger where provider_id=p.id and available_at>now()),0),
        'net_paise',coalesce((select sum(provider_net_paise) from public.provider_financial_ledger where provider_id=p.id),0),
        'advance_outstanding_paise',coalesce((select sum(amount_paise-recovered_paise) from public.provider_advance_requests where provider_id=p.id and status in('DISBURSED','RECOVERED')),0)),
      'packages',coalesce((select jsonb_agg(to_jsonb(k)||jsonb_build_object('prices',coalesce((select jsonb_agg(to_jsonb(v) order by v.version desc) from public.package_price_versions v where v.package_id=k.id),'[]'))) from public.packages k where k.provider_id=p.id),'[]'),
      'service_areas',coalesce((select jsonb_agg(to_jsonb(a)) from public.provider_service_areas a where a.provider_id=p.id),'[]'),
      'delivery_people',coalesce((select jsonb_agg(to_jsonb(d)) from public.provider_delivery_personnel d where d.provider_id=p.id),'[]'),
      'menus',coalesce(public.admin_provider_workspace(p.id)->'menus','[]'),
      'media',coalesce((select jsonb_agg(to_jsonb(m)) from public.provider_media m where m.provider_id=p.id),'[]')
    ) into result from public.providers p where p.id=target_id;
  else raise exception 'Invalid account type'; end if;
  if result is null then raise exception 'Account not found' using errcode='P0002'; end if;
  -- Explicitly exclude checkout payloads, gateway secrets, tokens and passwords.
  result:=result||jsonb_build_object('page',page_number,'page_size',25,
    'subscriptions',coalesce((select jsonb_agg(to_jsonb(s)) from (
      select c.*,k.name package_name,k.kind package_kind,k.duration_days,p.display_name provider_name
      from public.customer_subscriptions c join public.packages k on k.id=c.package_id join public.providers p on p.id=c.provider_id
      where (target_kind='user' and c.customer_id=target_id) or (target_kind='provider' and c.provider_id=target_id)
      order by c.created_at desc,c.id limit 25 offset page_number*25)s),'[]'),
    'subscription_count',(select count(*) from public.customer_subscriptions c where (target_kind='user' and c.customer_id=target_id) or (target_kind='provider' and c.provider_id=target_id)),
    'payments',coalesce((select jsonb_agg(to_jsonb(o)) from (select id,customer_id,provider_id,subscription_id,amount_paise,status,test_mode,created_at,gateway_payment_id
      from public.payment_orders where (target_kind='user' and customer_id=target_id) or (target_kind='provider' and provider_id=target_id)
      order by created_at desc,id limit 25 offset page_number*25)o),'[]'),
    'payment_count',(select count(*) from public.payment_orders where (target_kind='user' and customer_id=target_id) or (target_kind='provider' and provider_id=target_id)),
    'wallet_entries',case when target_kind='user' then coalesce((select jsonb_agg(to_jsonb(e)) from (select * from public.customer_wallet_entries where customer_id=target_id order by created_at desc,id limit 25 offset page_number*25)e),'[]')
      else coalesce((select jsonb_agg(to_jsonb(e)) from (select * from public.provider_financial_ledger where provider_id=target_id order by created_at desc,id limit 25 offset page_number*25)e),'[]') end,
    'wallet_entry_count',case when target_kind='user' then (select count(*) from public.customer_wallet_entries where customer_id=target_id) else (select count(*) from public.provider_financial_ledger where provider_id=target_id) end,
    'payouts',coalesce((select jsonb_agg(to_jsonb(r)) from (select id,amount_paise,status,requested_at,paid_at,payment_reference from public.provider_payout_requests where target_kind='provider' and provider_id=target_id order by requested_at desc,id limit 25 offset page_number*25)r),'[]'),
    'payout_count',(select count(*) from public.provider_payout_requests where target_kind='provider' and provider_id=target_id),
    'advances',coalesce((select jsonb_agg(to_jsonb(a)) from (select id,amount_paise,recovered_paise,purpose,status,requested_at,disbursed_at from public.provider_advance_requests where target_kind='provider' and provider_id=target_id order by requested_at desc,id limit 25 offset page_number*25)a),'[]'),
    'advance_count',(select count(*) from public.provider_advance_requests where target_kind='provider' and provider_id=target_id));
  insert into public.audit_logs(actor_id,action,entity_type,entity_id) values(auth.uid(),'ACCOUNT_DETAILS_VIEWED',target_kind,target_id);
  return result;
end; $$;

create or replace function public.super_admin_deletion_preview(target_kind text,target_id uuid)
returns jsonb language plpgsql security definer set search_path=public as $$
declare blockers text[]:='{}'; name text; counts jsonb;
begin
  if not public.can_manage_accounts() then raise exception 'Administrator access required' using errcode='42501'; end if;
  if target_kind='user' then
    select full_name into name from public.profiles where id=target_id;
    if not found then raise exception 'Account not found'; end if;
    if target_id=auth.uid() or exists(select 1 from public.admin_staff_profiles where user_id=target_id)
      or exists(select 1 from public.user_roles where user_id=target_id and role in('ADMIN','OPERATIONS','FINANCE')) then blockers:=array_append(blockers,'Staff accounts cannot be deleted here.'); end if;
    if exists(select 1 from public.provider_members where user_id=target_id) then blockers:=array_append(blockers,'Delete or transfer the linked provider profiles first.'); end if;
    if exists(select 1 from public.customer_wallet_entries where customer_id=target_id) or exists(select 1 from public.customer_wallets where customer_id=target_id and balance_paise<>0)
      or exists(select 1 from public.customer_referral_claims where (referrer_id=target_id or referred_customer_id=target_id) and (status='REWARDED' or referrer_reward_paise<>0 or referred_reward_paise<>0)) then blockers:=array_append(blockers,'Wallet or rewarded-referral history must be retained.'); end if;
  elsif target_kind='provider' then
    select display_name into name from public.providers where id=target_id;
    if not found then raise exception 'Provider not found'; end if;
    if exists(select 1 from public.provider_financial_ledger where provider_id=target_id)
      or exists(select 1 from public.provider_payout_requests where provider_id=target_id)
      or exists(select 1 from public.provider_advance_requests where provider_id=target_id)
      or exists(select 1 from public.finance_journal_lines where provider_id=target_id) then blockers:=array_append(blockers,'Provider accounting, payout or advance history must be retained.'); end if;
  else raise exception 'Invalid account type'; end if;
  if exists(select 1 from public.customer_subscriptions where (target_kind='user' and customer_id=target_id) or (target_kind='provider' and provider_id=target_id)) then blockers:=array_append(blockers,'Subscriptions exist. Use a reviewed settlement/data-retention process, not permanent deletion.'); end if;
  if exists(select 1 from public.payment_orders where ((target_kind='user' and customer_id=target_id) or (target_kind='provider' and provider_id=target_id)) and (not test_mode or status::text not in ('CREATED','FAILED'))) then blockers:=array_append(blockers,'Real payments or completed test payments must be retained.'); end if;
  counts:=jsonb_build_object('addresses',(select count(*) from public.customer_addresses where target_kind='user' and customer_id=target_id),
    'packages',(select count(*) from public.packages where target_kind='provider' and provider_id=target_id),
    'memberships',(select count(*) from public.provider_members where target_kind='provider' and provider_id=target_id),
    'photos',(select count(*) from public.provider_media where target_kind='provider' and provider_id=target_id));
  return jsonb_build_object('kind',target_kind,'id',target_id,'name',name,'allowed',cardinality(blockers)=0,'blockers',to_jsonb(blockers),'counts',counts,
    'confirmation','DELETE '||target_id::text,'scope',case when target_kind='user' then 'Deletes this login, profile, addresses, drafts and unearned referral records. The number can register again.' else 'Deletes this business, packages, menus, service areas and photos. Member logins are kept; delete those separately if needed.' end);
end; $$;

create or replace function public.super_admin_delete_account(target_kind text,target_id uuid,confirmation text,reason text)
returns jsonb language plpgsql security definer set search_path=public as $$
declare preview jsonb; objects jsonb; job uuid;
begin
  if not public.can_manage_accounts() then raise exception 'Administrator access required' using errcode='42501'; end if;
  if confirmation is distinct from 'DELETE '||target_id::text or length(trim(coalesce(reason,'')))<10 or length(reason)>500 then raise exception 'Exact confirmation and a reason (10–500 characters) are required'; end if;
  -- Serialize against new orders/subscriptions/wallet credits while eligibility is rechecked.
  lock table public.payment_orders,public.customer_subscriptions,public.customer_wallet_entries,public.customer_wallets,
    public.customer_referral_claims,public.provider_members,public.provider_financial_ledger,
    public.provider_payout_requests,public.provider_advance_requests,public.finance_journal_lines in share row exclusive mode;
  if target_kind='user' then perform 1 from public.profiles where id=target_id for update;
  else perform 1 from public.providers where id=target_id for update; end if;
  preview:=public.super_admin_deletion_preview(target_kind,target_id);
  if not (preview->>'allowed')::boolean then raise exception '%',preview->'blockers'; end if;
  -- Only known profile-owned folders; never delete Storage metadata with SQL.
  select coalesce(jsonb_agg(jsonb_build_object('bucket',bucket_id,'path',name)),'[]') into objects
    from storage.objects where bucket_id in('provider-media','provider-documents') and split_part(name,'/',1)=target_id::text;
  insert into public.admin_account_deletions(actor_id,target_kind,target_id,reason,objects,cleanup_status,completed_at)
    values(auth.uid(),target_kind,target_id,trim(reason),objects,case when jsonb_array_length(objects)=0 then 'COMPLETE' else 'PENDING' end,case when jsonb_array_length(objects)=0 then now() end) returning id into job;
  delete from public.payment_gateway_events where payment_order_id in(select id from public.payment_orders where (target_kind='user' and customer_id=target_id) or (target_kind='provider' and provider_id=target_id));
  delete from public.payment_orders where (target_kind='user' and customer_id=target_id) or (target_kind='provider' and provider_id=target_id);
  if target_kind='provider' then
    delete from public.provider_menus where provider_id=target_id;
    delete from public.provider_commission_terms where provider_id=target_id;
    delete from public.providers where id=target_id;
  else
    -- Keep administrative history but detach the disappearing actor identity.
    update public.audit_logs set actor_id=null where actor_id=target_id;
    delete from auth.users where id=target_id;
  end if;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,metadata)
    values(auth.uid(),'ACCOUNT_PERMANENTLY_DELETED',target_kind,target_id,jsonb_build_object('reason',trim(reason),'job_id',job,'counts',preview->'counts'));
  return jsonb_build_object('deleted',true,'job_id',job,'cleanup_pending',jsonb_array_length(objects)>0);
exception when foreign_key_violation then
  raise exception 'Deletion stopped: other records still reference this account. No data was deleted. Review linked records first.';
end; $$;

revoke all on function public.super_admin_account_search(text,integer),public.super_admin_account_detail(text,uuid,integer),
  public.super_admin_deletion_preview(text,uuid),public.super_admin_delete_account(text,uuid,text,text) from public;
grant execute on function public.super_admin_account_search(text,integer),public.super_admin_account_detail(text,uuid,integer),
  public.super_admin_deletion_preview(text,uuid),public.super_admin_delete_account(text,uuid,text,text) to authenticated;
