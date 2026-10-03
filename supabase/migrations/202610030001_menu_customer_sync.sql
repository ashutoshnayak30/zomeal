-- Approved recurring menus must update the saved choices of existing customers.
-- Historical deliveries, money and operationally locked meals are never rewritten.
create table public.dated_menu_changes(
 id uuid primary key default gen_random_uuid(),provider_id uuid not null references providers(id),
 old_item uuid not null references menu_items(id),new_name text not null check(length(trim(new_name)) between 2 and 100),
 new_item uuid references menu_items(id),meal_slot public.meal_slot not null,service_dates date[] not null,
 status text not null default 'PENDING' check(status in ('PENDING','APPROVED','REJECTED')),
 requested_by uuid not null references profiles(id),created_at timestamptz not null default now(),reviewed_at timestamptz,
 review_note text,reviewed_by uuid references profiles(id)
);
alter table public.dated_menu_changes enable row level security;
revoke all on public.dated_menu_changes from public,anon,authenticated;
create table public.customer_menu_updates (
 id uuid primary key default gen_random_uuid(),
 customer_id uuid not null references profiles(id),
 provider_id uuid not null references providers(id),
 source_key text not null,
 changes jsonb not null,
 created_at timestamptz not null default now(),
 unique(customer_id,source_key)
);
alter table public.customer_menu_updates enable row level security;
revoke all on public.customer_menu_updates from public,anon,authenticated;

-- Match by diet and ordinal within that diet, never by a veg/non-veg mixture.
-- Keep an explicitly chosen dish when it still exists in the same weekday/slot.
create function public.menu_replacement_item(target_menu uuid,old_item uuid,weekday integer,slot public.meal_slot)
returns uuid language plpgsql stable security definer set search_path=public as $$
declare result uuid; old_diet public.dietary_type; old_rank integer:=1;
begin
 select dietary_type into old_diet from menu_items where id=old_item;
 if old_diet is null or old_diet::text='BOTH' then return null; end if;
 if exists(select 1 from menu_days d join menu_day_choices c on c.menu_day_id=d.id
 where d.menu_id=target_menu and d.day_of_week=weekday and d.meal_slot=slot and d.is_available
 and c.choice_group='MAIN_COURSE' and c.menu_item_id=old_item) then return old_item; end if;
 select ranked.position::integer into old_rank from (
  select c.menu_item_id,row_number() over(order by c.display_order,c.is_default desc,i.created_at,i.id) position
  from menu_days d join menu_day_choices c on c.menu_day_id=d.id join menu_items i on i.id=c.menu_item_id
  where d.menu_id=(select m.id from provider_menus m join menu_days md on md.menu_id=m.id
   join menu_day_choices mc on mc.menu_day_id=md.id where m.id<>target_menu and mc.menu_item_id=old_item
   and md.day_of_week=weekday and md.meal_slot=slot and m.status in ('APPROVED','ARCHIVED')
   order by m.valid_from desc,m.updated_at desc,m.id limit 1)
  and d.day_of_week=weekday and d.meal_slot=slot and c.choice_group='MAIN_COURSE' and i.dietary_type=old_diet
 ) ranked where ranked.menu_item_id=old_item;
 select ranked.menu_item_id into result from (
  select c.menu_item_id,row_number() over(order by c.display_order,c.is_default desc,i.created_at,i.id) position
  from menu_days d join menu_day_choices c on c.menu_day_id=d.id join menu_items i on i.id=c.menu_item_id
  where d.menu_id=target_menu and d.day_of_week=weekday and d.meal_slot=slot and d.is_available
   and c.choice_group='MAIN_COURSE' and i.dietary_type=old_diet and i.status in ('APPROVED','PENDING_REVIEW')
 ) ranked order by (ranked.position=coalesce(old_rank,1)) desc,ranked.position limit 1;
 return result;
end $$;
revoke all on function public.menu_replacement_item(uuid,uuid,integer,public.meal_slot) from public,anon,authenticated;

create function public.sync_customer_menu(target_menu uuid,source text,apply_changes boolean default false)
returns jsonb language plpgsql security definer set search_path=public as $$
declare menu provider_menus; r record; replacement uuid; changes jsonb:='[]'; conflicts jsonb:='[]';
 local_now timestamp:=now() at time zone 'Asia/Kolkata'; customer record; nid uuid; event_id uuid;
 day_count integer; msg text; kitchen text;
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
 if apply_changes then
  if jsonb_array_length(conflicts)>0 then raise exception 'Menu has % dietary conflicts. Add compatible veg/non-veg choices; no subscriber changes were saved.',jsonb_array_length(conflicts); end if;
  for r in select value c from jsonb_array_elements(changes) loop
   if r.c->>'kind'='weekly' then
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
    values(customer.id::uuid,menu.provider_id,source,customer.rows)
    on conflict(customer_id,source_key) do nothing returning id into event_id;
   if event_id is not null then
    msg:=kitchen||' updated your menu on '||customer.days||case when customer.days=1 then ' weekday' else ' weekdays' end||'. Your veg/non-veg choices are preserved. Changes apply to upcoming eligible meals; meals already in preparation are unchanged. Tap to view changes.';
    insert into customer_notifications(customer_id,category,title,message,destination,dedupe_key)
    values(customer.id::uuid,'Menu','Your menu has been updated 🍱',msg,'plan','MENU_UPDATE_'||source)
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

-- Persist the provider's visible option order; older staging gave all choices 0.
create function public.order_requested_menu(target_request uuid) returns void
language sql security definer set search_path=public as $$
 update menu_day_choices c set display_order=(dish.ordinality-1)::smallint
 from menu_days d join provider_menus m on m.id=d.menu_id
 join provider_change_requests r on r.id=m.change_request_id,
 lateral jsonb_array_elements(r.requested_payload->'payload'->'menus') days,
 lateral jsonb_array_elements(days->lower(d.meal_slot::text)) with ordinality dish
 where c.menu_day_id=d.id and m.change_request_id=target_request and c.choice_group='MAIN_COURSE'
 and d.day_of_week=array_position(array['Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday'],days->>'day')
 and exists(select 1 from menu_items i where i.id=c.menu_item_id and i.name=trim(dish.value->>'name'));
$$;
revoke all on function public.order_requested_menu(uuid) from public,anon,authenticated;
alter function public.provider_submit_business_update(jsonb) rename to provider_submit_business_update_before_menu_order;
revoke all on function public.provider_submit_business_update_before_menu_order(jsonb) from public,anon,authenticated;
create function public.provider_submit_business_update(payload jsonb) returns jsonb
language plpgsql security definer set search_path=public as $$
declare result jsonb;
begin
 result:=provider_submit_business_update_before_menu_order(payload);
 perform order_requested_menu((result->>'change_request_id')::uuid);
 return result;
end $$;
revoke all on function public.provider_submit_business_update(jsonb) from public,anon;
grant execute on function public.provider_submit_business_update(jsonb) to authenticated;

-- Run after the existing approval has published all dishes and photos. A failure
-- rolls the complete approval back, rather than partially publishing a menu.
alter function public.admin_review_provider_business_update(uuid,text,text,jsonb) rename to admin_review_provider_business_update_before_menu_sync;
revoke all on function public.admin_review_provider_business_update_before_menu_sync(uuid,text,text,jsonb) from public,anon,authenticated;
create function public.admin_review_provider_business_update(target_request uuid,target_decision text,target_note text default null,revised_payload jsonb default null)
returns jsonb language plpgsql security definer set search_path=public as $$
declare result jsonb; menu_id uuid; impact jsonb; provider uuid; previous_items jsonb;
begin
 if not (has_role('ADMIN') or has_role('OPERATIONS')) then raise exception 'Admin access required'; end if;
 select provider_id into provider from provider_change_requests where id=target_request;
 perform 1 from providers where id=provider for update;
 perform order_requested_menu(target_request);
 select jsonb_agg(jsonb_build_object('id',i.id,'name',i.name,'diet',i.dietary_type)) into previous_items
 from menu_items i where i.provider_id=provider and exists(select 1 from subscription_meals sm where sm.selected_menu_item_id=i.id);
 result:=admin_review_provider_business_update_before_menu_sync(target_request,target_decision,target_note,revised_payload);
 if upper(target_decision)='APPROVED' then
  if exists(select 1 from jsonb_array_elements(coalesce(previous_items,'[]')) x join menu_items i on i.id=(x->>'id')::uuid
    where i.name<>x->>'name' or i.dietary_type::text<>x->>'diet') then
   raise exception 'Do not rename or change the diet of a dish already used in meal history. Submit it as a new dish instead.';
  end if;
  select id into menu_id from provider_menus where change_request_id=target_request and status='APPROVED' order by updated_at desc limit 1;
  if menu_id is not null then impact:=sync_customer_menu(menu_id,target_request::text,true); end if;
 end if;
 return result||jsonb_build_object('customer_menu_impact',impact);
end $$;
revoke all on function public.admin_review_provider_business_update(uuid,text,text,jsonb) from public,anon;
grant execute on function public.admin_review_provider_business_update(uuid,text,text,jsonb) to authenticated;

create function public.admin_menu_sync(target_request uuid,apply_changes boolean default false) returns jsonb
language plpgsql security definer set search_path=public as $$
declare menu_id uuid;
begin
 if not (has_role('ADMIN') or has_role('OPERATIONS')) then raise exception 'Admin access required'; end if;
 select id into menu_id from provider_menus where change_request_id=target_request
 and status=case when apply_changes then 'APPROVED'::catalogue_status else status end
 order by updated_at desc limit 1;
 if menu_id is null then raise exception 'No approved menu to synchronise'; end if;
 if apply_changes and exists(select 1 from provider_menus a join provider_menus b on b.provider_id=a.provider_id
 where a.id=menu_id and b.status='APPROVED' and b.id<>a.id and b.valid_from<=(now() at time zone 'Asia/Kolkata')::date
 and (b.valid_from,b.updated_at)>(a.valid_from,a.updated_at)) then raise exception 'A newer menu exists. Open its approved request instead.'; end if;
 return sync_customer_menu(menu_id,target_request::text,apply_changes);
end $$;
revoke all on function public.admin_menu_sync(uuid,boolean) from public,anon;
grant execute on function public.admin_menu_sync(uuid,boolean) to authenticated;

create function public.customer_menu_update_history() returns jsonb
language sql stable security definer set search_path=public as $$
 select jsonb_build_object('items',coalesce(jsonb_agg(to_jsonb(x) order by created_at desc),'[]'::jsonb)) from
 (select u.id,u.created_at,p.display_name provider_name,u.changes from customer_menu_updates u
 join providers p on p.id=u.provider_id where u.customer_id=auth.uid() order by u.created_at desc limit 30) x;
$$;
revoke all on function public.customer_menu_update_history() from public,anon;
grant execute on function public.customer_menu_update_history() to authenticated;
