-- Zomeal Phase 1: identity, providers, serviceability, packages, pricing and capacity.
-- Monetary values are integer paise. Timestamps are UTC timestamptz values.

create extension if not exists pgcrypto;

create type public.app_role as enum ('CUSTOMER', 'PROVIDER', 'OPERATIONS', 'FINANCE', 'ADMIN');
create type public.provider_status as enum ('DRAFT', 'PENDING_APPROVAL', 'ACTIVE', 'SUSPENDED', 'EXIT_PENDING', 'INACTIVE');
create type public.approval_status as enum ('DRAFT', 'PENDING', 'APPROVED', 'REJECTED', 'CANCELLED');
create type public.change_request_type as enum ('SERVICE_AREA_ADD', 'SERVICE_AREA_REMOVE', 'PRICE_CHANGE', 'CAPACITY_CHANGE', 'TEMPORARY_CLOSURE', 'PERMANENT_CLOSURE');
create type public.meal_slot as enum ('LUNCH', 'DINNER');
create type public.package_kind as enum ('LUNCH_ONLY', 'DINNER_ONLY', 'LUNCH_AND_DINNER');
create type public.dietary_type as enum ('VEG', 'NON_VEG', 'BOTH');

create table public.profiles (
    id uuid primary key references auth.users(id) on delete cascade,
    full_name text not null default '',
    phone text,
    avatar_url text,
    is_active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint profiles_phone_format check (phone is null or phone ~ '^\\+?[1-9][0-9]{7,14}$')
);

create unique index profiles_phone_unique on public.profiles(phone) where phone is not null;

create table public.user_roles (
    user_id uuid not null references public.profiles(id) on delete cascade,
    role public.app_role not null,
    granted_by uuid references public.profiles(id),
    granted_at timestamptz not null default now(),
    primary key (user_id, role)
);

create table public.providers (
    id uuid primary key default gen_random_uuid(),
    legal_name text not null,
    display_name text not null,
    slug text not null unique,
    status public.provider_status not null default 'DRAFT',
    dietary_type public.dietary_type not null,
    description text,
    fssai_number text,
    tax_identifier text,
    support_phone text,
    support_email text,
    approved_by uuid references public.profiles(id),
    approved_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint providers_approval_consistency check (
        (status <> 'ACTIVE') or (approved_by is not null and approved_at is not null)
    )
);

create table public.provider_members (
    provider_id uuid not null references public.providers(id) on delete cascade,
    user_id uuid not null references public.profiles(id) on delete cascade,
    member_role text not null default 'OWNER',
    is_active boolean not null default true,
    created_at timestamptz not null default now(),
    primary key (provider_id, user_id)
);

create table public.pincodes (
    code text primary key,
    locality text,
    city text not null,
    district text,
    state text not null,
    country_code char(2) not null default 'IN',
    is_enabled boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint pincodes_indian_format check (code ~ '^[1-9][0-9]{5}$')
);

create table public.provider_service_areas (
    id uuid primary key default gen_random_uuid(),
    provider_id uuid not null references public.providers(id) on delete cascade,
    pincode text not null references public.pincodes(code),
    status public.approval_status not null default 'PENDING',
    delivery_radius_km numeric(6,2),
    requested_by uuid references public.profiles(id),
    approved_by uuid references public.profiles(id),
    approved_at timestamptz,
    effective_from date,
    effective_until date,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (provider_id, pincode),
    constraint service_area_radius_positive check (delivery_radius_km is null or delivery_radius_km > 0),
    constraint service_area_dates_valid check (effective_until is null or effective_from is null or effective_until >= effective_from),
    constraint service_area_approval_consistency check (
        (status <> 'APPROVED') or (approved_by is not null and approved_at is not null)
    )
);

create table public.packages (
    id uuid primary key default gen_random_uuid(),
    provider_id uuid not null references public.providers(id) on delete cascade,
    name text not null,
    description text,
    kind public.package_kind not null,
    dietary_type public.dietary_type not null,
    duration_days integer not null default 30,
    is_active boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint packages_duration_positive check (duration_days > 0),
    unique (provider_id, name)
);

create table public.package_price_versions (
    id uuid primary key default gen_random_uuid(),
    package_id uuid not null references public.packages(id) on delete cascade,
    version integer not null,
    total_price_paise bigint not null,
    lunch_value_paise bigint not null default 0,
    dinner_value_paise bigint not null default 0,
    status public.approval_status not null default 'PENDING',
    effective_from timestamptz,
    effective_until timestamptz,
    requested_by uuid references public.profiles(id),
    approved_by uuid references public.profiles(id),
    approved_at timestamptz,
    created_at timestamptz not null default now(),
    unique (package_id, version),
    constraint package_prices_nonnegative check (
        total_price_paise >= 0 and lunch_value_paise >= 0 and dinner_value_paise >= 0
    ),
    constraint package_price_components_match check (total_price_paise = lunch_value_paise + dinner_value_paise),
    constraint package_price_dates_valid check (effective_until is null or effective_from is null or effective_until > effective_from),
    constraint package_price_approval_consistency check (
        (status <> 'APPROVED') or (approved_by is not null and approved_at is not null and effective_from is not null)
    )
);

create unique index one_current_approved_price_per_package
    on public.package_price_versions(package_id)
    where status = 'APPROVED' and effective_until is null;

create table public.provider_capacity (
    id uuid primary key default gen_random_uuid(),
    provider_id uuid not null references public.providers(id) on delete cascade,
    pincode text not null references public.pincodes(code),
    service_date date not null,
    meal_slot public.meal_slot not null,
    capacity_limit integer not null,
    reserved_count integer not null default 0,
    confirmed_count integer not null default 0,
    is_available boolean not null default true,
    updated_by uuid references public.profiles(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (provider_id, pincode, service_date, meal_slot),
    constraint capacity_counts_valid check (
        capacity_limit >= 0 and reserved_count >= 0 and confirmed_count >= 0
        and reserved_count + confirmed_count <= capacity_limit
    )
);

create table public.provider_change_requests (
    id uuid primary key default gen_random_uuid(),
    provider_id uuid not null references public.providers(id) on delete cascade,
    request_type public.change_request_type not null,
    status public.approval_status not null default 'PENDING',
    requested_payload jsonb not null default '{}'::jsonb,
    reason text,
    requested_by uuid not null references public.profiles(id),
    reviewed_by uuid references public.profiles(id),
    reviewed_at timestamptz,
    review_note text,
    requested_at timestamptz not null default now(),
    constraint provider_change_review_consistency check (
        (status not in ('APPROVED', 'REJECTED')) or (reviewed_by is not null and reviewed_at is not null)
    )
);

create table public.platform_settings (
    id uuid primary key default gen_random_uuid(),
    setting_key text not null,
    value jsonb not null,
    effective_from timestamptz not null,
    effective_until timestamptz,
    created_by uuid references public.profiles(id),
    created_at timestamptz not null default now(),
    unique (setting_key, effective_from),
    constraint platform_setting_dates_valid check (effective_until is null or effective_until > effective_from)
);

create table public.audit_logs (
    id bigint generated always as identity primary key,
    actor_id uuid references public.profiles(id),
    action text not null,
    entity_type text not null,
    entity_id text,
    before_data jsonb,
    after_data jsonb,
    metadata jsonb not null default '{}'::jsonb,
    occurred_at timestamptz not null default now()
);

create index providers_status_idx on public.providers(status);
create index provider_service_areas_pincode_status_idx on public.provider_service_areas(pincode, status);
create index packages_provider_active_idx on public.packages(provider_id, is_active);
create index provider_capacity_lookup_idx on public.provider_capacity(pincode, service_date, meal_slot, is_available);
create index provider_change_requests_queue_idx on public.provider_change_requests(status, requested_at);
create index audit_logs_entity_idx on public.audit_logs(entity_type, entity_id, occurred_at desc);

create or replace function public.set_updated_at()
returns trigger
language plpgsql
security invoker
set search_path = public
as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

create trigger profiles_set_updated_at before update on public.profiles
for each row execute function public.set_updated_at();
create trigger providers_set_updated_at before update on public.providers
for each row execute function public.set_updated_at();
create trigger pincodes_set_updated_at before update on public.pincodes
for each row execute function public.set_updated_at();
create trigger provider_service_areas_set_updated_at before update on public.provider_service_areas
for each row execute function public.set_updated_at();
create trigger packages_set_updated_at before update on public.packages
for each row execute function public.set_updated_at();
create trigger provider_capacity_set_updated_at before update on public.provider_capacity
for each row execute function public.set_updated_at();

create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    insert into public.profiles (id, full_name, phone)
    values (new.id, coalesce(new.raw_user_meta_data ->> 'full_name', ''), new.phone)
    on conflict (id) do nothing;

    insert into public.user_roles (user_id, role)
    values (new.id, 'CUSTOMER')
    on conflict (user_id, role) do nothing;
    return new;
end;
$$;

create trigger on_auth_user_created
after insert on auth.users
for each row execute function public.handle_new_user();

create or replace function public.has_role(required_role public.app_role)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
    select exists (
        select 1 from public.user_roles
        where user_id = auth.uid() and role = required_role
    );
$$;

alter table public.profiles enable row level security;
alter table public.user_roles enable row level security;
alter table public.providers enable row level security;
alter table public.provider_members enable row level security;
alter table public.pincodes enable row level security;
alter table public.provider_service_areas enable row level security;
alter table public.packages enable row level security;
alter table public.package_price_versions enable row level security;
alter table public.provider_capacity enable row level security;
alter table public.provider_change_requests enable row level security;
alter table public.platform_settings enable row level security;
alter table public.audit_logs enable row level security;

create policy profiles_read_own on public.profiles for select using (id = auth.uid() or public.has_role('ADMIN'));
create policy profiles_update_own on public.profiles for update using (id = auth.uid()) with check (id = auth.uid());
create policy roles_read_own on public.user_roles for select using (user_id = auth.uid() or public.has_role('ADMIN'));

create policy active_providers_public_read on public.providers for select using (status = 'ACTIVE' or public.has_role('ADMIN'));
create policy enabled_pincodes_authenticated_read on public.pincodes for select to authenticated using (is_enabled or public.has_role('ADMIN'));
create policy approved_service_areas_read on public.provider_service_areas for select using (status = 'APPROVED' or public.has_role('ADMIN'));
create policy active_packages_read on public.packages for select using (is_active or public.has_role('ADMIN'));
create policy approved_prices_read on public.package_price_versions for select using (status = 'APPROVED' or public.has_role('ADMIN'));

create policy provider_members_read on public.provider_members for select using (
    user_id = auth.uid() or public.has_role('ADMIN')
);
create policy provider_capacity_member_read on public.provider_capacity for select using (
    public.has_role('ADMIN') or exists (
        select 1 from public.provider_members pm
        where pm.provider_id = provider_capacity.provider_id and pm.user_id = auth.uid() and pm.is_active
    )
);
create policy provider_change_requests_member_read on public.provider_change_requests for select using (
    public.has_role('ADMIN') or public.has_role('OPERATIONS') or exists (
        select 1 from public.provider_members pm
        where pm.provider_id = provider_change_requests.provider_id and pm.user_id = auth.uid() and pm.is_active
    )
);
create policy admin_settings_read on public.platform_settings for select using (
    public.has_role('ADMIN') or public.has_role('FINANCE')
);
create policy admin_audit_read on public.audit_logs for select using (public.has_role('ADMIN'));

-- Privileged writes intentionally have no direct client policies. They must pass
-- through audited server-side functions using the Supabase service role.

insert into public.platform_settings (setting_key, value, effective_from)
values
    ('provider_commission', '{"type":"PERCENT","basis_points":1400}'::jsonb, now()),
    ('customer_platform_fee', '{"type":"PERCENT","basis_points":150}'::jsonb, now()),
    ('subscription_delivery_fee', '{"type":"FIXED","amount_paise":9900,"duration_days":30}'::jsonb, now()),
    ('provider_earning_hold', '{"hours":48}'::jsonb, now()),
    ('customer_pause_allowance', '{"lunches":7,"dinners":7,"per":"SUBSCRIPTION_CYCLE"}'::jsonb, now()),
    ('cancellation_review_sla', '{"hours":48}'::jsonb, now());

