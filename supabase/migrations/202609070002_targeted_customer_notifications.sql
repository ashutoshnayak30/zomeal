-- Auditable, targeted customer notification campaigns for the admin panel.
create table if not exists public.customer_notification_campaigns (
  id uuid primary key default gen_random_uuid(),
  created_by uuid not null references public.profiles(id),
  audience_type text not null check(audience_type in ('ALL','PINCODE','CITY','PROVIDER')),
  audience_value text,
  audience_label text not null,
  title text not null check(length(title) between 1 and 100),
  message text not null check(length(message) between 1 and 500),
  destination text not null default 'notifications',
  recipient_count integer not null default 0,
  device_count integer not null default 0,
  sent_count integer not null default 0,
  failed_count integer not null default 0,
  created_at timestamptz not null default now(),
  completed_at timestamptz
);

create index if not exists customer_notification_campaigns_created_idx
  on public.customer_notification_campaigns(created_at desc);
alter table public.customer_notification_campaigns enable row level security;
revoke all on public.customer_notification_campaigns from anon,authenticated;

create or replace function public.notification_customer_directory()
returns table(
  user_id uuid,full_name text,phone text,pincode text,city text,
  provider_id uuid,provider_name text,subscription_status text
) language sql stable security definer set search_path=public as $$
  select profile.id,profile.full_name,profile.phone,
    coalesce(address.pincode,subscription.delivery_address->>'pincode') pincode,
    coalesce(nullif(address.city,''),nullif(subscription.delivery_address->>'city',''),pin.city) city,
    subscription.provider_id,provider.display_name,subscription.status
  from public.profiles profile
  left join lateral (
    select a.pincode,a.city from public.customer_addresses a where a.customer_id=profile.id
    order by a.is_default desc,a.updated_at desc limit 1
  ) address on true
  left join lateral (
    select s.provider_id,s.status,s.delivery_address from public.customer_subscriptions s
    where s.customer_id=profile.id and s.status not in ('CANCELLED','COMPLETED')
    order by (s.status='ACTIVE') desc,s.updated_at desc limit 1
  ) subscription on true
  left join public.providers provider on provider.id=subscription.provider_id
  left join public.pincodes pin on pin.code=coalesce(address.pincode,subscription.delivery_address->>'pincode')
  where profile.is_active
    and exists(select 1 from public.user_roles role where role.user_id=profile.id and role.role='CUSTOMER');
$$;

revoke all on function public.notification_customer_directory() from public;

create or replace function public.admin_notification_center(search_text text default '')
returns jsonb language plpgsql stable security definer set search_path=public as $$
declare clean_search text:=trim(coalesce(search_text,'')); result jsonb;
begin
  if not public.can_manage_accounts() then raise exception 'Administrator access required' using errcode='42501'; end if;
  select jsonb_build_object(
    'active_user_count',(select count(*) from public.notification_customer_directory()),
    'users',coalesce((select jsonb_agg(to_jsonb(row_data) order by row_data.full_name nulls last,row_data.phone nulls last)
      from (select * from public.notification_customer_directory() d
        where clean_search='' or d.full_name ilike '%'||clean_search||'%' or d.phone like '%'||clean_search||'%' or d.user_id::text ilike '%'||clean_search||'%'
        order by d.full_name nulls last,d.phone nulls last limit 500) row_data),'[]'::jsonb),
    'pincodes',coalesce((select jsonb_agg(jsonb_build_object('value',pincode,'label',pincode,'count',customer_count) order by pincode)
      from (select d.pincode,count(*) customer_count from public.notification_customer_directory() d where d.pincode is not null group by d.pincode) grouped),'[]'::jsonb),
    'cities',coalesce((select jsonb_agg(jsonb_build_object('value',city,'label',city,'count',customer_count) order by city)
      from (select d.city,count(*) customer_count from public.notification_customer_directory() d where nullif(trim(d.city),'') is not null group by d.city) grouped),'[]'::jsonb),
    'providers',coalesce((select jsonb_agg(jsonb_build_object('value',provider_id,'label',provider_name,'count',customer_count) order by provider_name)
      from (select d.provider_id,d.provider_name,count(*) customer_count from public.notification_customer_directory() d where d.provider_id is not null group by d.provider_id,d.provider_name) grouped),'[]'::jsonb),
    'campaigns',coalesce((select jsonb_agg(to_jsonb(campaign) order by campaign.created_at desc)
      from (select id,audience_type,audience_label,title,recipient_count,device_count,sent_count,failed_count,created_at,completed_at
        from public.customer_notification_campaigns order by created_at desc limit 12) campaign),'[]'::jsonb)
  ) into result;
  return result;
end; $$;

create or replace function public.admin_create_customer_notification_campaign(
  requesting_admin uuid,target_audience text,target_value text,target_title text,target_message text,target_destination text default 'notifications'
) returns jsonb language plpgsql security definer set search_path=public as $$
declare
  clean_type text:=upper(trim(coalesce(target_audience,'')));
  clean_value text:=nullif(trim(coalesce(target_value,'')),'');
  clean_title text:=trim(coalesce(target_title,''));
  clean_message text:=trim(coalesce(target_message,''));
  clean_destination text:=left(coalesce(nullif(trim(target_destination),''),'notifications'),80);
  campaign_id uuid; audience_label text; recipients uuid[];
begin
  if not exists(select 1 from public.user_roles r where r.user_id=requesting_admin and r.role in ('ADMIN','OPERATIONS','FINANCE')) then
    raise exception 'Administrator access required' using errcode='42501';
  end if;
  if clean_type not in ('ALL','PINCODE','CITY','PROVIDER') then raise exception 'Choose a valid notification audience'; end if;
  if clean_type<>'ALL' and clean_value is null then raise exception 'Choose a notification segment'; end if;
  if length(clean_title) not between 1 and 100 or length(clean_message) not between 1 and 500 then raise exception 'Notification title or message is invalid'; end if;

  select coalesce(array_agg(d.user_id order by d.user_id),'{}'::uuid[]) into recipients
  from public.notification_customer_directory() d
  where clean_type='ALL'
     or (clean_type='PINCODE' and d.pincode=clean_value)
     or (clean_type='CITY' and lower(d.city)=lower(clean_value))
     or (clean_type='PROVIDER' and d.provider_id::text=clean_value);
  if coalesce(array_length(recipients,1),0)=0 then raise exception 'No active customers match this audience'; end if;

  audience_label:=case clean_type when 'ALL' then 'All active customers' when 'PINCODE' then 'Pincode '||clean_value
    when 'CITY' then clean_value else coalesce((select display_name from public.providers where id::text=clean_value),'Provider') end;
  insert into public.customer_notification_campaigns(created_by,audience_type,audience_value,audience_label,title,message,destination,recipient_count)
  values(requesting_admin,clean_type,clean_value,audience_label,clean_title,clean_message,clean_destination,array_length(recipients,1)) returning id into campaign_id;

  insert into public.customer_notifications(customer_id,category,title,message,destination,dedupe_key)
  select customer_id,'Zomeal',clean_title,clean_message,'notifications','ADMIN_CAMPAIGN_'||campaign_id::text from unnest(recipients) customer_id;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,metadata)
  values(requesting_admin,'CUSTOMER_NOTIFICATION_CAMPAIGN_SENT','notification_campaign',campaign_id,
    jsonb_build_object('audience_type',clean_type,'audience_value',clean_value,'recipient_count',array_length(recipients,1),'title',clean_title));
  return jsonb_build_object('campaign_id',campaign_id,'user_ids',to_jsonb(recipients),'recipient_count',array_length(recipients,1));
end; $$;

revoke all on function public.admin_notification_center(text),public.admin_create_customer_notification_campaign(uuid,text,text,text,text,text) from public;
grant execute on function public.admin_notification_center(text) to authenticated;
grant execute on function public.admin_create_customer_notification_campaign(uuid,text,text,text,text,text) to service_role;
