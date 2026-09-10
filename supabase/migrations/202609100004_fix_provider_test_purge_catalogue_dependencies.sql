-- Provider menus and customer weekly selections both point to menu_items with
-- ON DELETE RESTRICT. Remove those test-only selections before the provider's
-- cascading delete reaches menu_items.

create or replace function public.cleanup_test_provider_catalogue_dependencies()
returns trigger
language plpgsql
security definer
set search_path=public
as $$
begin
  if current_setting('app.zomeal_test_purge',true)='on' and public.can_manage_accounts() then
    delete from public.customer_weekly_menu_selections selection
    using public.menu_items item
    where selection.menu_item_id=item.id and item.provider_id=old.id;

    delete from public.menu_day_choices choice
    using public.menu_items item
    where choice.menu_item_id=item.id and item.provider_id=old.id;
  end if;
  return old;
end;
$$;

drop trigger if exists providers_test_purge_catalogue_dependencies on public.providers;
create trigger providers_test_purge_catalogue_dependencies
before delete on public.providers
for each row execute function public.cleanup_test_provider_catalogue_dependencies();

revoke all on function public.cleanup_test_provider_catalogue_dependencies() from public,anon,authenticated;

