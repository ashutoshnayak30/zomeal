-- Provider self-service review and editing while an application is pending.

create or replace function public.provider_submitted_application()
returns jsonb language sql stable security definer set search_path=public as $$
  select coalesce((
    select jsonb_build_object(
      'payload',d.payload,
      'draft_status',d.status,
      'submitted_at',d.updated_at,
      'provider_id',p.id,
      'provider_status',p.status
    )
    from public.provider_form_drafts d
    left join public.providers p on p.id=d.provider_id
    where d.owner_user_id=auth.uid()
      and d.form_scope='provider_mobile_onboarding'
    order by d.updated_at desc
    limit 1
  ),'{}'::jsonb);
$$;

create or replace function public.provider_resume_application()
returns jsonb language plpgsql security definer set search_path=public as $$
declare result jsonb; current_status public.provider_status; target_provider_id uuid;
begin
  select p.id,p.status into target_provider_id,current_status
  from public.provider_members pm join public.providers p on p.id=pm.provider_id
  where pm.user_id=auth.uid() and pm.is_active
  order by pm.created_at desc limit 1 for update of p;

  if target_provider_id is null then raise exception 'Provider application was not found'; end if;
  if current_status not in ('DRAFT'::public.provider_status,'PENDING_APPROVAL'::public.provider_status) then
    raise exception 'Only draft or pending applications can be edited';
  end if;

  update public.providers set status='DRAFT',updated_at=now() where id=target_provider_id;
  update public.provider_form_drafts set status='IN_PROGRESS',updated_at=now()
  where id=(select id from public.provider_form_drafts
    where owner_user_id=auth.uid() and form_scope='provider_mobile_onboarding'
    order by updated_at desc limit 1)
  returning payload into result;
  return coalesce(result,'{}'::jsonb);
end; $$;

revoke all on function public.provider_submitted_application(),public.provider_resume_application() from public;
grant execute on function public.provider_submitted_application(),public.provider_resume_application() to authenticated;

