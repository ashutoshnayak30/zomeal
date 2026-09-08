-- Administrator low-wallet follow-up queue and audited customer reminders.

create or replace function public.admin_low_wallet_customers(search_text text default '',page_number integer default 0)
returns jsonb language plpgsql stable security definer set search_path=public as $$
declare q text:=lower(trim(coalesce(search_text,''))); result jsonb;
begin
  if not public.can_manage_accounts() then raise exception 'Administrator access required' using errcode='42501'; end if;
  if page_number is null or page_number<0 or page_number>10000 then raise exception 'Invalid page'; end if;
  with candidates as (
    select p.id,p.full_name,coalesce(nullif(p.phone,''),u.phone) phone,coalesce(w.balance_paise,0) balance_paise,
      s.id subscription_id,s.status subscription_status,s.pause_reason,s.start_date,s.end_date,
      provider.display_name provider_name,package.name package_name,
      (select sm.meal_value_paise from public.subscription_meals sm where sm.subscription_id=s.id
       and sm.wallet_charged_at is null and sm.status not in('PAUSED','CANCELLED')
       order by sm.service_date,sm.meal_slot limit 1) next_meal_paise,
      (select max(n.created_at) from public.customer_notifications n where n.customer_id=p.id and n.dedupe_key like 'ADMIN_LOW_WALLET_%') last_reminded_at
    from public.profiles p
    join auth.users u on u.id=p.id
    left join public.customer_wallets w on w.customer_id=p.id
    left join lateral (
      select cs.* from public.customer_subscriptions cs where cs.customer_id=p.id
      and cs.status in('ACTIVE','PAUSED','CANCEL_PENDING') order by cs.created_at desc limit 1
    ) s on true
    left join public.providers provider on provider.id=s.provider_id
    left join public.packages package on package.id=s.package_id
    where p.is_active and coalesce(w.balance_paise,0)<50000
      and not exists(select 1 from public.admin_staff_profiles staff where staff.user_id=p.id)
      and (q='' or lower(p.full_name) like '%'||q||'%' or lower(p.id::text) like '%'||q||'%'
        or (regexp_replace(q,'[^0-9]','','g')<>'' and regexp_replace(coalesce(p.phone,u.phone,''),'[^0-9]','','g') like '%'||regexp_replace(q,'[^0-9]','','g')||'%'))
  )
  select jsonb_build_object(
    'threshold_paise',50000,'total',(select count(*) from candidates),'page',page_number,
    'customers',coalesce((select jsonb_agg(to_jsonb(row_data) order by balance_paise,full_name)
      from (select * from candidates order by balance_paise,full_name limit 25 offset page_number*25) row_data),'[]'::jsonb)
  ) into result;
  return result;
end; $$;

create or replace function public.admin_notify_low_wallet_customer(target_customer uuid)
returns jsonb language plpgsql security definer set search_path=public as $$
declare balance bigint; customer_name text;
begin
  if not public.can_manage_accounts() then raise exception 'Administrator access required' using errcode='42501'; end if;
  select p.full_name,coalesce(w.balance_paise,0) into customer_name,balance from public.profiles p
  left join public.customer_wallets w on w.customer_id=p.id where p.id=target_customer and p.is_active;
  if customer_name is null then raise exception 'Customer was not found'; end if;
  if balance>=50000 then raise exception 'This customer no longer has a low wallet balance'; end if;
  insert into public.customer_notifications(customer_id,category,title,message,destination,dedupe_key)
  values(target_customer,'Payment','Please recharge your Zomeal Wallet',
    'Your wallet balance is below ₹500. Recharge now to prevent your meal subscription from being paused.','wallet',
    'ADMIN_LOW_WALLET_'||(now() at time zone 'Asia/Kolkata')::date::text)
  on conflict(customer_id,dedupe_key) do update set message=excluded.message,created_at=now(),read_at=null;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,metadata)
  values(auth.uid(),'LOW_WALLET_CUSTOMER_NOTIFIED','customer',target_customer,jsonb_build_object('balance_paise',balance));
  return jsonb_build_object('notified',true,'customer_id',target_customer,'balance_paise',balance);
end; $$;

create or replace function public.admin_notify_all_low_wallet_customers()
returns jsonb language plpgsql security definer set search_path=public as $$
declare affected integer;
begin
  if not public.can_manage_accounts() then raise exception 'Administrator access required' using errcode='42501'; end if;
  insert into public.customer_notifications(customer_id,category,title,message,destination,dedupe_key)
  select p.id,'Payment','Please recharge your Zomeal Wallet',
    'Your wallet balance is below ₹500. Recharge now to prevent your meal subscription from being paused.','wallet',
    'ADMIN_LOW_WALLET_'||(now() at time zone 'Asia/Kolkata')::date::text
  from public.profiles p left join public.customer_wallets w on w.customer_id=p.id
  where p.is_active and coalesce(w.balance_paise,0)<50000
    and not exists(select 1 from public.admin_staff_profiles staff where staff.user_id=p.id)
  on conflict(customer_id,dedupe_key) do update set message=excluded.message,created_at=now(),read_at=null;
  get diagnostics affected=row_count;
  insert into public.audit_logs(actor_id,action,entity_type,metadata)
  values(auth.uid(),'ALL_LOW_WALLET_CUSTOMERS_NOTIFIED','customer',jsonb_build_object('customer_count',affected,'threshold_paise',50000));
  return jsonb_build_object('notified',affected,'threshold_paise',50000);
end; $$;

revoke all on function public.admin_low_wallet_customers(text,integer),public.admin_notify_low_wallet_customer(uuid),public.admin_notify_all_low_wallet_customers() from public;
grant execute on function public.admin_low_wallet_customers(text,integer),public.admin_notify_low_wallet_customer(uuid),public.admin_notify_all_low_wallet_customers() to authenticated;
