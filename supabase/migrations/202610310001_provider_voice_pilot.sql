-- Opt-in pilot only. No expense, wallet, stock or payroll mutations.
create table public.provider_voice_pilot (
 provider_id uuid primary key references public.providers(id),
 enabled boolean not null default false,
 expires_at timestamptz not null,
 daily_requests integer not null default 20 check(daily_requests between 1 and 100),
 daily_seconds integer not null default 600 check(daily_seconds between 1 and 3600)
);
create table public.provider_voice_usage (
 provider_id uuid not null references public.providers(id),
 usage_day date not null,
 requests integer not null default 0,
 seconds integer not null default 0,
 primary key(provider_id,usage_day)
);
alter table public.provider_voice_pilot enable row level security;
alter table public.provider_voice_usage enable row level security;
revoke all on public.provider_voice_pilot,public.provider_voice_usage from public,anon,authenticated;

create function public.provider_voice_reserve(request_id uuid,audio_seconds integer)
returns jsonb language plpgsql security definer set search_path=public as $$
declare p uuid; settings public.provider_voice_pilot; used public.provider_voice_usage;
 d date:=(now() at time zone 'Asia/Kolkata')::date;
begin
 if auth.uid() is null or coalesce((auth.jwt()->>'is_anonymous')::boolean,false) then
  return jsonb_build_object('allowed',false,'reason','ACCESS');
 end if;
 if request_id is null or audio_seconds is null or audio_seconds not between 1 and 60 then
  raise exception 'Invalid voice reservation';
 end if;
 -- Same ownership semantics as the existing kitchen book.
 begin p:=public.kitchen_book_provider(); exception when others then
  return jsonb_build_object('allowed',false,'reason','ACCESS'); end;
 if not exists(select 1 from public.providers where id=p and status::text='ACTIVE') then
  return jsonb_build_object('allowed',false,'reason','ACCESS');
 end if;
 select * into settings from public.provider_voice_pilot where provider_id=p for update;
 if not found or not settings.enabled or settings.expires_at<=now() then
  return jsonb_build_object('allowed',false,'reason','ACCESS');
 end if;
 -- Serialize by kitchen, shared across owners, managers and Cloud Run instances.
 insert into public.provider_voice_usage(provider_id,usage_day) values(p,d) on conflict do nothing;
 select * into used from public.provider_voice_usage where provider_id=p and usage_day=d for update;
 if used.requests>=settings.daily_requests or used.seconds+audio_seconds>settings.daily_seconds then
  return jsonb_build_object('allowed',false,'reason','LIMIT');
 end if;
 update public.provider_voice_usage set requests=requests+1,seconds=seconds+audio_seconds where provider_id=p and usage_day=d;
 return jsonb_build_object('allowed',true,'request_id',request_id,'remaining_requests',settings.daily_requests-used.requests-1);
end $$;
revoke all on function public.provider_voice_reserve(uuid,integer) from public,anon;
grant execute on function public.provider_voice_reserve(uuid,integer) to authenticated;
