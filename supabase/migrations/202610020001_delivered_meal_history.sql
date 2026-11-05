-- All subscriptions, no rolling date window. Only the signed-in customer's meals.
create or replace function public.customer_delivered_months() returns jsonb
language sql stable security definer set search_path=public as $$
 select jsonb_build_object('items',coalesce(jsonb_agg(to_jsonb(x) order by x."month" desc),'[]'::jsonb))
 from (select to_char(service_date,'YYYY-MM') as "month",count(*) as count
 from subscription_meals where customer_id=auth.uid() and status='DELIVERED'
 group by 1) x;
$$;
create or replace function public.customer_delivered_month(target_month text) returns jsonb
language sql stable security definer set search_path=public as $$
 select jsonb_build_object('items',coalesce(jsonb_agg(to_jsonb(x) order by service_date desc,meal_slot,meal_id),'[]'::jsonb))
 from (select sm.id meal_id,sm.subscription_id,sm.provider_id,p.display_name provider_name,
 sm.service_date,sm.meal_slot,sm.status,mi.id item_id,coalesce(mi.name,'') item_name,
 coalesce(mi.description,'') description,sm.meal_value_paise price_paise,
 r.rating,r.category_ratings,r.tags,r.review_text,r.is_anonymous,r.updated_at reviewed_at,
 (select storage_path from provider_media pm where pm.menu_item_id=sm.selected_menu_item_id
 and pm.status='APPROVED' order by pm.is_primary desc,pm.created_at desc limit 1) photo_path
 from subscription_meals sm join providers p on p.id=sm.provider_id
 left join menu_items mi on mi.id=sm.selected_menu_item_id
 left join customer_meal_reviews r on r.meal_id=sm.id
 where sm.customer_id=auth.uid() and sm.status='DELIVERED'
 and sm.service_date>=to_date(target_month||'-01','YYYY-MM-DD')
 and sm.service_date<(to_date(target_month||'-01','YYYY-MM-DD')+interval '1 month')) x;
$$;
revoke all on function public.customer_delivered_months() from public,anon;
revoke all on function public.customer_delivered_month(text) from public,anon;
grant execute on function public.customer_delivered_months() to authenticated;
grant execute on function public.customer_delivered_month(text) to authenticated;
