-- Per-device Firebase Cloud Messaging registrations for customer and provider apps.
create table if not exists public.push_device_tokens (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  token text not null unique check(length(token) between 20 and 4096),
  app_kind text not null check(app_kind in ('CUSTOMER','PROVIDER')),
  platform text not null default 'ANDROID' check(platform in ('ANDROID','IOS','WEB')),
  enabled boolean not null default true,
  last_seen_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists push_device_tokens_user_idx
  on public.push_device_tokens(user_id,app_kind) where enabled;
alter table public.push_device_tokens enable row level security;

drop policy if exists push_device_tokens_own_read on public.push_device_tokens;
create policy push_device_tokens_own_read on public.push_device_tokens for select to authenticated
  using(user_id=auth.uid());

create or replace function public.register_push_device(target_token text,target_app text,target_platform text default 'ANDROID')
returns jsonb language plpgsql security definer set search_path=public as $$
declare normalized_token text:=trim(coalesce(target_token,'')); normalized_app text:=upper(trim(coalesce(target_app,''))); normalized_platform text:=upper(trim(coalesce(target_platform,'ANDROID'))); row_id uuid;
begin
  if auth.uid() is null then raise exception 'Authentication required' using errcode='42501'; end if;
  if length(normalized_token)<20 or length(normalized_token)>4096 then raise exception 'Invalid push token'; end if;
  if normalized_app not in ('CUSTOMER','PROVIDER') then raise exception 'Invalid app kind'; end if;
  if normalized_platform not in ('ANDROID','IOS','WEB') then raise exception 'Invalid platform'; end if;
  insert into public.push_device_tokens(user_id,token,app_kind,platform,enabled,last_seen_at,updated_at)
  values(auth.uid(),normalized_token,normalized_app,normalized_platform,true,now(),now())
  on conflict(token) do update set user_id=excluded.user_id,app_kind=excluded.app_kind,platform=excluded.platform,
    enabled=true,last_seen_at=now(),updated_at=now()
  returning id into row_id;
  return jsonb_build_object('registered',true,'id',row_id);
end; $$;

create or replace function public.unregister_push_device(target_token text)
returns jsonb language plpgsql security definer set search_path=public as $$
declare affected integer;
begin
  if auth.uid() is null then raise exception 'Authentication required' using errcode='42501'; end if;
  update public.push_device_tokens set enabled=false,updated_at=now()
  where user_id=auth.uid() and token=trim(coalesce(target_token,''));
  get diagnostics affected=row_count;
  return jsonb_build_object('unregistered',affected>0);
end; $$;

revoke all on public.push_device_tokens from anon,authenticated;
grant select on public.push_device_tokens to authenticated;
revoke all on function public.register_push_device(text,text,text),public.unregister_push_device(text) from public;
grant execute on function public.register_push_device(text,text,text),public.unregister_push_device(text) to authenticated;
