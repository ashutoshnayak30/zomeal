-- Retire deterministic catalogue fixtures and prevent incomplete, duplicate or
-- test-labelled providers from being published to customers. Inactivation keeps
-- any linked subscription or finance history available for audit.

update public.providers
set status='INACTIVE',approved_by=null,approved_at=null,updated_at=now()
where id in (
  md5('zomeal-test-provider-1')::uuid,
  md5('zomeal-test-provider-2')::uuid,
  md5('zomeal-test-provider-3')::uuid,
  md5('zomeal-test-provider-4')::uuid,
  md5('zomeal-test-provider-5')::uuid
);

-- Preserve non-seeded records and their audit/finance history, but remove
-- obvious test providers from every customer-facing path.
update public.providers
set status='INACTIVE',approved_by=null,approved_at=null,updated_at=now()
where status<>'INACTIVE'
  and (
    lower(trim(display_name)) ~ '^(test|testing|demo|sample)(\s|$)'
    or lower(trim(legal_name)) ~ '^(test|testing|demo|sample)(\s|$)'
    or lower(slug) like 'test-%'
  );

-- Keep the best canonical row when the same phone, or the same name and
-- business pincode, was submitted more than once. Nothing is deleted because
-- older records can be referenced by subscriptions or financial journals.
with ranked as (
  select id,row_number() over(
    partition by regexp_replace(coalesce(support_phone,''),'[^0-9]','','g')
    order by case status when 'ACTIVE' then 0 when 'PENDING_APPROVAL' then 1 else 2 end,
      approved_at desc nulls last,created_at desc,id
  ) as position
  from public.providers
  where length(regexp_replace(coalesce(support_phone,''),'[^0-9]','','g'))>=10
)
update public.providers provider
set status='INACTIVE',approved_by=null,approved_at=null,updated_at=now()
from ranked
where provider.id=ranked.id and ranked.position>1 and provider.status<>'INACTIVE';

with ranked as (
  select id,row_number() over(
    partition by lower(trim(display_name)),coalesce(business_pincode,'')
    order by case status when 'ACTIVE' then 0 when 'PENDING_APPROVAL' then 1 else 2 end,
      approved_at desc nulls last,created_at desc,id
  ) as position
  from public.providers
  where nullif(trim(display_name),'') is not null
)
update public.providers provider
set status='INACTIVE',approved_by=null,approved_at=null,updated_at=now()
from ranked
where provider.id=ranked.id and ranked.position>1 and provider.status<>'INACTIVE';

create or replace function public.provider_publication_quality_check(target_provider uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path=public
as $$
declare
  provider_record public.providers%rowtype;
  missing text[]:='{}';
  normalized_phone text;
begin
  select * into provider_record from public.providers where id=target_provider;
  if not found then raise exception 'Provider not found'; end if;

  normalized_phone:=right(regexp_replace(coalesce(provider_record.support_phone,''),'[^0-9]','','g'),10);
  if length(trim(provider_record.display_name))<3 then missing:=array_append(missing,'Public provider name'); end if;
  if lower(trim(provider_record.display_name)) ~ '^(test|testing|demo|sample)(\s|$)' then missing:=array_append(missing,'Production provider name'); end if;
  if length(trim(coalesce(provider_record.description,'')))<30 then missing:=array_append(missing,'Customer description (minimum 30 characters)'); end if;
  if normalized_phone !~ '^[6-9][0-9]{9}$' then missing:=array_append(missing,'Valid 10-digit Indian support phone'); end if;
  if not exists(
    select 1 from public.provider_media media
    where media.provider_id=target_provider and media.status='APPROVED'
      and media.media_type in('PROVIDER_LOGO','OWNER_PROFILE','PACKAGE_COVER','MEAL')
  ) then missing:=array_append(missing,'At least one approved customer-visible provider or food photo'); end if;
  if exists(
    select 1 from public.providers other
    where other.id<>target_provider and other.status='ACTIVE'
      and (
        right(regexp_replace(coalesce(other.support_phone,''),'[^0-9]','','g'),10)=normalized_phone
        or (lower(trim(other.display_name))=lower(trim(provider_record.display_name))
          and coalesce(other.business_pincode,'')=coalesce(provider_record.business_pincode,''))
      )
  ) then missing:=array_append(missing,'Duplicate active provider identity'); end if;

  return jsonb_build_object('provider_id',target_provider,'ready',cardinality(missing)=0,'missing_requirements',to_jsonb(missing));
end;
$$;

revoke all on function public.provider_publication_quality_check(uuid) from public,anon;
grant execute on function public.provider_publication_quality_check(uuid) to authenticated;

create or replace function public.enforce_provider_publication_quality()
returns trigger
language plpgsql
set search_path=public
as $$
declare quality jsonb;
begin
  if new.status='ACTIVE' and (tg_op='INSERT' or old.status is distinct from new.status) then
    quality:=public.provider_publication_quality_check(new.id);
    if not coalesce((quality->>'ready')::boolean,false) then
      raise exception 'Provider cannot be activated: %',quality->'missing_requirements';
    end if;
  end if;
  return new;
end;
$$;

drop trigger if exists enforce_provider_publication_quality on public.providers;
create trigger enforce_provider_publication_quality
before insert or update of status on public.providers
for each row execute function public.enforce_provider_publication_quality();

-- Extend the existing seven-day menu gate with the minimum public profile.
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
  select menu.id from public.provider_menus menu
  where menu.provider_id=target_provider and menu.status='APPROVED'
    and menu.valid_from<=current_date and(menu.valid_until is null or menu.valid_until>=current_date)
  order by menu.valid_from desc,menu.updated_at desc limit 1
), coverage as (
  select day.meal_slot,count(distinct day.day_of_week)::integer as covered_days
  from current_menu menu
  join public.menu_days day on day.menu_id=menu.id and day.is_available
  where exists(
    select 1 from public.menu_day_choices choice
    join public.menu_items item on item.id=choice.menu_item_id
    where choice.menu_day_id=day.id and choice.choice_group='MAIN_COURSE'
      and item.status='APPROVED' and nullif(trim(item.name),'') is not null
  )
  group by day.meal_slot
), quality as (
  select public.provider_publication_quality_check(target_provider) result
)
select
  coalesce((quality.result->>'ready')::boolean,false)
  and exists(select 1 from current_menu)
  and (not requirements.needs_lunch or coalesce((select covered_days from coverage where meal_slot='LUNCH'),0)=7)
  and (not requirements.needs_dinner or coalesce((select covered_days from coverage where meal_slot='DINNER'),0)=7)
  and (requirements.needs_lunch or requirements.needs_dinner)
from requirements cross join quality;
$$;

revoke all on function public.provider_customer_catalogue_ready(uuid) from public,anon;
grant execute on function public.provider_customer_catalogue_ready(uuid) to authenticated;

comment on function public.provider_customer_catalogue_ready(uuid) is
'Customer visibility guard: complete approved seven-day menu plus a real public identity, description and approved customer-visible photo.';

notify pgrst,'reload schema';
