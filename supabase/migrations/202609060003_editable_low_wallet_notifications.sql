-- Editable low-wallet messages. Text is supplied by an authorized administrator.
create or replace function public.admin_notify_low_wallet_customer_custom(target_customer uuid,target_title text,target_message text)
returns jsonb language plpgsql security definer set search_path=public as $$
declare balance bigint; customer_name text; clean_title text:=trim(coalesce(target_title,'')); clean_message text:=trim(coalesce(target_message,''));
begin
  if not public.can_manage_accounts() then raise exception 'Administrator access required' using errcode='42501'; end if;
  if length(clean_title) not between 1 and 100 or length(clean_message) not between 1 and 500 then raise exception 'Notification title or message is invalid'; end if;
  select p.full_name,coalesce(w.balance_paise,0) into customer_name,balance from public.profiles p
  left join public.customer_wallets w on w.customer_id=p.id where p.id=target_customer and p.is_active;
  if customer_name is null then raise exception 'Customer was not found'; end if;
  if balance>=50000 then raise exception 'This customer no longer has a low wallet balance'; end if;
  insert into public.customer_notifications(customer_id,category,title,message,destination,dedupe_key)
  values(target_customer,'Payment',clean_title,clean_message,'wallet','ADMIN_LOW_WALLET_'||(now() at time zone 'Asia/Kolkata')::date::text)
  on conflict(customer_id,dedupe_key) do update set title=excluded.title,message=excluded.message,created_at=now(),read_at=null;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,metadata)
  values(auth.uid(),'LOW_WALLET_CUSTOMER_NOTIFIED','customer',target_customer,jsonb_build_object('balance_paise',balance,'title',clean_title));
  return jsonb_build_object('notified',true,'customer_id',target_customer,'balance_paise',balance);
end; $$;

create or replace function public.admin_notify_all_low_wallet_customers_custom(target_title text,target_message text)
returns jsonb language plpgsql security definer set search_path=public as $$
declare affected integer; clean_title text:=trim(coalesce(target_title,'')); clean_message text:=trim(coalesce(target_message,''));
begin
  if not public.can_manage_accounts() then raise exception 'Administrator access required' using errcode='42501'; end if;
  if length(clean_title) not between 1 and 100 or length(clean_message) not between 1 and 500 then raise exception 'Notification title or message is invalid'; end if;
  insert into public.customer_notifications(customer_id,category,title,message,destination,dedupe_key)
  select p.id,'Payment',clean_title,clean_message,'wallet','ADMIN_LOW_WALLET_'||(now() at time zone 'Asia/Kolkata')::date::text
  from public.profiles p left join public.customer_wallets w on w.customer_id=p.id
  where p.is_active and coalesce(w.balance_paise,0)<50000
    and not exists(select 1 from public.admin_staff_profiles staff where staff.user_id=p.id)
    and coalesce(nullif(trim(p.full_name),''),nullif(trim(p.phone),'')) is not null
  on conflict(customer_id,dedupe_key) do update set title=excluded.title,message=excluded.message,created_at=now(),read_at=null;
  get diagnostics affected=row_count;
  insert into public.audit_logs(actor_id,action,entity_type,metadata)
  values(auth.uid(),'ALL_LOW_WALLET_CUSTOMERS_NOTIFIED','customer',jsonb_build_object('customer_count',affected,'threshold_paise',50000,'title',clean_title));
  return jsonb_build_object('notified',affected,'threshold_paise',50000);
end; $$;

revoke all on function public.admin_notify_low_wallet_customer_custom(uuid,text,text),public.admin_notify_all_low_wallet_customers_custom(text,text) from public;
grant execute on function public.admin_notify_low_wallet_customer_custom(uuid,text,text),public.admin_notify_all_low_wallet_customers_custom(text,text) to authenticated;
