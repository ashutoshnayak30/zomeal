-- Production-only marketplace analytics for the Zomeal admin overview.
-- Monetary values are returned in paise; the client only formats them.
create or replace function public.admin_business_dashboard(target_from date default ((now() at time zone 'Asia/Kolkata')::date-29),target_to date default (now() at time zone 'Asia/Kolkata')::date)
returns jsonb language plpgsql stable security definer set search_path=public as $$
declare from_date date:=coalesce(target_from,(now() at time zone 'Asia/Kolkata')::date-29); to_date date:=coalesce(target_to,(now() at time zone 'Asia/Kolkata')::date); today_ist date:=(now() at time zone 'Asia/Kolkata')::date; result jsonb;
begin
  if not (public.has_role('ADMIN') or public.has_role('FINANCE') or public.has_role('OPERATIONS')) then raise exception 'Admin analytics access is required'; end if;
  if from_date>to_date or to_date-from_date>366 then raise exception 'Choose a valid reporting period of no more than 367 days'; end if;
  with production_payments as (select * from public.payment_orders where test_mode=false),
  production_ledger as (select * from public.provider_financial_ledger where entry_type in ('MEAL_EARNING','REVERSAL') and coalesce((metadata->>'is_test')::boolean,false)=false),
  dates as (select generate_series(from_date,to_date,'1 day'::interval)::date report_date),
  payment_days as (
    select (coalesce(captured_at,verified_at,updated_at) at time zone 'Asia/Kolkata')::date report_date,
      sum(amount_paise) filter(where status='CAPTURED')::bigint captured_paise,
      sum(platform_fee_paise) filter(where status='CAPTURED')::bigint platform_fee_paise,
      sum(delivery_fee_paise) filter(where status='CAPTURED')::bigint delivery_fee_paise,
      sum(discount_paise) filter(where status='CAPTURED')::bigint discount_paise,
      sum(amount_paise) filter(where status in ('REFUNDED','PARTIALLY_REFUNDED'))::bigint refunded_order_value_paise,
      count(*) filter(where status='CAPTURED')::integer captured_count,count(*) filter(where status='FAILED')::integer failed_count
    from production_payments where (coalesce(captured_at,verified_at,updated_at) at time zone 'Asia/Kolkata')::date between from_date and to_date group by 1),
  ledger_days as (
    select coalesce(service_date,(created_at at time zone 'Asia/Kolkata')::date) report_date,sum(gross_paise)::bigint delivered_gmv_paise,
      sum(commission_paise)::bigint commission_paise,sum(provider_net_paise)::bigint provider_net_paise
    from production_ledger where coalesce(service_date,(created_at at time zone 'Asia/Kolkata')::date) between from_date and to_date group by 1),
  history as (
    select d.report_date,coalesce(p.captured_paise,0) captured_paise,coalesce(p.platform_fee_paise,0) platform_fee_paise,
      coalesce(p.delivery_fee_paise,0) delivery_fee_paise,coalesce(p.discount_paise,0) discount_paise,
      coalesce(p.refunded_order_value_paise,0) refunded_order_value_paise,coalesce(p.captured_count,0) captured_count,
      coalesce(p.failed_count,0) failed_count,coalesce(l.delivered_gmv_paise,0) delivered_gmv_paise,
      coalesce(l.commission_paise,0) commission_paise,coalesce(l.provider_net_paise,0) provider_net_paise,
      coalesce(p.platform_fee_paise,0)+coalesce(l.commission_paise,0) zomeal_revenue_paise
    from dates d left join payment_days p using(report_date) left join ledger_days l using(report_date)),
  provider_performance as (
    select p.id,p.display_name,p.status,count(distinct s.id) filter(where s.status in ('ACTIVE','PAUSED','CANCEL_PENDING'))::integer active_subscribers,
      coalesce((select sum(po.amount_paise) from production_payments po where po.provider_id=p.id and po.status='CAPTURED' and (coalesce(po.captured_at,po.verified_at,po.updated_at) at time zone 'Asia/Kolkata')::date between from_date and to_date),0)::bigint captured_paise,
      coalesce((select sum(pl.commission_paise) from production_ledger pl where pl.provider_id=p.id and coalesce(pl.service_date,(pl.created_at at time zone 'Asia/Kolkata')::date) between from_date and to_date),0)::bigint commission_paise,
      coalesce((select sum(pl.provider_net_paise) from production_ledger pl where pl.provider_id=p.id and coalesce(pl.service_date,(pl.created_at at time zone 'Asia/Kolkata')::date) between from_date and to_date),0)::bigint provider_net_paise
    from public.providers p left join public.customer_subscriptions s on s.provider_id=p.id group by p.id,p.display_name,p.status)
  select jsonb_build_object('generated_at',now(),'timezone','Asia/Kolkata','production_only',true,'from_date',from_date,'to_date',to_date,
    'summary',jsonb_build_object(
      'active_subscribers',(select count(*) from public.customer_subscriptions where status in ('ACTIVE','PAUSED','CANCEL_PENDING')),
      'paused_subscribers',(select count(*) from public.customer_subscriptions where status='PAUSED'),
      'active_providers',(select count(*) from public.providers where status='ACTIVE'),'pending_providers',(select count(*) from public.providers where status='PENDING_APPROVAL'),
      'total_providers',(select count(*) from public.providers),'serviceable_pincodes',(select count(distinct pincode) from public.provider_service_areas where status='APPROVED'),
      'pending_changes',(select count(*) from public.provider_change_requests where status='PENDING'),
      'today_collected_paise',(select coalesce(captured_paise,0) from history where report_date=today_ist),
      'today_platform_fee_paise',(select coalesce(platform_fee_paise,0) from history where report_date=today_ist),
      'today_commission_paise',(select coalesce(commission_paise,0) from history where report_date=today_ist),
      'today_zomeal_revenue_paise',(select coalesce(zomeal_revenue_paise,0) from history where report_date=today_ist),
      'period_collected_paise',(select coalesce(sum(captured_paise),0) from history),'period_platform_fee_paise',(select coalesce(sum(platform_fee_paise),0) from history),
      'period_commission_paise',(select coalesce(sum(commission_paise),0) from history),'period_zomeal_revenue_paise',(select coalesce(sum(zomeal_revenue_paise),0) from history),
      'period_provider_net_paise',(select coalesce(sum(provider_net_paise),0) from history),'period_refunded_order_value_paise',(select coalesce(sum(refunded_order_value_paise),0) from history)),
    'history',(select coalesce(jsonb_agg(to_jsonb(h) order by report_date),'[]'::jsonb) from history h),
    'providers',(select coalesce(jsonb_agg(to_jsonb(pp) order by active_subscribers desc,captured_paise desc,display_name),'[]'::jsonb) from provider_performance pp)) into result;
  return result;
end; $$;
revoke all on function public.admin_business_dashboard(date,date) from public;
grant execute on function public.admin_business_dashboard(date,date) to authenticated;
comment on function public.admin_business_dashboard(date,date) is 'Admin-only production subscriber, provider, collections and revenue reporting in Asia/Kolkata.';
