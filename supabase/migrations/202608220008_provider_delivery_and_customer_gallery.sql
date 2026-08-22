-- Reliable provider delivery contact updates and an approved customer gallery.

create or replace function public.provider_upsert_primary_delivery_contact(contact_name text,contact_phone text)
returns uuid language plpgsql security definer set search_path=public as $$
declare target_provider uuid;saved_contact uuid;
begin
  select member.provider_id into target_provider from public.provider_members member
  where member.user_id=auth.uid() and member.is_active order by member.created_at desc limit 1;
  if target_provider is null then raise exception 'Provider account was not found'; end if;
  if contact_phone is null or contact_phone !~ '^[6-9][0-9]{9}$' then
    raise exception 'Enter a valid 10-digit Indian mobile number beginning with 6, 7, 8 or 9';
  end if;
  update public.provider_delivery_personnel set is_primary=false where provider_id=target_provider and is_primary;
  insert into public.provider_delivery_personnel(provider_id,full_name,phone,is_primary,is_active,created_by)
  values(target_provider,nullif(trim(contact_name),''),contact_phone,true,true,auth.uid())
  on conflict(provider_id,phone) do update set full_name=excluded.full_name,is_primary=true,is_active=true,updated_at=now()
  returning id into saved_contact;
  return saved_contact;
end; $$;
revoke all on function public.provider_upsert_primary_delivery_contact(text,text) from public;
grant execute on function public.provider_upsert_primary_delivery_contact(text,text) to authenticated;

drop function if exists public.customer_marketplace(text);
create function public.customer_marketplace(target_pincode text)
returns table(provider_id uuid,display_name text,description text,dietary_type text,locality text,city text,packages jsonb,weekly_menu jsonb,primary_photo_path text,kitchen_photo_path text,meal_photo_path text)
language sql stable security definer set search_path=public as $$
select p.id,p.display_name,p.description,p.dietary_type::text,pc.locality,pc.city,
coalesce((select jsonb_agg(jsonb_build_object('id',pkg.id,'name',pkg.name,'kind',pkg.kind::text,'price_paise',price.total_price_paise)
  order by case pkg.kind when 'LUNCH_ONLY' then 1 when 'LUNCH_AND_DINNER' then 2 else 3 end,price.total_price_paise)
  from public.packages pkg cross join lateral(select version.total_price_paise from public.package_price_versions version
    where version.package_id=pkg.id and version.status='APPROVED' and version.effective_from<=now()
      and(version.effective_until is null or version.effective_until>now()) order by version.effective_from desc,version.created_at desc limit 1) price
  where pkg.provider_id=p.id and pkg.is_active),'[]'::jsonb),
coalesce((select jsonb_agg(jsonb_build_object('day_of_week',rows.day_of_week,'meal_slot',rows.meal_slot,'items',rows.items)
  order by rows.day_of_week,rows.meal_slot) from(
    select day.day_of_week,day.meal_slot::text meal_slot,jsonb_agg(jsonb_build_object(
      'id',item.id,'name',item.name,'category',item.category::text,'dietary_type',item.dietary_type::text,
      'description',item.description,'ingredients',item.ingredients,'allergen_notes',item.allergen_notes,
      'is_default',choice.is_default,'is_changeable',choice.is_changeable,
      'photo_path',(select media.storage_path from public.provider_media media where media.provider_id=p.id and media.menu_item_id=item.id and media.status='APPROVED' order by media.is_primary desc,media.display_order,media.created_at desc limit 1)) order by choice.display_order,item.name) items
    from lateral(select candidate.id from public.provider_menus candidate where candidate.provider_id=p.id and candidate.status='APPROVED' and candidate.valid_from<=current_date and(candidate.valid_until is null or candidate.valid_until>=current_date) order by candidate.valid_from desc,candidate.updated_at desc limit 1) current_menu
    join public.menu_days day on day.menu_id=current_menu.id and day.is_available
    join public.menu_day_choices choice on choice.menu_day_id=day.id
    join public.menu_items item on item.id=choice.menu_item_id and item.status='APPROVED'
    group by day.day_of_week,day.meal_slot) rows),'[]'::jsonb),
(select media.storage_path from public.provider_media media where media.provider_id=p.id and media.status='APPROVED' and media.media_type in('PROVIDER_LOGO','OWNER_PROFILE','PACKAGE_COVER','MEAL') order by case media.media_type when 'PROVIDER_LOGO' then 1 when 'OWNER_PROFILE' then 2 when 'MEAL' then 3 else 4 end,media.is_primary desc,media.created_at desc limit 1),
(select media.storage_path from public.provider_media media where media.provider_id=p.id and media.status='APPROVED' and media.media_type='KITCHEN' order by media.is_primary desc,media.created_at desc limit 1),
(select media.storage_path from public.provider_media media where media.provider_id=p.id and media.status='APPROVED' and media.media_type in('MEAL','PACKAGE_COVER') order by media.is_primary desc,media.created_at desc limit 1)
from public.providers p
join public.provider_service_areas area on area.provider_id=p.id and area.pincode=target_pincode and area.status='APPROVED' and(area.effective_from is null or area.effective_from<=current_date) and(area.effective_until is null or area.effective_until>=current_date)
join public.pincodes pc on pc.code=area.pincode and pc.is_enabled
where p.status='ACTIVE' and public.provider_customer_catalogue_ready(p.id)
  and exists(select 1 from public.packages pkg where pkg.provider_id=p.id and pkg.is_active and exists(select 1 from public.package_price_versions price where price.package_id=pkg.id and price.status='APPROVED' and price.effective_from<=now() and(price.effective_until is null or price.effective_until>now())))
order by p.display_name;
$$;
revoke all on function public.customer_marketplace(text) from public;
grant execute on function public.customer_marketplace(text) to anon,authenticated;
notify pgrst,'reload schema';
