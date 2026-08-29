alter table public.admin_staff_invitations
  add column if not exists staff_role text;

update public.admin_staff_invitations
set staff_role = case role
  when 'ADMIN' then 'ADMINISTRATOR'
  when 'FINANCE' then 'FINANCE_MANAGER'
  else 'OPERATIONS_MANAGER'
end
where staff_role is null;

alter table public.admin_staff_invitations
  alter column staff_role set default 'OPERATIONS_MANAGER';

alter table public.admin_staff_invitations
  drop constraint if exists admin_staff_invitation_title;
alter table public.admin_staff_invitations
  add constraint admin_staff_invitation_title check (staff_role in (
    'SUPER_ADMIN','ADMINISTRATOR','OPERATIONS_MANAGER','PROVIDER_ONBOARDING',
    'CATALOGUE_REVIEWER','SERVICE_AREA_MANAGER','CUSTOMER_SUPPORT',
    'FINANCE_MANAGER','FINANCE_EXECUTIVE','AUDITOR'
  ));
alter table public.admin_staff_invitations
  alter column staff_role set not null;

create table if not exists public.admin_staff_profiles (
  user_id uuid primary key references auth.users(id) on delete cascade,
  staff_role text not null,
  assigned_by uuid references auth.users(id),
  assigned_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint admin_staff_profile_role check (staff_role in (
    'SUPER_ADMIN','ADMINISTRATOR','OPERATIONS_MANAGER','PROVIDER_ONBOARDING',
    'CATALOGUE_REVIEWER','SERVICE_AREA_MANAGER','CUSTOMER_SUPPORT',
    'FINANCE_MANAGER','FINANCE_EXECUTIVE','AUDITOR'
  ))
);

insert into public.admin_staff_profiles(user_id,staff_role)
select ur.user_id, case ur.role
  when 'ADMIN' then 'ADMINISTRATOR'
  when 'FINANCE' then 'FINANCE_MANAGER'
  else 'OPERATIONS_MANAGER'
end
from public.user_roles ur
where ur.role in ('ADMIN','OPERATIONS','FINANCE')
on conflict (user_id) do nothing;

alter table public.admin_staff_profiles enable row level security;
drop policy if exists admin_staff_profiles_admin_read on public.admin_staff_profiles;
create policy admin_staff_profiles_admin_read on public.admin_staff_profiles
  for select to authenticated using (public.has_role('ADMIN'));
revoke all on public.admin_staff_profiles from anon, authenticated;
grant select on public.admin_staff_profiles to authenticated;

comment on table public.admin_staff_profiles is
  'Human-readable Zomeal staff responsibilities mapped by the management function to existing database permission groups.';
