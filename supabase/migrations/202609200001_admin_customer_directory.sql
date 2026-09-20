-- Read-only customer directory. Keep bulk personal data behind the existing
-- Administrator / Super Administrator permission, not browser-only visibility.
create index if not exists customer_subscriptions_customer_directory_idx
  on public.customer_subscriptions(customer_id, start_date, end_date, created_at desc);

create or replace function public.admin_customer_directory(
  search_text text default '', customer_filter text default 'ALL', page_number integer default 0
) returns jsonb language plpgsql security definer set search_path=public as $$
declare
  q text:=lower(trim(coalesce(search_text,'')));
  digits text;
  filter_name text:=upper(coalesce(customer_filter,'ALL'));
  today date:=(now() at time zone 'Asia/Kolkata')::date;
  result jsonb;
begin
  if not public.can_manage_accounts() then
    raise exception 'Administrator access required' using errcode='42501';
  end if;
  if page_number is null or page_number<0 or page_number>10000 then raise exception 'Invalid page'; end if;
  if length(q)>100 then raise exception 'Search must be 100 characters or fewer'; end if;
  if filter_name not in ('ALL','REGISTERED','ACTIVE','PAUSED','NO_PLAN','INCOMPLETE','INACTIVE') then
    raise exception 'Choose a valid customer filter';
  end if;
  digits:=case when q ~ '^[+0-9 ()-]+$' then regexp_replace(q,'[^0-9]','','g') else null end;
  if length(digits)=12 and left(digits,2)='91' then digits:=right(digits,10); end if;

  with customers as materialized (
    select p.id,
      coalesce(nullif(nullif(trim(p.full_name),''),'Customer'),nullif(trim(u.raw_user_meta_data->>'full_name'),'')) full_name,
      phone.value phone,
      phone.value is not null is_registered,
      p.is_active account_enabled,
      u.created_at registered_at,
      u.last_sign_in_at,
      coalesce(u.raw_user_meta_data->>'account_type','')='CUSTOMER_TEST' is_test_account,
      coalesce(wallet.balance_paise,0) balance_paise,
      location.pincode,
      coalesce(case when s.status in ('ACTIVE','PAUSED','CANCEL_PENDING') and s.start_date<=today and s.end_date>=today
        then nullif(trim(s.delivery_address->>'city'),'') end,nullif(trim(a.city),''),pin.city) city,
      coalesce(p.is_active and s.status='ACTIVE' and s.start_date<=today and s.end_date>=today,false) active_subscription,
      coalesce(p.is_active and s.status='PAUSED' and s.start_date<=today and s.end_date>=today,false) paused_subscription,
      coalesce(s.status in ('ACTIVE','PAUSED','CANCEL_PENDING') and s.start_date<=today and s.end_date>=today,false) has_current_plan,
      case when s.id is null then 'NONE'
        when s.status in ('ACTIVE','PAUSED','CANCEL_PENDING') and s.end_date<today then 'EXPIRED'
        when s.status in ('ACTIVE','PAUSED','CANCEL_PENDING') and s.start_date>today then 'UPCOMING'
        else s.status end subscription_state,
      case when s.id is null then null else jsonb_build_object(
        'id',s.id,'status',s.status,'provider_id',s.provider_id,'provider_name',provider.display_name,
        'package_name',package.name,'package_kind',package.kind,'duration_days',package.duration_days,
        'start_date',s.start_date,'end_date',s.end_date,'pause_reason',s.pause_reason,
        'package_price_paise',s.package_price_paise,'total_paid_paise',s.total_paid_paise
      ) end subscription
    from public.profiles p
    join auth.users u on u.id=p.id
    left join public.customer_wallets wallet on wallet.customer_id=p.id
    left join lateral (
      -- A valid saved phone marks registration; anonymous drafts remain visible
      -- under Incomplete profiles rather than inflating registered-user counts.
      select '+91'||right(candidate.value,10) value
      from unnest(array[regexp_replace(coalesce(p.phone,''),'[^0-9]','','g'),
                        regexp_replace(coalesce(u.phone,''),'[^0-9]','','g')]) with ordinality candidate(value,priority)
      where candidate.value ~ '^[0-9]{10}$' or candidate.value ~ '^91[0-9]{10}$'
      order by candidate.priority limit 1
    ) phone on true
    left join lateral (
      -- Choose an in-period plan before a future relocation/upgrade or history.
      -- One row per account even with several subscriptions or saved addresses.
      select cs.* from public.customer_subscriptions cs where cs.customer_id=p.id
      order by case
        when cs.start_date<=today and cs.end_date>=today and cs.status='ACTIVE' then 0
        when cs.start_date<=today and cs.end_date>=today and cs.status='PAUSED' then 1
        when cs.start_date<=today and cs.end_date>=today and cs.status='CANCEL_PENDING' then 2
        when cs.start_date>today and cs.status in ('ACTIVE','PAUSED','CANCEL_PENDING') then 3
        else 4 end,
        case when cs.start_date>today and cs.status in ('ACTIVE','PAUSED','CANCEL_PENDING') then cs.start_date end asc,
        cs.created_at desc,cs.id
      limit 1
    ) s on true
    left join public.providers provider on provider.id=s.provider_id
    left join public.packages package on package.id=s.package_id
    left join lateral (
      select ca.* from public.customer_addresses ca where ca.customer_id=p.id
      order by ca.is_default desc,ca.updated_at desc,ca.id limit 1
    ) a on true
    left join public.customer_checkout_drafts draft on draft.customer_id=p.id
    left join lateral (
      select candidate.value pincode from unnest(array[
        case when s.status in ('ACTIVE','PAUSED','CANCEL_PENDING') and s.start_date<=today and s.end_date>=today
          then nullif(trim(s.delivery_address->>'pincode'),'') end,
        nullif(trim(a.pincode),''),p.registration_pincode,
        nullif(trim(draft.checkout_payload#>>'{delivery_address,pincode}'),''),
        nullif(trim(u.raw_user_meta_data->>'pincode'),'')
      ]) with ordinality candidate(value,priority)
      where candidate.value ~ '^[1-9][0-9]{5}$' order by candidate.priority limit 1
    ) location on true
    left join public.pincodes pin on pin.code=location.pincode
    where exists(select 1 from public.user_roles role where role.user_id=p.id and role.role='CUSTOMER')
      and not exists(select 1 from public.admin_staff_profiles staff where staff.user_id=p.id)
      and not exists(select 1 from public.user_roles role where role.user_id=p.id and role.role in ('ADMIN','OPERATIONS','FINANCE'))
      -- The auth trigger gives provider logins CUSTOMER too. Exclude provider-only
      -- identities, but keep a provider who also completed customer onboarding.
      and (not exists(select 1 from public.provider_members member where member.user_id=p.id)
           and not exists(select 1 from public.user_roles role where role.user_id=p.id and role.role='PROVIDER')
        or p.registration_pincode is not null or s.id is not null or a.id is not null or draft.customer_id is not null)
  ), matching as (
    select * from customers c
    where (filter_name='ALL'
      or (filter_name='REGISTERED' and c.is_registered)
      or (filter_name='ACTIVE' and c.is_registered and c.active_subscription)
      or (filter_name='PAUSED' and c.is_registered and c.paused_subscription)
      or (filter_name='NO_PLAN' and c.is_registered and not c.has_current_plan)
      or (filter_name='INCOMPLETE' and not c.is_registered)
      or (filter_name='INACTIVE' and not c.account_enabled))
      and (q='' or strpos(lower(coalesce(c.full_name,'')),q)>0 or strpos(c.id::text,q)>0
        or strpos(coalesce(c.pincode,''),q)>0
        or (digits<>'' and strpos(regexp_replace(coalesce(c.phone,''),'[^0-9]','','g'),digits)>0))
  )
  select jsonb_build_object(
    'as_of',now(),'as_of_date',today,'page',page_number,'page_size',25,'filter',filter_name,
    'summary',jsonb_build_object(
      'total_accounts',(select count(*) from customers),
      'registered',(select count(*) from customers where is_registered),
      'active',(select count(*) from customers where is_registered and active_subscription),
      'paused',(select count(*) from customers where is_registered and paused_subscription),
      'no_plan',(select count(*) from customers where is_registered and not has_current_plan),
      'incomplete',(select count(*) from customers where not is_registered),
      'inactive',(select count(*) from customers where not account_enabled)
    ),
    'total',(select count(*) from matching),
    'customers',coalesce((select jsonb_agg(to_jsonb(row_data) order by registered_at desc,id)
      from (select * from matching order by registered_at desc,id limit 25 offset page_number*25) row_data),'[]'::jsonb)
  ) into result;
  insert into public.audit_logs(actor_id,action,entity_type,metadata)
    values(auth.uid(),'CUSTOMER_DIRECTORY_VIEWED','customer',jsonb_build_object(
      'filter',filter_name,'page',page_number,'result_count',result->'total'));
  return result;
end; $$;

revoke all on function public.admin_customer_directory(text,text,integer) from public,anon;
grant execute on function public.admin_customer_directory(text,text,integer) to authenticated;
notify pgrst,'reload schema';
