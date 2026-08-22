-- Backfill provider-selected dish classifications and add the customer-controlled
-- cancellation/provider-change request workflow. Requests never stop meals
-- immediately; operations/admin review protects delivery and refund handling.

with classified_items as (
  select distinct on (mi.id)
    mi.id,
    case upper(replace(course->>'foodType','-','_'))
      when 'VEG' then 'VEG'::public.dietary_type
      when 'NON_VEG' then 'NON_VEG'::public.dietary_type
      when 'VEGAN' then 'VEGAN'::public.dietary_type
      else mi.dietary_type
    end as selected_type
  from public.menu_items mi
  join public.provider_members member on member.provider_id=mi.provider_id and member.is_active
  join public.provider_form_drafts draft on draft.owner_user_id=member.user_id
    and draft.form_scope='provider_mobile_onboarding'
  cross join lateral jsonb_array_elements(coalesce(draft.payload->'menus','[]'::jsonb)) menu_day
  cross join lateral jsonb_array_elements(
    coalesce(menu_day->'lunch','[]'::jsonb) || coalesce(menu_day->'dinner','[]'::jsonb)
  ) course
  where mi.category='MAIN_COURSE'
    and lower(trim(course->>'name'))=lower(trim(mi.name))
  order by mi.id,draft.updated_at desc
)
update public.menu_items item
set dietary_type=classified.selected_type,updated_at=now()
from classified_items classified
where item.id=classified.id and item.dietary_type is distinct from classified.selected_type;

create table if not exists public.customer_subscription_change_requests (
  id uuid primary key default gen_random_uuid(),
  subscription_id uuid not null references public.customer_subscriptions(id) on delete cascade,
  customer_id uuid not null references public.profiles(id),
  request_type text not null check(request_type in ('CANCEL_SUBSCRIPTION','CHANGE_PROVIDER')),
  requested_provider_id uuid references public.providers(id),
  reason text,
  status text not null default 'PENDING' check(status in ('PENDING','CONTACTED','APPROVED','REJECTED','CANCELLED','COMPLETED')),
  refund_estimate_paise bigint check(refund_estimate_paise is null or refund_estimate_paise>=0),
  review_note text,
  reviewed_by uuid references public.profiles(id),
  reviewed_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint provider_change_target_valid check(
    (request_type='CANCEL_SUBSCRIPTION' and requested_provider_id is null)
    or request_type='CHANGE_PROVIDER'
  )
);

create unique index if not exists one_open_customer_subscription_change
on public.customer_subscription_change_requests(subscription_id,request_type)
where status in ('PENDING','CONTACTED','APPROVED');

create index if not exists customer_subscription_change_queue
on public.customer_subscription_change_requests(status,created_at);

alter table public.customer_subscription_change_requests enable row level security;

create policy customer_subscription_change_owner_read
on public.customer_subscription_change_requests for select to authenticated
using(customer_id=auth.uid());

create policy customer_subscription_change_admin_read
on public.customer_subscription_change_requests for select to authenticated
using(public.has_role('ADMIN') or public.has_role('OPERATIONS'));

create trigger customer_subscription_change_updated_at
before update on public.customer_subscription_change_requests
for each row execute function public.set_updated_at();

create or replace function public.customer_request_subscription_change(
  target_subscription uuid,
  requested_action text,
  replacement_provider uuid default null,
  customer_reason text default null
) returns jsonb language plpgsql security definer set search_path=public as $$
declare
  subscription_record public.customer_subscriptions;
  normalized_action text:=upper(trim(requested_action));
  request_id uuid;
begin
  select * into subscription_record
  from public.customer_subscriptions
  where id=target_subscription and customer_id=auth.uid()
  for update;

  if subscription_record.id is null then raise exception 'Active subscription was not found'; end if;
  if subscription_record.status not in ('ACTIVE','PAUSED') then raise exception 'This subscription cannot be changed in its current status'; end if;
  if normalized_action not in ('CANCEL_SUBSCRIPTION','CHANGE_PROVIDER') then raise exception 'Unsupported subscription action'; end if;

  if normalized_action='CHANGE_PROVIDER' then
    if replacement_provider is not null and replacement_provider=subscription_record.provider_id then
      raise exception 'Choose a different service provider';
    end if;
    if replacement_provider is not null and not exists(
      select 1 from public.providers where id=replacement_provider and status='ACTIVE'
    ) then raise exception 'Replacement provider is not active'; end if;
  else
    replacement_provider:=null;
  end if;

  insert into public.customer_subscription_change_requests(
    subscription_id,customer_id,request_type,requested_provider_id,reason
  ) values(
    target_subscription,auth.uid(),normalized_action,replacement_provider,nullif(trim(customer_reason),'')
  ) returning id into request_id;

  if normalized_action='CANCEL_SUBSCRIPTION' then
    update public.customer_subscriptions set status='CANCEL_PENDING',updated_at=now()
    where id=target_subscription;
  end if;

  insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data)
  values(auth.uid(),normalized_action,'customer_subscription',target_subscription::text,
    jsonb_build_object('request_id',request_id,'replacement_provider_id',replacement_provider));

  return jsonb_build_object(
    'request_id',request_id,
    'subscription_id',target_subscription,
    'request_type',normalized_action,
    'status','PENDING',
    'review_sla_hours',48
  );
end; $$;

revoke all on function public.customer_request_subscription_change(uuid,text,uuid,text) from public;
grant execute on function public.customer_request_subscription_change(uuid,text,uuid,text) to authenticated;

comment on function public.customer_request_subscription_change(uuid,text,uuid,text) is
'Creates an authenticated customer cancellation or provider-change request without immediately interrupting meals.';
