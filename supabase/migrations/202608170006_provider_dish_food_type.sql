-- Apply the per-dish food classification saved by provider onboarding.
-- The submission RPC creates menu_items; this trigger makes the draft's explicit
-- Veg / Non-Veg / Vegan choice authoritative for every main-course insert/update.
create or replace function public.apply_provider_dish_food_type()
returns trigger language plpgsql security definer set search_path=public as $$
declare selected_type text;
begin
  if new.category <> 'MAIN_COURSE' then return new; end if;

  select upper(replace(course->>'foodType','-','_')) into selected_type
  from public.provider_form_drafts draft
  cross join lateral jsonb_array_elements(coalesce(draft.payload->'menus','[]'::jsonb)) menu_day
  cross join lateral jsonb_array_elements(
    coalesce(menu_day->'lunch','[]'::jsonb) || coalesce(menu_day->'dinner','[]'::jsonb)
  ) course
  where draft.owner_user_id=auth.uid()
    and draft.form_scope='provider_mobile_onboarding'
    and lower(trim(course->>'name'))=lower(trim(new.name))
  order by draft.updated_at desc
  limit 1;

  if selected_type in ('VEG','NON_VEG','VEGAN') then
    new.dietary_type=selected_type::public.dietary_type;
  end if;
  return new;
end;
$$;

drop trigger if exists menu_items_provider_food_type on public.menu_items;
create trigger menu_items_provider_food_type
before insert or update on public.menu_items
for each row execute function public.apply_provider_dish_food_type();
