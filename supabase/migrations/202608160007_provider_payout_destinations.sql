-- Verified payout destinations collected only after provider activation.
create table public.provider_payout_destinations (
  id uuid primary key default gen_random_uuid(),
  provider_id uuid not null unique references public.providers(id) on delete cascade,
  method text not null check(method in ('UPI','BANK_TRANSFER')),
  account_holder_name text not null,
  upi_id text,
  bank_account_number text,
  bank_ifsc text,
  bank_name text,
  status text not null default 'PENDING' check(status in ('DRAFT','PENDING','VERIFIED','REJECTED')),
  provider_note text,
  admin_note text,
  submitted_at timestamptz,
  verified_at timestamptz,
  verified_by uuid references auth.users(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint payout_destination_fields check(
    (method='UPI' and nullif(trim(upi_id),'') is not null)
    or (method='BANK_TRANSFER' and nullif(trim(bank_account_number),'') is not null and nullif(trim(bank_ifsc),'') is not null)
  )
);
create trigger payout_destination_updated before update on public.provider_payout_destinations
for each row execute function public.set_updated_at();
alter table public.provider_payout_destinations enable row level security;
create policy payout_destination_provider_read on public.provider_payout_destinations for select to authenticated
using(public.is_provider_member(provider_id));
create policy payout_destination_finance_read on public.provider_payout_destinations for select to authenticated
using(public.has_role('ADMIN') or public.has_role('FINANCE'));

create or replace function public.mask_payout_value(value text,visible integer default 4)
returns text language sql immutable as $$
  select case when value is null then null when length(value)<=visible then repeat('*',length(value))
    else repeat('*',greatest(length(value)-visible,4))||right(value,visible) end;
$$;

create or replace function public.provider_payout_destination()
returns jsonb language plpgsql stable security definer set search_path=public as $$
declare target_provider uuid; d public.provider_payout_destinations;
begin
  select pm.provider_id into target_provider from public.provider_members pm where pm.user_id=auth.uid() and pm.is_active limit 1;
  if target_provider is null then raise exception 'Active provider membership is required'; end if;
  select * into d from public.provider_payout_destinations where provider_id=target_provider;
  if d.id is null then return jsonb_build_object('provider_id',target_provider,'status','NOT_ADDED'); end if;
  return jsonb_build_object('id',d.id,'provider_id',d.provider_id,'method',d.method,'account_holder_name',d.account_holder_name,
    'masked_destination',case when d.method='UPI' then public.mask_payout_value(d.upi_id,5)
      else coalesce(d.bank_name||' · ','')||public.mask_payout_value(d.bank_account_number,4)||' · '||upper(d.bank_ifsc) end,
    'status',d.status,'provider_note',d.provider_note,'admin_note',d.admin_note,'submitted_at',d.submitted_at,'verified_at',d.verified_at);
end; $$;

create or replace function public.provider_save_payout_destination(target_method text,target_holder text,target_upi text default null,target_account text default null,target_ifsc text default null,target_bank text default null,target_note text default null)
returns jsonb language plpgsql security definer set search_path=public as $$
declare target_provider uuid; normalized text:=upper(trim(target_method)); destination_id uuid;
begin
  select pm.provider_id into target_provider from public.provider_members pm join public.providers p on p.id=pm.provider_id
    where pm.user_id=auth.uid() and pm.is_active and p.status='ACTIVE' limit 1;
  if target_provider is null then raise exception 'Payout details can be added after provider activation'; end if;
  if normalized not in ('UPI','BANK_TRANSFER') then raise exception 'Choose UPI or bank transfer'; end if;
  if nullif(trim(target_holder),'') is null then raise exception 'Account holder name is required'; end if;
  if normalized='UPI' and (nullif(trim(target_upi),'') is null or position('@' in target_upi)=0) then raise exception 'Enter a valid UPI ID'; end if;
  if normalized='BANK_TRANSFER' and (length(regexp_replace(coalesce(target_account,''),'[^0-9]','','g'))<6 or upper(trim(coalesce(target_ifsc,''))) !~ '^[A-Z]{4}0[A-Z0-9]{6}$') then raise exception 'Enter a valid account number and IFSC'; end if;
  insert into public.provider_payout_destinations(provider_id,method,account_holder_name,upi_id,bank_account_number,bank_ifsc,bank_name,status,provider_note,submitted_at)
  values(target_provider,normalized,trim(target_holder),case when normalized='UPI' then lower(trim(target_upi)) end,
    case when normalized='BANK_TRANSFER' then regexp_replace(target_account,'[^0-9]','','g') end,
    case when normalized='BANK_TRANSFER' then upper(trim(target_ifsc)) end,case when normalized='BANK_TRANSFER' then nullif(trim(target_bank),'') end,
    'PENDING',nullif(trim(target_note),''),now())
  on conflict(provider_id) do update set method=excluded.method,account_holder_name=excluded.account_holder_name,upi_id=excluded.upi_id,
    bank_account_number=excluded.bank_account_number,bank_ifsc=excluded.bank_ifsc,bank_name=excluded.bank_name,status='PENDING',
    provider_note=excluded.provider_note,admin_note=null,submitted_at=now(),verified_at=null,verified_by=null
  returning id into destination_id;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data) values(auth.uid(),'PAYOUT_DESTINATION_SUBMITTED','payout_destination',destination_id::text,jsonb_build_object('provider_id',target_provider,'method',normalized));
  return public.provider_payout_destination();
end; $$;

create or replace function public.admin_payout_destination_queue(target_status text default null)
returns jsonb language plpgsql stable security definer set search_path=public as $$
declare result jsonb;
begin
  if not (public.has_role('ADMIN') or public.has_role('FINANCE')) then raise exception 'Finance access is required'; end if;
  select coalesce(jsonb_agg(jsonb_build_object(
    'id',d.id,'provider_id',d.provider_id,'provider_name',p.display_name,'provider_phone',p.support_phone,'method',d.method,
    'account_holder_name',d.account_holder_name,'masked_destination',case when d.method='UPI' then public.mask_payout_value(d.upi_id,5) else coalesce(d.bank_name||' · ','')||public.mask_payout_value(d.bank_account_number,4)||' · '||upper(d.bank_ifsc) end,
    'status',d.status,'provider_note',d.provider_note,'admin_note',d.admin_note,'submitted_at',d.submitted_at,'verified_at',d.verified_at) order by d.submitted_at desc),'[]'::jsonb) into result
  from public.provider_payout_destinations d join public.providers p on p.id=d.provider_id
  where target_status is null or target_status='' or d.status=upper(target_status);
  return result;
end;
$$;

create or replace function public.admin_review_payout_destination(target_destination uuid,target_status text,target_note text default null)
returns jsonb language plpgsql security definer set search_path=public as $$
declare normalized text:=upper(trim(target_status)); d public.provider_payout_destinations;
begin
  if not (public.has_role('ADMIN') or public.has_role('FINANCE')) then raise exception 'Finance access is required'; end if;
  if normalized not in ('VERIFIED','REJECTED') then raise exception 'Choose VERIFIED or REJECTED'; end if;
  if normalized='REJECTED' and nullif(trim(target_note),'') is null then raise exception 'A rejection reason is required'; end if;
  update public.provider_payout_destinations set status=normalized,admin_note=nullif(trim(target_note),''),verified_by=case when normalized='VERIFIED' then auth.uid() end,verified_at=case when normalized='VERIFIED' then now() end
  where id=target_destination returning * into d;
  if d.id is null then raise exception 'Payout destination not found'; end if;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data) values(auth.uid(),'PAYOUT_DESTINATION_'||normalized,'payout_destination',d.id::text,jsonb_build_object('provider_id',d.provider_id,'note',target_note));
  return jsonb_build_object('id',d.id,'status',d.status,'provider_id',d.provider_id);
end; $$;

-- A settlement must have an approved destination matching the requested electronic method.
create or replace function public.provider_request_payout(target_amount_paise bigint,target_method text,target_note text default null)
returns jsonb language plpgsql security definer set search_path=public as $$
declare target_provider uuid; request_id uuid; available bigint; normalized text:=upper(trim(target_method)); verified_method text;
begin
  select pm.provider_id into target_provider from public.provider_members pm where pm.user_id=auth.uid() and pm.is_active limit 1;
  if target_provider is null then raise exception 'Active provider membership is required'; end if;
  if target_amount_paise<=0 then raise exception 'Payout amount must be positive'; end if;
  if normalized not in ('UPI','BANK_TRANSFER','CHEQUE','CASH') then raise exception 'Unsupported payout method'; end if;
  select method into verified_method from public.provider_payout_destinations where provider_id=target_provider and status='VERIFIED';
  if normalized in ('UPI','BANK_TRANSFER') and verified_method is null then raise exception 'Add and verify your payout details before requesting an electronic payout'; end if;
  if normalized in ('UPI','BANK_TRANSFER') and normalized<>verified_method then raise exception 'Requested payout method must match your verified destination'; end if;
  select coalesce((public.provider_earnings_summary()->>'available_paise')::bigint,0) into available;
  if target_amount_paise>available then raise exception 'Requested amount exceeds available earnings'; end if;
  insert into public.provider_payout_requests(provider_id,amount_paise,preferred_method,provider_note,requested_by)
  values(target_provider,target_amount_paise,normalized,nullif(trim(target_note),''),auth.uid()) returning id into request_id;
  return jsonb_build_object('request_id',request_id,'status','PENDING');
end; $$;

revoke all on function public.provider_payout_destination(),public.provider_save_payout_destination(text,text,text,text,text,text,text),public.admin_payout_destination_queue(text),public.admin_review_payout_destination(uuid,text,text) from public;
grant execute on function public.provider_payout_destination(),public.provider_save_payout_destination(text,text,text,text,text,text,text),public.admin_payout_destination_queue(text),public.admin_review_payout_destination(uuid,text,text) to authenticated;
