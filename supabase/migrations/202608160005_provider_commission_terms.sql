-- Provider-specific negotiated commission terms. Default is 14%; advances always carry 0% commission.

create table public.provider_commission_terms (
  id uuid primary key default gen_random_uuid(), provider_id uuid not null references public.providers(id),
  commission_basis_points integer not null default 1400 check(commission_basis_points between 0 and 10000),
  status text not null default 'ACTIVE' check(status in ('ACTIVE','SUPERSEDED','CANCELLED')),
  effective_from timestamptz not null default now(), effective_until timestamptz,
  negotiation_note text, agreed_by_provider boolean not null default false,
  created_by uuid references public.profiles(id), created_at timestamptz not null default now()
);
create unique index provider_one_active_commission on public.provider_commission_terms(provider_id) where status='ACTIVE';
create index provider_commission_history_idx on public.provider_commission_terms(provider_id,effective_from desc);
alter table public.provider_commission_terms enable row level security;
create policy provider_commission_member_read on public.provider_commission_terms for select to authenticated using(public.is_provider_member(provider_id));
create policy provider_commission_admin_read on public.provider_commission_terms for select to authenticated using(public.has_role('ADMIN') or public.has_role('FINANCE') or public.has_role('OPERATIONS'));
insert into public.provider_commission_terms(provider_id,commission_basis_points,status,negotiation_note,agreed_by_provider)
select id,1400,'ACTIVE','Default Zomeal commission',false from public.providers on conflict do nothing;

create or replace function public.current_provider_commission_basis_points(target_provider uuid,target_time timestamptz default now())
returns integer language sql stable security definer set search_path=public as $$
  select coalesce((select commission_basis_points from public.provider_commission_terms where provider_id=target_provider and status='ACTIVE'
    and effective_from<=target_time and (effective_until is null or effective_until>target_time) order by effective_from desc limit 1),1400);
$$;

create or replace function public.apply_provider_commission_to_subscription() returns trigger language plpgsql security definer set search_path=public as $$
begin new.commission_basis_points:=public.current_provider_commission_basis_points(new.provider_id,coalesce(new.activated_at,now())); return new; end; $$;
drop trigger if exists customer_subscription_provider_commission on public.customer_subscriptions;
create trigger customer_subscription_provider_commission before insert on public.customer_subscriptions for each row execute function public.apply_provider_commission_to_subscription();

create or replace function public.admin_set_provider_commission(target_provider uuid,target_basis_points integer,target_note text,target_agreed boolean default true)
returns jsonb language plpgsql security definer set search_path=public as $$
declare term_id uuid;
begin
  if not (public.has_role('ADMIN') or public.has_role('FINANCE')) then raise exception 'Finance access is required'; end if;
  if target_basis_points<0 or target_basis_points>10000 then raise exception 'Commission percentage must be between zero and one hundred'; end if;
  if nullif(trim(target_note),'') is null then raise exception 'Negotiation or approval note is required'; end if;
  update public.provider_commission_terms set status='SUPERSEDED',effective_until=now() where provider_id=target_provider and status='ACTIVE';
  insert into public.provider_commission_terms(provider_id,commission_basis_points,status,negotiation_note,agreed_by_provider,created_by)
    values(target_provider,target_basis_points,'ACTIVE',trim(target_note),target_agreed,auth.uid()) returning id into term_id;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data) values(auth.uid(),'PROVIDER_COMMISSION_UPDATED','provider',target_provider::text,jsonb_build_object('commission_basis_points',target_basis_points,'agreed_by_provider',target_agreed,'note',target_note));
  return jsonb_build_object('term_id',term_id,'provider_id',target_provider,'commission_basis_points',target_basis_points,'rate_percent',target_basis_points/100.0,'agreed_by_provider',target_agreed);
end; $$;

create or replace function public.provider_commission_summary(target_provider uuid default null) returns jsonb language plpgsql stable security definer set search_path=public as $$
declare resolved uuid; result jsonb;
begin
  if target_provider is null then select provider_id into resolved from public.provider_members where user_id=auth.uid() and is_active order by created_at desc limit 1;
  else resolved:=target_provider; if not (public.has_role('ADMIN') or public.has_role('FINANCE') or public.has_role('OPERATIONS') or public.is_provider_member(resolved)) then raise exception 'Access denied'; end if; end if;
  if resolved is null then raise exception 'Provider account was not found'; end if;
  select jsonb_build_object('provider_id',p.id,'provider_name',p.display_name,'commission_basis_points',coalesce(t.commission_basis_points,1400),
    'rate_percent',coalesce(t.commission_basis_points,1400)/100.0,'agreed_by_provider',coalesce(t.agreed_by_provider,false),
    'negotiation_note',coalesce(t.negotiation_note,'Default Zomeal commission'),'effective_from',t.effective_from)
    into result from public.providers p left join public.provider_commission_terms t on t.provider_id=p.id and t.status='ACTIVE' where p.id=resolved;
  return result;
end; $$;

create or replace function public.admin_create_finance_test_cycle(target_provider uuid,target_gross_paise bigint default 100000)
returns jsonb language plpgsql security definer set search_path=public as $$
declare commission bigint; net bigint; ledger_id uuid; payout_id uuid; rate_bps integer;
begin
  if not public.has_role('ADMIN') then raise exception 'Administrator access is required'; end if;
  if not exists(select 1 from public.providers where id=target_provider and status='ACTIVE') then raise exception 'Choose an active provider'; end if;
  if target_gross_paise<10000 or target_gross_paise>10000000 then raise exception 'Test gross must be between ₹100 and ₹100,000'; end if;
  rate_bps:=public.current_provider_commission_basis_points(target_provider); commission:=round(target_gross_paise::numeric*rate_bps/10000); net:=target_gross_paise-commission;
  insert into public.provider_financial_ledger(provider_id,entry_type,meal_slot,service_date,gross_paise,commission_basis_points,commission_paise,provider_net_paise,available_at,created_by,metadata)
    values(target_provider,'TEST_EARNING','LUNCH',current_date,target_gross_paise,rate_bps,commission,net,now(),auth.uid(),jsonb_build_object('is_test',true,'commission_basis_points',rate_bps)) returning id into ledger_id;
  insert into public.provider_payout_requests(provider_id,amount_paise,preferred_method,provider_note,requested_by) values(target_provider,net,'UPI','ADMIN TEST — do not pay',auth.uid()) returning id into payout_id;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data) values(auth.uid(),'FINANCE_TEST_CYCLE_CREATED','provider',target_provider::text,jsonb_build_object('ledger_id',ledger_id,'payout_request_id',payout_id,'commission_basis_points',rate_bps,'is_test',true));
  return jsonb_build_object('ledger_id',ledger_id,'payout_request_id',payout_id,'net_paise',net,'commission_basis_points',rate_bps,'is_test',true);
end; $$;

alter function public.provider_earnings_summary() rename to provider_earnings_summary_finance_v1;
create function public.provider_earnings_summary() returns jsonb language sql stable security definer set search_path=public as $$
  select public.provider_earnings_summary_finance_v1() || jsonb_build_object(
    'commission_basis_points',(public.provider_commission_summary(null)->>'commission_basis_points')::integer,
    'commission_rate_percent',(public.provider_commission_summary(null)->>'rate_percent')::numeric,
    'commission_agreed',(public.provider_commission_summary(null)->>'agreed_by_provider')::boolean
  );
$$;

alter function public.provider_daily_dashboard(public.meal_slot,date) rename to provider_daily_dashboard_commission_v1;
create function public.provider_daily_dashboard(target_slot public.meal_slot,target_date date default null) returns jsonb language plpgsql stable security definer set search_path=public as $$
declare result jsonb; provider_id uuid; rate_bps integer; gross bigint;
begin
  result:=public.provider_daily_dashboard_commission_v1(target_slot,target_date); provider_id:=(result->>'provider_id')::uuid;
  rate_bps:=public.current_provider_commission_basis_points(provider_id); gross:=coalesce((result->'metrics'->>'gross_paise')::bigint,0);
  return jsonb_set(result,'{commission}',jsonb_build_object('rate_percent',rate_bps/100.0,'basis_points',rate_bps,'gross_paise',gross,'commission_paise',round(gross::numeric*rate_bps/10000),'provider_net_paise',gross-round(gross::numeric*rate_bps/10000)));
end; $$;

revoke all on function public.admin_set_provider_commission(uuid,integer,text,boolean),public.provider_commission_summary(uuid) from public;
grant execute on function public.admin_set_provider_commission(uuid,integer,text,boolean),public.provider_commission_summary(uuid) to authenticated;
grant execute on function public.provider_earnings_summary(),public.provider_daily_dashboard(public.meal_slot,date) to authenticated;
