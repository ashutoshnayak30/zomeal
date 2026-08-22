-- Provider profile hub: identity and real subscriber totals for the signed-in
-- provider. Financial terms remain sourced from the dedicated commission RPC.

create or replace function public.provider_profile_hub()
returns jsonb
language plpgsql
stable
security definer
set search_path=public
as $$
declare
  target_provider uuid;
  result jsonb;
begin
  select member.provider_id into target_provider
    from public.provider_members member
   where member.user_id=auth.uid() and member.is_active
   order by member.created_at desc limit 1;
  if target_provider is null then raise exception 'Provider membership was not found'; end if;

  select jsonb_build_object(
    'provider_id',provider.id,
    'provider_name',provider.display_name,
    'contact_name',provider.contact_person_name,
    'status',provider.status,
    'category',provider.dietary_type,
    'city',provider.business_city,
    'state',provider.business_state,
    'pincode',provider.business_pincode,
    'address',provider.business_address_line,
    'active_subscribers',(select count(*) from public.customer_subscriptions subscription
      where subscription.provider_id=provider.id and subscription.status in('ACTIVE','PAUSED','CANCEL_PENDING')
        and subscription.end_date>=current_date),
    'active_packages',(select count(*) from public.packages package where package.provider_id=provider.id and package.is_active),
    'serviceable_pincodes',(select count(*) from public.provider_service_areas area where area.provider_id=provider.id and area.status='APPROVED'),
    'pending_change_requests',(select count(*) from public.provider_change_requests request where request.provider_id=provider.id and request.status='PENDING'
      and request.requested_payload->>'scope'='FULL_BUSINESS_UPDATE')
  ) into result from public.providers provider where provider.id=target_provider;
  return coalesce(result,'{}'::jsonb);
end; $$;

revoke all on function public.provider_profile_hub() from public;
grant execute on function public.provider_profile_hub() to authenticated;

