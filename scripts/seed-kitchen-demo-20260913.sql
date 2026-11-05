-- Explicit, one-off production test fixture. NOT a schema migration.
-- Every identity is banned, has no phone and has an .invalid email address.
-- Meal billing is excluded via wallet_charged_at; this is a TEST processing
-- sentinel, not evidence of payment. All monetary columns remain zero.
begin;
do $demo$
declare
  run constant text := 'zomeal-kitchen-demo-20260913';
  provider uuid; package uuid; customer uuid; subscription uuid; item uuid;
  items uuid[]; areas text[]; address jsonb; meal_status text;
  day date; slot public.meal_slot; customer_number integer; offset_day integer;
begin
  if exists(select 1 from auth.users where raw_app_meta_data->>'zomeal_demo_run'=run) then
    raise exception 'This demo run already exists; refusing duplicate fixtures';
  end if;
  if not exists(select 1 from public.providers p join public.provider_members pm on pm.provider_id=p.id and pm.is_active
    join public.profiles pr on pr.id=pm.user_id where p.id='9726ff16-eead-4e63-ba58-e797e8cb97ed'
    and p.status='ACTIVE' and p.display_name='MANJU KITCHEN 1'
    and right(regexp_replace(pr.phone,'[^0-9]','','g'),10)='7205586281') then raise exception 'Manju identity changed'; end if;
  if not exists(select 1 from public.providers where id='07eeb007-ed44-47ad-92c7-b82d9f51b6c1'
    and status='ACTIVE' and lower(display_name)='yogesh kitchen') then raise exception 'Yogesh identity changed'; end if;
  foreach provider in array array['9726ff16-eead-4e63-ba58-e797e8cb97ed'::uuid,'07eeb007-ed44-47ad-92c7-b82d9f51b6c1'::uuid] loop
    select id into package from public.packages where provider_id=provider and is_active
      and kind='LUNCH_AND_DINNER' and duration_days=7 order by id limit 1;
    if package is null then raise exception 'No active weekly combined package for %',provider; end if;
    select array_agg(pincode order by pincode) into areas from public.provider_service_areas where provider_id=provider and status='APPROVED';
    if coalesce(cardinality(areas),0)=0 then raise exception 'No approved areas'; end if;
    for customer_number in 1..12 loop
      customer:=gen_random_uuid(); subscription:=gen_random_uuid();
      insert into auth.users(id,aud,role,email,encrypted_password,banned_until,raw_app_meta_data,raw_user_meta_data,created_at,updated_at)
      values(customer,'authenticated','authenticated',customer::text||'@demo.zomeal.invalid','',timestamptz '2100-01-01 UTC',
        jsonb_build_object('zomeal_demo_run',run,'billing_disabled',true),
        jsonb_build_object('full_name','DEMO — '||case when provider='9726ff16-eead-4e63-ba58-e797e8cb97ed' then 'Manju' else 'Yogesh' end||' Test '||lpad(customer_number::text,2,'0')),now(),now());
      update public.profiles set is_active=false,phone=null where id=customer;
      address:=jsonb_build_object('demo_run_id',run,'house_number','DEMO '||customer_number,
        'address_line','DO NOT DELIVER — dashboard training only','locality','DEMO training group '||case when customer_number<=6 then 'A' else 'B' end,
        'pincode',areas[1+((customer_number-1)%cardinality(areas))], 'city','TEST ADDRESS — not a delivery destination');
      insert into public.customer_subscriptions(id,customer_id,provider_id,package_id,status,start_date,end_date,delivery_address,
        total_paid_paise,package_price_paise,lunch_component_paise,dinner_component_paise,payment_reference)
      values(subscription,customer,provider,package,'ACTIVE',date '2026-09-13',date '2026-09-19',address,0,0,0,0,'DEMO:'||run||':'||subscription::text);
      for offset_day in 0..6 loop
        day:=date '2026-09-13'+offset_day;
        foreach slot in array array['LUNCH'::public.meal_slot,'DINNER'::public.meal_slot] loop
          select array_agg(id order by name,id) into items from (
            select distinct i.id,i.name from public.provider_menus m
            join public.menu_days md on md.menu_id=m.id and md.day_of_week=extract(isodow from day)::smallint and md.meal_slot=slot
            join public.menu_day_choices c on c.menu_day_id=md.id and c.choice_group='MAIN_COURSE'
            join public.menu_items i on i.id=c.menu_item_id where m.provider_id=provider and m.status='APPROVED'
          ) choices;
          if coalesce(cardinality(items),0)=0 then raise exception 'No approved main course for % % %',provider,day,slot; end if;
          item:=items[1+((customer_number-1)%cardinality(items))];
          meal_status:=case when customer_number=12 then 'CANCELLED'
            when customer_number=11 or (slot='DINNER' and customer_number=10) then 'PAUSED' else 'SCHEDULED' end;
          insert into public.subscription_meals(subscription_id,provider_id,customer_id,service_date,meal_slot,selected_menu_item_id,
            status,meal_value_paise,delivery_address,wallet_charged_at)
          values(subscription,provider,customer,day,slot,item,meal_status,0,address,now());
        end loop;
      end loop;
    end loop;
  end loop;
  if (select count(*) from public.customer_subscriptions where payment_reference='DEMO:'||run||':'||id::text)<>24 then raise exception 'Unexpected subscription count'; end if;
  if (select count(*) from public.subscription_meals where delivery_address->>'demo_run_id'=run)<>336 then raise exception 'Unexpected meal count'; end if;
  if exists(select 1 from public.subscription_meals where delivery_address->>'demo_run_id'=run and (meal_value_paise<>0 or wallet_charged_at is null)) then raise exception 'Demo billing safety failed'; end if;
  insert into public.audit_logs(action,entity_type,entity_id,after_data)
  values('DEMO_KITCHEN_DATA_CREATED','demo_run',run,jsonb_build_object('customers',24,'meals',336,'start_date','2026-09-13','end_date','2026-09-19','money_paise',0));
end
$demo$;
commit;
