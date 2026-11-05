-- Keep the existing ownership, optimistic locking and audit implementation private.
alter function public.provider_recipe_save(jsonb) rename to provider_recipe_save_v1;
alter function public.provider_recipe_book() rename to provider_recipe_book_v1;
revoke all on function public.provider_recipe_save_v1(jsonb),public.provider_recipe_book_v1() from public,anon,authenticated;

create function public.provider_recipe_menu_options() returns jsonb
language plpgsql stable security definer set search_path=public as $$
declare p uuid:=kitchen_book_provider();
begin
 if not exists(select 1 from profiles where id=auth.uid() and is_active) then raise exception 'Active account required'; end if;
 return coalesce((select jsonb_agg(jsonb_build_object('id',i.id,'name',i.name) order by i.name)
 from menu_items i where i.provider_id=p and i.status='APPROVED' and exists(
 select 1 from menu_day_choices c join menu_days d on d.id=c.menu_day_id
 join provider_menus m on m.id=d.menu_id join package_menus pm on pm.menu_id=m.id
 join packages pk on pk.id=pm.package_id
 where c.menu_item_id=i.id and d.is_available and m.provider_id=p and m.status='APPROVED'
 and pk.provider_id=p and pk.is_active
 and m.valid_from<=(now() at time zone 'Asia/Kolkata')::date
 and (m.valid_until is null or m.valid_until>=(now() at time zone 'Asia/Kolkata')::date)
 and pm.effective_from<=(now() at time zone 'Asia/Kolkata')::date
 and (pm.effective_until is null or pm.effective_until>=(now() at time zone 'Asia/Kolkata')::date)
 )), '[]'::jsonb);
end $$;

create function public.provider_recipe_book() returns jsonb
language plpgsql stable security definer set search_path=public as $$
begin
 return jsonb_set(provider_recipe_book_v1(),'{menus}',provider_recipe_menu_options());
end $$;

create function public.provider_recipe_save(payload jsonb) returns jsonb
language plpgsql security definer set search_path=public as $$
declare m jsonb; r jsonb; result_rows jsonb:='[]'; q numeric; price numeric; factor numeric;
 basis text; u text; pu text; denom numeric; family text; pf text;
begin
 select value into m from jsonb_array_elements(provider_recipe_menu_options()) where value->>'id'=payload->>'menu_item_id';
 if m is null then raise exception 'Select a dish from your current approved package menu'; end if;
 if jsonb_typeof(payload->'rows') is distinct from 'array' then raise exception 'Ingredients required'; end if;
 for r in select value from jsonb_array_elements(payload->'rows') loop
  basis:=r->>'price_basis'; u:=r->>'unit'; q:=(r->>'quantity')::numeric;
  if basis is null or basis not in('kg','litre','piece','packet','batch') then raise exception 'Review each ingredient price basis'; end if;
  price:=nullif(r->>'entered_price','')::numeric;
  if price is not null and (price<0 or price>1000000) then raise exception 'Invalid ingredient price'; end if;
  if price is null then r:=jsonb_set(r,'{price}','""');
  else
   if q is null or q<=0 or q>1000000 then raise exception 'Invalid quantity'; end if;
   if basis='batch' then factor:=price/q;
   else
    family:=case when u in('kg','g') then 'mass' when u in('litre','ml') then 'volume' when u='piece' then 'count' else 'invalid' end;
    pu:=case when basis='packet' then r->>'pack_unit' else basis end;
    pf:=case when pu in('kg','g') then 'mass' when pu in('litre','ml') then 'volume' when pu='piece' then 'count' else 'invalid' end;
    if family='invalid' or pf<>family then raise exception 'Price unit must match quantity type; do not guess oil density'; end if;
    denom:=case when basis='packet' then (r->>'pack_quantity')::numeric else 1 end;
    if denom is null or denom<=0 or denom>1000000 then raise exception 'Enter packet size'; end if;
    denom:=denom*case when pu in('kg','litre') then 1000 else 1 end;
    factor:=price*(case when u in('kg','litre') then 1000 else 1 end)/denom;
   end if;
   r:=jsonb_set(r,'{price}',to_jsonb(round(factor,12)::text));
  end if;
  result_rows:=result_rows||jsonb_build_array(r);
 end loop;
 return provider_recipe_save_v1(payload||jsonb_build_object('name',m->>'name','rows',result_rows));
end $$;
revoke all on function public.provider_recipe_menu_options(),public.provider_recipe_book(),public.provider_recipe_save(jsonb) from public,anon;
grant execute on function public.provider_recipe_book(),public.provider_recipe_save(jsonb) to authenticated;
notify pgrst,'reload schema';
