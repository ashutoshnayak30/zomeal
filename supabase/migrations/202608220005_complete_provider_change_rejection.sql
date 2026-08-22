-- Complete the rejected provider-change lifecycle.
-- Customer-facing approved data is deliberately untouched. The rejected
-- replacement remains an editable provider draft so it can be corrected and
-- submitted as a new review request.

create or replace function public.sync_rejected_provider_change_to_draft()
returns trigger
language plpgsql
security definer
set search_path=public
as $$
declare
  rejected_payload jsonb;
begin
  if new.status = 'REJECTED'
     and old.status is distinct from new.status
     and new.requested_payload->>'scope' = 'FULL_BUSINESS_UPDATE' then
    rejected_payload := coalesce(new.requested_payload->'payload', '{}'::jsonb);

    update public.provider_form_drafts
       set payload = rejected_payload,
           provider_id = new.provider_id,
           status = 'IN_PROGRESS',
           updated_at = now()
     where owner_user_id = new.requested_by
       and form_scope = 'provider_mobile_onboarding';
  end if;
  return new;
end;
$$;

drop trigger if exists provider_change_rejection_reopens_draft on public.provider_change_requests;
create trigger provider_change_rejection_reopens_draft
after update of status on public.provider_change_requests
for each row execute function public.sync_rejected_provider_change_to_draft();

-- Also repair a rejection that may already exist when this migration is
-- installed. Only a submitted draft is reopened; an in-progress correction is
-- never overwritten.
with latest_rejection as (
  select distinct on (request.provider_id, request.requested_by)
         request.provider_id, request.requested_by,
         request.requested_payload->'payload' as payload
    from public.provider_change_requests request
   where request.status = 'REJECTED'
     and request.requested_payload->>'scope' = 'FULL_BUSINESS_UPDATE'
   order by request.provider_id, request.requested_by, request.requested_at desc
)
update public.provider_form_drafts draft
   set payload = rejected.payload,
       status = 'IN_PROGRESS',
       updated_at = now()
  from latest_rejection rejected
 where draft.provider_id = rejected.provider_id
   and draft.owner_user_id = rejected.requested_by
   and draft.form_scope = 'provider_mobile_onboarding'
   and draft.status = 'SUBMITTED';

create or replace function public.provider_latest_business_change()
returns jsonb
language sql
stable
security definer
set search_path=public
as $$
  select coalesce((
    select jsonb_build_object(
      'id', request.id,
      'provider_id', request.provider_id,
      'status', request.status,
      'requested_at', request.requested_at,
      'reviewed_at', request.reviewed_at,
      'review_note', request.review_note
    )
      from public.provider_change_requests request
      join public.provider_members member on member.provider_id = request.provider_id
     where member.user_id = auth.uid()
       and member.is_active
       and request.requested_payload->>'scope' = 'FULL_BUSINESS_UPDATE'
     order by request.requested_at desc
     limit 1
  ), jsonb_build_object('status', 'NONE'));
$$;

revoke all on function public.provider_latest_business_change() from public;
grant execute on function public.provider_latest_business_change() to authenticated;
