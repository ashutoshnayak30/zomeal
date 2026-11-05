-- Private kitchen bookkeeping. It never changes meal pricing, wallets or payouts.
create table public.provider_kitchen_entries(
 id uuid primary key,provider_id uuid not null references providers(id),
 entry_date date not null,kind text not null check(kind in('EXPENSE','SALE')),
 category text not null check(category in('Grains','Vegetables','Meat & eggs','Oil & spices','Dairy','Gas & fuel','Packaging','Delivery','Staff','Rent','Utilities','Cleaning','Other','Other sales','Equipment / owner / loan')),
 item text not null check(length(trim(item)) between 1 and 100),amount_paise bigint not null check(amount_paise between 1 and 100000000),
 cost_type text not null check(cost_type in('VARIABLE','FIXED','EXCLUDED')),
 quantity numeric(12,3),unit text not null default '' check(length(unit)<=20),
 meal_count integer not null default 0 check(meal_count between 0 and 100000),
 paid boolean not null default true,supplier text not null default '' check(length(supplier)<=100),
 note text not null default '' check(length(note)<=500),
 recurring boolean not null default false,source_template uuid references provider_kitchen_entries(id),
 revision integer not null default 1,voided_at timestamptz,
 created_by uuid not null references profiles(id),request_payload jsonb not null default '{}',created_at timestamptz not null default now(),updated_at timestamptz not null default now(),
 check(quantity is null or quantity>0),check(kind='SALE' or meal_count=0),
 check(not recurring or (kind='EXPENSE' and source_template is null)),
 unique(provider_id,source_template,entry_date)
);
create index kitchen_entries_month on public.provider_kitchen_entries(provider_id,entry_date) where voided_at is null;
alter table public.provider_kitchen_entries enable row level security;
revoke all on public.provider_kitchen_entries from public,anon,authenticated;

create function public.kitchen_book_provider() returns uuid language plpgsql stable security definer set search_path=public as $$
declare result uuid; begin
 select provider_id into result from provider_members where user_id=auth.uid() and is_active and upper(member_role) in('OWNER','MANAGER') order by created_at desc limit 1;
 if result is null then raise exception 'Kitchen owner or manager access required'; end if;
 return result;
end $$;
revoke all on function public.kitchen_book_provider() from public,anon,authenticated;

create function public.provider_kitchen_save(payload jsonb) returns jsonb language plpgsql security definer set search_path=public as $$
declare p uuid:=kitchen_book_provider(); entry provider_kitchen_entries; key uuid:=(payload->>'id')::uuid; revision integer:=coalesce((payload->>'revision')::integer,0); d date:=(payload->>'date')::date;
begin
 if key is null or d is null or d<'2020-01-01' or d>(now() at time zone 'Asia/Kolkata')::date then raise exception 'Use a valid date up to today'; end if;
 perform 1 from providers where id=p for update;
 select * into entry from provider_kitchen_entries where id=key for update;
 if entry.id is not null and (entry.provider_id<>p or entry.voided_at is not null) then raise exception 'Entry not available'; end if;
 -- Same client UUID safely handles retries. Editing requires the loaded revision.
 if entry.id is not null and entry.request_payload=payload then return jsonb_build_object('saved',true,'id',key,'already_saved',true); end if;
 if entry.id is not null and entry.revision<>revision then raise exception 'Entry changed. Refresh before editing'; end if;
 insert into provider_kitchen_entries(id,provider_id,entry_date,kind,category,item,amount_paise,cost_type,quantity,unit,meal_count,paid,supplier,note,recurring,created_by,request_payload)
 values(key,p,d,payload->>'kind',payload->>'category',trim(payload->>'item'),(payload->>'amount_paise')::bigint,
 case when payload->>'category'='Equipment / owner / loan' or payload->>'kind'='SALE' then 'EXCLUDED' else payload->>'cost_type' end,
 nullif(payload->>'quantity','')::numeric,coalesce(payload->>'unit',''),coalesce((payload->>'meal_count')::integer,0),coalesce((payload->>'paid')::boolean,true),
 coalesce(payload->>'supplier',''),coalesce(payload->>'note',''),coalesce((payload->>'recurring')::boolean,false),auth.uid(),payload)
 on conflict(id) do update set entry_date=excluded.entry_date,kind=excluded.kind,category=excluded.category,item=excluded.item,amount_paise=excluded.amount_paise,
 cost_type=excluded.cost_type,quantity=excluded.quantity,unit=excluded.unit,meal_count=excluded.meal_count,paid=excluded.paid,supplier=excluded.supplier,note=excluded.note,
 recurring=excluded.recurring,request_payload=excluded.request_payload,revision=provider_kitchen_entries.revision+1,updated_at=now();
 insert into audit_logs(actor_id,action,entity_type,entity_id,after_data) values(auth.uid(),'KITCHEN_ENTRY_SAVED','provider_kitchen_entries',key::text,payload);
 return jsonb_build_object('saved',true,'id',key);
end $$;

create function public.provider_kitchen_void(target_id uuid,target_revision integer) returns jsonb language plpgsql security definer set search_path=public as $$
declare p uuid:=kitchen_book_provider(); begin
 update provider_kitchen_entries set voided_at=now(),revision=revision+1,updated_at=now() where id=target_id and provider_id=p and revision=target_revision and voided_at is null;
 if not found then raise exception 'Entry changed or no longer available. Refresh'; end if;
 insert into audit_logs(actor_id,action,entity_type,entity_id) values(auth.uid(),'KITCHEN_ENTRY_VOIDED','provider_kitchen_entries',target_id::text);
 return jsonb_build_object('saved',true);
end $$;

-- Reviewable monthly copies; no silent recurring debits or estimated bills.
create function public.provider_kitchen_repeat(target_id uuid,target_month date) returns jsonb language plpgsql security definer set search_path=public as $$
declare p uuid:=kitchen_book_provider(); source provider_kitchen_entries; month date:=date_trunc('month',target_month)::date; key uuid;
begin
 perform 1 from providers where id=p for update;
 select * into source from provider_kitchen_entries where id=target_id and provider_id=p and recurring and voided_at is null;
 if source.id is null or month<=date_trunc('month',source.entry_date)::date or month>date_trunc('month',now() at time zone 'Asia/Kolkata')::date then raise exception 'Recurring expense not available for this month'; end if;
 insert into provider_kitchen_entries(id,provider_id,entry_date,kind,category,item,amount_paise,cost_type,quantity,unit,paid,supplier,note,source_template,created_by)
 values(gen_random_uuid(),p,month,'EXPENSE',source.category,source.item,source.amount_paise,source.cost_type,source.quantity,source.unit,false,source.supplier,'Monthly copy — confirm payment separately',source.id,auth.uid())
 on conflict(provider_id,source_template,entry_date) do nothing returning id into key;
 insert into audit_logs(actor_id,action,entity_type,entity_id) select auth.uid(),'KITCHEN_RECURRING_CONFIRMED','provider_kitchen_entries',key::text where key is not null;
 return jsonb_build_object('saved',true,'already_saved',key is null);
end $$;

create function public.provider_kitchen_month(target_month date) returns jsonb language plpgsql stable security definer set search_path=public as $$
declare p uuid:=kitchen_book_provider(); month date:=date_trunc('month',target_month)::date; result jsonb; begin
 if month is null or month<'2020-01-01' or month>date_trunc('month',now() at time zone 'Asia/Kolkata')::date then raise exception 'Choose a valid month'; end if;
 with periods as (select generate_series(month-interval '5 months',month,interval '1 month')::date m),
 totals as(select periods.m,
 coalesce((select sum(l.provider_net_paise) from provider_financial_ledger l where l.provider_id=p and l.entry_type in('MEAL_EARNING','REVERSAL') and case when l.entry_type='REVERSAL' then (l.created_at at time zone 'Asia/Kolkata')::date else l.service_date end>=periods.m and case when l.entry_type='REVERSAL' then (l.created_at at time zone 'Asia/Kolkata')::date else l.service_date end<periods.m+interval '1 month'),0) zomeal_paise,
 coalesce((select sum(amount_paise) from provider_kitchen_entries e where e.provider_id=p and e.voided_at is null and e.entry_date>=periods.m and e.entry_date<periods.m+interval '1 month' and kind='SALE'),0) other_sales_paise,
 coalesce((select sum(amount_paise) from provider_kitchen_entries e where e.provider_id=p and e.voided_at is null and e.entry_date>=periods.m and e.entry_date<periods.m+interval '1 month' and kind='EXPENSE' and cost_type<>'EXCLUDED'),0) expenses_paise
 from periods)
 select jsonb_build_object('month',month,'trend',jsonb_agg(to_jsonb(t)||jsonb_build_object('profit_paise',zomeal_paise+other_sales_paise-expenses_paise) order by m)) into result from totals t;
 return result||jsonb_build_object(
 'entries',coalesce((select jsonb_agg(to_jsonb(e) order by entry_date desc,created_at desc) from provider_kitchen_entries e where e.provider_id=p and e.entry_date>=month and e.entry_date<month+interval '1 month' and e.voided_at is null),'[]'::jsonb),
 'recurring',coalesce((select jsonb_agg(to_jsonb(e)||jsonb_build_object('posted',exists(select 1 from provider_kitchen_entries child where child.provider_id=p and child.source_template=e.id and child.entry_date=month))) from provider_kitchen_entries e where e.provider_id=p and e.recurring and e.voided_at is null and e.entry_date<month),'[]'::jsonb),
 'delivered_meals',(select count(*) from subscription_meals where provider_id=p and status='DELIVERED' and service_date>=month and service_date<month+interval '1 month'),
 'payouts_received_paise',coalesce((select -sum(provider_net_paise) from provider_financial_ledger where provider_id=p and entry_type='PAYOUT' and (created_at at time zone 'Asia/Kolkata')::date>=month and (created_at at time zone 'Asia/Kolkata')::date<month+interval '1 month'),0));
end $$;
revoke all on function public.provider_kitchen_save(jsonb),public.provider_kitchen_void(uuid,integer),public.provider_kitchen_repeat(uuid,date),public.provider_kitchen_month(date) from public,anon;
grant execute on function public.provider_kitchen_save(jsonb),public.provider_kitchen_void(uuid,integer),public.provider_kitchen_repeat(uuid,date),public.provider_kitchen_month(date) to authenticated;
