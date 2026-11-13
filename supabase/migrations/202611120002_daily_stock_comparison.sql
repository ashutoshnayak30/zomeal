-- Read-only cycle comparison; never writes consumption, waste or expenses.
create or replace function public.provider_stock_cycle_compare(cycle_id uuid) returns jsonb
language plpgsql stable security definer set search_path=public as $$
declare p uuid:=kitchen_book_provider(); c provider_stock_cycles; r jsonb; o jsonb;
 items jsonb:='[]'; purchases jsonb; known numeric; reviews integer; factor numeric;
 first_day date; last_day date; family text; opening numeric; closing numeric;
begin
 select * into c from provider_stock_cycles where id=cycle_id and provider_id=p;
 if c.id is null or c.closed_at is null then raise exception 'A saved closing count is required'; end if;
 first_day:=(c.opened_at at time zone 'Asia/Kolkata')::date;
 last_day:=(c.closed_at at time zone 'Asia/Kolkata')::date;
 for r in select value from jsonb_array_elements(c.closing_rows) loop
  select value into o from jsonb_array_elements(c.opening_rows) x
   where lower(regexp_replace(trim(x->>'item'),'\s+',' ','g'))=lower(regexp_replace(trim(r->>'item'),'\s+',' ','g'));
  family:=case when r->>'unit' in('kg','g') then 'mass' when r->>'unit' in('litre','ml') then 'volume' else 'count' end;
  factor:=case when r->>'unit' in('kg','litre') then 1000 else 1 end;
  closing:=(r->>'quantity')::numeric; opening:=null;
  if o is not null and family=(case when o->>'unit' in('kg','g') then 'mass' when o->>'unit' in('litre','ml') then 'volume' else 'count' end) then
   opening:=(o->>'quantity')::numeric*(case when o->>'unit' in('kg','litre') then 1000 else 1 end)/factor;
  end if;
  select coalesce(jsonb_agg(jsonb_build_object('date',entry_date,'quantity',quantity,'unit',unit,'needs_review',needs_review) order by entry_date,id),'[]'),
   coalesce(sum(converted) filter(where not needs_review),0),count(*) filter(where needs_review)
  into purchases,known,reviews from (
   select e.*,e.quantity*(case when e.unit in('kg','litre') then 1000 else 1 end)/factor converted,
    (e.entry_date in(first_day,last_day) or e.quantity is null or e.unit not in('kg','g','litre','ml','piece')
     or family<>(case when e.unit in('kg','g') then 'mass' when e.unit in('litre','ml') then 'volume' else 'count' end)) needs_review
   from provider_kitchen_entries e where e.provider_id=p and e.voided_at is null and e.kind='EXPENSE'
    and e.category in('Grains','Vegetables','Meat & eggs','Oil & spices','Dairy') and not e.recurring and e.source_template is null
    and e.entry_date between first_day and last_day
    and lower(regexp_replace(trim(e.item),'\s+',' ','g'))=lower(regexp_replace(trim(r->>'item'),'\s+',' ','g'))
  ) entries;
  items:=items||jsonb_build_array(jsonb_build_object('item',r->>'item','unit',r->>'unit','opening',opening,'closing',closing,
   'opening_date',first_day,'opening_slot',to_char(c.opened_at at time zone 'Asia/Kolkata','HH24:MI')||' IST',
   'days',last_day-first_day,'purchases',purchases,'known_purchases',known,'purchase_review_count',reviews,
   'notice','No compatible opening count. Reduction is unknown; names and units are not guessed.'));
 end loop;
 return jsonb_build_object('date',last_day,'slot',to_char(c.closed_at at time zone 'Asia/Kolkata','HH24:MI')||' IST','items',items,
  'unreported',coalesce((select jsonb_agg(x->>'item') from jsonb_array_elements(c.opening_rows) x where not exists(
   select 1 from jsonb_array_elements(c.closing_rows) y where lower(regexp_replace(trim(x->>'item'),'\s+',' ','g'))=lower(regexp_replace(trim(y->>'item'),'\s+',' ','g')))),'[]'::jsonb));
end $$;
revoke all on function public.provider_stock_cycle_compare(uuid) from public,anon;
grant execute on function public.provider_stock_cycle_compare(uuid) to authenticated;
notify pgrst,'reload schema';
