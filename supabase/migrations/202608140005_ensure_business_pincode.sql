-- Ensure a provider's business pincode exists before the providers FK is checked.
-- The kitchen address may be outside (or different from) its delivery coverage.

create or replace function public.ensure_provider_business_pincode()
returns trigger language plpgsql security definer set search_path=public as $$
begin
  if new.business_pincode is null or trim(new.business_pincode)='' then
    return new;
  end if;
  if new.business_pincode !~ '^[1-9][0-9]{5}$' then
    raise exception 'Business pincode must be a valid 6-digit Indian pincode';
  end if;
  insert into public.pincodes(code,city,state,country_code,is_enabled)
  values(
    new.business_pincode,
    coalesce(nullif(trim(new.business_city),''),'Unknown'),
    coalesce(nullif(trim(new.business_state),''),'Unknown'),
    'IN',
    true
  )
  on conflict(code) do update set
    city=case when pincodes.city='Unknown' then excluded.city else pincodes.city end,
    state=case when pincodes.state='Unknown' then excluded.state else pincodes.state end,
    updated_at=now();
  return new;
end; $$;

drop trigger if exists providers_ensure_business_pincode on public.providers;
create trigger providers_ensure_business_pincode
before insert or update of business_pincode,business_city,business_state on public.providers
for each row execute function public.ensure_provider_business_pincode();
