-- Reminder-only pipeline: never charges, resumes, or edits a meal.
create table public.meal_reminder_settings (
 slot text primary key check(slot in ('LUNCH','DINNER')),
 enabled boolean not null default false,
 send_time time not null,
 title text not null check(length(title) between 1 and 100),
 message text not null check(length(message) between 1 and 500),
 low_title text not null check(length(low_title) between 1 and 100),
 low_message text not null check(length(low_message) between 1 and 500)
);
insert into public.meal_reminder_settings values
 ('LUNCH',false,'10:30','Your lunch is scheduled 🍛','Today’s lunch: {main_course} from {provider}. Something delicious to look forward to 😋','Your lunch needs a wallet check 🍽️','Check your wallet and meal status for {main_course}. Recharge for upcoming meals. Meals skipped after cutoff stay skipped.'),
 ('DINNER',false,'18:30','Your dinner is scheduled 🍽️','Tonight’s dinner: {main_course} from {provider}. A delicious end to your day 😋','Your dinner needs a wallet check 🍽️','Check your wallet and meal status for {main_course}. Recharge for upcoming meals. Meals skipped after cutoff stay skipped.');
alter table public.meal_reminder_settings enable row level security;
revoke all on public.meal_reminder_settings from public,anon,authenticated;

create function public.admin_meal_reminders(payload jsonb default null) returns jsonb
language plpgsql security definer set search_path=public as $$
declare r jsonb; template text;
begin
 if not public.can_manage_accounts() then raise exception 'Admin access required'; end if;
 if payload is not null then
  for r in select * from jsonb_array_elements(payload) loop
   if coalesce(r->>'slot','') not in ('LUNCH','DINNER') then raise exception 'Invalid meal'; end if;
   if (r->>'send_time')::time not between time '08:00' and time '21:00' then raise exception 'Choose an IST time between 08:00 and 21:00'; end if;
   foreach template in array array[r->>'title',r->>'message',r->>'low_title',r->>'low_message'] loop
    if nullif(trim(template),'') is null then raise exception 'All templates are required'; end if;
    if replace(replace(replace(template,'{main_course}',''),'{provider}',''),'{meal}','') ~ '[{}]' then
     raise exception 'Use only {main_course}, {provider} and {meal} placeholders';
    end if;
   end loop;
   update meal_reminder_settings set enabled=(r->>'enabled')::boolean,send_time=(r->>'send_time')::time,
    title=trim(r->>'title'),message=trim(r->>'message'),low_title=trim(r->>'low_title'),low_message=trim(r->>'low_message') where slot=r->>'slot';
  end loop;
 end if;
 return jsonb_build_object('items',(select jsonb_agg(to_jsonb(s) order by slot desc) from meal_reminder_settings s));
end $$;
revoke all on function public.admin_meal_reminders(jsonb) from public,anon;
grant execute on function public.admin_meal_reminders(jsonb) to authenticated;

create function public.process_personal_meal_reminders(target_now timestamptz default now()) returns void
language plpgsql security definer set search_path=public as $$
declare cfg meal_reminder_settings; m record; nid uuid; local_now timestamp:=target_now at time zone 'Asia/Kolkata';
 t text; b text; low boolean;
begin
 for cfg in select * from meal_reminder_settings where enabled loop
  -- Skip stale sends after outages; do not replay yesterday's messages.
  if local_now < local_now::date+cfg.send_time or local_now >= local_now::date+cfg.send_time+interval '30 minutes' then continue; end if;
  for m in
   select distinct on (sm.customer_id) sm.*,cs.pause_reason,cs.status subscription_status,
    coalesce(nullif(mi.name,''),'your selected meal') dish,p.display_name kitchen,coalesce(w.balance_paise,0) balance
   from subscription_meals sm join customer_subscriptions cs on cs.id=sm.subscription_id
   join profiles pr on pr.id=sm.customer_id and pr.is_active
   join providers p on p.id=sm.provider_id
   left join menu_items mi on mi.id=sm.selected_menu_item_id
   left join customer_wallets w on w.customer_id=sm.customer_id
   where sm.service_date=local_now::date and sm.meal_slot::text=cfg.slot
    and cs.start_date<=local_now::date and cs.end_date>=local_now::date
    and ((cs.status='ACTIVE' and sm.status in ('SCHEDULED','PREPARING','PACKING','READY','OUT_FOR_DELIVERY'))
      or (cs.status='PAUSED' and cs.pause_reason='INSUFFICIENT_WALLET' and sm.status='PAUSED'))
   order by sm.customer_id,cs.created_at desc,sm.id
  loop
   low:=m.subscription_status='PAUSED' or (m.wallet_charged_at is null and m.balance<m.meal_value_paise);
   t:=case when low then cfg.low_title else cfg.title end;
   b:=case when low then cfg.low_message else cfg.message end;
   t:=replace(replace(replace(t,'{main_course}',m.dish),'{provider}',m.kitchen),'{meal}',lower(cfg.slot));
   b:=replace(replace(replace(b,'{main_course}',m.dish),'{provider}',m.kitchen),'{meal}',lower(cfg.slot));
   nid:=null;
   insert into customer_notifications(customer_id,category,title,message,destination,dedupe_key)
   values(m.customer_id,'Reminder',t,b,case when low then 'wallet' else 'home' end,
    'PERSONAL_MEAL_'||local_now::date||'_'||cfg.slot)
   on conflict(customer_id,dedupe_key) do nothing returning id into nid;
   if nid is not null then
    insert into meal_push_outbox(notification_id,device_id)
    select nid,d.id from push_device_tokens d where d.user_id=m.customer_id and d.enabled and d.app_kind='CUSTOMER'
    on conflict do nothing;
   end if;
  end loop;
 end loop;
end $$;
revoke all on function public.process_personal_meal_reminders(timestamptz) from public,anon,authenticated;
-- HOSTED DISPATCH START
select cron.schedule('zomeal-personal-meal-reminders','* * * * *','select public.process_personal_meal_reminders();');
-- HOSTED DISPATCH END
