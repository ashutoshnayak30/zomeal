-- Accept valid E.164 phone numbers created by Supabase Auth. The original
-- expression used a double backslash and therefore rejected a leading '+'.
alter table public.profiles
  drop constraint if exists profiles_phone_format;

alter table public.profiles
  add constraint profiles_phone_format
  check (phone is null or phone ~ '^[+]?[1-9][0-9]{7,14}$');

-- Keep Indian profile numbers in the existing application-wide 10-digit
-- representation while Supabase Auth continues to use E.164 (+91...).
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
  profile_phone text;
begin
  profile_phone := case
    when new.phone ~ '^[+]91[6-9][0-9]{9}$' then right(new.phone, 10)
    else new.phone
  end;

  insert into public.profiles (id, full_name, phone)
  values (
    new.id,
    coalesce(new.raw_user_meta_data ->> 'full_name', ''),
    profile_phone
  )
  on conflict (id) do nothing;

  insert into public.user_roles (user_id, role)
  values (new.id, 'CUSTOMER')
  on conflict (user_id, role) do nothing;

  return new;
end;
$$;

comment on function public.handle_new_user() is
'Creates the default customer profile and normalizes Indian E.164 phone numbers to 10 digits.';
