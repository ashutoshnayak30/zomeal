-- Correct the admin provider decision wrapper to call the established
-- activation function and preserve its readiness validation.

create or replace function public.admin_review_provider(
  target_id uuid,
  decision text,
  review_reason text default null
) returns void language plpgsql security definer set search_path=public as $$
declare
  old_data jsonb;
  new_data jsonb;
  normalized text := upper(decision);
begin
  perform public.require_staff(array['ADMIN','OPERATIONS']::public.app_role[]);
  if normalized not in ('APPROVED','REJECTED','SUSPENDED') then
    raise exception 'Invalid decision';
  end if;
  if normalized in ('REJECTED','SUSPENDED') and nullif(trim(review_reason),'') is null then
    raise exception 'Reason is required';
  end if;

  select to_jsonb(p) into old_data
  from public.providers p where id=target_id for update;
  if old_data is null then raise exception 'Provider not found'; end if;

  if normalized='APPROVED' then
    perform public.activate_provider(target_id);
  else
    update public.providers
    set status=case normalized
      when 'SUSPENDED' then 'SUSPENDED'::public.provider_status
      else 'DRAFT'::public.provider_status
    end,
    approved_by=null,
    approved_at=null
    where id=target_id;
  end if;

  select to_jsonb(p) into new_data
  from public.providers p where id=target_id;

  insert into public.audit_logs(
    actor_id,action,entity_type,entity_id,before_data,after_data,metadata
  ) values (
    auth.uid(),'ADMIN_PROVIDER_'||normalized,'PROVIDER',target_id::text,
    old_data,new_data,jsonb_build_object('reason',review_reason)
  );
end; $$;

revoke all on function public.admin_review_provider(uuid,text,text) from public;
grant execute on function public.admin_review_provider(uuid,text,text) to authenticated;

