-- Durable provider notifications for activation, finance and commission events.
create table public.provider_notifications (
  id uuid primary key default gen_random_uuid(),
  provider_id uuid not null references public.providers(id) on delete cascade,
  recipient_user_id uuid not null references auth.users(id) on delete cascade,
  category text not null check(category in ('ACCOUNT','PAYOUT_DETAILS','PAYOUT','ADVANCE','COMMISSION','OPERATIONS')),
  title text not null,
  message text not null,
  destination text not null default 'DASHBOARD' check(destination in ('DASHBOARD','EARNINGS','PAYOUT_DETAILS','PROFILE','ORDERS')),
  entity_type text,
  entity_id text,
  read_at timestamptz,
  created_at timestamptz not null default now()
);
create index provider_notifications_recipient_idx on public.provider_notifications(recipient_user_id,created_at desc);
create index provider_notifications_unread_idx on public.provider_notifications(recipient_user_id,created_at desc) where read_at is null;
alter table public.provider_notifications enable row level security;
create policy provider_notification_own_read on public.provider_notifications for select to authenticated using(recipient_user_id=auth.uid());
create policy provider_notification_own_update on public.provider_notifications for update to authenticated using(recipient_user_id=auth.uid()) with check(recipient_user_id=auth.uid());

create or replace function public.notify_provider_members(target_provider uuid,target_category text,target_title text,target_message text,target_destination text,target_entity_type text default null,target_entity_id text default null)
returns integer language plpgsql security definer set search_path=public as $$
declare inserted_count integer;
begin
  insert into public.provider_notifications(provider_id,recipient_user_id,category,title,message,destination,entity_type,entity_id)
  select target_provider,pm.user_id,target_category,target_title,target_message,target_destination,target_entity_type,target_entity_id
  from public.provider_members pm where pm.provider_id=target_provider and pm.is_active;
  get diagnostics inserted_count=row_count;
  return inserted_count;
end; $$;

create or replace function public.provider_notification_feed(target_limit integer default 50)
returns jsonb language plpgsql stable security definer set search_path=public as $$
declare result jsonb;
begin
  select jsonb_build_object(
    'unread_count',count(*) filter(where read_at is null),
    'items',coalesce(jsonb_agg(jsonb_build_object('id',id,'category',category,'title',title,'message',message,'destination',destination,'entity_type',entity_type,'entity_id',entity_id,'read_at',read_at,'created_at',created_at) order by created_at desc),'[]'::jsonb)
  ) into result from (select * from public.provider_notifications where recipient_user_id=auth.uid() order by created_at desc limit least(greatest(target_limit,1),100)) feed;
  return coalesce(result,jsonb_build_object('unread_count',0,'items','[]'::jsonb));
end; $$;

create or replace function public.provider_mark_notifications_read(target_notification uuid default null)
returns jsonb language plpgsql security definer set search_path=public as $$
declare changed integer;
begin
  update public.provider_notifications set read_at=coalesce(read_at,now())
  where recipient_user_id=auth.uid() and read_at is null and (target_notification is null or id=target_notification);
  get diagnostics changed=row_count;
  return jsonb_build_object('updated',changed);
end; $$;

create or replace function public.emit_provider_status_notification() returns trigger language plpgsql security definer set search_path=public as $$
begin
  if new.status is distinct from old.status then
    perform public.notify_provider_members(new.id,'ACCOUNT',case when new.status='ACTIVE' then 'Your Zomeal account is active' when new.status='SUSPENDED' then 'Provider account suspended' else 'Provider status updated' end,
      case when new.status='ACTIVE' then 'You can now manage live meals, customers, payouts and delivery operations.' else 'Your provider status changed to '||replace(new.status::text,'_',' ')||'. Open the app for details.' end,
      'DASHBOARD','provider',new.id::text);
  end if; return new;
end; $$;
drop trigger if exists provider_status_notification on public.providers;
create trigger provider_status_notification after update of status on public.providers for each row execute function public.emit_provider_status_notification();

create or replace function public.emit_payout_destination_notification() returns trigger language plpgsql security definer set search_path=public as $$
begin
  if new.status is distinct from old.status and new.status in ('VERIFIED','REJECTED') then
    perform public.notify_provider_members(new.provider_id,'PAYOUT_DETAILS',case when new.status='VERIFIED' then 'Payout details verified' else 'Payout details need changes' end,
      case when new.status='VERIFIED' then 'Your '||replace(new.method,'_',' ')||' destination is verified and ready for electronic payouts.' else coalesce(new.admin_note,'Please update and resubmit your payout details.') end,
      'PAYOUT_DETAILS','payout_destination',new.id::text);
  end if; return new;
end; $$;
drop trigger if exists payout_destination_notification on public.provider_payout_destinations;
create trigger payout_destination_notification after update of status on public.provider_payout_destinations for each row execute function public.emit_payout_destination_notification();

create or replace function public.emit_payout_status_notification() returns trigger language plpgsql security definer set search_path=public as $$
begin
  if new.status is distinct from old.status then
    perform public.notify_provider_members(new.provider_id,'PAYOUT','Payout '||lower(replace(new.status,'_',' ')),
      'Your payout request for ₹'||to_char(new.amount_paise/100.0,'FM99,99,99,990.00')||' is now '||lower(replace(new.status,'_',' '))||case when new.admin_note is not null then '. '||new.admin_note else '.' end,
      'EARNINGS','payout_request',new.id::text);
  end if; return new;
end; $$;
drop trigger if exists payout_status_notification on public.provider_payout_requests;
create trigger payout_status_notification after update of status on public.provider_payout_requests for each row execute function public.emit_payout_status_notification();

create or replace function public.emit_advance_status_notification() returns trigger language plpgsql security definer set search_path=public as $$
begin
  if new.status is distinct from old.status then
    perform public.notify_provider_members(new.provider_id,'ADVANCE','Advance request '||lower(replace(new.status,'_',' ')),
      'Your approved advance amount is ₹'||to_char(coalesce(new.approved_amount_paise,new.amount_paise)/100.0,'FM99,99,99,990.00')||' and its status is '||lower(replace(new.status,'_',' '))||case when new.admin_note is not null then '. '||new.admin_note else '.' end,
      'EARNINGS','advance_request',new.id::text);
  end if; return new;
end; $$;
drop trigger if exists advance_status_notification on public.provider_advance_requests;
create trigger advance_status_notification after update of status on public.provider_advance_requests for each row execute function public.emit_advance_status_notification();

create or replace function public.emit_commission_notification() returns trigger language plpgsql security definer set search_path=public as $$
begin
  if new.status='ACTIVE' then
    perform public.notify_provider_members(new.provider_id,'COMMISSION','Commission terms updated',
      'Your current Zomeal commission is '||trim(to_char(new.commission_basis_points/100.0,'FM990.00'))||'%. '||coalesce(new.negotiation_note,''),
      'PROFILE','commission_term',new.id::text);
  end if; return new;
end; $$;
drop trigger if exists commission_term_notification on public.provider_commission_terms;
create trigger commission_term_notification after insert on public.provider_commission_terms for each row execute function public.emit_commission_notification();

revoke all on function public.notify_provider_members(uuid,text,text,text,text,text,text),public.provider_notification_feed(integer),public.provider_mark_notifications_read(uuid) from public;
grant execute on function public.provider_notification_feed(integer),public.provider_mark_notifications_read(uuid) to authenticated;
