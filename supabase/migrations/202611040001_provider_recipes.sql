-- Recipe plans never mutate inventory, purchases, wallets or deliveries.
create table public.provider_recipes (
 id uuid primary key, provider_id uuid not null references public.providers(id),
 name text not null, batch integer not null check(batch between 1 and 10000),
 rows jsonb not null, menu_item_id uuid references public.menu_items(id),
 revision integer not null default 1, updated_at timestamptz not null default now()
);
create unique index provider_recipe_menu on public.provider_recipes(provider_id,menu_item_id) where menu_item_id is not null;
alter table public.provider_recipes enable row level security;
revoke all on public.provider_recipes from public,anon,authenticated;

create function public.provider_recipe_save(payload jsonb) returns jsonb
language plpgsql security definer set search_path=public as $$
declare p uuid:=kitchen_book_provider(); k uuid:=(payload->>'id')::uuid;
 m uuid:=nullif(payload->>'menu_item_id','')::uuid; r jsonb; old public.provider_recipes; v integer;
begin
 if not exists(select 1 from profiles where id=auth.uid() and is_active) then raise exception 'Active account required'; end if;
 if k is null or coalesce(length(trim(payload->>'name')),0) not between 1 and 100
 or coalesce((payload->>'batch')::integer,0) not between 1 and 10000 then raise exception 'Check dish name and batch portions'; end if;
 if jsonb_typeof(payload->'rows') is distinct from 'array' then raise exception 'Ingredients required'; end if;
 if jsonb_array_length(payload->'rows') not between 1 and 30 then raise exception 'Use 1 to 30 ingredients'; end if;
 for r in select value from jsonb_array_elements(payload->'rows') loop
  if coalesce(length(trim(r->>'item')),0) not between 1 and 100 or coalesce(r->>'unit','') not in('kg','g','litre','ml','piece')
  or coalesce((r->>'quantity')::numeric,0) not between 0.000001 and 1000000
  or (nullif(r->>'price','') is not null and (r->>'price')::numeric not between 0 and 1000000) then raise exception 'Check ingredient quantity, unit and price'; end if;
 end loop;
 if m is not null and not exists(select 1 from menu_items where id=m and provider_id=p and status='APPROVED') then raise exception 'Choose an approved dish from this kitchen'; end if;
 perform 1 from providers where id=p for update;
 select * into old from provider_recipes where id=k;
 if found then
  if old.provider_id<>p then raise exception 'Recipe unavailable'; end if;
  if old.name=trim(payload->>'name') and old.batch=(payload->>'batch')::integer and old.rows=payload->'rows' and old.menu_item_id is not distinct from m then return to_jsonb(old); end if;
  if old.revision<>coalesce((payload->>'revision')::integer,0) then raise exception 'Recipe changed on another device. Refresh before editing'; end if;
  update provider_recipes set name=trim(payload->>'name'),batch=(payload->>'batch')::integer,rows=payload->'rows',menu_item_id=m,revision=revision+1,updated_at=now() where id=k returning revision into v;
 else
  if coalesce((payload->>'revision')::integer,0)<>0 then raise exception 'Refresh this recipe'; end if;
  insert into provider_recipes(id,provider_id,name,batch,rows,menu_item_id) values(k,p,trim(payload->>'name'),(payload->>'batch')::integer,payload->'rows',m) returning revision into v;
 end if;
 insert into audit_logs(actor_id,action,entity_type,entity_id,after_data) values(auth.uid(),'RECIPE_SAVED','provider_recipes',k::text,jsonb_build_object('revision',v));
 return (select to_jsonb(x) from provider_recipes x where id=k and provider_id=p);
end $$;

create function public.provider_recipe_book() returns jsonb
language plpgsql stable security definer set search_path=public as $$
declare p uuid:=kitchen_book_provider();
begin
 if not exists(select 1 from profiles where id=auth.uid() and is_active) then raise exception 'Active account required'; end if;
 return jsonb_build_object('recipes',coalesce((select jsonb_agg(to_jsonb(r) order by name) from provider_recipes r where provider_id=p),'[]'::jsonb),
 'menus',coalesce((select jsonb_agg(jsonb_build_object('id',id,'name',name) order by name) from menu_items where provider_id=p and status='APPROVED'),'[]'::jsonb),
 'plans',coalesce((select jsonb_agg(to_jsonb(x)) from (
 select m.service_date,m.meal_slot,r.id recipe_id,r.name,count(*) portions
 from subscription_meals m join provider_recipes r on r.provider_id=p and r.menu_item_id=m.selected_menu_item_id
 where m.provider_id=p and m.service_date between (now() at time zone 'Asia/Kolkata')::date and (now() at time zone 'Asia/Kolkata')::date+7
 and m.status in('SCHEDULED','PREPARING','PACKING','READY','OUT_FOR_DELIVERY')
 group by m.service_date,m.meal_slot,r.id,r.name order by m.service_date,m.meal_slot,r.name) x),'[]'::jsonb));
end $$;
revoke all on function public.provider_recipe_save(jsonb),public.provider_recipe_book() from public,anon;
grant execute on function public.provider_recipe_save(jsonb),public.provider_recipe_book() to authenticated;
notify pgrst,'reload schema';
