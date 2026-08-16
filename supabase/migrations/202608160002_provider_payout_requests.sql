-- Provider payout requests. Payout identity details are collected only after activation.

create table public.provider_payout_requests (
  id uuid primary key default gen_random_uuid(),
  provider_id uuid not null references public.providers(id),
  amount_paise bigint not null check(amount_paise>0),
  preferred_method text not null check(preferred_method in ('UPI','BANK_TRANSFER','CHEQUE','CASH')),
  status text not null default 'PENDING' check(status in ('PENDING','APPROVED','PROCESSING','PAID','REJECTED','CANCELLED')),
  provider_note text,
  requested_by uuid not null references public.profiles(id),
  requested_at timestamptz not null default now(),
  reviewed_by uuid references public.profiles(id),
  reviewed_at timestamptz,
  admin_note text,
  payment_reference text,
  paid_at timestamptz,
  updated_at timestamptz not null default now()
);

create index provider_payout_requests_provider_time_idx on public.provider_payout_requests(provider_id,requested_at desc);
create index provider_payout_requests_queue_idx on public.provider_payout_requests(status,requested_at);
create trigger provider_payout_requests_set_updated_at before update on public.provider_payout_requests
for each row execute function public.set_updated_at();

alter table public.provider_payout_requests enable row level security;
create policy provider_payout_member_read on public.provider_payout_requests for select to authenticated
  using(public.is_provider_member(provider_id));
create policy provider_payout_finance_read on public.provider_payout_requests for select to authenticated
  using(public.has_role('ADMIN') or public.has_role('FINANCE'));

create or replace function public.provider_request_payout(target_amount_paise bigint,target_method text,target_note text default null)
returns jsonb language plpgsql security definer set search_path=public as $$
declare target_provider uuid; normalized_method text:=upper(trim(target_method)); earned_available bigint; reserved bigint; request_id uuid;
begin
  select pm.provider_id into target_provider from public.provider_members pm join public.providers p on p.id=pm.provider_id
  where pm.user_id=auth.uid() and pm.is_active and p.status='ACTIVE' order by pm.created_at desc limit 1;
  if target_provider is null then raise exception 'An active provider account is required'; end if;
  if normalized_method not in ('UPI','BANK_TRANSFER','CHEQUE','CASH') then raise exception 'Unsupported payout method'; end if;
  if target_amount_paise<=0 then raise exception 'Payout amount must be greater than zero'; end if;
  perform pg_advisory_xact_lock(hashtext(target_provider::text));
  select coalesce(sum(provider_net_paise),0) into earned_available from public.provider_financial_ledger
    where provider_id=target_provider and available_at<=now();
  select coalesce(sum(amount_paise),0) into reserved from public.provider_payout_requests
    where provider_id=target_provider and status in ('PENDING','APPROVED','PROCESSING');
  if target_amount_paise>earned_available-reserved then raise exception 'Requested amount exceeds available balance'; end if;
  insert into public.provider_payout_requests(provider_id,amount_paise,preferred_method,provider_note,requested_by)
  values(target_provider,target_amount_paise,normalized_method,nullif(trim(target_note),''),auth.uid()) returning id into request_id;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data)
  values(auth.uid(),'PAYOUT_REQUESTED','provider_payout_request',request_id::text,
    jsonb_build_object('provider_id',target_provider,'amount_paise',target_amount_paise,'method',normalized_method));
  return jsonb_build_object('request_id',request_id,'status','PENDING','amount_paise',target_amount_paise);
end; $$;

create or replace function public.admin_review_provider_payout(target_request uuid,target_status text,target_payment_reference text default null,target_note text default null)
returns jsonb language plpgsql security definer set search_path=public as $$
declare request_record public.provider_payout_requests; normalized_status text:=upper(trim(target_status)); ledger_id uuid;
begin
  if not (public.has_role('ADMIN') or public.has_role('FINANCE')) then raise exception 'Finance access is required'; end if;
  if normalized_status not in ('APPROVED','PROCESSING','PAID','REJECTED','CANCELLED') then raise exception 'Unsupported payout status'; end if;
  select * into request_record from public.provider_payout_requests where id=target_request for update;
  if request_record.id is null then raise exception 'Payout request was not found'; end if;
  if request_record.status in ('PAID','REJECTED','CANCELLED') then raise exception 'Finalized payout request cannot be changed'; end if;
  if normalized_status='PAID' and (target_payment_reference is null or trim(target_payment_reference)='') then raise exception 'Payment reference is required'; end if;
  update public.provider_payout_requests set status=normalized_status,reviewed_by=auth.uid(),reviewed_at=now(),
    admin_note=nullif(trim(target_note),''),payment_reference=case when normalized_status='PAID' then target_payment_reference else payment_reference end,
    paid_at=case when normalized_status='PAID' then now() else paid_at end where id=target_request;
  if normalized_status='PAID' then
    insert into public.provider_financial_ledger(provider_id,entry_type,gross_paise,commission_basis_points,commission_paise,
      provider_net_paise,available_at,external_reference,created_by,metadata)
    values(request_record.provider_id,'PAYOUT',-request_record.amount_paise,0,0,-request_record.amount_paise,now(),
      target_payment_reference,auth.uid(),jsonb_build_object('payout_request_id',target_request)) returning id into ledger_id;
  end if;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data)
  values(auth.uid(),'PAYOUT_STATUS_CHANGED','provider_payout_request',target_request::text,
    jsonb_build_object('status',normalized_status,'payment_reference',target_payment_reference));
  return jsonb_build_object('request_id',target_request,'status',normalized_status,'ledger_id',ledger_id);
end; $$;

create or replace function public.provider_earnings_summary()
returns jsonb language plpgsql stable security definer set search_path=public as $$
declare target_provider uuid; ledger_available bigint; reserved bigint;
begin
  select provider_id into target_provider from public.provider_members where user_id=auth.uid() and is_active order by created_at desc limit 1;
  if target_provider is null then raise exception 'Provider account was not found'; end if;
  select coalesce(sum(provider_net_paise),0) into ledger_available from public.provider_financial_ledger where provider_id=target_provider and available_at<=now();
  select coalesce(sum(amount_paise),0) into reserved from public.provider_payout_requests where provider_id=target_provider and status in ('PENDING','APPROVED','PROCESSING');
  return jsonb_build_object(
    'provider_id',target_provider,
    'gross_paise',coalesce((select sum(gross_paise) from public.provider_financial_ledger where provider_id=target_provider and entry_type in ('MEAL_EARNING','REVERSAL')),0),
    'commission_paise',coalesce((select sum(commission_paise) from public.provider_financial_ledger where provider_id=target_provider and entry_type in ('MEAL_EARNING','REVERSAL')),0),
    'provider_net_paise',coalesce((select sum(provider_net_paise) from public.provider_financial_ledger where provider_id=target_provider),0),
    'available_paise',greatest(ledger_available-reserved,0),'reserved_paise',reserved,
    'pending_48h_paise',coalesce((select sum(provider_net_paise) from public.provider_financial_ledger where provider_id=target_provider and entry_type='MEAL_EARNING' and available_at>now()),0),
    'by_slot',coalesce((select jsonb_agg(jsonb_build_object('slot',meal_slot,'gross_paise',gross,'commission_paise',commission,'net_paise',net))
      from (select meal_slot,sum(gross_paise) gross,sum(commission_paise) commission,sum(provider_net_paise) net
        from public.provider_financial_ledger where provider_id=target_provider and entry_type in ('MEAL_EARNING','REVERSAL') group by meal_slot) slot_totals),'[]'::jsonb),
    'payout_requests',coalesce((select jsonb_agg(jsonb_build_object('id',id,'amount_paise',amount_paise,'preferred_method',preferred_method,
      'status',status,'requested_at',requested_at,'payment_reference',payment_reference,'paid_at',paid_at) order by requested_at desc)
      from (select * from public.provider_payout_requests where provider_id=target_provider order by requested_at desc limit 25) requests),'[]'::jsonb),
    'recent_entries',coalesce((select jsonb_agg(row_data order by created_at desc) from (
      select jsonb_build_object('id',id,'entry_type',entry_type,'meal_slot',meal_slot,'service_date',service_date,
        'package_kind',package_kind,'gross_paise',gross_paise,'commission_paise',commission_paise,
        'provider_net_paise',provider_net_paise,'available_at',available_at,'created_at',created_at) row_data,created_at
      from public.provider_financial_ledger where provider_id=target_provider order by created_at desc limit 50) recent),'[]'::jsonb)
  );
end; $$;

create or replace function public.admin_payout_queue(status_filter text default null,search_text text default null)
returns jsonb language sql stable security definer set search_path=public as $$
  with permitted as (
    select 1 where public.has_role('ADMIN') or public.has_role('FINANCE')
  ), balances as (
    select p.id provider_id,
      coalesce(sum(l.provider_net_paise) filter(where l.available_at<=now()),0)::bigint ledger_available_paise,
      coalesce(sum(l.provider_net_paise) filter(where l.available_at>now() and l.entry_type='MEAL_EARNING'),0)::bigint pending_48h_paise,
      count(*) filter(where l.entry_type='MEAL_EARNING')::int delivered_meals
    from public.providers p left join public.provider_financial_ledger l on l.provider_id=p.id group by p.id
  ), reserved as (
    select provider_id,coalesce(sum(amount_paise),0)::bigint reserved_paise from public.provider_payout_requests
    where status in ('PENDING','APPROVED','PROCESSING') group by provider_id
  )
  select coalesce(jsonb_agg(jsonb_build_object(
    'id',r.id,'provider_id',r.provider_id,'provider_name',p.display_name,'provider_phone',p.support_phone,
    'amount_paise',r.amount_paise,'preferred_method',r.preferred_method,'status',r.status,
    'provider_note',r.provider_note,'requested_at',r.requested_at,'reviewed_at',r.reviewed_at,
    'admin_note',r.admin_note,'payment_reference',r.payment_reference,'paid_at',r.paid_at,
    'ledger_available_paise',b.ledger_available_paise,'reserved_paise',coalesce(x.reserved_paise,0),
    'available_to_request_paise',greatest(b.ledger_available_paise-coalesce(x.reserved_paise,0),0),
    'pending_48h_paise',b.pending_48h_paise,'delivered_meals',b.delivered_meals
  ) order by r.requested_at desc),'[]'::jsonb)
  from permitted,public.provider_payout_requests r join public.providers p on p.id=r.provider_id
  join balances b on b.provider_id=r.provider_id left join reserved x on x.provider_id=r.provider_id
  where (status_filter is null or status_filter='' or r.status=upper(status_filter))
    and (search_text is null or search_text='' or p.display_name ilike '%'||search_text||'%' or coalesce(p.support_phone,'') ilike '%'||search_text||'%');
$$;

create or replace function public.admin_payout_detail(target_request uuid)
returns jsonb language plpgsql stable security definer set search_path=public as $$
declare result jsonb;
begin
  if not (public.has_role('ADMIN') or public.has_role('FINANCE')) then raise exception 'Finance access is required'; end if;
  select jsonb_build_object(
    'request',to_jsonb(r),'provider',jsonb_build_object('id',p.id,'name',p.display_name,'phone',p.support_phone,'status',p.status),
    'recent_entries',coalesce((select jsonb_agg(to_jsonb(e) order by e.created_at desc) from
      (select id,entry_type,meal_slot,service_date,package_kind,gross_paise,commission_paise,provider_net_paise,available_at,external_reference,created_at
       from public.provider_financial_ledger where provider_id=r.provider_id order by created_at desc limit 75) e),'[]'::jsonb),
    'payout_history',coalesce((select jsonb_agg(to_jsonb(h) order by h.requested_at desc) from
      (select id,amount_paise,preferred_method,status,requested_at,payment_reference,paid_at
       from public.provider_payout_requests where provider_id=r.provider_id order by requested_at desc limit 25) h),'[]'::jsonb)
  ) into result from public.provider_payout_requests r join public.providers p on p.id=r.provider_id where r.id=target_request;
  if result is null then raise exception 'Payout request was not found'; end if;
  return result;
end; $$;

revoke all on function public.provider_request_payout(bigint,text,text),public.admin_review_provider_payout(uuid,text,text,text),
  public.admin_payout_queue(text,text),public.admin_payout_detail(uuid) from public;
grant execute on function public.provider_request_payout(bigint,text,text),public.admin_review_provider_payout(uuid,text,text,text),
  public.admin_payout_queue(text,text),public.admin_payout_detail(uuid) to authenticated;
