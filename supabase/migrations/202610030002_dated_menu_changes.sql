-- Date-specific main-course replacements, separate from recurring weekly edits.

create function public.provider_dated_menu_changes(payload jsonb default null) returns jsonb
language plpgsql security definer set search_path=public as $$
declare provider uuid; dish menu_items; dates date[]; selected_date date; slot public.meal_slot;
begin
 select pm.provider_id into provider from provider_members pm join providers p on p.id=pm.provider_id
 where pm.user_id=auth.uid() and pm.is_active and p.status='ACTIVE' order by pm.created_at desc limit 1;
 if provider is null then raise exception 'Active provider membership required'; end if;
 if payload is not null then
  perform 1 from providers where id=provider for update;
  select * into dish from menu_items where id=(payload->>'old_item')::uuid and provider_id=provider and status='APPROVED' and category='MAIN_COURSE';
  if dish.id is null or dish.dietary_type::text='BOTH' then raise exception 'Choose an approved veg, vegan or non-veg main course'; end if;
  slot:=(payload->>'slot')::meal_slot;
  select array_agg(distinct value::date order by value::date) into dates from jsonb_array_elements_text(payload->'dates');
  if coalesce(cardinality(dates),0) not between 1 and 7 then raise exception 'Choose 1 to 7 exact dates'; end if;
  if lower(trim(payload->>'new_name'))=lower(dish.name) then raise exception 'Replacement must be a different dish'; end if;
  foreach selected_date in array dates loop
   if selected_date>(now() at time zone 'Asia/Kolkata')::date+30 or (now() at time zone 'Asia/Kolkata')>=selected_date+(case slot when 'LUNCH' then time '08:00' else time '18:00' end) then
    raise exception 'Choose a future meal before its preparation cutoff, within 30 days'; end if;
   if not exists(select 1 from provider_menus m join menu_days d on d.menu_id=m.id join menu_day_choices c on c.menu_day_id=d.id
    where m.provider_id=provider and m.status='APPROVED' and m.valid_from<=selected_date and (m.valid_until is null or m.valid_until>=selected_date)
    and d.day_of_week=extract(isodow from selected_date) and d.meal_slot=slot and d.is_available and c.menu_item_id=dish.id and c.choice_group='MAIN_COURSE') then
    raise exception 'The selected original dish is not offered on % for %',selected_date,slot; end if;
  end loop;
  if not exists(select 1 from dated_menu_changes c where c.provider_id=provider and c.old_item=dish.id
   and c.new_name=trim(payload->>'new_name') and c.meal_slot=slot and c.service_dates=dates and c.status in ('PENDING','APPROVED')) then
   insert into dated_menu_changes(provider_id,old_item,new_name,meal_slot,service_dates,requested_by)
   values(provider,dish.id,trim(payload->>'new_name'),slot,dates,auth.uid());
  end if;
 end if;
 return jsonb_build_object('items',(select coalesce(jsonb_agg(to_jsonb(x) order by created_at desc),'[]'::jsonb) from
 (select c.*,i.name old_name,i.dietary_type from dated_menu_changes c join menu_items i on i.id=c.old_item where c.provider_id=provider order by c.created_at desc limit 30)x),
 'dishes',(select coalesce(jsonb_agg(jsonb_build_object('id',i.id,'name',i.name,'diet',i.dietary_type) order by i.name),'[]'::jsonb)
 from menu_items i where i.provider_id=provider and i.status='APPROVED' and i.category='MAIN_COURSE'));
end $$;
revoke all on function public.provider_dated_menu_changes(jsonb) from public,anon;
grant execute on function public.provider_dated_menu_changes(jsonb) to authenticated;

create function public.apply_dated_menu_to_meal() returns trigger
language plpgsql security definer set search_path=public as $$
declare replacement uuid;
begin
 if new.status in ('SCHEDULED','PAUSED') and new.wallet_charged_at is null
 and (now() at time zone 'Asia/Kolkata')<new.service_date+(case new.meal_slot when 'LUNCH' then time '08:00' else time '18:00' end) then
  select c.new_item into replacement from dated_menu_changes c where c.provider_id=new.provider_id
   and c.status='APPROVED' and c.old_item=new.selected_menu_item_id and c.meal_slot=new.meal_slot and new.service_date=any(c.service_dates)
   order by c.reviewed_at desc,c.id limit 1;
  if replacement is not null then new.selected_menu_item_id:=replacement; end if;
 end if;
 return new;
end $$;
revoke all on function public.apply_dated_menu_to_meal() from public,anon,authenticated;
create trigger apply_dated_menu_to_meal before insert or update of selected_menu_item_id,status on public.subscription_meals
for each row execute function public.apply_dated_menu_to_meal();

create function public.admin_dated_menu_changes(target_id uuid default null,decision text default null,note text default null) returns jsonb
language plpgsql security definer set search_path=public as $$
declare change dated_menu_changes; dish menu_items; selected_date date; customer record; nid uuid; created_item uuid; changes jsonb; event_id uuid;
begin
 if not (has_role('ADMIN') or has_role('OPERATIONS')) then raise exception 'Admin access required'; end if;
 if decision is not null then
  select * into change from dated_menu_changes where id=target_id for update;
  if change.id is null then raise exception 'Change not found'; end if;
  if change.status<>'PENDING' then return jsonb_build_object('status',change.status); end if;
  if decision not in ('APPROVED','REJECTED') then raise exception 'Choose approve or reject'; end if;
  if decision='REJECTED' and nullif(trim(note),'') is null then raise exception 'Rejection reason required'; end if;
  perform 1 from providers where id=change.provider_id for update;
  select * into dish from menu_items where id=change.old_item;
  if decision='APPROVED' then
   foreach selected_date in array change.service_dates loop
    if (now() at time zone 'Asia/Kolkata')>=selected_date+(case change.meal_slot when 'LUNCH' then time '08:00' else time '18:00' end) then
     raise exception 'One of these meals is already past preparation cutoff. Reject and request revised dates.'; end if;
    if not exists(select 1 from provider_menus m join menu_days md on md.menu_id=m.id join menu_day_choices mc on mc.menu_day_id=md.id
     where m.provider_id=change.provider_id and m.status='APPROVED' and m.valid_from<=selected_date and (m.valid_until is null or m.valid_until>=selected_date)
     and md.day_of_week=extract(isodow from selected_date) and md.meal_slot=change.meal_slot and md.is_available and mc.menu_item_id=change.old_item) then
     raise exception 'The weekly menu has changed since this request. Reject and request a replacement for the current dish.'; end if;
    if exists(select 1 from dated_menu_changes c where c.id<>change.id and c.provider_id=change.provider_id and c.status='APPROVED'
     and c.meal_slot=change.meal_slot and selected_date=any(c.service_dates) and (c.old_item=change.old_item or c.new_item=change.old_item)) then
     raise exception 'This dish already has a replacement on %. Do not stack replacements.',selected_date; end if;
   end loop;
   select id into created_item from menu_items where provider_id=change.provider_id and name=change.new_name;
   if created_item is not null and not exists(select 1 from menu_items where id=created_item and status='APPROVED' and category='MAIN_COURSE' and dietary_type=dish.dietary_type) then
    raise exception 'Existing replacement dish has a different dietary category or is not approved'; end if;
   if created_item is null then
    insert into menu_items(provider_id,name,category,dietary_type,status,created_by,reviewed_by,reviewed_at)
     values(change.provider_id,change.new_name,'MAIN_COURSE',dish.dietary_type,'APPROVED',change.requested_by,auth.uid(),now()) returning id into created_item;
   end if;
   perform 1 from subscription_meals where provider_id=change.provider_id and service_date=any(change.service_dates) and meal_slot=change.meal_slot for update;
   select coalesce(jsonb_agg(jsonb_build_object('customer_id',sm.customer_id,'kind','daily','meal_id',sm.id,'day',extract(isodow from sm.service_date),
    'slot',sm.meal_slot,'date',sm.service_date,'old_item',dish.id,'new_item',created_item,'old_name',dish.name,'new_name',change.new_name)),'[]'::jsonb) into changes
    from subscription_meals sm join customer_subscriptions cs on cs.id=sm.subscription_id
    where sm.provider_id=change.provider_id and sm.service_date=any(change.service_dates) and sm.meal_slot=change.meal_slot
     and sm.selected_menu_item_id=change.old_item and sm.status in ('SCHEDULED','PAUSED') and sm.wallet_charged_at is null and cs.status in ('ACTIVE','PAUSED');
   update dated_menu_changes set status='APPROVED',new_item=created_item,reviewed_at=now(),reviewed_by=auth.uid(),review_note=note where id=change.id;
   update subscription_meals set selected_menu_item_id=created_item,updated_at=now() where id in(select (c->>'meal_id')::uuid from jsonb_array_elements(changes)c);
   for customer in select c->>'customer_id' id,jsonb_agg(c) rows,count(distinct c->>'date') days,string_agg(distinct c->>'date',', ' order by c->>'date') dates from jsonb_array_elements(changes)c group by c->>'customer_id' loop
    event_id:=null; nid:=null;
    insert into customer_menu_updates(customer_id,provider_id,source_key,changes) values(customer.id::uuid,change.provider_id,'DATE_'||change.id,customer.rows)
     on conflict do nothing returning id into event_id;
    if event_id is not null then
     insert into customer_notifications(customer_id,category,title,message,destination,dedupe_key)
     values(customer.id::uuid,'Menu','A menu update for '||customer.days||case when customer.days=1 then ' day 🍛' else ' days 🍛' end,
      dish.name||' → '||change.new_name||' for '||lower(change.meal_slot::text)||' on '||customer.dates||'. Same dietary category. Your regular weekly menu is unchanged.','plan','MENU_DATE_'||change.id)
     on conflict(customer_id,dedupe_key) do nothing returning id into nid;
     if nid is not null then insert into meal_push_outbox(notification_id,device_id) select nid,id from push_device_tokens where user_id=customer.id::uuid and enabled and app_kind='CUSTOMER' on conflict do nothing; end if;
    end if;
   end loop;
  else update dated_menu_changes set status='REJECTED',review_note=note,reviewed_at=now(),reviewed_by=auth.uid() where id=change.id;
  end if;
  perform notify_provider_members(change.provider_id,'OPERATIONS','Dated menu change '||lower(decision),coalesce(nullif(note,''),'Your requested dated main-course replacement was reviewed.'),'PROFILE','DATED_MENU_CHANGE',change.id::text);
  insert into audit_logs(actor_id,action,entity_type,entity_id,after_data) values(auth.uid(),'DATED_MENU_'||decision,'dated_menu_changes',change.id::text,jsonb_build_object('changes',changes,'note',note));
 end if;
 return jsonb_build_object('items',(select coalesce(jsonb_agg(to_jsonb(x) order by created_at desc),'[]'::jsonb) from
 (select c.*,i.name old_name,i.dietary_type,p.display_name provider_name,
 (select count(distinct sm.customer_id) from subscription_meals sm join customer_subscriptions cs on cs.id=sm.subscription_id
 where sm.provider_id=c.provider_id and sm.service_date=any(c.service_dates) and sm.meal_slot=c.meal_slot and sm.selected_menu_item_id=c.old_item
 and sm.status in ('SCHEDULED','PAUSED') and sm.wallet_charged_at is null and cs.status in ('ACTIVE','PAUSED')) affected_customers
 from dated_menu_changes c join menu_items i on i.id=c.old_item join providers p on p.id=c.provider_id order by c.created_at desc limit 100)x));
end $$;
revoke all on function public.admin_dated_menu_changes(uuid,text,text) from public,anon;
grant execute on function public.admin_dated_menu_changes(uuid,text,text) to authenticated;
