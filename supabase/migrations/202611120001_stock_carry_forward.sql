-- Read-only references. Purchase dates cannot establish intraday ordering.
create or replace function public.provider_stock_reference() returns jsonb
language plpgsql stable security definer set search_path=public as $$
declare p uuid:=kitchen_book_provider(); c provider_stock_cycles; stamp timestamptz;
begin
 select * into c from provider_stock_cycles where provider_id=p
 order by coalesce(closed_at,opened_at) desc,updated_at desc limit 1;
 if c.id is null then return jsonb_build_object('rows','[]'::jsonb,'purchases','[]'::jsonb); end if;
 stamp:=coalesce(c.closed_at,c.opened_at);
 return jsonb_build_object('cycle_id',c.id,'counted_at',stamp,
 'kind',case when c.closed_at is null then 'OPEN' else 'CLOSE' end,
 'rows',case when c.closed_at is null then c.opening_rows else c.closing_rows end,
 'purchases',coalesce((select jsonb_agg(jsonb_build_object('id',e.id,'item',e.item,
 'quantity',e.quantity,'unit',e.unit,'amount',e.amount_paise::numeric/100,'entry_date',e.entry_date)
 order by e.entry_date,e.id) from provider_kitchen_entries e
 where e.provider_id=p and e.voided_at is null and e.kind='EXPENSE'
 and e.category in('Grains','Vegetables','Meat & eggs','Oil & spices','Dairy')
 and e.source_template is null and not e.recurring
 and e.entry_date >= (stamp at time zone 'Asia/Kolkata')::date
 and e.entry_date <= (now() at time zone 'Asia/Kolkata')::date),'[]'::jsonb));
end $$;
revoke all on function public.provider_stock_reference() from public,anon;
grant execute on function public.provider_stock_reference() to authenticated;
notify pgrst,'reload schema';
