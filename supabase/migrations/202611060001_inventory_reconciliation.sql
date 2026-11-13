-- Read-only physical-count reconciliation. Never books usage, waste or expenses.
create or replace function public.provider_inventory_reconcile(payload jsonb) returns jsonb
language plpgsql stable security definer set search_path=public as $$
declare
 p uuid:=kitchen_book_provider(); d date:=(payload->>'date')::date; s text:=payload->>'slot';
 row_data jsonb; prior record; purchases jsonb; result jsonb:='[]'::jsonb;
 u text; family text; factor numeric; opening numeric; known numeric; review_count integer;
begin
 if d is null or d<'2020-01-01' or d>(now() at time zone 'Asia/Kolkata')::date
 or s is null or s not in('LUNCH','DINNER') then raise exception 'Choose a valid count date and meal'; end if;
 if jsonb_typeof(payload->'rows') is distinct from 'array' then raise exception 'Supply stock rows'; end if;
 if jsonb_array_length(payload->'rows') not between 1 and 100 then raise exception 'Use 1 to 100 items'; end if;
 if exists(select 1 from jsonb_array_elements(payload->'rows') x group by lower(trim(x->>'item')) having count(*)>1)
 then raise exception 'Use one row per ingredient'; end if;
 for row_data in select value from jsonb_array_elements(payload->'rows') loop
  u:=row_data->>'unit';
  if coalesce(length(trim(row_data->>'item')),0) not between 1 and 100
   or coalesce(u,'') not in('kg','g','litre','ml','piece')
   or coalesce((row_data->>'quantity')::numeric,-1) not between 0 and 100000
   or (row_data->>'quantity')::numeric<>round((row_data->>'quantity')::numeric,3)
   then raise exception 'Check ingredient, quantity and unit'; end if;
  family:=case when u in('kg','g') then 'mass' when u in('litre','ml') then 'volume' else 'count' end;
  factor:=case when u in('kg','litre') then 1000 else 1 end;
  -- Same-slot observations are corrections, not a new consumption interval.
  select o.report_date,o.meal_slot,r into prior from provider_inventory_observations o
   cross join lateral jsonb_array_elements(o.rows) r
   where o.provider_id=p and lower(trim(r->>'item'))=lower(trim(row_data->>'item'))
   and (o.report_date<d or (o.report_date=d and o.meal_slot='LUNCH' and s='DINNER'))
   order by o.report_date desc,case o.meal_slot when 'DINNER' then 2 else 1 end desc,o.created_at desc limit 1;
  if not found then
   result:=result||jsonb_build_array(jsonb_build_object('item',row_data->>'item','unit',u,'closing',row_data->'quantity',
    'opening',null,'purchases','[]'::jsonb,'notice','No earlier physical count. This report establishes a baseline.'));
   continue;
  end if;
  opening:=case when family=case when prior.r->>'unit' in('kg','g') then 'mass'
    when prior.r->>'unit' in('litre','ml') then 'volume' else 'count' end
   then (prior.r->>'quantity')::numeric*(case when prior.r->>'unit' in('kg','litre') then 1000 else 1 end)/factor end;
  -- Ledger dates have no before/after-meal timing. Boundary-day purchases require review.
  with entries as (
   select e.*,case when e.quantity is not null and e.quantity>=0 and
    family=case when e.unit in('kg','g') then 'mass' when e.unit in('litre','ml') then 'volume'
     when e.unit='piece' then 'count' else 'unknown' end
    then e.quantity*(case when e.unit in('kg','litre') then 1000 else 1 end)/factor end converted
   from provider_kitchen_entries e where e.provider_id=p and e.voided_at is null and e.kind='EXPENSE'
    and lower(trim(e.item))=lower(trim(row_data->>'item')) and e.entry_date between prior.report_date and d
  ) select coalesce(jsonb_agg(jsonb_build_object('date',entry_date,'item',item,'quantity',quantity,'unit',unit,
    'converted',converted,'needs_review',converted is null or entry_date in(prior.report_date,d)) order by entry_date,id),'[]'::jsonb),
   coalesce(sum(converted) filter(where entry_date>prior.report_date and entry_date<d),0),
   count(*) filter(where converted is null or entry_date in(prior.report_date,d))
   into purchases,known,review_count from entries;
  result:=result||jsonb_build_array(jsonb_build_object('item',row_data->>'item','unit',u,'closing',row_data->'quantity',
   'opening',opening,'opening_date',prior.report_date,'opening_slot',prior.meal_slot,'days',d-prior.report_date,
   'known_purchases',known,'purchase_review_count',review_count,'purchases',purchases,
   'notice',case when opening is null then 'Earlier count uses an incompatible unit. No conversion guessed.' else
    'Confirm purchases within the exact count interval, including missed purchases. Stock reduction is not proof of cooking usage or waste.' end));
 end loop;
 return jsonb_build_object('date',d,'slot',s,'items',result,'read_only',true);
end $$;
revoke all on function public.provider_inventory_reconcile(jsonb) from public,anon;
grant execute on function public.provider_inventory_reconcile(jsonb) to authenticated;
notify pgrst,'reload schema';
