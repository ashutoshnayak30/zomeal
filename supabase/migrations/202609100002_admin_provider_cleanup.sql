-- Guarded cleanup for duplicate, test and abandoned provider profiles.
-- It reuses the audited account deletion engine and its finance/subscription blockers.

create table if not exists public.provider_cleanup_protection (
  provider_id uuid primary key references public.providers(id) on delete cascade,
  protected_by uuid references auth.users(id),
  reason text not null,
  created_at timestamptz not null default now()
);
alter table public.provider_cleanup_protection enable row level security;
revoke all on public.provider_cleanup_protection from anon,authenticated;
grant all on public.provider_cleanup_protection to service_role;

-- Protect the three genuine pilot providers named by the business owner.
insert into public.provider_cleanup_protection(provider_id,reason)
select id,'Genuine pilot provider protected during initial cleanup'
from public.providers
where lower(trim(display_name)) in ('yogesh kitchen','manju kitchen 1','silu')
on conflict(provider_id) do nothing;

create or replace function public.admin_provider_cleanup_candidates(
  search_text text default null,
  candidate_filter text default 'CANDIDATES'
)
returns jsonb
language plpgsql
security definer
set search_path=public
as $$
declare
  result jsonb := '[]'::jsonb;
  row_data record;
  preview jsonb;
  likely_test boolean;
  normalized_filter text := upper(coalesce(nullif(trim(candidate_filter),''),'CANDIDATES'));
begin
  if not public.can_manage_accounts() then raise exception 'Administrator access required' using errcode='42501'; end if;
  if normalized_filter not in ('CANDIDATES','DRAFT_INACTIVE','ALL') then raise exception 'Invalid provider cleanup filter'; end if;

  for row_data in
    select p.*,
      protection.reason as protection_reason,
      (select count(*) from public.provider_members member where member.provider_id=p.id) as member_count,
      (select count(*) from public.packages package where package.provider_id=p.id) as package_count,
      (select count(*) from public.provider_menus menu where menu.provider_id=p.id) as menu_count,
      (select count(*) from public.provider_service_areas area where area.provider_id=p.id) as area_count,
      (select count(*) from public.provider_media media where media.provider_id=p.id) as photo_count,
      (select count(*) from public.customer_subscriptions subscription where subscription.provider_id=p.id) as subscription_count,
      (select count(*) from public.payment_orders payment where payment.provider_id=p.id) as payment_count
    from public.providers p
    left join public.provider_cleanup_protection protection on protection.provider_id=p.id
    where search_text is null or trim(search_text)='' or concat_ws(' ',p.display_name,p.contact_person_name,p.support_phone,p.id::text) ilike '%'||trim(search_text)||'%'
    order by p.created_at desc,p.display_name
  loop
    likely_test := row_data.status in ('DRAFT','INACTIVE')
      or lower(coalesce(row_data.display_name,'')) ~ '(test|testing|demo|dummy|sample|fake|temp)'
      or (row_data.package_count=0 and row_data.menu_count=0 and row_data.area_count=0);
    if normalized_filter='DRAFT_INACTIVE' and row_data.status not in ('DRAFT','INACTIVE') then continue; end if;
    if normalized_filter='CANDIDATES' and not likely_test then continue; end if;

    preview := public.super_admin_deletion_preview('provider',row_data.id);
    result := result || jsonb_build_array(jsonb_build_object(
      'id',row_data.id,'name',row_data.display_name,'contact_name',row_data.contact_person_name,
      'phone',row_data.support_phone,'status',row_data.status,'created_at',row_data.created_at,
      'likely_test',likely_test,'protected',row_data.protection_reason is not null,
      'protection_reason',row_data.protection_reason,
      'deletion_allowed',(preview->>'allowed')::boolean and row_data.protection_reason is null,
      'blockers',case when row_data.protection_reason is null then preview->'blockers'
        else (preview->'blockers') || jsonb_build_array('Marked as a genuine provider. Remove Keep protection before deletion.') end,
      'member_count',row_data.member_count,'package_count',row_data.package_count,
      'menu_count',row_data.menu_count,'area_count',row_data.area_count,'photo_count',row_data.photo_count,
      'subscription_count',row_data.subscription_count,'payment_count',row_data.payment_count
    ));
  end loop;
  return jsonb_build_object('providers',result,'filter',normalized_filter,'total',jsonb_array_length(result));
end;
$$;

create or replace function public.admin_set_provider_cleanup_protection(
  target_provider uuid,
  should_protect boolean,
  target_reason text default null
)
returns jsonb
language plpgsql
security definer
set search_path=public
as $$
declare provider_name text;
begin
  if not public.can_manage_accounts() then raise exception 'Administrator access required' using errcode='42501'; end if;
  select display_name into provider_name from public.providers where id=target_provider;
  if provider_name is null then raise exception 'Provider not found'; end if;
  if should_protect then
    insert into public.provider_cleanup_protection(provider_id,protected_by,reason)
    values(target_provider,auth.uid(),coalesce(nullif(trim(target_reason),''),'Confirmed as a genuine provider by administrator'))
    on conflict(provider_id) do update set protected_by=auth.uid(),reason=excluded.reason,created_at=now();
  else
    delete from public.provider_cleanup_protection where provider_id=target_provider;
  end if;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,metadata)
  values(auth.uid(),case when should_protect then 'PROVIDER_CLEANUP_PROTECTED' else 'PROVIDER_CLEANUP_UNPROTECTED' end,
    'provider',target_provider::text,jsonb_build_object('provider_name',provider_name,'reason',target_reason));
  return jsonb_build_object('provider_id',target_provider,'protected',should_protect);
end;
$$;

create or replace function public.admin_delete_provider_cleanup(
  target_ids uuid[],
  confirmation text,
  reason text
)
returns jsonb
language plpgsql
security definer
set search_path=public
as $$
declare
  target uuid;
  targets uuid[];
  target_count integer;
  preview jsonb;
  outcome jsonb;
  deleted_rows jsonb := '[]'::jsonb;
begin
  if not public.can_manage_accounts() then raise exception 'Administrator access required' using errcode='42501'; end if;
  select array_agg(id order by id) into targets from (select distinct unnest(target_ids) id) selected;
  target_count := coalesce(cardinality(targets),0);
  if target_count<1 or target_count>25 then raise exception 'Select between 1 and 25 providers'; end if;
  if confirmation is distinct from 'DELETE '||target_count||' PROVIDERS' then raise exception 'Type the exact confirmation shown'; end if;
  if length(trim(coalesce(reason,'')))<10 or length(reason)>500 then raise exception 'A reason between 10 and 500 characters is required'; end if;

  foreach target in array targets loop
    if exists(select 1 from public.provider_cleanup_protection where provider_id=target) then
      raise exception 'Deletion stopped: provider % is marked as genuine',target;
    end if;
    preview := public.super_admin_deletion_preview('provider',target);
    if not coalesce((preview->>'allowed')::boolean,false) then
      raise exception 'Deletion stopped for %: %',coalesce(preview->>'name',target::text),preview->'blockers';
    end if;
  end loop;

  foreach target in array targets loop
    outcome := public.super_admin_delete_account('provider',target,'DELETE '||target::text,trim(reason));
    deleted_rows := deleted_rows || jsonb_build_array(jsonb_build_object(
      'provider_id',target,'job_id',outcome->>'job_id','cleanup_pending',outcome->'cleanup_pending'
    ));
  end loop;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,metadata)
  values(auth.uid(),'PROVIDER_CLEANUP_BATCH_DELETED','provider_cleanup',gen_random_uuid()::text,
    jsonb_build_object('provider_ids',to_jsonb(targets),'reason',trim(reason),'count',target_count));
  return jsonb_build_object('deleted',target_count,'results',deleted_rows);
end;
$$;

revoke all on function public.admin_provider_cleanup_candidates(text,text),
  public.admin_set_provider_cleanup_protection(uuid,boolean,text),
  public.admin_delete_provider_cleanup(uuid[],text,text) from public;
grant execute on function public.admin_provider_cleanup_candidates(text,text),
  public.admin_set_provider_cleanup_protection(uuid,boolean,text),
  public.admin_delete_provider_cleanup(uuid[],text,text) to authenticated;

notify pgrst,'reload schema';
