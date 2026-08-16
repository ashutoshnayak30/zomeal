-- Separate requested and approved advance amounts. Advances always use 0% commission.

alter table public.provider_advance_requests add column approved_amount_paise bigint check(approved_amount_paise>0);
alter table public.provider_advance_requests drop constraint if exists advance_recovery_limit;
alter table public.provider_advance_requests add constraint advance_recovery_limit
  check(recovered_paise<=coalesce(approved_amount_paise,amount_paise));
update public.provider_advance_requests set approved_amount_paise=amount_paise where status in ('APPROVED','DISBURSED','RECOVERED') and approved_amount_paise is null;

create or replace function public.admin_set_advance_approved_amount(target_request uuid,target_approved_amount_paise bigint,target_note text)
returns jsonb language plpgsql security definer set search_path=public as $$
begin
  if not (public.has_role('ADMIN') or public.has_role('FINANCE')) then raise exception 'Finance access is required'; end if;
  if target_approved_amount_paise<=0 then raise exception 'Approved advance must be greater than zero'; end if;
  if nullif(trim(target_note),'') is null then raise exception 'Discussion note is required'; end if;
  update public.provider_advance_requests set approved_amount_paise=target_approved_amount_paise,admin_note=trim(target_note)
    where id=target_request and status in ('PENDING','APPROVED');
  if not found then raise exception 'Advance request cannot be edited'; end if;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data) values(auth.uid(),'ADVANCE_AMOUNT_NEGOTIATED','provider_advance',target_request::text,jsonb_build_object('approved_amount_paise',target_approved_amount_paise,'commission_basis_points',0,'note',target_note));
  return jsonb_build_object('request_id',target_request,'approved_amount_paise',target_approved_amount_paise,'commission_basis_points',0);
end; $$;

create or replace function public.admin_review_provider_advance(target_request uuid,target_status text,target_payment_reference text default null,target_note text default null)
returns jsonb language plpgsql security definer set search_path=public as $$
declare request_record public.provider_advance_requests; normalized text:=upper(trim(target_status)); ledger_id uuid; disbursed bigint;
begin
  if not (public.has_role('ADMIN') or public.has_role('FINANCE')) then raise exception 'Finance access is required'; end if;
  if normalized not in ('APPROVED','DISBURSED','REJECTED','CANCELLED') then raise exception 'Unsupported advance status'; end if;
  select * into request_record from public.provider_advance_requests where id=target_request for update;
  if request_record.id is null then raise exception 'Advance request was not found'; end if;
  if request_record.status in ('DISBURSED','REJECTED','CANCELLED','RECOVERED') then raise exception 'Finalized advance cannot be changed'; end if;
  disbursed:=coalesce(request_record.approved_amount_paise,request_record.amount_paise);
  if normalized='DISBURSED' and nullif(trim(target_payment_reference),'') is null then raise exception 'Payment reference is required'; end if;
  update public.provider_advance_requests set status=normalized,approved_amount_paise=case when normalized in ('APPROVED','DISBURSED') then disbursed else approved_amount_paise end,
    reviewed_by=auth.uid(),reviewed_at=now(),admin_note=coalesce(nullif(trim(target_note),''),admin_note),
    payment_reference=case when normalized='DISBURSED' then trim(target_payment_reference) else payment_reference end,
    disbursed_at=case when normalized='DISBURSED' then now() else disbursed_at end where id=target_request;
  if normalized='DISBURSED' then
    insert into public.provider_financial_ledger(provider_id,entry_type,gross_paise,commission_basis_points,commission_paise,provider_net_paise,available_at,external_reference,created_by,metadata)
      values(request_record.provider_id,'ADVANCE_DISBURSEMENT',-disbursed,0,0,-disbursed,now(),trim(target_payment_reference),auth.uid(),jsonb_build_object('advance_request_id',target_request,'commission_basis_points',0,'full_approved_amount',true)) returning id into ledger_id;
  end if;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data) values(auth.uid(),'ADVANCE_STATUS_CHANGED','provider_advance',target_request::text,jsonb_build_object('status',normalized,'approved_amount_paise',disbursed,'commission_basis_points',0,'payment_reference',target_payment_reference));
  return jsonb_build_object('request_id',target_request,'status',normalized,'approved_amount_paise',disbursed,'commission_basis_points',0,'ledger_id',ledger_id);
end; $$;

create or replace function public.admin_allocate_provider_advance(target_provider uuid,target_amount_paise bigint,target_purpose text,target_payment_reference text,target_note text default null)
returns jsonb language plpgsql security definer set search_path=public as $$
declare request_id uuid; ledger_id uuid;
begin
  if not (public.has_role('ADMIN') or public.has_role('FINANCE')) then raise exception 'Finance access is required'; end if;
  if not exists(select 1 from public.providers where id=target_provider and status='ACTIVE') then raise exception 'Choose an active provider'; end if;
  if target_amount_paise<=0 then raise exception 'Advance amount must be greater than zero'; end if;
  if nullif(trim(target_purpose),'') is null or nullif(trim(target_payment_reference),'') is null then raise exception 'Purpose and payment reference are required'; end if;
  insert into public.provider_advance_requests(provider_id,amount_paise,approved_amount_paise,purpose,status,requested_by,reviewed_by,reviewed_at,admin_note,payment_reference,disbursed_at)
    values(target_provider,target_amount_paise,target_amount_paise,trim(target_purpose),'DISBURSED',auth.uid(),auth.uid(),now(),nullif(trim(target_note),''),trim(target_payment_reference),now()) returning id into request_id;
  insert into public.provider_financial_ledger(provider_id,entry_type,gross_paise,commission_basis_points,commission_paise,provider_net_paise,available_at,external_reference,created_by,metadata)
    values(target_provider,'ADVANCE_DISBURSEMENT',-target_amount_paise,0,0,-target_amount_paise,now(),trim(target_payment_reference),auth.uid(),jsonb_build_object('advance_request_id',request_id,'allocated_by_admin',true,'commission_basis_points',0)) returning id into ledger_id;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data) values(auth.uid(),'ADVANCE_ALLOCATED','provider_advance',request_id::text,jsonb_build_object('provider_id',target_provider,'approved_amount_paise',target_amount_paise,'commission_basis_points',0,'payment_reference',target_payment_reference));
  return jsonb_build_object('request_id',request_id,'ledger_id',ledger_id,'status','DISBURSED','approved_amount_paise',target_amount_paise,'commission_basis_points',0);
end; $$;

create or replace function public.recover_provider_advances() returns trigger language plpgsql security definer set search_path=public as $$
declare remaining bigint:=greatest(new.provider_net_paise,0); advance_record record; applied bigint; approved bigint;
begin
  if new.entry_type not in ('MEAL_EARNING','TEST_EARNING') or remaining=0 then return new; end if;
  for advance_record in select * from public.provider_advance_requests where provider_id=new.provider_id and status='DISBURSED' and recovered_paise<coalesce(approved_amount_paise,amount_paise) order by disbursed_at,id for update loop
    exit when remaining=0; approved:=coalesce(advance_record.approved_amount_paise,advance_record.amount_paise); applied:=least(remaining,approved-advance_record.recovered_paise);
    update public.provider_advance_requests set recovered_paise=recovered_paise+applied,status=case when recovered_paise+applied=approved then 'RECOVERED' else status end where id=advance_record.id;
    remaining:=remaining-applied;
  end loop; return new;
end; $$;

create or replace function public.admin_advance_queue(status_filter text default null) returns jsonb language sql stable security definer set search_path=public as $$
  select case when public.has_role('ADMIN') or public.has_role('FINANCE') then coalesce(jsonb_agg(jsonb_build_object(
    'id',a.id,'provider_id',a.provider_id,'provider_name',p.display_name,'provider_phone',p.support_phone,'requested_amount_paise',a.amount_paise,
    'approved_amount_paise',coalesce(a.approved_amount_paise,a.amount_paise),'amount_paise',coalesce(a.approved_amount_paise,a.amount_paise),
    'purpose',a.purpose,'status',a.status,'requested_at',a.requested_at,'admin_note',a.admin_note,'payment_reference',a.payment_reference,
    'disbursed_at',a.disbursed_at,'recovered_paise',a.recovered_paise,'remaining_paise',coalesce(a.approved_amount_paise,a.amount_paise)-a.recovered_paise,'commission_basis_points',0
  ) order by a.requested_at desc),'[]'::jsonb) else '[]'::jsonb end from public.provider_advance_requests a join public.providers p on p.id=a.provider_id
  where status_filter is null or status_filter='' or a.status=upper(status_filter);
$$;

alter function public.provider_earnings_summary() rename to provider_earnings_summary_commission_v2;
create function public.provider_earnings_summary() returns jsonb language sql stable security definer set search_path=public as $$
  with base as (select public.provider_earnings_summary_commission_v2() value), provider as (select (value->>'provider_id')::uuid id from base)
  select base.value || jsonb_build_object(
    'advance_outstanding_paise',coalesce((select sum(coalesce(a.approved_amount_paise,a.amount_paise)-a.recovered_paise) from public.provider_advance_requests a,provider p where a.provider_id=p.id and a.status in ('DISBURSED','RECOVERED')),0),
    'advance_requests',coalesce((select jsonb_agg(jsonb_build_object('id',a.id,'requested_amount_paise',a.amount_paise,'approved_amount_paise',coalesce(a.approved_amount_paise,a.amount_paise),'amount_paise',coalesce(a.approved_amount_paise,a.amount_paise),'purpose',a.purpose,'status',a.status,'requested_at',a.requested_at,'admin_note',a.admin_note,'payment_reference',a.payment_reference,'disbursed_at',a.disbursed_at,'recovered_paise',a.recovered_paise,'commission_basis_points',0) order by a.requested_at desc) from public.provider_advance_requests a,provider p where a.provider_id=p.id),'[]'::jsonb)
  ) from base;
$$;

revoke all on function public.admin_set_advance_approved_amount(uuid,bigint,text) from public;
grant execute on function public.admin_set_advance_approved_amount(uuid,bigint,text) to authenticated;
grant execute on function public.provider_earnings_summary() to authenticated;
