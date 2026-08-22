create table if not exists public.customer_checkout_drafts (
  customer_id uuid primary key references auth.users(id) on delete cascade,
  provider_id uuid not null references public.providers(id) on delete cascade,
  package_id uuid not null references public.packages(id) on delete cascade,
  checkout_payload jsonb not null default '{}'::jsonb,
  status text not null default 'READY_FOR_PAYMENT' check (status in ('READY_FOR_PAYMENT','COMPLETED','ABANDONED')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table public.customer_checkout_drafts enable row level security;
drop policy if exists customer_checkout_drafts_own on public.customer_checkout_drafts;
create policy customer_checkout_drafts_own on public.customer_checkout_drafts
  for all using (customer_id=auth.uid()) with check (customer_id=auth.uid());

drop trigger if exists customer_checkout_drafts_set_updated_at on public.customer_checkout_drafts;
create trigger customer_checkout_drafts_set_updated_at before update on public.customer_checkout_drafts
for each row execute function public.set_updated_at();

create or replace function public.customer_save_checkout_draft(
  target_provider uuid,
  target_package uuid,
  target_payload jsonb
) returns jsonb language plpgsql security definer set search_path=public as $$
begin
  if auth.uid() is null then raise exception 'Customer authentication is required'; end if;
  if not exists(select 1 from public.packages p where p.id=target_package and p.provider_id=target_provider and p.is_active) then
    raise exception 'The selected provider package is no longer available';
  end if;
  insert into public.customer_checkout_drafts(customer_id,provider_id,package_id,checkout_payload,status)
  values(auth.uid(),target_provider,target_package,coalesce(target_payload,'{}'::jsonb),'READY_FOR_PAYMENT')
  on conflict(customer_id) do update set provider_id=excluded.provider_id,package_id=excluded.package_id,
    checkout_payload=excluded.checkout_payload,status='READY_FOR_PAYMENT',updated_at=now();
  return jsonb_build_object('saved',true,'status','READY_FOR_PAYMENT');
end; $$;

create or replace function public.customer_pending_checkout()
returns jsonb language sql security definer set search_path=public stable as $$
  select coalesce((
    select jsonb_build_object(
      'has_pending_checkout',true,
      'provider_id',draft.provider_id,
      'package_id',draft.package_id,
      'payload',draft.checkout_payload,
      'updated_at',draft.updated_at
    ) from public.customer_checkout_drafts draft
    where draft.customer_id=auth.uid() and draft.status='READY_FOR_PAYMENT'
  ),jsonb_build_object('has_pending_checkout',false));
$$;

create or replace function public.customer_clear_checkout_draft()
returns jsonb language plpgsql security definer set search_path=public as $$
begin
  update public.customer_checkout_drafts set status='ABANDONED',updated_at=now()
  where customer_id=auth.uid() and status='READY_FOR_PAYMENT';
  return jsonb_build_object('cleared',true);
end; $$;

grant execute on function public.customer_save_checkout_draft(uuid,uuid,jsonb) to authenticated;
grant execute on function public.customer_pending_checkout() to authenticated;
grant execute on function public.customer_clear_checkout_draft() to authenticated;

comment on table public.customer_checkout_drafts is 'Recoverable customer checkout state saved before Razorpay opens.';
