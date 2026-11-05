-- Item-level inventory observations. These never post expenses or change profit.
create table public.provider_inventory_observations (
 id uuid primary key, provider_id uuid not null references public.providers(id),
 report_date date not null, meal_slot text not null check(meal_slot in ('LUNCH','DINNER')),
 rows jsonb not null check(jsonb_typeof(rows)='array'),
 created_by uuid not null references public.profiles(id), created_at timestamptz not null default now()
);
create index provider_inventory_report_date on public.provider_inventory_observations(provider_id,report_date desc,created_at desc);
alter table public.provider_inventory_observations enable row level security;
revoke all on public.provider_inventory_observations from public,anon,authenticated;

create function public.provider_inventory_save(payload jsonb) returns jsonb
language plpgsql security definer set search_path=public as $$
declare p uuid:=kitchen_book_provider(); k uuid:=(payload->>'id')::uuid;
 d date:=(payload->>'date')::date; s text:=payload->>'slot'; r jsonb; old public.provider_inventory_observations;
begin
 if k is null or d is null or d<'2020-01-01' or d>(now() at time zone 'Asia/Kolkata')::date or s is null or s not in('LUNCH','DINNER') then raise exception 'Choose a valid stock date and meal'; end if;
 if jsonb_typeof(payload->'rows') is distinct from 'array' then raise exception 'Supply item rows'; end if;
 if jsonb_array_length(payload->'rows') not between 1 and 100 then raise exception 'Use 1 to 100 items'; end if;
 for r in select value from jsonb_array_elements(payload->'rows') loop
  if coalesce(length(trim(r->>'item')),0) not between 1 and 100 or coalesce(r->>'unit','') not in('kg','g','litre','ml','piece')
   or coalesce((r->>'quantity')::numeric,-1) not between 0 and 100000
   or (r->>'quantity')::numeric <> round((r->>'quantity')::numeric,3)
   or (nullif(r->>'per_meal','') is not null and ((r->>'per_meal')::numeric not between 0.000001 and 1000))
   then raise exception 'Check item, base unit, remaining quantity and optional per-meal estimate'; end if;
 end loop;
 if exists(select 1 from jsonb_array_elements(payload->'rows') a group by lower(trim(a->>'item')) having count(*)>1) then raise exception 'Use one row per ingredient'; end if;
 perform 1 from providers where id=p for update;
 select * into old from provider_inventory_observations where id=k;
 if found then
  if old.provider_id=p and old.report_date=d and old.meal_slot=s and old.rows=payload->'rows' then return jsonb_build_object('saved',true,'already_saved',true); end if;
  raise exception 'Report ID already used. Refresh before retrying';
 end if;
 insert into provider_inventory_observations(id,provider_id,report_date,meal_slot,rows,created_by) values(k,p,d,s,payload->'rows',auth.uid());
 insert into audit_logs(actor_id,action,entity_type,entity_id,after_data) values(auth.uid(),'INVENTORY_OBSERVED','provider_inventory_observations',k::text,payload);
 return jsonb_build_object('saved',true);
end $$;

create function public.provider_inventory_review(review_days integer default 7) returns jsonb
language plpgsql stable security definer set search_path=public as $$
declare p uuid:=kitchen_book_provider(); today date:=(now() at time zone 'Asia/Kolkata')::date; last_date date; items jsonb; daily jsonb;
begin
 if review_days not in(7,15) or review_days is null then raise exception 'Choose 7 or 15 days'; end if;
 select max(report_date) into last_date from provider_inventory_observations where provider_id=p;
 -- Latest observation PER ITEM: omitted ingredients retain their own old date.
 with latest as (
  select distinct on(lower(trim(r->>'item'))) r, o.report_date,o.meal_slot
  from provider_inventory_observations o cross join lateral jsonb_array_elements(o.rows) r
  where o.provider_id=p
  order by lower(trim(r->>'item')),o.report_date desc,case o.meal_slot when 'DINNER' then 2 else 1 end desc,o.created_at desc
 ), facts as (
  select l.*,
   (select count(*) from subscription_meals m where m.provider_id=p and m.status='DELIVERED' and m.service_date<=today
    and (m.service_date>l.report_date or (m.service_date=l.report_date and l.meal_slot='LUNCH' and m.meal_slot='DINNER'))) delivered,
   (select coalesce(sum(e.quantity),0) from provider_kitchen_entries e where e.provider_id=p and e.voided_at is null and e.kind='EXPENSE'
    and lower(trim(e.item))=lower(trim(l.r->>'item')) and e.unit=l.r->>'unit' and e.entry_date>l.report_date and e.entry_date<=today) purchased,
   exists(select 1 from provider_kitchen_entries e where e.provider_id=p and e.voided_at is null and e.kind='EXPENSE'
    and lower(trim(e.item))=lower(trim(l.r->>'item')) and e.entry_date>l.report_date and e.entry_date<=today and (e.quantity is null or e.unit<>l.r->>'unit')) incompatible
  from latest l
 ) select coalesce(jsonb_agg(jsonb_build_object('item',r->>'item','unit',r->>'unit','quantity',(r->>'quantity')::numeric,
  'per_meal',nullif(r->>'per_meal','')::numeric,'last_date',report_date,'slot',meal_slot,'days_since',today-report_date,
  'delivered_since',delivered,'recorded_purchases',purchased,'needs_unit_review',incompatible,
  'estimated_used',case when not incompatible then delivered*nullif(r->>'per_meal','')::numeric end,
  'estimated_remaining',case when not incompatible then (r->>'quantity')::numeric+purchased-delivered*nullif(r->>'per_meal','')::numeric end)
  order by r->>'item'),'[]'::jsonb) into items from facts;
 select coalesce(jsonb_agg(jsonb_build_object('date',d::date,
  'delivered',(select count(*) from subscription_meals where provider_id=p and service_date=d::date and status='DELIVERED'),
  'reported',exists(select 1 from provider_inventory_observations where provider_id=p and report_date=d::date)) order by d),'[]'::jsonb)
 into daily from generate_series(today-(review_days-1),today,interval '1 day') d;
 return jsonb_build_object('last_report',last_date,'days_since',today-last_date,'items',items,'days',daily,
  'notice','Estimates use delivered meals, not measured cooking consumption. Staff meals, waste, unrecorded purchases and same-day purchase timing are not included. Matching uses exact item names and base units. No financial entries are created.');
end $$;
revoke all on function public.provider_inventory_save(jsonb),public.provider_inventory_review(integer) from public,anon;
grant execute on function public.provider_inventory_save(jsonb),public.provider_inventory_review(integer) to authenticated;
