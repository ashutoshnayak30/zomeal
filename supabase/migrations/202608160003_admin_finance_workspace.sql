-- Admin read models for payout verification and settlement operations.
-- Kept in a separate migration so existing deployments of the payout table can upgrade safely.

create or replace function public.admin_payout_queue(status_filter text default null,search_text text default null)
returns jsonb language sql stable security definer set search_path=public as $$
  with permitted as (select 1 where public.has_role('ADMIN') or public.has_role('FINANCE')),
  balances as (
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
  from permitted cross join public.provider_payout_requests r join public.providers p on p.id=r.provider_id
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

revoke all on function public.admin_payout_queue(text,text),public.admin_payout_detail(uuid) from public;
grant execute on function public.admin_payout_queue(text,text),public.admin_payout_detail(uuid) to authenticated;
