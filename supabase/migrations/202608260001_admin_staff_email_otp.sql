create table if not exists public.admin_staff_invitations (
  id uuid primary key default gen_random_uuid(),
  email text not null,
  role public.app_role not null,
  status text not null default 'PENDING' check (status in ('PENDING','VERIFIED','CANCELLED','EXPIRED')),
  invited_by uuid not null references auth.users(id),
  invited_at timestamptz not null default now(),
  expires_at timestamptz not null default (now() + interval '24 hours'),
  verified_user_id uuid references auth.users(id),
  verified_at timestamptz,
  constraint admin_staff_invitation_staff_role check (role in ('ADMIN','OPERATIONS','FINANCE'))
);

create unique index if not exists one_pending_staff_invitation_per_email
  on public.admin_staff_invitations (lower(email)) where status='PENDING';

alter table public.admin_staff_invitations enable row level security;

create policy admin_staff_invitations_admin_read
  on public.admin_staff_invitations for select to authenticated
  using (public.has_role('ADMIN'));

revoke all on public.admin_staff_invitations from anon, authenticated;
grant select on public.admin_staff_invitations to authenticated;

comment on table public.admin_staff_invitations is
  'Time-limited staff invitations. Supabase Auth owns and verifies the actual email OTP.';
