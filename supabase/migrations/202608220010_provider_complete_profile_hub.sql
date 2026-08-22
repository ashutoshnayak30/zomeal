-- A single provider-facing read model for the Profile page. Only the signed-in
-- provider's approved customer-visible catalogue is returned.
create or replace function public.provider_profile_hub()
returns jsonb language plpgsql stable security definer set search_path=public as $$
declare target_provider uuid; result jsonb;
begin
  select member.provider_id into target_provider from public.provider_members member
  where member.user_id=auth.uid() and member.is_active order by member.created_at desc limit 1;
  if target_provider is null then raise exception 'Provider membership was not found'; end if;

  select jsonb_build_object(
    'provider_id',p.id,'provider_name',p.display_name,'contact_name',p.contact_person_name,
    'status',p.status,'category',p.dietary_type,'description',p.description,
    'city',p.business_city,'state',p.business_state,'pincode',p.business_pincode,'address',p.business_address_line,
    'profile_photo_path',coalesce((select m.storage_path from public.provider_media m where m.provider_id=p.id and m.media_type='OWNER_PROFILE' and m.status='APPROVED' order by m.is_primary desc,m.reviewed_at desc nulls last limit 1),''),
    'kitchen_photo_path',coalesce((select m.storage_path from public.provider_media m where m.provider_id=p.id and m.media_type='KITCHEN' and m.status='APPROVED' order by m.is_primary desc,m.reviewed_at desc nulls last limit 1),''),
    'meal_photo_path',coalesce((select m.storage_path from public.provider_media m where m.provider_id=p.id and m.media_type='MEAL' and m.status='APPROVED' order by m.is_primary desc,m.reviewed_at desc nulls last limit 1),''),
    'active_subscribers',(select count(*) from public.customer_subscriptions s where s.provider_id=p.id and s.status in('ACTIVE','PAUSED','CANCEL_PENDING') and s.end_date>=current_date),
    'active_packages',(select count(*) from public.packages package where package.provider_id=p.id and package.is_active),
    'serviceable_pincodes',(select count(*) from public.provider_service_areas area where area.provider_id=p.id and area.status='APPROVED'),
    'pending_change_requests',(select count(*) from public.provider_change_requests r where r.provider_id=p.id and r.status='PENDING' and r.requested_payload->>'scope'='FULL_BUSINESS_UPDATE'),
    'packages',coalesce((select jsonb_agg(jsonb_build_object('id',package.id,'name',package.name,'kind',package.kind,'description',package.description,'price_paise',price.total_price_paise,'duration_days',package.duration_days) order by package.kind)
      from public.packages package join lateral(select v.total_price_paise from public.package_price_versions v where v.package_id=package.id and v.status='APPROVED' and v.effective_until is null order by v.version desc limit 1) price on true
      where package.provider_id=p.id and package.is_active),'[]'::jsonb),
    'weekly_menu',coalesce((select jsonb_agg(jsonb_build_object('day_of_week',rows.day_of_week,'meal_slot',rows.meal_slot,'items',rows.items) order by rows.day_of_week,rows.meal_slot)
      from(select day.day_of_week,day.meal_slot::text,jsonb_agg(jsonb_build_object('id',item.id,'name',item.name,'category',item.category,'dietary_type',item.dietary_type,'description',item.description,'photo_path',coalesce(media.storage_path,'')) order by choice.display_order,item.name) items
        from public.provider_menus menu join public.menu_days day on day.menu_id=menu.id and day.is_available
        join public.menu_day_choices choice on choice.menu_day_id=day.id join public.menu_items item on item.id=choice.menu_item_id and item.status='APPROVED'
        left join lateral(select m.storage_path from public.provider_media m where m.menu_item_id=item.id and m.media_type='MENU_ITEM' and m.status='APPROVED' order by m.is_primary desc,m.reviewed_at desc nulls last limit 1) media on true
        where menu.provider_id=p.id and menu.status='APPROVED' group by day.day_of_week,day.meal_slot) rows),'[]'::jsonb)
  ) into result from public.providers p where p.id=target_provider;
  return coalesce(result,'{}'::jsonb);
end; $$;

revoke all on function public.provider_profile_hub() from public;
grant execute on function public.provider_profile_hub() to authenticated;
notify pgrst,'reload schema';
