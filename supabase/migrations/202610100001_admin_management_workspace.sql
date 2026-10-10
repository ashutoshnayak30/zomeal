-- Organised admin workspace. No payment state or wallet balance edits.
alter table public.profiles add column if not exists admin_reviewed_at timestamptz;
alter table public.profiles add column if not exists admin_reviewed_by uuid references auth.users(id);
create or replace function public.protect_customer_admin_flags() returns trigger language plpgsql security definer set search_path=public as $$
begin
 if auth.uid() is not null and not can_manage_accounts() and
 (new.is_active is distinct from old.is_active or new.admin_reviewed_at is distinct from old.admin_reviewed_at or new.admin_reviewed_by is distinct from old.admin_reviewed_by)
 then raise exception 'Administrator access required'; end if;
 return new;
end $$;
create trigger protect_customer_admin_flags before update on public.profiles for each row execute function public.protect_customer_admin_flags();

create function public.admin_customer_action(target_id uuid,target_action text,reason text) returns jsonb
language plpgsql security definer set search_path=public as $$
declare old_data jsonb; begin
 if not can_manage_accounts() then raise exception 'Administrator access required'; end if;
 if target_id=auth.uid() or exists(select 1 from user_roles where user_id=target_id and role<>'CUSTOMER') or exists(select 1 from provider_members where user_id=target_id)
 then raise exception 'Use staff or provider management for this account'; end if;
 if target_action is null or target_action not in ('VERIFY','BLOCK','UNBLOCK') or length(trim(coalesce(reason,'')))<10 or length(reason)>500 then raise exception 'Choose an action and provide a reason (10–500 characters)'; end if;
 select to_jsonb(p) into old_data from profiles p where id=target_id for update;
 if not found then raise exception 'Customer not found'; end if;
 if target_action='VERIFY' then
  update profiles set admin_reviewed_at=now(),admin_reviewed_by=auth.uid() where id=target_id;
 else
  update profiles set is_active=(target_action='UNBLOCK') where id=target_id;
  update auth.users set banned_until=case when target_action='BLOCK' then now()+interval '100 years' else null end where id=target_id;
 end if;
 insert into audit_logs(actor_id,action,entity_type,entity_id,before_data,after_data)
 values(auth.uid(),'CUSTOMER_'||target_action,'profiles',target_id::text,jsonb_build_object('is_active',old_data->'is_active','admin_reviewed_at',old_data->'admin_reviewed_at'),jsonb_build_object('reason',trim(reason)));
 return jsonb_build_object('success',true);
end $$;

create function public.admin_payment_register(target_from date,target_to date,status_filter text default '',search_text text default '',page_number integer default 0) returns jsonb
language plpgsql stable security definer set search_path=public as $$
declare result jsonb; begin
 if not (has_role('ADMIN') or has_role('FINANCE')) then raise exception 'Finance access required'; end if;
 if target_from is null or target_to is null or target_from>target_to or target_to-target_from>366 or page_number is null or page_number<0 or page_number>10000 or length(coalesce(search_text,''))>100 then raise exception 'Invalid date range, search or page'; end if;
 if coalesce(status_filter,'') not in ('','CREATING','CREATED','AUTHORIZED','CAPTURED','FAILED','CANCELLED','REFUNDED','PARTIALLY_REFUNDED') then raise exception 'Invalid payment status'; end if;
 with filtered as materialized (
 select o.id,o.created_at,o.captured_at,o.gateway_order_id,o.gateway_payment_id,o.status,o.amount_paise,o.platform_fee_paise,o.failure_description,p.full_name customer,k.display_name provider
 from payment_orders o left join profiles p on p.id=o.customer_id left join providers k on k.id=o.provider_id
 where not o.test_mode and o.created_at >= (target_from::timestamp at time zone 'Asia/Kolkata') and o.created_at < ((target_to+1)::timestamp at time zone 'Asia/Kolkata')
 and (coalesce(status_filter,'')='' or o.status=status_filter)
 and (coalesce(search_text,'')='' or strpos(lower(concat_ws(' ',o.id::text,o.gateway_order_id,o.gateway_payment_id,p.full_name,k.display_name)),lower(trim(search_text)))>0))
 select jsonb_build_object('total',(select count(*) from filtered),'rows',coalesce((select jsonb_agg(to_jsonb(x) order by created_at desc,id desc) from (select * from filtered order by created_at desc,id desc limit 50 offset page_number*50)x),'[]'::jsonb)) into result;
 return result;
end $$;

create function public.admin_ceo_report(target_from date,target_to date) returns jsonb
language plpgsql stable security definer set search_path=public as $$
declare report jsonb; today date:=(now() at time zone 'Asia/Kolkata')::date; metrics jsonb; begin
 report:=admin_business_dashboard(target_from,target_to);
 select jsonb_build_object(
 'registered_customers',(select count(*) from profiles p where nullif(trim(p.phone),'') is not null and not exists(select 1 from user_roles r where r.user_id=p.id and r.role<>'CUSTOMER') and not exists(select 1 from provider_members m where m.user_id=p.id)),
 'active_customers',(select count(distinct s.customer_id) from customer_subscriptions s join profiles p on p.id=s.customer_id where p.is_active and s.status='ACTIVE' and s.start_date<=today and s.end_date>=today),
 'blocked_customers',(select count(*) from profiles p where not p.is_active and not exists(select 1 from user_roles r where r.user_id=p.id and r.role<>'CUSTOMER') and not exists(select 1 from provider_members m where m.user_id=p.id)),
 'delivered_gmv_paise',(select coalesce(sum((h->>'delivered_gmv_paise')::bigint),0) from jsonb_array_elements(report->'history')h),
 'provider_earnings_paise',report->'summary'->'period_provider_net_paise',
 'captured_payments',(select coalesce(sum((h->>'captured_count')::bigint),0) from jsonb_array_elements(report->'history')h),
 'failed_payments',(select coalesce(sum((h->>'failed_count')::bigint),0) from jsonb_array_elements(report->'history')h)
 ) into metrics;
 return report||jsonb_build_object('ceo',metrics);
end $$;

alter table public.notification_schedules add column category text not null default 'UPDATE' check(category in ('OFFER','UPDATE','REMINDER'));
alter function public.admin_save_notification_schedule(jsonb) rename to admin_save_notification_schedule_base;
create function public.admin_save_notification_schedule(payload jsonb) returns uuid language plpgsql security definer set search_path=public as $$
declare sid uuid; category_name text:=coalesce(payload->>'category','UPDATE'); begin
 if not can_manage_accounts() then raise exception 'Administrator access required'; end if;
 if category_name not in ('OFFER','UPDATE','REMINDER') then raise exception 'Invalid category'; end if;
 sid:=admin_save_notification_schedule_base(payload);
 update notification_schedules set category=category_name where id=sid;
 return sid;
end $$;
revoke all on function public.admin_save_notification_schedule_base(jsonb) from public,anon,authenticated;
revoke all on function public.admin_customer_action(uuid,text,text),public.admin_payment_register(date,date,text,text,integer),public.admin_ceo_report(date,date),public.admin_save_notification_schedule(jsonb) from public,anon;
grant execute on function public.admin_customer_action(uuid,text,text),public.admin_payment_register(date,date,text,text,integer),public.admin_ceo_report(date,date),public.admin_save_notification_schedule(jsonb) to authenticated;
