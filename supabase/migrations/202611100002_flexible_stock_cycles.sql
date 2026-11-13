-- Physical stock cycles, independent of monthly valuation and financial entries.
create table public.provider_stock_cycles (
 id uuid primary key,provider_id uuid not null references public.providers(id),
 opened_at timestamptz not null,closed_at timestamptz,
 opening_rows jsonb not null,closing_rows jsonb,revision integer not null default 1,
 created_at timestamptz not null default now(),updated_at timestamptz not null default now(),
 created_by uuid not null, last_request uuid not null,last_payload jsonb not null,
 check(closed_at is null or closed_at>=opened_at)
);
create unique index provider_stock_one_open on public.provider_stock_cycles(provider_id) where closed_at is null;
alter table public.provider_stock_cycles enable row level security;
revoke all on public.provider_stock_cycles from public,anon,authenticated;

create function public.provider_stock_cycles_book() returns jsonb
language plpgsql stable security definer set search_path=public as $$
declare p uuid:=kitchen_book_provider();begin
 return jsonb_build_object('cycles',coalesce((select jsonb_agg(to_jsonb(c)-'last_payload'-'created_by'-'last_request' order by c.opened_at desc)
 from (select * from provider_stock_cycles where provider_id=p order by (closed_at is null) desc,opened_at desc limit 60) c),'[]'::jsonb));
end $$;

create function public.provider_stock_cycle_save(payload jsonb) returns jsonb
language plpgsql security definer set search_path=public as $$
declare p uuid:=kitchen_book_provider(); k uuid:=(payload->>'id')::uuid; req uuid:=(payload->>'request_id')::uuid;
 action text:=payload->>'action'; stamp timestamptz; r jsonb; original jsonb; normalized jsonb:='[]';
 q numeric; price numeric; denom numeric; amount numeric; existing provider_stock_cycles; stored provider_stock_cycles;
begin
 if k is null or req is null or action is null or action not in('OPEN','CLOSE') then raise exception 'Invalid stock request'; end if;
 if coalesce(payload->>'counted_at','') !~ '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$' then raise exception 'Enter actual count date and time in IST'; end if;
 stamp:=(payload->>'counted_at')::timestamp at time zone 'Asia/Kolkata';
 if stamp<'2020-01-01'::timestamptz or stamp>now() then raise exception 'Count time must be in the past or now'; end if;
 if jsonb_typeof(payload->'rows') is distinct from 'array' then raise exception 'Add stock items'; end if;
 if jsonb_array_length(payload->'rows') not between 1 and 100 then raise exception 'Use 1 to 100 stock items'; end if;
 if exists(select 1 from jsonb_array_elements(payload->'rows') x group by lower(regexp_replace(trim(x->>'item'),'\s+',' ','g')) having count(*)>1)
 then raise exception 'Use one row per ingredient'; end if;
 perform 1 from providers where id=p for update;
 select * into existing from provider_stock_cycles where id=k for update;
 if found and existing.provider_id<>p then raise exception 'Stock cycle unavailable'; end if;
 if existing.id is not null and existing.last_request=req then
  if existing.last_payload<>payload then raise exception 'Retry payload changed'; end if;
  return jsonb_build_object('cycle',to_jsonb(existing)-'last_payload'-'created_by'-'last_request','already_saved',true);
 end if;
 if action='OPEN' then
  if existing.id is not null then raise exception 'Cycle already exists. Refresh'; end if;
  if exists(select 1 from provider_stock_cycles where provider_id=p and closed_at is null) then raise exception 'Close your active stock cycle before opening another'; end if;
 else
  if existing.id is null or existing.closed_at is not null then raise exception 'Open stock cycle unavailable. Refresh'; end if;
  if existing.revision is distinct from (payload->>'revision')::integer then raise exception 'Stock cycle changed. Refresh'; end if;
  if stamp<existing.opened_at then raise exception 'Closing count cannot be earlier than opening'; end if;
 end if;
 for r in select value from jsonb_array_elements(payload->'rows') loop
  q:=(r->>'quantity')::numeric; amount:=null;
  if coalesce(length(trim(r->>'item')),0) not between 1 and 100 or coalesce(r->>'unit','') not in('kg','g','litre','ml','piece')
   or q is null or q not between 0 and 100000 or q<>round(q,3) then raise exception 'Check item, base unit and quantity'; end if;
  if action='OPEN' then
   amount:=nullif(r->>'amount','')::numeric;
   if amount is not null and (amount not between 0 and 1000000 or amount<>round(amount,2) or (q=0 and amount<>0)) then raise exception 'Check opening item total cost'; end if;
  else
   select value into original from jsonb_array_elements(existing.opening_rows) x
    where lower(regexp_replace(trim(x->>'item'),'\s+',' ','g'))=lower(regexp_replace(trim(r->>'item'),'\s+',' ','g'));
   if q=0 then amount:=0;
   elsif original is not null and nullif(original->>'amount','') is not null and (original->>'quantity')::numeric>0
    and (case when r->>'unit' in('kg','g') then 'mass' when r->>'unit' in('litre','ml') then 'volume' else 'count' end)=
        (case when original->>'unit' in('kg','g') then 'mass' when original->>'unit' in('litre','ml') then 'volume' else 'count' end)
   then
    denom:=(original->>'quantity')::numeric*(case when original->>'unit' in('kg','litre') then 1000 else 1 end);
    amount:=round((original->>'amount')::numeric*q*(case when r->>'unit' in('kg','litre') then 1000 else 1 end)/denom,2);
   end if;
  end if;
  normalized:=normalized||jsonb_build_array(jsonb_build_object('item',trim(r->>'item'),'quantity',q,'unit',r->>'unit','amount',amount));
 end loop;
 if action='OPEN' then
  insert into provider_stock_cycles(id,provider_id,opened_at,opening_rows,created_by,last_request,last_payload)
   values(k,p,stamp,normalized,auth.uid(),req,payload) returning * into stored;
 else
  update provider_stock_cycles set closed_at=stamp,closing_rows=normalized,revision=revision+1,updated_at=now(),last_request=req,last_payload=payload
   where id=k and provider_id=p returning * into stored;
 end if;
 insert into audit_logs(actor_id,action,entity_type,entity_id,after_data)
 values(auth.uid(),'STOCK_CYCLE_'||action,'provider_stock_cycles',k::text,jsonb_build_object('counted_at',stamp,'rows',normalized));
 return jsonb_build_object('cycle',to_jsonb(stored)-'last_payload'-'created_by'-'last_request');
end $$;
revoke all on function public.provider_stock_cycles_book(),public.provider_stock_cycle_save(jsonb) from public,anon;
grant execute on function public.provider_stock_cycles_book(),public.provider_stock_cycle_save(jsonb) to authenticated;
notify pgrst,'reload schema';
