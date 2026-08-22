-- Keep the provider editor anchored to a complete business snapshot.
-- Older activated accounts can have an empty/stale onboarding draft after an
-- admin decision, while the accepted change request still contains the exact
-- profile, package and seven-day menu payload that was published.

create or replace function public.provider_submitted_application()
returns jsonb
language sql
stable
security definer
set search_path=public
as $$
  with membership as (
    select provider.id as provider_id, provider.status as provider_status
      from public.provider_members member
      join public.providers provider on provider.id=member.provider_id
     where member.user_id=auth.uid()
       and member.is_active
     order by member.created_at desc
     limit 1
  ), latest_draft as (
    select draft.payload,draft.status,draft.updated_at,draft.provider_id
      from public.provider_form_drafts draft
     where draft.owner_user_id=auth.uid()
       and draft.form_scope='provider_mobile_onboarding'
       and jsonb_typeof(draft.payload)='object'
       and draft.payload<>'{}'::jsonb
     order by draft.updated_at desc
     limit 1
  ), latest_business_request as (
    select request.requested_payload->'payload' as payload,
           request.status,
           coalesce(request.reviewed_at,request.requested_at) as updated_at,
           request.provider_id
      from public.provider_change_requests request
      join membership on membership.provider_id=request.provider_id
     where request.requested_payload->>'scope'='FULL_BUSINESS_UPDATE'
       and jsonb_typeof(request.requested_payload->'payload')='object'
       and request.requested_payload->'payload'<>'{}'::jsonb
     order by
       case request.status when 'PENDING' then 0 when 'APPROVED' then 1 when 'REJECTED' then 2 else 3 end,
       request.requested_at desc
     limit 1
  ), selected as (
    -- A live edit draft is newest and must resume exactly where the provider
    -- stopped. Otherwise use the last complete submitted/accepted snapshot.
    select payload,status::text as status,updated_at,provider_id,0 as priority
      from latest_draft
    union all
    select payload,status::text,updated_at,provider_id,1
      from latest_business_request
    order by priority,updated_at desc
    limit 1
  )
  select coalesce((
    select jsonb_build_object(
      'payload',selected.payload,
      'draft_status',selected.status,
      'submitted_at',selected.updated_at,
      'provider_id',coalesce(selected.provider_id,membership.provider_id),
      'provider_status',membership.provider_status
    )
      from selected
      left join membership on true
  ),'{}'::jsonb);
$$;

revoke all on function public.provider_submitted_application() from public;
grant execute on function public.provider_submitted_application() to authenticated;

notify pgrst,'reload schema';
