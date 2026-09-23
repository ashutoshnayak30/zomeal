-- Server-owned campaign scheduler. No client may write queues or choose push tokens.
create table public.notification_schedules (
 id uuid primary key default gen_random_uuid(), created_by uuid references auth.users(id) on delete set null,
 app_kind text not null check(app_kind in ('CUSTOMER','PROVIDER')),
 audience text not null check(audience in ('ALL','USERS','PINCODE','CITY','PROVIDER','LOW_WALLET')),
 target_values text[] not null default '{}', title text not null check(length(title) between 1 and 100),
 message text not null check(length(message) between 1 and 500),
 interval_hours integer check(interval_hours in (2,6,12,24)),
 next_run_at timestamptz not null, ends_at timestamptz not null,
 daytime_only boolean not null default true,
 state text not null default 'ACTIVE' check(state in ('ACTIVE','PAUSED','CANCELLED','COMPLETED')),
 created_at timestamptz not null default now(), updated_at timestamptz not null default now()
);
create table public.notification_schedule_runs (
 id uuid primary key default gen_random_uuid(), schedule_id uuid not null references notification_schedules(id),
 due_at timestamptz not null, created_at timestamptz not null default now(), recipients integer not null default 0,
 unique(schedule_id,due_at)
);
create table public.campaign_push_outbox (
 id uuid primary key default gen_random_uuid(), run_id uuid not null references notification_schedule_runs(id),
 user_id uuid not null references auth.users(id) on delete cascade,
 device_id uuid not null references push_device_tokens(id) on delete cascade,
 notification_id uuid not null, app_kind text not null, title text not null,message text not null,
 attempts integer not null default 0, available_at timestamptz not null default now(),
 lease_id uuid, completed_at timestamptz, last_error text, unique(run_id,device_id)
);
alter table notification_schedules enable row level security;
alter table notification_schedule_runs enable row level security;
alter table campaign_push_outbox enable row level security;
revoke all on notification_schedules,notification_schedule_runs,campaign_push_outbox from public,anon,authenticated;
create index campaign_push_due on campaign_push_outbox(available_at) where completed_at is null;

create function public.campaign_recipients(kind text,segment text,targets text[])
returns table(user_id uuid,full_name text,phone text,provider_id uuid,provider_name text,pincode text)
language sql stable security definer set search_path=public as $$
 select distinct d.user_id,d.full_name,d.phone,null::uuid,null::text,d.pincode
 from notification_customer_directory() d
 where kind='CUSTOMER' and nullif(trim(d.phone),'') is not null
 and (not exists(select 1 from user_roles r where r.user_id=d.user_id and r.role<>'CUSTOMER')
      or exists(select 1 from customer_subscriptions s where s.customer_id=d.user_id))
 and case segment
 when 'ALL' then true
 when 'USERS' then d.user_id::text=any(targets)
 when 'PINCODE' then d.pincode=any(targets)
 when 'CITY' then lower(d.city)=any(select lower(unnest(targets)))
 when 'LOW_WALLET' then coalesce((select w.balance_paise from customer_wallets w where w.customer_id=d.user_id),0)<50000
 when 'PROVIDER' then exists(select 1 from customer_subscriptions s where s.customer_id=d.user_id
   and s.provider_id::text=any(targets) and s.status in ('ACTIVE','PAUSED')
   and (now() at time zone 'Asia/Kolkata')::date between s.start_date and s.end_date)
 else false end
 union all
 select m.user_id,p.full_name,coalesce(nullif(p.phone,''),u.phone),k.id,k.display_name,null::text
 from provider_members m join profiles p on p.id=m.user_id join auth.users u on u.id=p.id
 join providers k on k.id=m.provider_id
 where kind='PROVIDER' and m.is_active and p.is_active and k.status not in ('INACTIVE','SUSPENDED')
 and case segment when 'ALL' then true when 'USERS' then m.user_id::text=any(targets)
 when 'PROVIDER' then k.id::text=any(targets)
 when 'PINCODE' then exists(select 1 from provider_service_areas a where a.provider_id=k.id and a.status='APPROVED'
   and a.pincode=any(targets) and (a.effective_from is null or a.effective_from<=current_date)
   and (a.effective_until is null or a.effective_until>=current_date))
 when 'CITY' then exists(select 1 from provider_service_areas a join pincodes z on z.code=a.pincode
   where a.provider_id=k.id and a.status='APPROVED' and lower(z.city)=any(select lower(unnest(targets)))
   and (a.effective_from is null or a.effective_from<=current_date) and (a.effective_until is null or a.effective_until>=current_date))
 else false end;
$$;

create function public.admin_campaign_preview(kind text,segment text,targets text[] default '{}',search_text text default '')
returns jsonb language plpgsql stable security definer set search_path=public as $$
begin
 if not can_manage_accounts() then raise exception 'Administrator access required'; end if;
 return jsonb_build_object('count',(select count(distinct user_id) from campaign_recipients(kind,segment,targets)),
 'kitchens',coalesce((select jsonb_agg(jsonb_build_object('id',id,'name',display_name) order by display_name) from providers
 where status not in ('INACTIVE','SUSPENDED')),'[]'::jsonb),
 'users',coalesce((select jsonb_agg(to_jsonb(x)) from (select * from campaign_recipients(kind,segment,targets)
 where search_text='' or full_name ilike '%'||search_text||'%' or phone like '%'||regexp_replace(search_text,'[^0-9]','','g')||'%' and search_text~'[0-9]'
 order by full_name,user_id limit 100) x),'[]'::jsonb));
end $$;

create function public.admin_save_notification_schedule(payload jsonb) returns uuid
language plpgsql security definer set search_path=public as $$
declare sid uuid; start_at timestamptz; finish_at timestamptz; hours integer; vals text[]; kind text; segment text;
begin
 if not can_manage_accounts() then raise exception 'Administrator access required'; end if;
 start_at:=(payload->>'start_at')::timestamptz; finish_at:=(payload->>'ends_at')::timestamptz;
 hours:=nullif(payload->>'interval_hours','')::integer;
 kind:=payload->>'app_kind'; segment:=payload->>'audience';
 select coalesce(array_agg(trim(v)),'{}') into vals from jsonb_array_elements_text(coalesce(payload->'target_values','[]')) v;
 if start_at is null or finish_at is null or start_at<now()-interval '1 minute' or finish_at<=start_at
 or finish_at>now()+interval '90 days' then raise exception 'Choose a future start and an end within 90 days'; end if;
 if kind is null or segment is null or kind not in ('CUSTOMER','PROVIDER') or segment not in ('ALL','USERS','PINCODE','CITY','PROVIDER','LOW_WALLET')
 or (kind='PROVIDER' and segment='LOW_WALLET') then raise exception 'Invalid audience'; end if;
 if segment not in ('ALL','LOW_WALLET') and cardinality(vals)=0 then raise exception 'Select an audience'; end if;
 if segment='PINCODE' and exists(select 1 from unnest(vals) v where v !~ '^[1-9][0-9]{5}$') then raise exception 'Invalid pincode'; end if;
 if hours is not null and hours not in (2,6,12,24) then raise exception 'Choose 2, 6, 12 or 24 hours'; end if;
 if payload->>'id' is not null then
   sid:=(payload->>'id')::uuid;
   perform 1 from notification_schedules where id=sid and state in ('ACTIVE','PAUSED') for update;
   if not found then raise exception 'Schedule unavailable for editing'; end if;
   update campaign_push_outbox o set completed_at=now(),last_error='EDITED' from notification_schedule_runs r
   where r.id=o.run_id and r.schedule_id=sid and o.completed_at is null and o.lease_id is null;
   update notification_schedules set app_kind=kind,audience=segment,target_values=vals,title=trim(payload->>'title'),message=trim(payload->>'message'),
   interval_hours=hours,next_run_at=start_at,ends_at=finish_at,daytime_only=coalesce((payload->>'daytime_only')::boolean,true),updated_at=now() where id=sid;
 else
   insert into notification_schedules(created_by,app_kind,audience,target_values,title,message,interval_hours,next_run_at,ends_at,daytime_only)
   values(auth.uid(),kind,segment,vals,trim(payload->>'title'),trim(payload->>'message'),hours,start_at,finish_at,coalesce((payload->>'daytime_only')::boolean,true)) returning id into sid;
 end if;
 return sid;
end $$;

create function public.admin_notification_schedule_state(target_id uuid,target_state text) returns void
language plpgsql security definer set search_path=public as $$
declare s notification_schedules;
begin
 if not can_manage_accounts() then raise exception 'Administrator access required'; end if;
 select * into s from notification_schedules where id=target_id for update;
 if not found or s.state not in ('ACTIVE','PAUSED') or target_state not in ('ACTIVE','PAUSED','CANCELLED') then raise exception 'Invalid state transition'; end if;
 if target_state='ACTIVE' and s.ends_at<=now() then raise exception 'Schedule expired; create a new schedule'; end if;
 update notification_schedules set state=target_state,next_run_at=case when target_state='ACTIVE' then greatest(next_run_at,now()) else next_run_at end,updated_at=now() where id=target_id;
 -- Do not replay old queued marketing on resume. In-flight FCM requests cannot be recalled.
 if target_state in ('PAUSED','CANCELLED') then
 update campaign_push_outbox o set completed_at=now(),last_error=target_state from notification_schedule_runs r
 where r.id=o.run_id and r.schedule_id=target_id and o.completed_at is null;
 end if;
end $$;

create function public.admin_notification_schedules() returns jsonb
language plpgsql stable security definer set search_path=public as $$
begin
 if not can_manage_accounts() then raise exception 'Administrator access required'; end if;
 return jsonb_build_object('schedules',coalesce((select jsonb_agg(to_jsonb(s)) from
 (select * from notification_schedules order by created_at desc limit 100) s),'[]'),
 'runs',coalesce((select jsonb_agg(to_jsonb(x)) from (select r.*,s.title,
 count(o.id) devices,count(o.id) filter(where o.completed_at is not null and o.last_error is null) accepted,
 count(o.id) filter(where o.last_error is not null and (o.completed_at is not null or o.attempts>=8)) failed,
 count(o.id) filter(where o.completed_at is null and o.attempts<8) pending
 from notification_schedule_runs r join notification_schedules s on s.id=r.schedule_id left join campaign_push_outbox o on o.run_id=r.id
 group by r.id,s.title order by r.created_at desc limit 30) x),'[]'));
end $$;

create function public.process_notification_schedules() returns void
language plpgsql security definer set search_path=public as $$
declare s notification_schedules; rid uuid; recipient record; nid uuid; local_time timestamp;
begin
 local_time:=now() at time zone 'Asia/Kolkata';
 update notification_schedules set state='COMPLETED',updated_at=now() where state='ACTIVE' and ends_at<=now();
 for s in select * from notification_schedules where state='ACTIVE' and next_run_at<=now() and ends_at>now()
 order by next_run_at limit 20 for update skip locked loop
   if s.daytime_only and (local_time::time<time '08:00' or local_time::time>=time '21:00') then
     update notification_schedules set next_run_at=((local_time::date+case when local_time::time>=time '21:00' then 1 else 0 end)+time '08:00') at time zone 'Asia/Kolkata' where id=s.id;
     continue;
   end if;
   insert into notification_schedule_runs(schedule_id,due_at) values(s.id,s.next_run_at) on conflict do nothing returning id into rid;
   if rid is not null then
     for recipient in select distinct on (user_id) * from campaign_recipients(s.app_kind,s.audience,s.target_values) order by user_id,provider_id loop
       if s.app_kind='CUSTOMER' then
         insert into customer_notifications(customer_id,category,title,message,destination,dedupe_key)
         values(recipient.user_id,'Reminder',s.title,s.message,'notifications','CAMPAIGN_'||rid) returning id into nid;
       else
         insert into provider_notifications(provider_id,recipient_user_id,category,title,message,destination,entity_type,entity_id)
         values(recipient.provider_id,recipient.user_id,'OPERATIONS',s.title,s.message,'DASHBOARD','notification_campaign',rid::text) returning id into nid;
       end if;
       insert into campaign_push_outbox(run_id,user_id,device_id,notification_id,app_kind,title,message)
       select rid,recipient.user_id,d.id,nid,s.app_kind,s.title,s.message from push_device_tokens d
       where d.user_id=recipient.user_id and d.enabled and d.app_kind=s.app_kind on conflict do nothing;
       update notification_schedule_runs set recipients=recipients+1 where id=rid;
     end loop;
   end if;
   update notification_schedules set state=case when s.interval_hours is null then 'COMPLETED' else 'ACTIVE' end,
   -- Keep original cadence but skip a backlog after outages.
   next_run_at=case when s.interval_hours is null then s.next_run_at else
   s.next_run_at+make_interval(hours=>s.interval_hours)*(floor(extract(epoch from (now()-s.next_run_at))/(s.interval_hours*3600))+1) end,updated_at=now() where id=s.id;
 end loop;
end $$;

create function public.claim_campaign_push_batch() returns jsonb
language sql security definer set search_path=public as $$
 with pending as (select o.id from campaign_push_outbox o join notification_schedule_runs r on r.id=o.run_id
 join notification_schedules s on s.id=r.schedule_id
 where o.completed_at is null and o.attempts<8 and o.available_at<=now() and s.state in ('ACTIVE','COMPLETED')
 and (not s.daytime_only or (now() at time zone 'Asia/Kolkata')::time>=time '08:00' and (now() at time zone 'Asia/Kolkata')::time<time '21:00')
 and r.created_at>now()-interval '24 hours' order by o.available_at limit 50 for update of o skip locked),
 claimed as (update campaign_push_outbox o set attempts=attempts+1,lease_id=gen_random_uuid(),available_at=now()+interval '5 minutes'
 from pending p where p.id=o.id returning o.*)
 select coalesce(jsonb_agg(jsonb_build_object('id',c.id,'lease_id',c.lease_id,'notification_id',c.notification_id,
 'token',case when d.enabled and d.user_id=c.user_id and d.app_kind=c.app_kind then d.token end,
 'app_kind',c.app_kind,'title',c.title,'body',c.message,'destination','notifications')),'[]')
 from claimed c join push_device_tokens d on d.id=c.device_id;
$$;
create function public.finish_campaign_push(target_id uuid,target_lease uuid,delivered boolean,invalid_token boolean default false,error_code text default null) returns void
language plpgsql security definer set search_path=public as $$
declare device uuid;
begin
 update campaign_push_outbox set completed_at=case when delivered or invalid_token or attempts>=8 then now() end,
 last_error=left(error_code,120),available_at=now()+make_interval(secs=>least(3600,30*(2^attempts)::integer)),lease_id=null
 where id=target_id and lease_id=target_lease and completed_at is null returning device_id into device;
 if invalid_token and device is not null then update push_device_tokens set enabled=false where id=device; end if;
end $$;

revoke all on function campaign_recipients(text,text,text[]),process_notification_schedules(),claim_campaign_push_batch(),finish_campaign_push(uuid,uuid,boolean,boolean,text) from public,anon,authenticated;
grant execute on function claim_campaign_push_batch(),finish_campaign_push(uuid,uuid,boolean,boolean,text) to service_role;
revoke all on function admin_campaign_preview(text,text,text[],text),admin_save_notification_schedule(jsonb),admin_notification_schedule_state(uuid,text),admin_notification_schedules() from public,anon;
grant execute on function admin_campaign_preview(text,text,text[],text),admin_save_notification_schedule(jsonb),admin_notification_schedule_state(uuid,text),admin_notification_schedules() to authenticated;

-- HOSTED DISPATCH START (external scheduler excluded from local tests)
create function public.dispatch_campaign_push() returns void
language plpgsql security definer set search_path=public as $$
begin
 perform process_notification_schedules();
 update campaign_push_outbox set completed_at=now(),last_error='EXPIRED' where completed_at is null and
 run_id in(select id from notification_schedule_runs where created_at<now()-interval '24 hours');
 if exists(select 1 from campaign_push_outbox where completed_at is null and attempts<8 and available_at<=now()) then
 perform net.http_post(url:='https://tojgwcxfvicrenfabgml.supabase.co/functions/v1/dispatch-campaign-push',
 headers:=jsonb_build_object('Content-Type','application/json','x-meal-dispatch-secret',(select secret from meal_push_dispatch_config)),body:='{}',timeout_milliseconds:=10000);
 end if;
end $$;
revoke all on function dispatch_campaign_push() from public,anon,authenticated;
select cron.schedule('zomeal-campaign-push-dispatch','* * * * *','select public.dispatch_campaign_push();');
-- HOSTED DISPATCH END
notify pgrst,'reload schema';
