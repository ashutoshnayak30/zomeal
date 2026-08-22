-- Return one customer-facing provider row with every approved active package.
-- This replaces the earlier lowest-price-only marketplace contract.
drop function if exists public.customer_marketplace(text);

create function public.customer_marketplace(target_pincode text)
returns table(
  provider_id uuid,display_name text,description text,dietary_type text,locality text,city text,
  packages jsonb,weekly_menu jsonb,primary_photo_path text
) language sql stable security definer set search_path=public as $$
  select
    p.id,p.display_name,p.description,p.dietary_type::text,pc.locality,pc.city,
    coalesce((
      select jsonb_agg(jsonb_build_object(
        'id',package.id,'name',package.name,'kind',package.kind::text,
        'price_paise',price.total_price_paise
      ) order by
        case package.kind when 'LUNCH_ONLY' then 1 when 'LUNCH_AND_DINNER' then 2 else 3 end,
        price.total_price_paise)
      from public.packages package
      join public.package_price_versions price on price.package_id=package.id
        and price.status='APPROVED' and price.effective_from<=now()
        and (price.effective_until is null or price.effective_until>now())
      where package.provider_id=p.id and package.is_active
    ),'[]'::jsonb),
    coalesce((
      select jsonb_agg(jsonb_build_object(
        'day_of_week',menu_rows.day_of_week,'meal_slot',menu_rows.meal_slot,
        'items',menu_rows.items
      ) order by menu_rows.day_of_week,menu_rows.meal_slot)
      from (
        select md.day_of_week,md.meal_slot::text meal_slot,
          jsonb_agg(jsonb_build_object(
            'id',mi.id,'name',mi.name,'category',mi.category::text,'dietary_type',mi.dietary_type::text,'description',mi.description,
            'is_default',mdc.is_default,'is_changeable',mdc.is_changeable
          ) order by mdc.display_order,mi.name) items
        from public.provider_menus pm
        join public.menu_days md on md.menu_id=pm.id and md.is_available
        join public.menu_day_choices mdc on mdc.menu_day_id=md.id
        join public.menu_items mi on mi.id=mdc.menu_item_id and mi.status='APPROVED'
        where pm.provider_id=p.id and pm.status='APPROVED'
          and pm.valid_from<=current_date and (pm.valid_until is null or pm.valid_until>=current_date)
        group by md.day_of_week,md.meal_slot
      ) menu_rows
    ),'[]'::jsonb),
    (select media.storage_path from public.provider_media media where media.provider_id=p.id
      and media.status='APPROVED' and media.media_type in('PROVIDER_LOGO','OWNER_PROFILE','PACKAGE_COVER','MEAL')
      order by media.is_primary desc,media.display_order,media.created_at desc limit 1)
  from public.providers p
  join public.provider_service_areas area on area.provider_id=p.id and area.pincode=target_pincode
    and area.status='APPROVED' and (area.effective_from is null or area.effective_from<=current_date)
    and (area.effective_until is null or area.effective_until>=current_date)
  join public.pincodes pc on pc.code=area.pincode and pc.is_enabled
  where p.status='ACTIVE'
    and exists(
      select 1 from public.packages package
      join public.package_price_versions price on price.package_id=package.id
        and price.status='APPROVED' and price.effective_from<=now()
        and (price.effective_until is null or price.effective_until>now())
      where package.provider_id=p.id and package.is_active
    )
  order by p.display_name;
$$;

revoke all on function public.customer_marketplace(text) from public;
grant execute on function public.customer_marketplace(text) to anon,authenticated;

comment on function public.customer_marketplace(text) is
'Returns active providers serving a pincode with all approved packages, prices, approved menus and primary media.';
