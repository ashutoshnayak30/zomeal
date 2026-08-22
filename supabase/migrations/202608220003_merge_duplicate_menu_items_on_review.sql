-- Admin edits may rename a proposed dish to a dish already saved by the same
-- provider. Reuse that canonical dish instead of violating
-- menu_items(provider_id,name), while limiting rewiring to pending change assets.

create or replace function public.merge_duplicate_menu_item_on_review()
returns trigger
language plpgsql
set search_path=public
as $$
declare
  canonical_id uuid;
begin
  if new.name is not distinct from old.name then return new; end if;

  select item.id into canonical_id
  from public.menu_items item
  where item.provider_id=new.provider_id and item.name=new.name and item.id<>old.id
  order by case when item.status='APPROVED' then 0 else 1 end,item.updated_at desc
  limit 1;

  if canonical_id is null then return new; end if;

  -- Apply the reviewed descriptive data to the canonical saved dish. The nested
  -- update does not recurse into a merge because its name is unchanged.
  update public.menu_items
  set description=new.description,category=new.category,dietary_type=new.dietary_type,
      ingredients=new.ingredients,allergen_notes=new.allergen_notes,
      provider_notes=new.provider_notes,updated_at=now()
  where id=canonical_id;

  -- If the canonical dish is already present in the same proposed meal, discard
  -- only the duplicate choice before redirecting all other proposed choices.
  delete from public.menu_day_choices duplicate_choice
  using public.menu_days duplicate_day,public.provider_menus duplicate_menu
  where duplicate_choice.menu_day_id=duplicate_day.id
    and duplicate_day.menu_id=duplicate_menu.id
    and duplicate_menu.status='PENDING_REVIEW'
    and duplicate_choice.menu_item_id=old.id
    and exists(
      select 1 from public.menu_day_choices canonical_choice
      where canonical_choice.menu_day_id=duplicate_choice.menu_day_id
        and canonical_choice.menu_item_id=canonical_id
    );

  update public.menu_day_choices choice
  set menu_item_id=canonical_id
  from public.menu_days day,public.provider_menus menu
  where choice.menu_day_id=day.id and day.menu_id=menu.id
    and menu.status='PENDING_REVIEW' and choice.menu_item_id=old.id;

  update public.provider_media media
  set menu_item_id=canonical_id,updated_at=now()
  where media.menu_item_id=old.id and media.status in ('UPLOADING','PENDING_REVIEW');

  -- Skip the conflicting rename. The now-unreferenced staged row remains as an
  -- audit-safe proposal record and is not customer-visible.
  return null;
end;
$$;

drop trigger if exists merge_duplicate_menu_item_on_review on public.menu_items;
create trigger merge_duplicate_menu_item_on_review
before update of name on public.menu_items
for each row execute function public.merge_duplicate_menu_item_on_review();

comment on function public.merge_duplicate_menu_item_on_review() is
'Reuses an existing provider dish when admin review renames a proposed item to a duplicate name.';
