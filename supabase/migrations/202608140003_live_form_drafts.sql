-- Resumable, cross-device drafts for long admin/provider forms.
create table if not exists public.provider_form_drafts (
  id uuid primary key default gen_random_uuid(),
  owner_user_id uuid not null references auth.users(id) on delete cascade,
  provider_id uuid references public.providers(id) on delete cascade,
  draft_key text not null,
  form_scope text not null,
  payload jsonb not null default '{}'::jsonb,
  status text not null default 'IN_PROGRESS' check(status in ('IN_PROGRESS','SUBMITTED','DISCARDED')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique(draft_key)
);
alter table public.provider_form_drafts enable row level security;
create policy form_drafts_owner_access on public.provider_form_drafts for all to authenticated
using(owner_user_id=auth.uid()) with check(owner_user_id=auth.uid());

create or replace function public.save_provider_form_draft(target_provider_id uuid,scope_name text,draft_payload jsonb)
returns public.provider_form_drafts language plpgsql security definer set search_path=public as $$
declare result public.provider_form_drafts; key_value text;
begin
  if auth.uid() is null then raise exception 'Authentication required'; end if;
  if target_provider_id is not null and not (public.has_role('ADMIN') or public.has_role('OPERATIONS') or public.is_provider_member(target_provider_id)) then raise exception 'Not allowed to edit this provider'; end if;
  key_value:=scope_name||':'||coalesce(target_provider_id::text,'new:'||auth.uid()::text);
  insert into public.provider_form_drafts(owner_user_id,provider_id,draft_key,form_scope,payload,status)
  values(auth.uid(),target_provider_id,key_value,scope_name,coalesce(draft_payload,'{}'::jsonb),'IN_PROGRESS')
  on conflict(draft_key) do update set payload=excluded.payload,provider_id=excluded.provider_id,owner_user_id=auth.uid(),status='IN_PROGRESS',updated_at=now()
  returning * into result;
  return result;
end; $$;

create or replace function public.get_provider_form_draft(target_provider_id uuid,scope_name text)
returns public.provider_form_drafts language plpgsql security definer set search_path=public as $$
declare result public.provider_form_drafts; key_value text;
begin
  if auth.uid() is null then raise exception 'Authentication required'; end if;
  if target_provider_id is not null and not (public.has_role('ADMIN') or public.has_role('OPERATIONS') or public.is_provider_member(target_provider_id)) then raise exception 'Not allowed to read this provider'; end if;
  key_value:=scope_name||':'||coalesce(target_provider_id::text,'new:'||auth.uid()::text);
  select * into result from public.provider_form_drafts where draft_key=key_value and status='IN_PROGRESS';
  return result;
end; $$;
revoke all on function public.save_provider_form_draft(uuid,text,jsonb),public.get_provider_form_draft(uuid,text) from public;
grant execute on function public.save_provider_form_draft(uuid,text,jsonb),public.get_provider_form_draft(uuid,text) to authenticated;
