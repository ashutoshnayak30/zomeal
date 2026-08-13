-- Zomeal Phase 2: provider catalogue, weekly menus and moderated media.

create type public.catalogue_status as enum ('DRAFT', 'PENDING_REVIEW', 'APPROVED', 'REJECTED', 'SUSPENDED', 'ARCHIVED');
create type public.menu_item_category as enum ('MAIN_COURSE', 'CARB', 'DAL', 'SIDE', 'SALAD', 'PICKLE', 'DESSERT', 'BEVERAGE', 'OTHER');
create type public.allergen_code as enum ('MILK', 'EGG', 'PEANUT', 'TREE_NUT', 'SOY', 'WHEAT', 'SESAME', 'MUSTARD', 'FISH', 'SHELLFISH', 'OTHER');
create type public.media_type as enum ('PROVIDER_LOGO', 'OWNER_PROFILE', 'KITCHEN', 'PACKAGING', 'PACKAGE_COVER', 'MEAL', 'MENU_ITEM', 'FSSAI_DOCUMENT', 'OTHER_DOCUMENT');
create type public.media_status as enum ('UPLOADING', 'PENDING_REVIEW', 'APPROVED', 'REJECTED', 'SUSPENDED', 'ARCHIVED');

create or replace function public.is_provider_member(target_provider_id uuid)
returns boolean language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from public.provider_members
    where provider_id = target_provider_id and user_id = auth.uid() and is_active
  );
$$;

create table public.menu_items (
  id uuid primary key default gen_random_uuid(),
  provider_id uuid not null references public.providers(id) on delete cascade,
  name text not null,
  description text,
  category public.menu_item_category not null,
  dietary_type public.dietary_type not null,
  status public.catalogue_status not null default 'DRAFT',
  ingredients text[] not null default '{}',
  allergen_notes text,
  provider_notes text,
  submitted_at timestamptz,
  reviewed_by uuid references public.profiles(id),
  reviewed_at timestamptz,
  rejection_reason text,
  created_by uuid not null references public.profiles(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique(provider_id, name),
  constraint menu_item_review_valid check (
    (status not in ('APPROVED','REJECTED','SUSPENDED')) or (reviewed_by is not null and reviewed_at is not null)
  ),
  constraint menu_item_rejection_reason check (status <> 'REJECTED' or nullif(trim(rejection_reason), '') is not null)
);

create table public.menu_item_nutrition (
  menu_item_id uuid primary key references public.menu_items(id) on delete cascade,
  serving_size_grams numeric(8,2),
  calories_kcal numeric(8,2),
  protein_grams numeric(8,2),
  carbohydrates_grams numeric(8,2),
  fat_grams numeric(8,2),
  fibre_grams numeric(8,2),
  sodium_mg numeric(8,2),
  is_provider_estimate boolean not null default true,
  updated_at timestamptz not null default now(),
  constraint nutrition_values_nonnegative check (
    coalesce(serving_size_grams,0) >= 0 and coalesce(calories_kcal,0) >= 0 and
    coalesce(protein_grams,0) >= 0 and coalesce(carbohydrates_grams,0) >= 0 and
    coalesce(fat_grams,0) >= 0 and coalesce(fibre_grams,0) >= 0 and coalesce(sodium_mg,0) >= 0
  )
);

create table public.menu_item_allergens (
  menu_item_id uuid not null references public.menu_items(id) on delete cascade,
  allergen public.allergen_code not null,
  custom_name text,
  may_contain boolean not null default false,
  primary key(menu_item_id, allergen),
  constraint custom_allergen_named check (allergen <> 'OTHER' or nullif(trim(custom_name), '') is not null)
);

create table public.provider_menus (
  id uuid primary key default gen_random_uuid(),
  provider_id uuid not null references public.providers(id) on delete cascade,
  name text not null,
  description text,
  status public.catalogue_status not null default 'DRAFT',
  valid_from date not null,
  valid_until date,
  submitted_at timestamptz,
  reviewed_by uuid references public.profiles(id),
  reviewed_at timestamptz,
  rejection_reason text,
  created_by uuid not null references public.profiles(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique(provider_id, name, valid_from),
  constraint provider_menu_dates_valid check (valid_until is null or valid_until >= valid_from),
  constraint provider_menu_review_valid check (
    (status not in ('APPROVED','REJECTED','SUSPENDED')) or (reviewed_by is not null and reviewed_at is not null)
  ),
  constraint provider_menu_rejection_reason check (status <> 'REJECTED' or nullif(trim(rejection_reason), '') is not null)
);

create table public.menu_days (
  id uuid primary key default gen_random_uuid(),
  menu_id uuid not null references public.provider_menus(id) on delete cascade,
  day_of_week smallint not null check(day_of_week between 1 and 7),
  meal_slot public.meal_slot not null,
  is_available boolean not null default true,
  selection_note text,
  unique(menu_id, day_of_week, meal_slot)
);

create table public.menu_day_choices (
  id uuid primary key default gen_random_uuid(),
  menu_day_id uuid not null references public.menu_days(id) on delete cascade,
  menu_item_id uuid not null references public.menu_items(id) on delete restrict,
  choice_group public.menu_item_category not null,
  is_default boolean not null default false,
  is_required boolean not null default true,
  is_changeable boolean not null default true,
  min_select smallint not null default 0 check(min_select >= 0),
  max_select smallint not null default 1 check(max_select > 0),
  display_order smallint not null default 0,
  unique(menu_day_id, menu_item_id),
  constraint selection_range_valid check(min_select <= max_select)
);

create table public.package_menus (
  package_id uuid not null references public.packages(id) on delete cascade,
  menu_id uuid not null references public.provider_menus(id) on delete cascade,
  effective_from date not null,
  effective_until date,
  primary key(package_id, menu_id, effective_from),
  constraint package_menu_dates_valid check(effective_until is null or effective_until >= effective_from)
);

create table public.customer_weekly_menu_templates (
  id uuid primary key default gen_random_uuid(),
  customer_id uuid not null references public.profiles(id) on delete cascade,
  package_id uuid not null references public.packages(id) on delete cascade,
  name text not null default 'My weekly menu',
  dietary_preference public.dietary_type not null,
  is_active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.customer_weekly_menu_selections (
  template_id uuid not null references public.customer_weekly_menu_templates(id) on delete cascade,
  day_of_week smallint not null check(day_of_week between 1 and 7),
  meal_slot public.meal_slot not null,
  choice_group public.menu_item_category not null,
  menu_item_id uuid not null references public.menu_items(id) on delete restrict,
  created_at timestamptz not null default now(),
  primary key(template_id, day_of_week, meal_slot, choice_group, menu_item_id)
);

create table public.provider_media (
  id uuid primary key default gen_random_uuid(),
  provider_id uuid not null references public.providers(id) on delete cascade,
  media_type public.media_type not null,
  status public.media_status not null default 'UPLOADING',
  storage_bucket text not null,
  storage_path text not null,
  mime_type text not null,
  size_bytes bigint not null,
  width_px integer,
  height_px integer,
  alt_text text,
  package_id uuid references public.packages(id) on delete set null,
  menu_item_id uuid references public.menu_items(id) on delete set null,
  replaces_media_id uuid references public.provider_media(id) on delete set null,
  is_primary boolean not null default false,
  display_order smallint not null default 0,
  uploaded_by uuid not null references public.profiles(id),
  submitted_at timestamptz,
  reviewed_by uuid references public.profiles(id),
  reviewed_at timestamptz,
  rejection_reason text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique(storage_bucket, storage_path),
  constraint provider_media_size_valid check(size_bytes between 1 and 5242880),
  constraint provider_media_mime_valid check(mime_type in ('image/jpeg','image/png','image/webp')),
  constraint provider_media_dimensions_valid check(
    (width_px is null and height_px is null) or (width_px > 0 and height_px > 0)
  ),
  constraint provider_media_bucket_valid check(
    (media_type in ('FSSAI_DOCUMENT','OTHER_DOCUMENT') and storage_bucket = 'provider-documents') or
    (media_type not in ('FSSAI_DOCUMENT','OTHER_DOCUMENT') and storage_bucket = 'provider-media')
  ),
  constraint provider_media_review_valid check(
    (status not in ('APPROVED','REJECTED','SUSPENDED')) or (reviewed_by is not null and reviewed_at is not null)
  ),
  constraint provider_media_rejection_reason check(status <> 'REJECTED' or nullif(trim(rejection_reason), '') is not null)
);

create unique index one_approved_primary_media
on public.provider_media(provider_id, media_type, coalesce(package_id, '00000000-0000-0000-0000-000000000000'::uuid), coalesce(menu_item_id, '00000000-0000-0000-0000-000000000000'::uuid))
where status = 'APPROVED' and is_primary;

create index menu_items_provider_status_idx on public.menu_items(provider_id, status);
create index provider_menus_provider_status_idx on public.provider_menus(provider_id, status);
create index menu_days_lookup_idx on public.menu_days(menu_id, day_of_week, meal_slot);
create index provider_media_moderation_idx on public.provider_media(status, submitted_at);
create index provider_media_public_idx on public.provider_media(provider_id, media_type, status, display_order);

create trigger menu_items_set_updated_at before update on public.menu_items for each row execute function public.set_updated_at();
create trigger menu_item_nutrition_set_updated_at before update on public.menu_item_nutrition for each row execute function public.set_updated_at();
create trigger provider_menus_set_updated_at before update on public.provider_menus for each row execute function public.set_updated_at();
create trigger customer_weekly_menu_templates_set_updated_at before update on public.customer_weekly_menu_templates for each row execute function public.set_updated_at();
create trigger provider_media_set_updated_at before update on public.provider_media for each row execute function public.set_updated_at();

alter table public.menu_items enable row level security;
alter table public.menu_item_nutrition enable row level security;
alter table public.menu_item_allergens enable row level security;
alter table public.provider_menus enable row level security;
alter table public.menu_days enable row level security;
alter table public.menu_day_choices enable row level security;
alter table public.package_menus enable row level security;
alter table public.customer_weekly_menu_templates enable row level security;
alter table public.customer_weekly_menu_selections enable row level security;
alter table public.provider_media enable row level security;

create policy approved_menu_items_read on public.menu_items for select using (
  status = 'APPROVED' or public.is_provider_member(provider_id) or public.has_role('ADMIN') or public.has_role('OPERATIONS')
);
create policy provider_menu_items_insert on public.menu_items for insert with check (
  public.is_provider_member(provider_id) and created_by = auth.uid() and status in ('DRAFT','PENDING_REVIEW')
);
create policy provider_menu_items_update on public.menu_items for update using (
  public.is_provider_member(provider_id) and status in ('DRAFT','PENDING_REVIEW','REJECTED')
) with check (public.is_provider_member(provider_id) and status in ('DRAFT','PENDING_REVIEW'));

create policy nutrition_read on public.menu_item_nutrition for select using (
  exists(select 1 from public.menu_items mi where mi.id = menu_item_id and (mi.status = 'APPROVED' or public.is_provider_member(mi.provider_id) or public.has_role('ADMIN') or public.has_role('OPERATIONS')))
);
create policy nutrition_provider_write on public.menu_item_nutrition for all using (
  exists(select 1 from public.menu_items mi where mi.id = menu_item_id and public.is_provider_member(mi.provider_id) and mi.status in ('DRAFT','PENDING_REVIEW','REJECTED'))
) with check (
  exists(select 1 from public.menu_items mi where mi.id = menu_item_id and public.is_provider_member(mi.provider_id) and mi.status in ('DRAFT','PENDING_REVIEW'))
);
create policy allergens_read on public.menu_item_allergens for select using (
  exists(select 1 from public.menu_items mi where mi.id = menu_item_id and (mi.status = 'APPROVED' or public.is_provider_member(mi.provider_id) or public.has_role('ADMIN') or public.has_role('OPERATIONS')))
);
create policy allergens_provider_write on public.menu_item_allergens for all using (
  exists(select 1 from public.menu_items mi where mi.id = menu_item_id and public.is_provider_member(mi.provider_id) and mi.status in ('DRAFT','PENDING_REVIEW','REJECTED'))
) with check (
  exists(select 1 from public.menu_items mi where mi.id = menu_item_id and public.is_provider_member(mi.provider_id) and mi.status in ('DRAFT','PENDING_REVIEW'))
);

create policy approved_provider_menus_read on public.provider_menus for select using (
  status = 'APPROVED' or public.is_provider_member(provider_id) or public.has_role('ADMIN') or public.has_role('OPERATIONS')
);
create policy provider_menus_insert on public.provider_menus for insert with check (
  public.is_provider_member(provider_id) and created_by = auth.uid() and status in ('DRAFT','PENDING_REVIEW')
);
create policy provider_menus_update on public.provider_menus for update using (
  public.is_provider_member(provider_id) and status in ('DRAFT','PENDING_REVIEW','REJECTED')
) with check (public.is_provider_member(provider_id) and status in ('DRAFT','PENDING_REVIEW'));

create policy approved_menu_days_read on public.menu_days for select using (
  exists(select 1 from public.provider_menus pm where pm.id = menu_id and (pm.status = 'APPROVED' or public.is_provider_member(pm.provider_id) or public.has_role('ADMIN') or public.has_role('OPERATIONS')))
);
create policy provider_menu_days_write on public.menu_days for all using (
  exists(select 1 from public.provider_menus pm where pm.id = menu_id and public.is_provider_member(pm.provider_id) and pm.status in ('DRAFT','PENDING_REVIEW','REJECTED'))
) with check (
  exists(select 1 from public.provider_menus pm where pm.id = menu_id and public.is_provider_member(pm.provider_id) and pm.status in ('DRAFT','PENDING_REVIEW'))
);
create policy approved_menu_choices_read on public.menu_day_choices for select using (
  exists(select 1 from public.menu_days md join public.provider_menus pm on pm.id = md.menu_id where md.id = menu_day_id and (pm.status = 'APPROVED' or public.is_provider_member(pm.provider_id) or public.has_role('ADMIN') or public.has_role('OPERATIONS')))
);
create policy provider_menu_choices_write on public.menu_day_choices for all using (
  exists(select 1 from public.menu_days md join public.provider_menus pm on pm.id = md.menu_id where md.id = menu_day_id and public.is_provider_member(pm.provider_id) and pm.status in ('DRAFT','PENDING_REVIEW','REJECTED'))
) with check (
  exists(select 1 from public.menu_days md join public.provider_menus pm on pm.id = md.menu_id where md.id = menu_day_id and public.is_provider_member(pm.provider_id) and pm.status in ('DRAFT','PENDING_REVIEW'))
);
create policy approved_package_menus_read on public.package_menus for select using (
  exists(select 1 from public.provider_menus pm where pm.id = menu_id and (pm.status = 'APPROVED' or public.is_provider_member(pm.provider_id) or public.has_role('ADMIN') or public.has_role('OPERATIONS')))
);
create policy provider_package_menus_write on public.package_menus for all using (
  exists(select 1 from public.provider_menus pm where pm.id = menu_id and public.is_provider_member(pm.provider_id) and pm.status in ('DRAFT','PENDING_REVIEW','REJECTED'))
) with check (
  exists(select 1 from public.provider_menus pm where pm.id = menu_id and public.is_provider_member(pm.provider_id) and pm.status in ('DRAFT','PENDING_REVIEW'))
);

create policy customer_templates_own on public.customer_weekly_menu_templates for all
using(customer_id = auth.uid()) with check(customer_id = auth.uid());
create policy customer_template_selections_own on public.customer_weekly_menu_selections for all
using(exists(select 1 from public.customer_weekly_menu_templates t where t.id = template_id and t.customer_id = auth.uid()))
with check(exists(select 1 from public.customer_weekly_menu_templates t where t.id = template_id and t.customer_id = auth.uid()));

create policy moderated_provider_media_read on public.provider_media for select using (
  (status = 'APPROVED' and media_type not in ('FSSAI_DOCUMENT','OTHER_DOCUMENT')) or
  public.is_provider_member(provider_id) or public.has_role('ADMIN') or public.has_role('OPERATIONS')
);
create policy provider_media_insert on public.provider_media for insert with check (
  public.is_provider_member(provider_id) and uploaded_by = auth.uid() and status in ('UPLOADING','PENDING_REVIEW')
);
create policy provider_media_update on public.provider_media for update using (
  public.is_provider_member(provider_id) and status in ('UPLOADING','PENDING_REVIEW','REJECTED')
) with check(public.is_provider_member(provider_id) and status in ('UPLOADING','PENDING_REVIEW'));

-- Private buckets. Customer access is mediated by provider_media approval status.
insert into storage.buckets(id, name, public, file_size_limit, allowed_mime_types)
values
 ('provider-media','provider-media',false,5242880,array['image/jpeg','image/png','image/webp']),
 ('provider-documents','provider-documents',false,5242880,array['image/jpeg','image/png','image/webp'])
on conflict(id) do update set public = excluded.public, file_size_limit = excluded.file_size_limit, allowed_mime_types = excluded.allowed_mime_types;

create policy provider_storage_insert on storage.objects for insert to authenticated with check (
  bucket_id in ('provider-media','provider-documents') and exists(
    select 1 from public.provider_members pm
    where pm.provider_id::text = (storage.foldername(name))[1] and pm.user_id = auth.uid() and pm.is_active
  )
);
create policy provider_storage_own_read on storage.objects for select to authenticated using (
  bucket_id in ('provider-media','provider-documents') and exists(
    select 1 from public.provider_members pm
    where pm.provider_id::text = (storage.foldername(name))[1] and pm.user_id = auth.uid() and pm.is_active
  )
);
create policy provider_storage_pending_update on storage.objects for update to authenticated using (
  bucket_id in ('provider-media','provider-documents') and exists(
    select 1 from public.provider_members pm
    where pm.provider_id::text = (storage.foldername(name))[1] and pm.user_id = auth.uid() and pm.is_active
  )
) with check (
  bucket_id in ('provider-media','provider-documents') and exists(
    select 1 from public.provider_members pm
    where pm.provider_id::text = (storage.foldername(name))[1] and pm.user_id = auth.uid() and pm.is_active
  )
);

-- Approved public media is readable only when its object has an approved metadata row.
create policy approved_media_object_read on storage.objects for select using (
  bucket_id = 'provider-media' and exists(
    select 1 from public.provider_media pm
    where pm.storage_bucket = bucket_id and pm.storage_path = name and pm.status = 'APPROVED'
  )
);

-- Approval, rejection, suspension, deletion and document access are server/admin operations.
-- Service-role workflows must write corresponding audit_logs entries.
