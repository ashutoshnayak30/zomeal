-- Save included dishes independently from the customer's main-course choice.
alter table public.subscription_meals add column side_dishes jsonb;
alter table public.customer_weekly_menu_templates add column side_dishes jsonb;

create function public.menu_side_dishes(target_menu uuid,weekday integer,slot public.meal_slot)
returns jsonb language sql stable security definer set search_path=public as $$
 select coalesce(jsonb_agg(jsonb_build_object('id',i.id,'name',i.name,'category',c.choice_group,'diet',i.dietary_type)
 order by c.display_order,c.choice_group,i.name,i.id),'[]'::jsonb)
 from menu_days d join menu_day_choices c on c.menu_day_id=d.id join menu_items i on i.id=c.menu_item_id
 where d.menu_id=target_menu and d.day_of_week=weekday and d.meal_slot=slot and d.is_available
 and c.choice_group<>'MAIN_COURSE' and i.status in ('APPROVED','PENDING_REVIEW');
$$;
create function public.menu_side_text(dishes jsonb) returns text language sql immutable as $$
 select coalesce(string_agg(x->>'name',' · ' order by n),'') from jsonb_array_elements(coalesce(dishes,'[]')) with ordinality a(x,n);
$$;
create function public.current_side_menu(provider uuid,service_day date) returns uuid
language sql stable security definer set search_path=public as $$
 select id from provider_menus where provider_id=provider and status='APPROVED'
 and valid_from<=service_day and (valid_until is null or valid_until>=service_day)
 order by valid_from desc,updated_at desc,id limit 1;
$$;
create function public.weekly_side_snapshot(provider uuid) returns jsonb
language sql stable security definer set search_path=public as $$
 select jsonb_object_agg(day::text||'_'||slot::text,menu_side_dishes(current_side_menu(provider,(now() at time zone 'Asia/Kolkata')::date),day,slot))
 from generate_series(1,7) day cross join unnest(array['LUNCH','DINNER']::meal_slot[]) slot;
$$;
create function public.capture_meal_side_dishes() returns trigger
language plpgsql security definer set search_path=public as $$
begin
 if tg_op='INSERT' then
  if new.side_dishes is null then new.side_dishes:=menu_side_dishes(current_side_menu(new.provider_id,new.service_date),extract(isodow from new.service_date)::integer,new.meal_slot); end if;
 elsif new.provider_id is distinct from old.provider_id then
  new.side_dishes:=menu_side_dishes(current_side_menu(new.provider_id,new.service_date),extract(isodow from new.service_date)::integer,new.meal_slot);
 end if;
 return new;
end $$;
create trigger capture_meal_side_dishes before insert or update of provider_id on public.subscription_meals
for each row execute function public.capture_meal_side_dishes();
create function public.capture_weekly_side_dishes() returns trigger
language plpgsql security definer set search_path=public as $$
begin
 if new.side_dishes is null then new.side_dishes:=weekly_side_snapshot((select provider_id from packages where id=new.package_id)); end if;
 return new;
end $$;
create trigger capture_weekly_side_dishes before insert on public.customer_weekly_menu_templates
for each row execute function public.capture_weekly_side_dishes();
-- Do not invent historical sides from today's catalogue. Capture current/future
-- meals once; later approvals can change only the not-yet-locked eligible ones.
update subscription_meals set side_dishes=menu_side_dishes(current_side_menu(provider_id,service_date),extract(isodow from service_date)::integer,meal_slot)
where side_dishes is null and service_date>=(now() at time zone 'Asia/Kolkata')::date;
update customer_weekly_menu_templates t set side_dishes=weekly_side_snapshot(p.provider_id)
from packages p where p.id=t.package_id and t.side_dishes is null;
revoke all on function public.menu_side_dishes(uuid,integer,meal_slot),public.current_side_menu(uuid,date),
public.weekly_side_snapshot(uuid),public.capture_meal_side_dishes(),public.capture_weekly_side_dishes() from public,anon,authenticated;
create or replace function public.sync_customer_menu(target_menu uuid,source text,apply_changes boolean default false)
returns jsonb language plpgsql security definer set search_path=public as $$
declare menu provider_menus; r record; replacement uuid; changes jsonb:='[]'; conflicts jsonb:='[]';
 local_now timestamp:=now() at time zone 'Asia/Kolkata'; customer record; nid uuid; event_id uuid;
 day_count integer; msg text; kitchen text; next_sides jsonb;
begin
 select * into menu from provider_menus where id=target_menu;
 if menu.id is null then raise exception 'Menu not found'; end if;
 if apply_changes and menu.status<>'APPROVED' then raise exception 'Menu must be approved'; end if;
 if menu.valid_until is not null and menu.valid_until<local_now::date then raise exception 'This menu has expired. Select the latest approved menu.'; end if;
 perform 1 from providers where id=menu.provider_id for update;
 if apply_changes then
  perform 1 from subscription_meals where provider_id=menu.provider_id and service_date>=local_now::date for update;
  perform 1 from customer_weekly_menu_templates t join packages p on p.id=t.package_id where p.provider_id=menu.provider_id and t.is_active for update of t;
 end if;
 if menu.valid_from>local_now::date then raise exception 'Future-dated recurring menus need an activation workflow'; end if;
 for r in
  select 'weekly' kind,t.customer_id,s.template_id,null::uuid meal_id,s.day_of_week::integer weekday,s.meal_slot,
   s.menu_item_id old_item,null::date service_date
  from customer_weekly_menu_selections s join customer_weekly_menu_templates t on t.id=s.template_id
  join packages p on p.id=t.package_id
  where p.provider_id=menu.provider_id and t.is_active and s.choice_group='MAIN_COURSE'
  and exists(select 1 from customer_subscriptions cs where cs.customer_id=t.customer_id and cs.package_id=t.package_id
   and cs.status in ('ACTIVE','PAUSED') and cs.end_date>=local_now::date)
  union all
  select 'daily',sm.customer_id,null::uuid,sm.id,extract(isodow from sm.service_date)::integer,sm.meal_slot,
   sm.selected_menu_item_id,sm.service_date
  from subscription_meals sm join customer_subscriptions cs on cs.id=sm.subscription_id
  where sm.provider_id=menu.provider_id and cs.status in ('ACTIVE','PAUSED')
   and sm.status in ('SCHEDULED','PAUSED') and sm.wallet_charged_at is null
   and sm.service_date>=greatest(menu.valid_from,local_now::date)
   and (menu.valid_until is null or sm.service_date<=menu.valid_until)
   and local_now<sm.service_date+case sm.meal_slot when 'LUNCH' then time '08:00' else time '18:00' end
 loop
  if r.kind='daily' and exists(select 1 from dated_menu_changes dc where dc.provider_id=menu.provider_id
    and dc.status='APPROVED' and dc.new_item=r.old_item and dc.meal_slot=r.meal_slot and r.service_date=any(dc.service_dates)) then continue; end if;
  replacement:=menu_replacement_item(menu.id,r.old_item,r.weekday,r.meal_slot);
  if replacement is null then
   conflicts:=conflicts||jsonb_build_array(jsonb_build_object('customer_id',r.customer_id,'day',r.weekday,'slot',r.meal_slot,'old_item',r.old_item,'reason','No same-diet main course. Add a compatible dish before approving.'));
  elsif replacement<>r.old_item then
   changes:=changes||jsonb_build_array(jsonb_build_object('customer_id',r.customer_id,'kind',r.kind,'template_id',r.template_id,
    'meal_id',r.meal_id,'day',r.weekday,'slot',r.meal_slot,'date',r.service_date,'old_item',r.old_item,'new_item',replacement,
    'old_name',(select name from menu_items where id=r.old_item),'new_name',(select name from menu_items where id=replacement)));
  end if;
 end loop;

 -- Side dishes are shared included items, not a replacement main-course choice.
 for r in
  select 'weekly_side' kind,t.customer_id,t.id template_id,null::uuid meal_id,day weekday,slot meal_slot,
   null::date service_date,t.side_dishes->(day::text||'_'||slot::text) old_sides,t.dietary_preference diet
  from customer_weekly_menu_templates t join packages p on p.id=t.package_id
  cross join generate_series(1,7) day cross join unnest(array['LUNCH','DINNER']::meal_slot[]) slot
  where p.provider_id=menu.provider_id and t.is_active
   and not (p.kind='LUNCH_ONLY' and slot='DINNER') and not (p.kind='DINNER_ONLY' and slot='LUNCH')
   and exists(select 1 from customer_subscriptions cs where cs.customer_id=t.customer_id and cs.package_id=t.package_id
    and cs.status in ('ACTIVE','PAUSED') and cs.end_date>=local_now::date)
  union all
  select 'daily_side',sm.customer_id,null::uuid,sm.id,extract(isodow from sm.service_date)::integer,sm.meal_slot,
   sm.service_date,sm.side_dishes,i.dietary_type
  from subscription_meals sm join customer_subscriptions cs on cs.id=sm.subscription_id
  left join menu_items i on i.id=sm.selected_menu_item_id
  where sm.provider_id=menu.provider_id and cs.status in ('ACTIVE','PAUSED')
   and sm.status in ('SCHEDULED','PAUSED') and sm.wallet_charged_at is null
   and sm.service_date>=greatest(menu.valid_from,local_now::date)
   and (menu.valid_until is null or sm.service_date<=menu.valid_until)
   and local_now<sm.service_date+(case sm.meal_slot when 'LUNCH' then time '08:00' else time '18:00' end)
 loop
  next_sides:=menu_side_dishes(menu.id,r.weekday,r.meal_slot);
  if coalesce(r.old_sides,'[]')=next_sides then continue; end if;
  if exists(select 1 from jsonb_array_elements(next_sides) x where
   (r.diet in ('VEG','VEGAN') and x->>'diet'='NON_VEG')
   or (r.diet='VEGAN' and x->>'diet'='VEG')) then
   conflicts:=conflicts||jsonb_build_array(jsonb_build_object('customer_id',r.customer_id,'day',r.weekday,'slot',r.meal_slot,
    'reason','Included side dishes conflict with the customer diet. Supply compatible sides before approval.'));
  end if;
  changes:=changes||jsonb_build_array(jsonb_build_object('customer_id',r.customer_id,'kind',r.kind,'category','SIDE',
   'template_id',r.template_id,'meal_id',r.meal_id,'day',r.weekday,'slot',r.meal_slot,'date',r.service_date,
   'old_name',menu_side_text(r.old_sides),'new_name',menu_side_text(next_sides),'side_dishes',next_sides));
 end loop;

 if apply_changes then
  if jsonb_array_length(conflicts)>0 then raise exception 'Menu has % dietary conflicts. Add compatible veg/non-veg choices; no subscriber changes were saved.',jsonb_array_length(conflicts); end if;
  for r in select value c from jsonb_array_elements(changes) loop
   if r.c->>'kind'='weekly_side' then
    update customer_weekly_menu_templates set side_dishes=jsonb_set(coalesce(side_dishes,'{}'),
     array[(r.c->>'day')||'_'||(r.c->>'slot')],r.c->'side_dishes'),updated_at=now()
    where id=(r.c->>'template_id')::uuid;
   elsif r.c->>'kind'='daily_side' then
    update subscription_meals set side_dishes=r.c->'side_dishes',updated_at=now()
    where id=(r.c->>'meal_id')::uuid and status in ('SCHEDULED','PAUSED') and wallet_charged_at is null;
   elsif r.c->>'kind'='weekly' then
    insert into customer_weekly_menu_selections(template_id,day_of_week,meal_slot,choice_group,menu_item_id)
    values((r.c->>'template_id')::uuid,(r.c->>'day')::smallint,(r.c->>'slot')::meal_slot,'MAIN_COURSE',(r.c->>'new_item')::uuid) on conflict do nothing;
    delete from customer_weekly_menu_selections where template_id=(r.c->>'template_id')::uuid
     and day_of_week=(r.c->>'day')::smallint and meal_slot=(r.c->>'slot')::meal_slot and choice_group='MAIN_COURSE' and menu_item_id=(r.c->>'old_item')::uuid;
   else
    update subscription_meals set selected_menu_item_id=(r.c->>'new_item')::uuid,updated_at=now()
    where id=(r.c->>'meal_id')::uuid and selected_menu_item_id=(r.c->>'old_item')::uuid
     and status in ('SCHEDULED','PAUSED') and wallet_charged_at is null;
   end if;
  end loop;
  select display_name into kitchen from providers where id=menu.provider_id;
  for customer in select c->>'customer_id' id,jsonb_agg(c) rows,count(distinct c->>'day') days
   from jsonb_array_elements(changes) c group by c->>'customer_id' loop
   event_id:=null;
   insert into customer_menu_updates(customer_id,provider_id,source_key,changes)
    values(customer.id::uuid,menu.provider_id,source||'_WITH_SIDES',customer.rows)
    on conflict(customer_id,source_key) do nothing returning id into event_id;
   if event_id is not null then
    msg:=kitchen||' updated your menu on '||customer.days||case when customer.days=1 then ' weekday' else ' weekdays' end||'. Your main-course preferences are preserved. Updated sides are included. Changes apply to upcoming eligible meals; meals already in preparation are unchanged. Tap to view changes.';
    insert into customer_notifications(customer_id,category,title,message,destination,dedupe_key)
    values(customer.id::uuid,'Menu','Your menu has been updated 🍱',msg,'plan','MENU_UPDATE_'||source||'_WITH_SIDES')
    on conflict(customer_id,dedupe_key) do nothing returning id into nid;
    if nid is not null then insert into meal_push_outbox(notification_id,device_id)
     select nid,d.id from push_device_tokens d where d.user_id=customer.id::uuid and d.enabled and d.app_kind='CUSTOMER' on conflict do nothing; end if;
   end if;
  end loop;
  insert into audit_logs(actor_id,action,entity_type,entity_id,after_data)
  values(auth.uid(),'CUSTOMER_MENU_SYNC','provider_menus',menu.id::text,jsonb_build_object('source',source,'changes',changes));
 end if;
 return jsonb_build_object('changes',changes,'conflicts',conflicts,'customers',(select count(distinct c->>'customer_id') from jsonb_array_elements(changes) c));
end $$;
revoke all on function public.sync_customer_menu(uuid,text,boolean) from public,anon,authenticated;


alter function public.customer_active_subscription_state() rename to customer_active_subscription_state_before_sides;
revoke all on function public.customer_active_subscription_state_before_sides() from public,anon,authenticated;
create function public.customer_active_subscription_state() returns jsonb
language plpgsql stable security definer set search_path=public as $$
declare result jsonb; daily jsonb; weekly jsonb; provider uuid;
begin
 result:=customer_active_subscription_state_before_sides();
 if not coalesce((result->>'has_active_subscription')::boolean,false) then return result; end if;
 provider:=(result->'subscription'->>'provider_id')::uuid;
 select coalesce(jsonb_agg(x||jsonb_build_object('side_dishes',sm.side_dishes,
  'side_text',case when sm.side_dishes is not null then menu_side_text(sm.side_dishes) else null end)),'[]')
 into daily from jsonb_array_elements(coalesce(result->'daily_meals','[]')) x
 left join subscription_meals sm on sm.id=(x->>'id')::uuid and sm.customer_id=auth.uid();
 select coalesce(jsonb_agg(x||jsonb_build_object('side_text',menu_side_text(menu_side_dishes(
  current_side_menu(provider,(now() at time zone 'Asia/Kolkata')::date),(x->>'day_of_week')::integer,(x->>'meal_slot')::meal_slot)))),'[]')
 into weekly from jsonb_array_elements(coalesce(result->'weekly_menu','[]')) x;
 return result||jsonb_build_object('daily_meals',daily,'weekly_menu',weekly);
end $$;
revoke all on function public.customer_active_subscription_state() from public,anon;
grant execute on function public.customer_active_subscription_state() to authenticated;
notify pgrst,'reload schema';

