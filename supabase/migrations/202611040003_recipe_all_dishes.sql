-- One recipe serving is one customer's serving of the dish (e.g. two rotis).
-- Use saved side snapshots, never today's menu substituted into another day's meal.
create function public.recipe_meal_dishes(main_id uuid,sides jsonb)
returns table(dish_id text,dish_name text,dish_kind text)
language sql immutable set search_path=public as $$
 with raw as (
 select coalesce(main_id::text,'missing-main') id,'Main course not selected' name,'MAIN' kind
 union all
 select coalesce(nullif(x->>'id',''),'missing-side:'||coalesce(x->>'name','Unnamed side')),coalesce(x->>'name','Unnamed side'),'SIDE'
 from jsonb_array_elements(case when jsonb_typeof(sides)='array' then sides else '[]'::jsonb end) x
 union all
 select 'missing-side-snapshot','Included sides not recorded','UNKNOWN'
 where sides is null or jsonb_typeof(sides)<>'array'
 ) select id,min(name),min(kind) from raw group by id;
$$;
revoke all on function public.recipe_meal_dishes(uuid,jsonb) from public,anon,authenticated;

create or replace function public.provider_recipe_book() returns jsonb
language plpgsql stable security definer set search_path=public as $$
declare p uuid:=kitchen_book_provider(); result jsonb; dish_groups jsonb; coverage jsonb;
begin
 result:=provider_recipe_book_v1(); -- retains active-profile guard and scoped recipes
 with meals as (
 select * from subscription_meals where provider_id=p
 and service_date between (now() at time zone 'Asia/Kolkata')::date and (now() at time zone 'Asia/Kolkata')::date+7
 and status in('SCHEDULED','PREPARING','PACKING','READY','OUT_FOR_DELIVERY')
 ), dishes as (
 select m.id,m.service_date,m.meal_slot,d.* from meals m cross join lateral recipe_meal_dishes(m.selected_menu_item_id,m.side_dishes) d
 ) select coalesce(jsonb_agg(to_jsonb(g) order by service_date,meal_slot,name),'[]'::jsonb) into dish_groups from (
 select d.service_date,d.meal_slot,d.dish_id,d.dish_kind,r.id recipe_id,coalesce(i.name,d.dish_name) name,count(distinct d.id) portions
 from dishes d left join menu_items i on i.id::text=d.dish_id and i.provider_id=p
 left join provider_recipes r on r.menu_item_id::text=d.dish_id and r.provider_id=p
 group by d.service_date,d.meal_slot,d.dish_id,d.dish_kind,r.id,i.name,d.dish_name
 ) g;
 select coalesce(jsonb_agg(to_jsonb(g)),'[]'::jsonb) into coverage from (
 select service_date,meal_slot,count(*) meals from subscription_meals where provider_id=p
 and service_date between (now() at time zone 'Asia/Kolkata')::date and (now() at time zone 'Asia/Kolkata')::date+7
 and status in('SCHEDULED','PREPARING','PACKING','READY','OUT_FOR_DELIVERY') group by service_date,meal_slot
 ) g;
 return result||jsonb_build_object('menus',provider_recipe_menu_options(),'all_dishes',true,'coverage',coverage,
 'plans',coalesce((select jsonb_agg(x) from jsonb_array_elements(dish_groups) x where x->>'recipe_id' is not null),'[]'::jsonb),
 'missing_recipes',coalesce((select jsonb_agg(x) from jsonb_array_elements(dish_groups) x where x->>'recipe_id' is null),'[]'::jsonb));
end $$;
notify pgrst,'reload schema';
