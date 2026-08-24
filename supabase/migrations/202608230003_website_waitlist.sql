create table if not exists public.website_leads(
  id uuid primary key default gen_random_uuid(),
  full_name text not null,
  phone text not null,
  pincode text not null,
  locality text,
  interest text not null default 'CUSTOMER' check(interest in('CUSTOMER','PROVIDER','LAUNCH_ALERT')),
  consent_at timestamptz not null,
  source text not null default 'zomeal.in',
  status text not null default 'NEW' check(status in('NEW','CONTACTED','CONVERTED','CLOSED')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists website_leads_created_at_idx on public.website_leads(created_at desc);
create index if not exists website_leads_pincode_idx on public.website_leads(pincode,status);
alter table public.website_leads enable row level security;

revoke all on public.website_leads from anon,authenticated;

create or replace function public.website_join_waitlist(
  lead_name text,
  lead_phone text,
  lead_pincode text,
  lead_locality text default null,
  lead_interest text default 'CUSTOMER',
  accepted_consent boolean default false
) returns jsonb
language plpgsql
security definer
set search_path=public
as $$
declare
  normalized_phone text := regexp_replace(coalesce(lead_phone,''),'\D','','g');
  normalized_pincode text := trim(coalesce(lead_pincode,''));
  normalized_interest text := upper(trim(coalesce(lead_interest,'CUSTOMER')));
  lead_id uuid;
begin
  if length(trim(coalesce(lead_name,'')))<2 then raise exception 'Please enter your name'; end if;
  if normalized_phone like '91%' and length(normalized_phone)=12 then normalized_phone:=right(normalized_phone,10); end if;
  if normalized_phone!~'^[6-9][0-9]{9}$' then raise exception 'Enter a valid Indian mobile number'; end if;
  if normalized_pincode!~'^[1-9][0-9]{5}$' then raise exception 'Enter a valid six-digit pincode'; end if;
  if normalized_interest not in('CUSTOMER','PROVIDER','LAUNCH_ALERT') then raise exception 'Choose a valid interest'; end if;
  if not accepted_consent then raise exception 'Consent is required before joining'; end if;

  select id into lead_id from public.website_leads
  where phone=normalized_phone and pincode=normalized_pincode and interest=normalized_interest
    and created_at>now()-interval '24 hours'
  order by created_at desc limit 1;

  if lead_id is null then
    insert into public.website_leads(full_name,phone,pincode,locality,interest,consent_at)
    values(trim(lead_name),normalized_phone,normalized_pincode,nullif(trim(coalesce(lead_locality,'')),''),normalized_interest,now())
    returning id into lead_id;
  end if;

  return jsonb_build_object('ok',true,'lead_id',lead_id,'message','You are on the Zomeal early-access list');
end;
$$;

revoke all on function public.website_join_waitlist(text,text,text,text,text,boolean) from public;
grant execute on function public.website_join_waitlist(text,text,text,text,text,boolean) to anon,authenticated;

create or replace function public.admin_website_leads(status_filter text default null,search_text text default null)
returns setof public.website_leads
language sql
stable
security definer
set search_path=public
as $$
  select lead.* from public.website_leads lead
  where public.has_role('ADMIN')
    and(status_filter is null or lead.status=upper(status_filter))
    and(search_text is null or lead.full_name ilike '%'||search_text||'%' or lead.phone like '%'||search_text||'%' or lead.pincode like '%'||search_text||'%')
  order by lead.created_at desc;
$$;

revoke all on function public.admin_website_leads(text,text) from public;
grant execute on function public.admin_website_leads(text,text) to authenticated;
notify pgrst,'reload schema';
