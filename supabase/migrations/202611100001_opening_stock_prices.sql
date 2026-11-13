-- Retain the reviewed item detail that formerly disappeared into a single total.
alter table public.provider_kitchen_reviews add column opening_rows jsonb not null default '[]'::jsonb;
alter function public.provider_kitchen_review_save(jsonb) rename to provider_kitchen_review_save_before_prices;
revoke all on function public.provider_kitchen_review_save_before_prices(jsonb) from public,anon,authenticated;

create function public.provider_kitchen_review_save(payload jsonb) returns jsonb
language plpgsql security definer set search_path=public as $$
declare p uuid:=kitchen_book_provider(); m date:=(payload->>'month')::date;
 rows jsonb:=payload->'opening_rows'; r jsonb; total numeric:=0; old_total bigint; saved jsonb;
begin
 perform 1 from providers where id=p for update;
 select opening_stock_paise into old_total from provider_kitchen_reviews where provider_id=p and month=m;
 if payload ? 'opening_rows' then
  if jsonb_typeof(rows) is distinct from 'array' then raise exception 'Opening stock must contain item rows'; end if;
  if jsonb_array_length(rows)>100 then raise exception 'Use at most 100 opening items'; end if;
  if exists(select 1 from jsonb_array_elements(rows) x group by lower(regexp_replace(trim(x->>'item'),'\s+',' ','g')) having count(*)>1)
   then raise exception 'Use one opening row per ingredient'; end if;
  for r in select value from jsonb_array_elements(rows) loop
   if coalesce(length(trim(r->>'item')),0) not between 1 and 100
    or coalesce(r->>'unit','') not in('kg','g','litre','ml','piece')
    or coalesce((r->>'quantity')::numeric,-1) not between 0 and 100000
    or (r->>'quantity')::numeric<>round((r->>'quantity')::numeric,3)
    or coalesce((r->>'amount')::numeric,-1) not between 0 and 1000000
    or (r->>'amount')::numeric<>round((r->>'amount')::numeric,2)
    or ((r->>'quantity')::numeric=0 and (r->>'amount')::numeric<>0)
    then raise exception 'Check opening item quantities, base units and full costs'; end if;
   total:=total+round((r->>'amount')::numeric*100);
  end loop;
  if jsonb_array_length(rows)>0 and total is distinct from (payload->>'opening_stock_paise')::numeric
   then raise exception 'Opening items do not match the stock total'; end if;
 end if;
 -- Preserve existing revision checks, source-signature checks and audit entry atomically.
 saved:=provider_kitchen_review_save_before_prices(payload);
 if payload ? 'opening_rows' then
  update provider_kitchen_reviews set opening_rows=rows where provider_id=p and month=m;
 elsif old_total is distinct from (payload->>'opening_stock_paise')::bigint then
  -- Older clients changing a total cannot leave stale item rates attached to it.
  update provider_kitchen_reviews set opening_rows='[]'::jsonb where provider_id=p and month=m;
 end if;
 return provider_kitchen_statement(m);
end $$;
revoke all on function public.provider_kitchen_review_save(jsonb) from public,anon;
grant execute on function public.provider_kitchen_review_save(jsonb) to authenticated;
notify pgrst,'reload schema';
