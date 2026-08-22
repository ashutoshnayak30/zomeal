-- Publishing a replacement photo must rotate the primary flag atomically.
-- A provider request can contain several photos for the same dish/scope; the
-- last approved primary wins and earlier photos remain approved gallery images.

create or replace function public.rotate_primary_provider_media()
returns trigger
language plpgsql
set search_path=public
as $$
begin
  if new.status='APPROVED' and new.is_primary then
    -- A single request may upload several images for the same scope and mark
    -- more than one primary. Deterministically retain only its newest one.
    if new.change_request_id is not null and exists(
      select 1 from public.provider_media sibling
      where sibling.id<>new.id
        and sibling.change_request_id=new.change_request_id
        and sibling.provider_id=new.provider_id
        and sibling.media_type=new.media_type
        and sibling.package_id is not distinct from new.package_id
        and sibling.menu_item_id is not distinct from new.menu_item_id
        and sibling.status in ('PENDING_REVIEW','APPROVED') and sibling.is_primary
        and (sibling.created_at,sibling.id)>(new.created_at,new.id)
    ) then
      new.is_primary=false;
      return new;
    end if;

    update public.provider_media media
    set is_primary=false,updated_at=now()
    where media.id<>new.id
      and media.provider_id=new.provider_id
      and media.media_type=new.media_type
      and media.package_id is not distinct from new.package_id
      and media.menu_item_id is not distinct from new.menu_item_id
      and media.status='APPROVED'
      and media.is_primary;
  end if;
  return new;
end;
$$;

drop trigger if exists rotate_primary_provider_media on public.provider_media;
create trigger rotate_primary_provider_media
before insert or update of status,is_primary on public.provider_media
for each row execute function public.rotate_primary_provider_media();

comment on function public.rotate_primary_provider_media() is
'Atomically demotes an earlier primary provider image before approving its replacement.';
