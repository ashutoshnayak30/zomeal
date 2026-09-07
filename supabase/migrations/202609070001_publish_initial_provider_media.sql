-- Initial onboarding is reviewed as one provider application. When staff
-- activates that provider, publish the photos that were part of the submitted
-- application as well. Photos uploaded later by an active provider continue to
-- travel through a provider_change_request and are not auto-approved here.

create or replace function public.publish_initial_provider_media_on_activation()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  if new.status = 'ACTIVE'
     and old.status is distinct from 'ACTIVE'
     and new.approved_by is not null then
    update public.provider_media media
    set status = 'APPROVED',
        reviewed_by = new.approved_by,
        reviewed_at = coalesce(new.approved_at, now()),
        rejection_reason = null,
        updated_at = now()
    where media.provider_id = new.id
      and media.status = 'PENDING_REVIEW'
      and media.change_request_id is null
      and media.submitted_at is not null
      and media.submitted_at <= coalesce(new.approved_at, now());
  end if;
  return new;
end;
$$;

drop trigger if exists publish_initial_provider_media_on_activation on public.providers;
create trigger publish_initial_provider_media_on_activation
after update of status on public.providers
for each row execute function public.publish_initial_provider_media_on_activation();

-- Repair existing active providers affected by the old activation workflow.
-- The timestamp boundary deliberately excludes any upload made after approval.
update public.provider_media media
set status = 'APPROVED',
    reviewed_by = provider.approved_by,
    reviewed_at = provider.approved_at,
    rejection_reason = null,
    updated_at = now()
from public.providers provider
where media.provider_id = provider.id
  and provider.status = 'ACTIVE'
  and provider.approved_by is not null
  and provider.approved_at is not null
  and media.status = 'PENDING_REVIEW'
  and media.change_request_id is null
  and media.submitted_at is not null
  and media.submitted_at <= provider.approved_at;

comment on function public.publish_initial_provider_media_on_activation() is
'Publishes initial onboarding photos reviewed as part of provider activation; later provider edits still require a change-request decision.';
