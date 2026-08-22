-- Forward repair for projects where 202608210001 was applied before asset
-- linkage was introduced. Never edit remote migration history; add columns,
-- backfill current drafts and link future staged records automatically.

alter table public.package_price_versions
  add column if not exists change_request_id uuid references public.provider_change_requests(id) on delete set null;
alter table public.provider_menus
  add column if not exists change_request_id uuid references public.provider_change_requests(id) on delete set null;
alter table public.provider_media
  add column if not exists change_request_id uuid references public.provider_change_requests(id) on delete set null;
create index if not exists package_price_change_request_idx on public.package_price_versions(change_request_id);
create index if not exists provider_menu_change_request_idx on public.provider_menus(change_request_id);
create index if not exists provider_media_change_request_idx on public.provider_media(change_request_id);

-- Recover linkage for existing unreviewed full-business submissions.
update public.package_price_versions price set change_request_id=(
  select request.id from public.provider_change_requests request
  join public.packages package on package.provider_id=request.provider_id
  where package.id=price.package_id and request.status='PENDING'
    and request.requested_payload->>'scope'='FULL_BUSINESS_UPDATE'
  order by request.requested_at desc limit 1
) where price.change_request_id is null and price.status='PENDING';

update public.provider_menus menu set change_request_id=(
  select request.id from public.provider_change_requests request
  where request.provider_id=menu.provider_id and request.status='PENDING'
    and request.requested_payload->>'scope'='FULL_BUSINESS_UPDATE'
  order by request.requested_at desc limit 1
) where menu.change_request_id is null and menu.status='PENDING_REVIEW';

update public.provider_media media set change_request_id=(
  select request.id from public.provider_change_requests request
  where request.provider_id=media.provider_id and request.status='PENDING'
    and request.requested_payload->>'scope'='FULL_BUSINESS_UPDATE'
    and request.requested_at<=coalesce(media.submitted_at,media.created_at)
  order by request.requested_at desc limit 1
) where media.change_request_id is null and media.status='PENDING_REVIEW';

create or replace function public.link_provider_change_asset()
returns trigger language plpgsql security definer set search_path=public as $$
declare target_provider uuid;
begin
  if tg_table_name='package_price_versions' then
    select package.provider_id into target_provider from public.packages package where package.id=new.package_id;
  else
    target_provider:=new.provider_id;
  end if;
  if new.change_request_id is null then
    select request.id into new.change_request_id from public.provider_change_requests request
    where request.provider_id=target_provider and request.status='PENDING'
      and request.requested_payload->>'scope'='FULL_BUSINESS_UPDATE'
    order by request.requested_at desc limit 1;
  end if;
  return new;
end;
$$;

drop trigger if exists link_price_to_provider_change on public.package_price_versions;
create trigger link_price_to_provider_change before insert on public.package_price_versions
for each row when(new.status='PENDING') execute function public.link_provider_change_asset();
drop trigger if exists link_menu_to_provider_change on public.provider_menus;
create trigger link_menu_to_provider_change before insert on public.provider_menus
for each row when(new.status='PENDING_REVIEW') execute function public.link_provider_change_asset();

create or replace function public.provider_register_change_media(target_change_request_id uuid,target_provider_id uuid,target_menu_item_name text,
  media_kind public.media_type,object_path text,mime text,bytes bigint,alt_text_value text)
returns uuid language plpgsql security definer set search_path=public as $$
declare saved_id uuid; saved_item uuid;
begin
  if not exists(select 1 from public.provider_change_requests request
    join public.provider_members member on member.provider_id=request.provider_id
    where request.id=target_change_request_id and request.provider_id=target_provider_id and request.status='PENDING'
      and member.user_id=auth.uid() and member.is_active) then
    raise exception 'This pending change request is not available to the signed-in provider';
  end if;
  if media_kind='MENU_ITEM' then
    select item.id into saved_item from public.menu_items item
    join public.menu_day_choices choice on choice.menu_item_id=item.id
    join public.menu_days day on day.id=choice.menu_day_id
    join public.provider_menus menu on menu.id=day.menu_id
    where menu.change_request_id=target_change_request_id and lower(item.name)=lower(trim(target_menu_item_name))
    order by item.created_at desc limit 1;
    if saved_item is null then raise exception 'Save the requested dish before uploading its photo'; end if;
  end if;
  insert into public.provider_media(provider_id,media_type,status,storage_bucket,storage_path,mime_type,size_bytes,alt_text,menu_item_id,
    is_primary,uploaded_by,submitted_at,change_request_id)
  values(target_provider_id,media_kind,'PENDING_REVIEW','provider-media',object_path,mime,bytes,nullif(trim(alt_text_value),''),saved_item,
    true,auth.uid(),now(),target_change_request_id) returning id into saved_id;
  return saved_id;
end;
$$;
revoke all on function public.provider_register_change_media(uuid,uuid,text,public.media_type,text,text,bigint,text) from public;
grant execute on function public.provider_register_change_media(uuid,uuid,text,public.media_type,text,text,bigint,text) to authenticated;

notify pgrst,'reload schema';
