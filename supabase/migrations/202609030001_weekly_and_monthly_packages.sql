-- Providers price each meal type independently for a 7-day trial and a
-- 30-day regular subscription. Existing 30-day packages remain unchanged.

create or replace function public.route_package_price_to_correct_duration()
returns trigger language plpgsql set search_path=public as $$
declare correct_package uuid; package_row public.packages;
begin
  select * into package_row from public.packages where id=new.package_id;
  if package_row.duration_days=7 and coalesce(current_setting('zomeal.weekly_price_write',true),'0')<>'1' then
    select id into correct_package from public.packages
    where provider_id=package_row.provider_id and kind=package_row.kind and duration_days=30
    order by is_active desc,created_at desc limit 1;
    if correct_package is not null then new.package_id:=correct_package; end if;
  end if;
  return new;
end; $$;

drop trigger if exists route_package_price_duration on public.package_price_versions;
create trigger route_package_price_duration before insert on public.package_price_versions
for each row execute function public.route_package_price_to_correct_duration();

create or replace function public.activate_package_after_price_approval()
returns trigger language plpgsql set search_path=public as $$
begin
  if new.status='APPROVED' and old.status is distinct from new.status then
    update public.packages set is_active=true,updated_at=now() where id=new.package_id;
  end if;
  return new;
end; $$;
drop trigger if exists activate_package_after_price_approval on public.package_price_versions;
create trigger activate_package_after_price_approval after update of status on public.package_price_versions
for each row execute function public.activate_package_after_price_approval();

create or replace function public.provider_sync_weekly_packages(payload jsonb,target_change_request uuid default null)
returns jsonb language plpgsql security definer set search_path=public as $$
declare
  target_provider uuid; row_data jsonb; saved_package_id uuid; next_version integer;
  total_paise bigint; lunch_paise bigint; dinner_paise bigint;
begin
  select member.provider_id into target_provider from public.provider_members member
  where member.user_id=auth.uid() and member.is_active order by member.created_at desc limit 1;
  if target_provider is null then raise exception 'Provider account was not found'; end if;
  if target_change_request is not null and not exists(
    select 1 from public.provider_change_requests request
    where request.id=target_change_request and request.provider_id=target_provider and request.status='PENDING'
  ) then raise exception 'The provider change request is not available'; end if;

  perform set_config('zomeal.weekly_price_write','1',true);
  for row_data in select value from jsonb_array_elements(jsonb_build_array(
    jsonb_build_object('enabled',payload->'lunchEnabled','kind','LUNCH_ONLY','name','Lunch Only · Weekly','price',payload->>'weeklyLunchPrice'),
    jsonb_build_object('enabled',payload->'dinnerEnabled','kind','DINNER_ONLY','name','Dinner Only · Weekly','price',payload->>'weeklyDinnerPrice'),
    jsonb_build_object('enabled',payload->'bothEnabled','kind','LUNCH_AND_DINNER','name','Lunch + Dinner · Weekly','price',payload->>'weeklyBothPrice')
  )) loop
    if coalesce((row_data->>'enabled')::boolean,false) then
      if nullif(trim(row_data->>'price'),'') is null then raise exception 'Weekly price required for %',row_data->>'name'; end if;
      total_paise:=round((row_data->>'price')::numeric*100);
      if total_paise<=0 then raise exception 'Weekly price must be greater than zero'; end if;
      select id into saved_package_id from public.packages
      where provider_id=target_provider and kind=(row_data->>'kind')::public.package_kind and duration_days=7
      order by is_active desc,created_at desc limit 1;
      if saved_package_id is null then
        insert into public.packages(provider_id,name,kind,dietary_type,duration_days,is_active)
        select target_provider,row_data->>'name',(row_data->>'kind')::public.package_kind,provider.dietary_type,7,false
        from public.providers provider where provider.id=target_provider returning id into saved_package_id;
      end if;
      if row_data->>'kind'='LUNCH_ONLY' then lunch_paise:=total_paise;dinner_paise:=0;
      elsif row_data->>'kind'='DINNER_ONLY' then lunch_paise:=0;dinner_paise:=total_paise;
      else lunch_paise:=total_paise/2;dinner_paise:=total_paise-lunch_paise;
      end if;
      select coalesce(max(price.version),0)+1 into next_version from public.package_price_versions price where price.package_id=saved_package_id;
      delete from public.package_price_versions price where price.package_id=saved_package_id and price.status='PENDING';
      insert into public.package_price_versions(package_id,version,total_price_paise,lunch_value_paise,dinner_value_paise,status,requested_by,change_request_id)
      values(saved_package_id,next_version,total_paise,lunch_paise,dinner_paise,'PENDING',auth.uid(),target_change_request);
      saved_package_id:=null;
    end if;
  end loop;
  return jsonb_build_object('saved',true,'duration_days',7);
end; $$;
revoke all on function public.provider_sync_weekly_packages(jsonb,uuid) from public;
grant execute on function public.provider_sync_weekly_packages(jsonb,uuid) to authenticated;

-- The combined-value editor always describes the monthly package. Explicitly
-- select 30 days now that the same meal kind can also have a weekly package.
create or replace function public.provider_set_combined_package_values(target_lunch_daily_rupees numeric)
returns jsonb language plpgsql security definer set search_path=public as $$
declare target_provider uuid;target_price uuid;total bigint;lunch_value bigint;dinner_value bigint;
begin
  select provider_id into target_provider from public.provider_members where user_id=auth.uid() and is_active order by created_at desc limit 1;
  select pv.id,pv.total_price_paise into target_price,total
  from public.packages p join public.package_price_versions pv on pv.package_id=p.id
  where p.provider_id=target_provider and p.kind='LUNCH_AND_DINNER' and p.duration_days=30 and pv.status='PENDING'
  order by pv.created_at desc limit 1 for update of pv;
  if target_price is null then raise exception 'Pending monthly combined package price was not found';end if;
  lunch_value:=round(target_lunch_daily_rupees*30*100);dinner_value:=total-lunch_value;
  if lunch_value<=0 or dinner_value<=0 then raise exception 'Lunch and dinner values must both be greater than zero';end if;
  update public.package_price_versions set lunch_value_paise=lunch_value,dinner_value_paise=dinner_value where id=target_price;
  return jsonb_build_object('total_price_paise',total,'lunch_value_paise',lunch_value,'dinner_value_paise',dinner_value,
    'lunch_daily_paise',round(lunch_value::numeric/30),'dinner_daily_paise',round(dinner_value::numeric/30));
end; $$;
revoke all on function public.provider_set_combined_package_values(numeric) from public;
grant execute on function public.provider_set_combined_package_values(numeric) to authenticated;

drop function if exists public.customer_marketplace(text);
create function public.customer_marketplace(target_pincode text)
returns table(provider_id uuid,display_name text,description text,dietary_type text,locality text,city text,packages jsonb,weekly_menu jsonb,primary_photo_path text,kitchen_photo_path text,meal_photo_path text)
language sql stable security definer set search_path=public as $$
select p.id,p.display_name,p.description,p.dietary_type::text,pc.locality,pc.city,
coalesce((select jsonb_agg(jsonb_build_object('id',pkg.id,'name',pkg.name,'kind',pkg.kind::text,'duration_days',pkg.duration_days,'price_paise',price.total_price_paise)
  order by pkg.duration_days,case pkg.kind when 'LUNCH_ONLY' then 1 when 'LUNCH_AND_DINNER' then 2 else 3 end)
  from public.packages pkg cross join lateral(select version.total_price_paise from public.package_price_versions version
    where version.package_id=pkg.id and version.status='APPROVED' and version.effective_from<=now()
      and(version.effective_until is null or version.effective_until>now()) order by version.effective_from desc,version.created_at desc limit 1) price
  where pkg.provider_id=p.id and pkg.is_active),'[]'::jsonb),
coalesce((select jsonb_agg(jsonb_build_object('day_of_week',rows.day_of_week,'meal_slot',rows.meal_slot,'items',rows.items) order by rows.day_of_week,rows.meal_slot) from(
  select day.day_of_week,day.meal_slot::text meal_slot,jsonb_agg(jsonb_build_object(
    'id',item.id,'name',item.name,'category',item.category::text,'dietary_type',item.dietary_type::text,'description',item.description,
    'ingredients',item.ingredients,'allergen_notes',item.allergen_notes,'is_default',choice.is_default,'is_changeable',choice.is_changeable,
    'photo_path',(select media.storage_path from public.provider_media media where media.provider_id=p.id and media.menu_item_id=item.id and media.status='APPROVED' order by media.is_primary desc,media.display_order,media.created_at desc limit 1)
  ) order by choice.display_order,item.name) items
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
