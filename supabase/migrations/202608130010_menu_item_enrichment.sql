-- Admin-managed menu item descriptions, ingredients, nutrition and allergens.

create or replace function public.admin_enrich_menu_item(
  target_item_id uuid,
  item_description text default null,
  ingredient_list text[] default '{}',
  allergen_list public.allergen_code[] default '{}',
  nutrition jsonb default '{}'::jsonb
) returns void language plpgsql security definer set search_path=public as $$
declare old_data jsonb; new_data jsonb; allergen_value public.allergen_code;
begin
  perform public.require_staff(array['ADMIN','OPERATIONS']::public.app_role[]);
  select to_jsonb(i) into old_data from public.menu_items i where id=target_item_id for update;
  if old_data is null then raise exception 'Menu item not found'; end if;
  update public.menu_items set description=nullif(trim(item_description),''),ingredients=coalesce(ingredient_list,'{}'),updated_at=now()
  where id=target_item_id;
  insert into public.menu_item_nutrition(menu_item_id,serving_size_grams,calories_kcal,protein_grams,carbohydrates_grams,fat_grams,fibre_grams,sodium_mg,is_provider_estimate)
  values(target_item_id,nullif(nutrition->>'serving_grams','')::numeric,nullif(nutrition->>'calories','')::numeric,
    nullif(nutrition->>'protein_grams','')::numeric,nullif(nutrition->>'carbs_grams','')::numeric,
    nullif(nutrition->>'fat_grams','')::numeric,nullif(nutrition->>'fibre_grams','')::numeric,
    nullif(nutrition->>'sodium_mg','')::numeric,true)
  on conflict(menu_item_id) do update set serving_size_grams=excluded.serving_size_grams,calories_kcal=excluded.calories_kcal,
    protein_grams=excluded.protein_grams,carbohydrates_grams=excluded.carbohydrates_grams,fat_grams=excluded.fat_grams,
    fibre_grams=excluded.fibre_grams,sodium_mg=excluded.sodium_mg,updated_at=now();
  delete from public.menu_item_allergens where menu_item_id=target_item_id;
  foreach allergen_value in array coalesce(allergen_list,'{}') loop
    insert into public.menu_item_allergens(menu_item_id,allergen) values(target_item_id,allergen_value);
  end loop;
  select to_jsonb(i) into new_data from public.menu_items i where id=target_item_id;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,before_data,after_data,metadata)
  values(auth.uid(),'MENU_ITEM_ENRICHED_BY_ADMIN','menu_items',target_item_id::text,old_data,new_data,
    jsonb_build_object('allergens',allergen_list,'nutrition',nutrition));
end; $$;

revoke all on function public.admin_enrich_menu_item(uuid,text,text[],public.allergen_code[],jsonb) from public;
grant execute on function public.admin_enrich_menu_item(uuid,text,text[],public.allergen_code[],jsonb) to authenticated;

