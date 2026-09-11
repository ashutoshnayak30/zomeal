-- Admin-managed home carousel. Images are public marketing assets, never private documents.
create table public.home_banners (
 id uuid primary key default gen_random_uuid(),
 title text not null check(length(trim(title)) between 1 and 100),
 image_path text not null check(image_path ~ '^[a-zA-Z0-9_-]+/[a-zA-Z0-9_-]+\.(webp|jpg|png)$'),
 destination text not null default 'NONE' check(destination in ('NONE','WALLET','REFERRALS','PLAN','PROVIDERS','HTTPS')),
 destination_value text not null default '',
 audience text not null default 'ALL' check(audience in ('ALL','SUBSCRIBED','NO_PLAN','WEEKLY','MONTHLY')),
 sort_order integer not null default 0 check(sort_order between 0 and 9999),
 enabled boolean not null default false,
 starts_at timestamptz,
 ends_at timestamptz,
 created_at timestamptz not null default now(),
 updated_at timestamptz not null default now(),
 check(ends_at is null or starts_at is null or ends_at > starts_at),
 check((destination='HTTPS' and destination_value ~ '^https://[A-Za-z0-9][A-Za-z0-9.-]+(:443)?([/?#][^[:space:]]*)?$' and length(destination_value)<=1000) or (destination<>'HTTPS' and destination_value=''))
);
create table public.home_banner_audit (
 id bigint generated always as identity primary key, banner_id uuid not null,
 actor uuid, action text not null, previous_data jsonb, new_data jsonb, created_at timestamptz not null default now()
);
alter table public.home_banners enable row level security;
alter table public.home_banner_audit enable row level security;
revoke all on public.home_banners,public.home_banner_audit from anon,authenticated;
grant select on public.home_banners,public.home_banner_audit to authenticated;
create policy home_banner_admin_read on public.home_banners for select to authenticated using(public.can_manage_accounts());
create policy home_banner_audit_admin_read on public.home_banner_audit for select to authenticated using(public.can_manage_accounts());

insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
 values('home-banners','home-banners',true,1048576,array['image/webp','image/jpeg','image/png']);
create policy home_banner_admin_upload on storage.objects for insert to authenticated
 with check(bucket_id='home-banners' and public.can_manage_accounts());
create policy home_banner_admin_storage_read on storage.objects for select to authenticated
 using(bucket_id='home-banners' and public.can_manage_accounts());
-- No object overwrite: each replacement uses a new UUID so CDN/device caches cannot show old artwork.

create function public.admin_save_home_banner(payload jsonb, expected_updated_at timestamptz default null)
returns jsonb language plpgsql security definer set search_path=public as $$
declare target uuid:=coalesce(nullif(payload->>'id','')::uuid,gen_random_uuid()); prior public.home_banners; saved public.home_banners;
begin
 if not public.can_manage_accounts() then raise exception 'Administrator access required' using errcode='42501'; end if;
 if not exists(select 1 from storage.objects where bucket_id='home-banners' and name=payload->>'image_path') then raise exception 'Upload the banner image first'; end if;
 select * into prior from public.home_banners where id=target for update;
 if prior.id is not null and (expected_updated_at is null or prior.updated_at<>expected_updated_at) then raise exception 'Banner changed elsewhere. Refresh before saving.'; end if;
 if prior.id is null and expected_updated_at is not null then raise exception 'Banner no longer exists. Refresh the list.'; end if;
 insert into public.home_banners(id,title,image_path,destination,destination_value,audience,sort_order,enabled,starts_at,ends_at)
 values(target,trim(payload->>'title'),payload->>'image_path',payload->>'destination',coalesce(payload->>'destination_value',''),payload->>'audience',(payload->>'sort_order')::integer,coalesce((payload->>'enabled')::boolean,false),nullif(payload->>'starts_at','')::timestamptz,nullif(payload->>'ends_at','')::timestamptz)
 on conflict(id) do update set title=excluded.title,image_path=excluded.image_path,destination=excluded.destination,destination_value=excluded.destination_value,audience=excluded.audience,sort_order=excluded.sort_order,enabled=excluded.enabled,starts_at=excluded.starts_at,ends_at=excluded.ends_at,updated_at=clock_timestamp()
 returning * into saved;
 insert into public.home_banner_audit(banner_id,actor,action,previous_data,new_data) values(target,auth.uid(),case when prior.id is null then 'CREATE' else 'UPDATE' end,to_jsonb(prior),to_jsonb(saved));
 return to_jsonb(saved);
end $$;
create function public.admin_delete_home_banner(target_id uuid,expected_updated_at timestamptz)
returns jsonb language plpgsql security definer set search_path=public as $$
declare prior public.home_banners;
begin
 if not public.can_manage_accounts() then raise exception 'Administrator access required' using errcode='42501'; end if;
 select * into prior from public.home_banners where id=target_id for update;
 if prior.id is null or expected_updated_at is null or prior.updated_at<>expected_updated_at then raise exception 'Banner changed or was removed. Refresh the list.'; end if;
 delete from public.home_banners where id=target_id;
 insert into public.home_banner_audit(banner_id,actor,action,previous_data) values(target_id,auth.uid(),'DELETE',to_jsonb(prior));
 return jsonb_build_object('deleted',true);
end $$;
create function public.customer_home_banners()
returns jsonb language plpgsql stable security definer set search_path=public as $$
declare days integer;
begin
 if auth.uid() is null then raise exception 'Sign in required' using errcode='42501'; end if;
 select p.duration_days into days from public.customer_subscriptions s join public.packages p on p.id=s.package_id where s.customer_id=auth.uid()
 and s.status in ('ACTIVE','PAUSED') and s.end_date >= (now() at time zone 'Asia/Kolkata')::date
 order by s.created_at desc limit 1;
 return jsonb_build_object('banners',coalesce((select jsonb_agg(to_jsonb(b) order by b.sort_order,b.id) from (
 select id,title,image_path,destination,destination_value,sort_order,updated_at from public.home_banners
 where enabled and (starts_at is null or starts_at<=now()) and (ends_at is null or ends_at>now())
 and (audience='ALL' or (audience='NO_PLAN' and days is null) or (audience='SUBSCRIBED' and days is not null) or (audience='WEEKLY' and days=7) or (audience='MONTHLY' and days=30))
 order by sort_order,id limit 10) b),'[]'::jsonb));
end $$;
revoke all on function public.admin_save_home_banner(jsonb,timestamptz),public.admin_delete_home_banner(uuid,timestamptz),public.customer_home_banners() from public;
grant execute on function public.admin_save_home_banner(jsonb,timestamptz),public.admin_delete_home_banner(uuid,timestamptz),public.customer_home_banners() to authenticated;
