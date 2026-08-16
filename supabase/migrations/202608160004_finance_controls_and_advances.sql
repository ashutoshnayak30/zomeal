-- Configurable settlement holds, safe finance test data, and recoverable provider advances.

create table public.finance_configuration (
  singleton boolean primary key default true check(singleton),
  payout_hold_hours integer not null default 48 check(payout_hold_hours between 0 and 720),
  updated_by uuid references public.profiles(id),
  updated_at timestamptz not null default now()
);
insert into public.finance_configuration(singleton,payout_hold_hours) values(true,48) on conflict(singleton) do nothing;
alter table public.finance_configuration enable row level security;
create policy finance_configuration_read on public.finance_configuration for select to authenticated using(true);

alter table public.provider_financial_ledger drop constraint if exists provider_financial_ledger_entry_type_check;
alter table public.provider_financial_ledger add constraint provider_financial_ledger_entry_type_check
  check(entry_type in ('MEAL_EARNING','REVERSAL','PAYOUT','ADVANCE_DISBURSEMENT','ADVANCE_ADJUSTMENT','TEST_EARNING'));

create table public.provider_advance_requests (
  id uuid primary key default gen_random_uuid(), provider_id uuid not null references public.providers(id),
  amount_paise bigint not null check(amount_paise>0), purpose text not null,
  status text not null default 'PENDING' check(status in ('PENDING','APPROVED','DISBURSED','REJECTED','CANCELLED','RECOVERED')),
  requested_by uuid not null references public.profiles(id), requested_at timestamptz not null default now(),
  reviewed_by uuid references public.profiles(id), reviewed_at timestamptz, admin_note text,
  payment_reference text, disbursed_at timestamptz, recovered_paise bigint not null default 0 check(recovered_paise>=0),
  updated_at timestamptz not null default now(), constraint advance_recovery_limit check(recovered_paise<=amount_paise)
);
create index provider_advance_queue_idx on public.provider_advance_requests(status,requested_at);
create index provider_advance_provider_idx on public.provider_advance_requests(provider_id,requested_at desc);
create trigger provider_advance_set_updated_at before update on public.provider_advance_requests for each row execute function public.set_updated_at();
alter table public.provider_advance_requests enable row level security;
create policy provider_advance_member_read on public.provider_advance_requests for select to authenticated using(public.is_provider_member(provider_id));
create policy provider_advance_finance_read on public.provider_advance_requests for select to authenticated using(public.has_role('ADMIN') or public.has_role('FINANCE'));

create or replace function public.current_payout_hold_hours() returns integer language sql stable security definer set search_path=public as $$
  select payout_hold_hours from public.finance_configuration where singleton=true;
$$;

create or replace function public.admin_set_payout_hold_hours(target_hours integer)
returns jsonb language plpgsql security definer set search_path=public as $$
begin
  if not public.has_role('ADMIN') then raise exception 'Administrator access is required'; end if;
  if target_hours<0 or target_hours>720 then raise exception 'Waiting period must be between 0 and 720 hours'; end if;
  update public.finance_configuration set payout_hold_hours=target_hours,updated_by=auth.uid(),updated_at=now() where singleton=true;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data) values(auth.uid(),'PAYOUT_HOLD_UPDATED','finance_configuration','singleton',jsonb_build_object('payout_hold_hours',target_hours));
  return jsonb_build_object('payout_hold_hours',target_hours);
end; $$;

create or replace function public.provider_request_advance(target_amount_paise bigint,target_purpose text)
returns jsonb language plpgsql security definer set search_path=public as $$
declare target_provider uuid; request_id uuid;
begin
  select pm.provider_id into target_provider from public.provider_members pm join public.providers p on p.id=pm.provider_id
    where pm.user_id=auth.uid() and pm.is_active and p.status='ACTIVE' order by pm.created_at desc limit 1;
  if target_provider is null then raise exception 'An active provider account is required'; end if;
  if target_amount_paise<=0 then raise exception 'Advance amount must be greater than zero'; end if;
  if nullif(trim(target_purpose),'') is null then raise exception 'Please explain why the advance is needed'; end if;
  if exists(select 1 from public.provider_advance_requests where provider_id=target_provider and status in ('PENDING','APPROVED')) then raise exception 'An advance request is already under review'; end if;
  insert into public.provider_advance_requests(provider_id,amount_paise,purpose,requested_by)
    values(target_provider,target_amount_paise,trim(target_purpose),auth.uid()) returning id into request_id;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data) values(auth.uid(),'ADVANCE_REQUESTED','provider_advance',request_id::text,jsonb_build_object('provider_id',target_provider,'amount_paise',target_amount_paise));
  return jsonb_build_object('request_id',request_id,'status','PENDING');
end; $$;

create or replace function public.admin_review_provider_advance(target_request uuid,target_status text,target_payment_reference text default null,target_note text default null)
returns jsonb language plpgsql security definer set search_path=public as $$
declare request_record public.provider_advance_requests; normalized text:=upper(trim(target_status)); ledger_id uuid;
begin
  if not (public.has_role('ADMIN') or public.has_role('FINANCE')) then raise exception 'Finance access is required'; end if;
  if normalized not in ('APPROVED','DISBURSED','REJECTED','CANCELLED') then raise exception 'Unsupported advance status'; end if;
  select * into request_record from public.provider_advance_requests where id=target_request for update;
  if request_record.id is null then raise exception 'Advance request was not found'; end if;
  if request_record.status in ('DISBURSED','REJECTED','CANCELLED','RECOVERED') then raise exception 'Finalized advance cannot be changed'; end if;
  if normalized='DISBURSED' and nullif(trim(target_payment_reference),'') is null then raise exception 'Payment reference is required'; end if;
  update public.provider_advance_requests set status=normalized,reviewed_by=auth.uid(),reviewed_at=now(),admin_note=nullif(trim(target_note),''),
    payment_reference=case when normalized='DISBURSED' then trim(target_payment_reference) else payment_reference end,
    disbursed_at=case when normalized='DISBURSED' then now() else disbursed_at end where id=target_request;
  if normalized='DISBURSED' then
    insert into public.provider_financial_ledger(provider_id,entry_type,gross_paise,commission_basis_points,commission_paise,provider_net_paise,available_at,external_reference,created_by,metadata)
      values(request_record.provider_id,'ADVANCE_DISBURSEMENT',-request_record.amount_paise,0,0,-request_record.amount_paise,now(),trim(target_payment_reference),auth.uid(),jsonb_build_object('advance_request_id',target_request)) returning id into ledger_id;
  end if;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data) values(auth.uid(),'ADVANCE_STATUS_CHANGED','provider_advance',target_request::text,jsonb_build_object('status',normalized,'payment_reference',target_payment_reference));
  return jsonb_build_object('request_id',target_request,'status',normalized,'ledger_id',ledger_id);
end; $$;

create or replace function public.admin_allocate_provider_advance(target_provider uuid,target_amount_paise bigint,target_purpose text,target_payment_reference text,target_note text default null)
returns jsonb language plpgsql security definer set search_path=public as $$
declare request_id uuid; ledger_id uuid;
begin
  if not (public.has_role('ADMIN') or public.has_role('FINANCE')) then raise exception 'Finance access is required'; end if;
  if not exists(select 1 from public.providers where id=target_provider and status='ACTIVE') then raise exception 'Choose an active provider'; end if;
  if target_amount_paise<=0 then raise exception 'Advance amount must be greater than zero'; end if;
  if nullif(trim(target_purpose),'') is null or nullif(trim(target_payment_reference),'') is null then raise exception 'Purpose and payment reference are required'; end if;
  insert into public.provider_advance_requests(provider_id,amount_paise,purpose,status,requested_by,reviewed_by,reviewed_at,admin_note,payment_reference,disbursed_at)
    values(target_provider,target_amount_paise,trim(target_purpose),'DISBURSED',auth.uid(),auth.uid(),now(),nullif(trim(target_note),''),trim(target_payment_reference),now()) returning id into request_id;
  insert into public.provider_financial_ledger(provider_id,entry_type,gross_paise,commission_basis_points,commission_paise,provider_net_paise,available_at,external_reference,created_by,metadata)
    values(target_provider,'ADVANCE_DISBURSEMENT',-target_amount_paise,0,0,-target_amount_paise,now(),trim(target_payment_reference),auth.uid(),jsonb_build_object('advance_request_id',request_id,'allocated_by_admin',true)) returning id into ledger_id;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data) values(auth.uid(),'ADVANCE_ALLOCATED','provider_advance',request_id::text,jsonb_build_object('provider_id',target_provider,'amount_paise',target_amount_paise,'payment_reference',target_payment_reference));
  return jsonb_build_object('request_id',request_id,'ledger_id',ledger_id,'status','DISBURSED');
end; $$;

create or replace function public.recover_provider_advances() returns trigger language plpgsql security definer set search_path=public as $$
declare remaining bigint:=greatest(new.provider_net_paise,0); advance_record record; applied bigint;
begin
  if new.entry_type not in ('MEAL_EARNING','TEST_EARNING') or remaining=0 then return new; end if;
  for advance_record in select * from public.provider_advance_requests where provider_id=new.provider_id and status='DISBURSED' and recovered_paise<amount_paise order by disbursed_at,id for update loop
    exit when remaining=0; applied:=least(remaining,advance_record.amount_paise-advance_record.recovered_paise);
    update public.provider_advance_requests set recovered_paise=recovered_paise+applied,
      status=case when recovered_paise+applied=amount_paise then 'RECOVERED' else status end where id=advance_record.id;
    remaining:=remaining-applied;
  end loop; return new;
end; $$;
drop trigger if exists provider_advance_recovery on public.provider_financial_ledger;
create trigger provider_advance_recovery after insert on public.provider_financial_ledger for each row execute function public.recover_provider_advances();

create or replace function public.admin_finance_test_candidates() returns jsonb language sql stable security definer set search_path=public as $$
  select case when public.has_role('ADMIN') then coalesce(jsonb_agg(jsonb_build_object('id',id,'name',display_name) order by display_name),'[]'::jsonb) else '[]'::jsonb end from public.providers where status='ACTIVE';
$$;

create or replace function public.admin_advance_queue(status_filter text default null) returns jsonb language sql stable security definer set search_path=public as $$
  select case when public.has_role('ADMIN') or public.has_role('FINANCE') then coalesce(jsonb_agg(jsonb_build_object(
    'id',a.id,'provider_id',a.provider_id,'provider_name',p.display_name,'provider_phone',p.support_phone,
    'amount_paise',a.amount_paise,'purpose',a.purpose,'status',a.status,'requested_at',a.requested_at,
    'admin_note',a.admin_note,'payment_reference',a.payment_reference,'disbursed_at',a.disbursed_at,
    'recovered_paise',a.recovered_paise,'remaining_paise',a.amount_paise-a.recovered_paise
  ) order by a.requested_at desc),'[]'::jsonb) else '[]'::jsonb end
  from public.provider_advance_requests a join public.providers p on p.id=a.provider_id
  where status_filter is null or status_filter='' or a.status=upper(status_filter);
$$;

create or replace function public.provider_earnings_summary()
returns jsonb language plpgsql stable security definer set search_path=public as $$
declare target_provider uuid; ledger_available bigint; reserved bigint;
begin
  select provider_id into target_provider from public.provider_members where user_id=auth.uid() and is_active order by created_at desc limit 1;
  if target_provider is null then raise exception 'Provider account was not found'; end if;
  select coalesce(sum(provider_net_paise),0) into ledger_available from public.provider_financial_ledger where provider_id=target_provider and available_at<=now();
  select coalesce(sum(amount_paise),0) into reserved from public.provider_payout_requests where provider_id=target_provider and status in ('PENDING','APPROVED','PROCESSING');
  return jsonb_build_object(
    'provider_id',target_provider,'payout_hold_hours',public.current_payout_hold_hours(),
    'gross_paise',coalesce((select sum(gross_paise) from public.provider_financial_ledger where provider_id=target_provider and entry_type in ('MEAL_EARNING','REVERSAL','TEST_EARNING')),0),
    'commission_paise',coalesce((select sum(commission_paise) from public.provider_financial_ledger where provider_id=target_provider and entry_type in ('MEAL_EARNING','REVERSAL','TEST_EARNING')),0),
    'provider_net_paise',coalesce((select sum(provider_net_paise) from public.provider_financial_ledger where provider_id=target_provider),0),
    'available_paise',greatest(ledger_available-reserved,0),'reserved_paise',reserved,
    'pending_48h_paise',coalesce((select sum(provider_net_paise) from public.provider_financial_ledger where provider_id=target_provider and entry_type in ('MEAL_EARNING','TEST_EARNING') and available_at>now()),0),
    'advance_outstanding_paise',coalesce((select sum(amount_paise-recovered_paise) from public.provider_advance_requests where provider_id=target_provider and status in ('DISBURSED','RECOVERED')),0),
    'advance_requests',coalesce((select jsonb_agg(to_jsonb(a) order by requested_at desc) from (select id,amount_paise,purpose,status,requested_at,admin_note,payment_reference,disbursed_at,recovered_paise from public.provider_advance_requests where provider_id=target_provider order by requested_at desc limit 20) a),'[]'::jsonb),
    'by_slot',coalesce((select jsonb_agg(jsonb_build_object('slot',meal_slot,'gross_paise',gross,'commission_paise',commission,'net_paise',net)) from (select meal_slot,sum(gross_paise) gross,sum(commission_paise) commission,sum(provider_net_paise) net from public.provider_financial_ledger where provider_id=target_provider and entry_type in ('MEAL_EARNING','REVERSAL','TEST_EARNING') group by meal_slot) s),'[]'::jsonb),
    'payout_requests',coalesce((select jsonb_agg(to_jsonb(r) order by requested_at desc) from (select id,amount_paise,preferred_method,status,requested_at,payment_reference,paid_at from public.provider_payout_requests where provider_id=target_provider order by requested_at desc limit 25) r),'[]'::jsonb),
    'recent_entries',coalesce((select jsonb_agg(to_jsonb(e) order by created_at desc) from (select id,entry_type,meal_slot,service_date,package_kind,gross_paise,commission_paise,provider_net_paise,available_at,created_at from public.provider_financial_ledger where provider_id=target_provider order by created_at desc limit 50) e),'[]'::jsonb)
  );
end; $$;

create or replace function public.admin_create_finance_test_cycle(target_provider uuid,target_gross_paise bigint default 100000)
returns jsonb language plpgsql security definer set search_path=public as $$
declare commission bigint; net bigint; ledger_id uuid; payout_id uuid;
begin
  if not public.has_role('ADMIN') then raise exception 'Administrator access is required'; end if;
  if not exists(select 1 from public.providers where id=target_provider and status='ACTIVE') then raise exception 'Choose an active provider'; end if;
  if target_gross_paise<10000 or target_gross_paise>10000000 then raise exception 'Test gross must be between ₹100 and ₹100,000'; end if;
  commission:=round(target_gross_paise::numeric*public.current_provider_commission_basis_points()/10000); net:=target_gross_paise-commission;
  insert into public.provider_financial_ledger(provider_id,entry_type,meal_slot,service_date,gross_paise,commission_basis_points,commission_paise,provider_net_paise,available_at,created_by,metadata)
    values(target_provider,'TEST_EARNING','LUNCH',current_date,target_gross_paise,public.current_provider_commission_basis_points(),commission,net,now(),auth.uid(),jsonb_build_object('is_test',true)) returning id into ledger_id;
  insert into public.provider_payout_requests(provider_id,amount_paise,preferred_method,provider_note,requested_by)
    values(target_provider,net,'UPI','ADMIN TEST — do not pay',auth.uid()) returning id into payout_id;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data) values(auth.uid(),'FINANCE_TEST_CYCLE_CREATED','provider',target_provider::text,jsonb_build_object('ledger_id',ledger_id,'payout_request_id',payout_id,'is_test',true));
  return jsonb_build_object('ledger_id',ledger_id,'payout_request_id',payout_id,'net_paise',net,'is_test',true);
end; $$;

create or replace function public.admin_clear_finance_test_data(target_provider uuid default null)
returns jsonb language plpgsql security definer set search_path=public as $$
declare payout_count integer; ledger_count integer:=0; affected integer; test_ids text[];
begin
  if not public.has_role('ADMIN') then raise exception 'Administrator access is required'; end if;
  select array_agg(id::text) into test_ids from public.provider_payout_requests where provider_note='ADMIN TEST — do not pay' and (target_provider is null or provider_id=target_provider);
  if test_ids is not null then
    delete from public.provider_financial_ledger where entry_type='PAYOUT' and metadata->>'payout_request_id'=any(test_ids);
    get diagnostics ledger_count=row_count;
  end if;
  delete from public.provider_payout_requests where provider_note='ADMIN TEST — do not pay' and (target_provider is null or provider_id=target_provider);
  get diagnostics payout_count=row_count;
  delete from public.provider_financial_ledger where metadata->>'is_test'='true' and (target_provider is null or provider_id=target_provider);
  get diagnostics affected=row_count; ledger_count:=ledger_count+affected;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data) values(auth.uid(),'FINANCE_TEST_DATA_CLEARED','finance_test',coalesce(target_provider::text,'all'),jsonb_build_object('payouts_deleted',payout_count,'ledger_entries_deleted',ledger_count));
  return jsonb_build_object('payouts_deleted',payout_count,'ledger_entries_deleted',ledger_count);
end; $$;

create or replace function public.record_meal_status_and_earning() returns trigger language plpgsql security definer set search_path=public as $$
declare subscription_record public.customer_subscriptions; package_type public.package_kind; commission bigint; hold_hours integer;
begin
  if old.status is distinct from new.status then insert into public.meal_status_events(meal_id,subscription_id,provider_id,customer_id,old_status,new_status,changed_by,metadata) values(new.id,new.subscription_id,new.provider_id,new.customer_id,old.status,new.status,auth.uid(),jsonb_build_object('delivery_personnel_id',new.delivery_personnel_id)); end if;
  if new.status='DELIVERED' and old.status is distinct from 'DELIVERED' then
    select * into subscription_record from public.customer_subscriptions where id=new.subscription_id; select kind into package_type from public.packages where id=subscription_record.package_id;
    commission:=round(new.meal_value_paise::numeric*subscription_record.commission_basis_points/10000); hold_hours:=public.current_payout_hold_hours();
    insert into public.provider_financial_ledger(provider_id,subscription_id,meal_id,entry_type,meal_slot,service_date,package_kind,gross_paise,commission_basis_points,commission_paise,provider_net_paise,available_at,created_by,metadata)
    values(new.provider_id,new.subscription_id,new.id,'MEAL_EARNING',new.meal_slot,new.service_date,package_type,new.meal_value_paise,subscription_record.commission_basis_points,commission,new.meal_value_paise-commission,coalesce(new.delivered_at,now())+make_interval(hours=>hold_hours),auth.uid(),jsonb_build_object('payment_reference',subscription_record.payment_reference,'hold_hours',hold_hours)) on conflict(meal_id) where entry_type='MEAL_EARNING' do nothing;
  end if; return new;
end; $$;

revoke all on function public.admin_set_payout_hold_hours(integer),public.provider_request_advance(bigint,text),public.admin_review_provider_advance(uuid,text,text,text),public.admin_allocate_provider_advance(uuid,bigint,text,text,text),public.admin_finance_test_candidates(),public.admin_create_finance_test_cycle(uuid,bigint),public.admin_clear_finance_test_data(uuid),public.admin_advance_queue(text) from public;
grant execute on function public.admin_set_payout_hold_hours(integer),public.provider_request_advance(bigint,text),public.admin_review_provider_advance(uuid,text,text,text),public.admin_allocate_provider_advance(uuid,bigint,text,text,text),public.admin_finance_test_candidates(),public.admin_create_finance_test_cycle(uuid,bigint),public.admin_clear_finance_test_data(uuid),public.admin_advance_queue(text) to authenticated;
