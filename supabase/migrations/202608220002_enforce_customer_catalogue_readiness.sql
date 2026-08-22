-- Never expose an active provider to customers unless the current approved
-- catalogue contains a main course for every day required by its active packages.

create or replace function public.provider_customer_catalogue_ready(target_provider uuid)
returns boolean
language sql
stable
security definer
set search_path=public
as $$
with requirements as (
  select
    exists(select 1 from public.packages where provider_id=target_provider and is_active and kind in ('LUNCH_ONLY','LUNCH_AND_DINNER')) as needs_lunch,
    exists(select 1 from public.packages where provider_id=target_provider and is_active and kind in ('DINNER_ONLY','LUNCH_AND_DINNER')) as needs_dinner
), current_menu as (
  select menu.id
  from public.provider_menus menu
  where menu.provider_id=target_provider and menu.status='APPROVED'
    and menu.valid_from<=current_date
    and(menu.valid_until is null or menu.valid_until>=current_date)
  order by menu.valid_from desc,menu.updated_at desc
  limit 1
), coverage as (
  select day.meal_slot,count(distinct day.day_of_week)::integer as covered_days
  from current_menu menu
  join public.menu_days day on day.menu_id=menu.id and day.is_available
  where exists(
    select 1
    from public.menu_day_choices choice
    join public.menu_items item on item.id=choice.menu_item_id
    where choice.menu_day_id=day.id
      and choice.choice_group='MAIN_COURSE'
      and item.status='APPROVED'
      and nullif(trim(item.name),'') is not null
  )
  group by day.meal_slot
)
select
  exists(select 1 from current_menu)
  and (not requirements.needs_lunch or coalesce((select covered_days from coverage where meal_slot='LUNCH'),0)=7)
  and (not requirements.needs_dinner or coalesce((select covered_days from coverage where meal_slot='DINNER'),0)=7)
  and (requirements.needs_lunch or requirements.needs_dinner)
from requirements;
$$;

revoke all on function public.provider_customer_catalogue_ready(uuid) from public;
grant execute on function public.provider_customer_catalogue_ready(uuid) to authenticated;

-- Reject an incomplete replacement before it can become the approved menu.
-- Items may still be PENDING_REVIEW at this point because the admin approval RPC
-- approves the menu immediately before approving its linked items.
create or replace function public.enforce_approved_provider_menu_coverage()
returns trigger
language plpgsql
set search_path=public
as $$
declare
  needs_lunch boolean;
  needs_dinner boolean;
  covered integer;
begin
  if new.status<>'APPROVED' or (tg_op='UPDATE' and old.status='APPROVED') then return new; end if;

  select
    bool_or(package.kind in ('LUNCH_ONLY','LUNCH_AND_DINNER')),
    bool_or(package.kind in ('DINNER_ONLY','LUNCH_AND_DINNER'))
  into needs_lunch,needs_dinner
  from public.packages package
  where package.provider_id=new.provider_id and package.is_active;

  if coalesce(needs_lunch,false) then
    select count(distinct day.day_of_week) into covered
    from public.menu_days day
    where day.menu_id=new.id and day.meal_slot='LUNCH' and day.is_available
      and exists(select 1 from public.menu_day_choices choice join public.menu_items item on item.id=choice.menu_item_id
        where choice.menu_day_id=day.id and choice.choice_group='MAIN_COURSE'
          and item.status in ('PENDING_REVIEW','APPROVED') and nullif(trim(item.name),'') is not null);
    if covered<>7 then raise exception 'Cannot publish menu: lunch main courses are complete for only % of 7 days',covered; end if;
  end if;

  if coalesce(needs_dinner,false) then
    select count(distinct day.day_of_week) into covered
    from public.menu_days day
    where day.menu_id=new.id and day.meal_slot='DINNER' and day.is_available
      and exists(select 1 from public.menu_day_choices choice join public.menu_items item on item.id=choice.menu_item_id
        where choice.menu_day_id=day.id and choice.choice_group='MAIN_COURSE'
          and item.status in ('PENDING_REVIEW','APPROVED') and nullif(trim(item.name),'') is not null);
    if covered<>7 then raise exception 'Cannot publish menu: dinner main courses are complete for only % of 7 days',covered; end if;
  end if;
  return new;
end;
$$;

drop trigger if exists enforce_approved_provider_menu_coverage on public.provider_menus;
create trigger enforce_approved_provider_menu_coverage
before insert or update of status on public.provider_menus
for each row execute function public.enforce_approved_provider_menu_coverage();

-- A newly approved replacement becomes the menu attached to every compatible
-- active package. Close the superseded package-menu links at the same time.
create or replace function public.attach_approved_provider_menu_to_packages()
returns trigger
language plpgsql
set search_path=public
as $$
begin
  if new.status='APPROVED' and (tg_op='INSERT' or old.status is distinct from new.status) then
    update public.package_menus link
    set effective_until=greatest(link.effective_from,current_date)
    where link.package_id in(select id from public.packages where provider_id=new.provider_id and is_active)
      and link.menu_id<>new.id and(link.effective_until is null or link.effective_until>=current_date);

    insert into public.package_menus(package_id,menu_id,effective_from,effective_until)
    select package.id,new.id,current_date,null
    from public.packages package
    where package.provider_id=new.provider_id and package.is_active
    on conflict(package_id,menu_id,effective_from) do update set effective_until=null;
  end if;
  return new;
end;
$$;

drop trigger if exists attach_approved_provider_menu_to_packages on public.provider_menus;
create trigger attach_approved_provider_menu_to_packages
after insert or update of status on public.provider_menus
for each row execute function public.attach_approved_provider_menu_to_packages();

-- Customer marketplace: the final WHERE condition is an independent safety net.
create or replace function public.customer_marketplace(target_pincode text)
returns table(provider_id uuid,display_name text,description text,dietary_type text,locality text,city text,packages jsonb,weekly_menu jsonb,primary_photo_path text)
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
      'photo_path',(select media.storage_path from public.provider_media media where media.provider_id=p.id
        and media.menu_item_id=item.id and media.status='APPROVED' order by media.is_primary desc,media.display_order,media.created_at desc limit 1)
    ) order by choice.display_order,item.name) items
    from lateral(select candidate.id from public.provider_menus candidate where candidate.provider_id=p.id and candidate.status='APPROVED'
      and candidate.valid_from<=current_date and(candidate.valid_until is null or candidate.valid_until>=current_date)
      order by candidate.valid_from desc,candidate.updated_at desc limit 1) current_menu
    join public.menu_days day on day.menu_id=current_menu.id and day.is_available
    join public.menu_day_choices choice on choice.menu_day_id=day.id
    join public.menu_items item on item.id=choice.menu_item_id and item.status='APPROVED'
    group by day.day_of_week,day.meal_slot) rows),'[]'::jsonb),
(select media.storage_path from public.provider_media media where media.provider_id=p.id and media.status='APPROVED'
  and media.media_type in('PROVIDER_LOGO','OWNER_PROFILE','PACKAGE_COVER','MEAL')
  order by media.is_primary desc,media.display_order,media.created_at desc limit 1)
from public.providers p
join public.provider_service_areas area on area.provider_id=p.id and area.pincode=target_pincode and area.status='APPROVED'
  and(area.effective_from is null or area.effective_from<=current_date) and(area.effective_until is null or area.effective_until>=current_date)
join public.pincodes pc on pc.code=area.pincode and pc.is_enabled
where p.status='ACTIVE' and public.provider_customer_catalogue_ready(p.id)
  and exists(select 1 from public.packages pkg where pkg.provider_id=p.id and pkg.is_active and exists(
    select 1 from public.package_price_versions price where price.package_id=pkg.id and price.status='APPROVED'
      and price.effective_from<=now() and(price.effective_until is null or price.effective_until>now())))
order by p.display_name;
$$;

revoke all on function public.customer_marketplace(text) from public;
grant execute on function public.customer_marketplace(text) to anon,authenticated;

comment on function public.provider_customer_catalogue_ready(uuid) is
'Customer visibility guard: every meal slot required by active packages must have seven approved main-course days.';
