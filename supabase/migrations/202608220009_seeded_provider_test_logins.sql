-- Staging-only login bridge for the five catalogue providers seeded in
-- 202608190002. A caller can claim only the provider whose known test phone is
-- already present in the caller's signed JWT metadata.
create or replace function public.provider_claim_seeded_test_account(target_phone text)
returns jsonb
language plpgsql
security definer
set search_path=public
as $$
declare
  normalized_phone text:=regexp_replace(coalesce(target_phone,''),'[^0-9]','','g');
  jwt_phone text:=regexp_replace(coalesce(auth.jwt()->'user_metadata'->>'zomeal_test_phone',''),'[^0-9]','','g');
  provider_number integer;
  target_provider uuid;
begin
  if auth.uid() is null then raise exception 'Authentication is required'; end if;
  if coalesce(auth.jwt()->'user_metadata'->>'account_type','')<>'PROVIDER_TEST' then
    raise exception 'This action is limited to provider test accounts';
  end if;
  if right(jwt_phone,10)<>normalized_phone then raise exception 'The signed-in test phone does not match'; end if;
  if normalized_phone not in ('7000000001','7000000002','7000000003','7000000004','7000000005') then
    raise exception 'Unknown seeded provider test number';
  end if;
  provider_number:=right(normalized_phone,1)::integer;
  target_provider:=md5('zomeal-test-provider-'||provider_number)::uuid;
  if not exists(select 1 from public.providers where id=target_provider) then raise exception 'Seeded provider was not found'; end if;

  insert into public.user_roles(user_id,role) values(auth.uid(),'PROVIDER') on conflict(user_id,role) do nothing;
  insert into public.provider_members(provider_id,user_id,member_role,is_active)
  values(target_provider,auth.uid(),'OWNER',true)
  on conflict(provider_id,user_id) do update set is_active=true,member_role='OWNER';
  return jsonb_build_object('provider_id',target_provider,'phone',normalized_phone);
end;
$$;

revoke all on function public.provider_claim_seeded_test_account(text) from public,anon;
grant execute on function public.provider_claim_seeded_test_account(text) to authenticated;
