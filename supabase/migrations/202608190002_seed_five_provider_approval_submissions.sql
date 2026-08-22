-- Five realistic provider submissions for customer-catalogue testing in 751030.
-- They intentionally remain PENDING_APPROVAL. The normal admin approval action
-- approves their service area, prices, dishes and weekly menu before customers
-- can discover them.

do $$
declare
  staff_id uuid;
  provider_index integer;
  day_number integer;
  slot_value public.meal_slot;
  seed_provider_id uuid;
  seed_menu_id uuid;
  seed_menu_day_id uuid;
  seed_package_id uuid;
  seed_veg_item_id uuid;
  seed_nonveg_item_id uuid;
  seed_carb_item_id uuid;
  seed_side_item_id uuid;
  provider_names text[]:=array[
    'Maa Annapurna Kitchen','Kalinga Home Meals','Green Bowl Tiffins',
    'Odisha Rasoi Club','Patia Family Kitchen'
  ];
  provider_slugs text[]:=array[
    'test-maa-annapurna-751030','test-kalinga-home-meals-751030','test-green-bowl-751030',
    'test-odisha-rasoi-751030','test-patia-family-kitchen-751030'
  ];
  veg_lunch text[]:=array['Paneer Masala','Chana Dal Curry','Soya Aloo Curry','Mushroom Masala','Mix Vegetable Korma','Rajma Masala','Palak Paneer'];
  nonveg_lunch text[]:=array['Home-style Chicken Curry','Fish Besara','Egg Masala','Chicken Kassa','Rohu Curry','Chicken Dalcha','Egg Curry'];
  veg_dinner text[]:=array['Dal Makhani','Kadai Paneer','Chana Masala','Vegetable Kofta','Matar Paneer','Soya Keema','Seasonal Mix Veg'];
  nonveg_dinner text[]:=array['Chicken Masala','Egg Tadka','Fish Curry','Chicken Stew','Egg Bhurji Curry','Chicken Do Pyaza','Fish Masala'];
begin
  select ur.user_id into staff_id
  from public.user_roles ur
  where ur.role='ADMIN'
  order by ur.granted_at
  limit 1;
  if staff_id is null then
    raise exception 'Create an ADMIN user before loading the five provider test submissions';
  end if;

  insert into public.pincodes(code,locality,city,district,state,is_enabled)
  values('751030','Khandagiri','Bhubaneswar','Khordha','Odisha',true)
  on conflict(code) do update set locality=excluded.locality,city=excluded.city,state=excluded.state,is_enabled=true;

  for provider_index in 1..5 loop
    seed_provider_id:=md5('zomeal-test-provider-'||provider_index)::uuid;
    seed_menu_id:=md5('zomeal-test-provider-menu-'||provider_index)::uuid;

    insert into public.providers(
      id,legal_name,display_name,slug,status,dietary_type,description,
      contact_person_name,support_phone,business_address_line,business_city,business_state,business_pincode
    ) values(
      seed_provider_id,provider_names[provider_index],provider_names[provider_index],provider_slugs[provider_index],
      'PENDING_APPROVAL','BOTH','Test provider offering fresh vegetarian and non-vegetarian monthly meals.',
      'Test Partner '||provider_index,'70000000'||lpad(provider_index::text,2,'0'),
      'Test Kitchen '||provider_index||', Khandagiri', 'Bhubaneswar','Odisha','751030'
    ) on conflict(id) do update set
      display_name=excluded.display_name,status='PENDING_APPROVAL',dietary_type='BOTH',
      description=excluded.description,business_pincode='751030',approved_by=null,approved_at=null;

    insert into public.provider_service_areas(provider_id,pincode,status,delivery_radius_km,requested_by,effective_from)
    values(seed_provider_id,'751030','PENDING',8,staff_id,current_date)
    on conflict(provider_id,pincode) do update set status='PENDING',requested_by=staff_id,approved_by=null,approved_at=null;

    insert into public.provider_delivery_personnel(provider_id,full_name,phone,is_primary,is_active,created_by)
    values(seed_provider_id,'Test Delivery Partner '||provider_index,'80000000'||lpad(provider_index::text,2,'0'),true,true,staff_id)
    on conflict(provider_id,phone) do update set is_primary=true,is_active=true;

    -- Three independently approvable packages.
    seed_package_id:=md5('zomeal-test-package-'||provider_index||'-LUNCH_ONLY')::uuid;
    insert into public.packages(id,provider_id,name,description,kind,dietary_type,duration_days,is_active,lunch_delivery_start,lunch_delivery_end)
    values(seed_package_id,seed_provider_id,'Lunch Only','Lunch delivery 12 PM–2 PM','LUNCH_ONLY','BOTH',30,false,'12:00','14:00')
    on conflict(id) do update set is_active=false;
    insert into public.package_price_versions(package_id,version,total_price_paise,lunch_value_paise,dinner_value_paise,status,requested_by)
    values(seed_package_id,1,(1900+provider_index*100)*100,(1900+provider_index*100)*100,0,'PENDING',staff_id)
    on conflict(package_id,version) do nothing;

    seed_package_id:=md5('zomeal-test-package-'||provider_index||'-DINNER_ONLY')::uuid;
    insert into public.packages(id,provider_id,name,description,kind,dietary_type,duration_days,is_active,dinner_delivery_start,dinner_delivery_end)
    values(seed_package_id,seed_provider_id,'Dinner Only','Dinner delivery 7 PM–9 PM','DINNER_ONLY','BOTH',30,false,'19:00','21:00')
    on conflict(id) do update set is_active=false;
    insert into public.package_price_versions(package_id,version,total_price_paise,lunch_value_paise,dinner_value_paise,status,requested_by)
    values(seed_package_id,1,(1700+provider_index*100)*100,0,(1700+provider_index*100)*100,'PENDING',staff_id)
    on conflict(package_id,version) do nothing;

    seed_package_id:=md5('zomeal-test-package-'||provider_index||'-LUNCH_AND_DINNER')::uuid;
    insert into public.packages(id,provider_id,name,description,kind,dietary_type,duration_days,is_active,lunch_delivery_start,lunch_delivery_end,dinner_delivery_start,dinner_delivery_end)
    values(seed_package_id,seed_provider_id,'Lunch + Dinner','Two meals every day','LUNCH_AND_DINNER','BOTH',30,false,'12:00','14:00','19:00','21:00')
    on conflict(id) do update set is_active=false;
    insert into public.package_price_versions(package_id,version,total_price_paise,lunch_value_paise,dinner_value_paise,status,requested_by)
    values(seed_package_id,1,(3300+provider_index*150)*100,(1850+provider_index*75)*100,(1450+provider_index*75)*100,'PENDING',staff_id)
    on conflict(package_id,version) do nothing;

    insert into public.provider_menus(id,provider_id,name,description,status,valid_from,submitted_at,created_by)
    values(seed_menu_id,seed_provider_id,'Test weekly menu','Two main-course alternatives per slot: one vegetarian and one non-vegetarian.','PENDING_REVIEW',current_date,now(),staff_id)
    on conflict(id) do update set status='PENDING_REVIEW',submitted_at=now(),reviewed_by=null,reviewed_at=null;

    for day_number in 1..7 loop
      foreach slot_value in array array['LUNCH'::public.meal_slot,'DINNER'::public.meal_slot] loop
        seed_menu_day_id:=md5('zomeal-test-menu-day-'||provider_index||'-'||day_number||'-'||slot_value)::uuid;
        insert into public.menu_days(id,menu_id,day_of_week,meal_slot,is_available,selection_note)
        values(seed_menu_day_id,seed_menu_id,day_number,slot_value,true,'Choose one main course')
        on conflict(id) do update set is_available=true;

        seed_veg_item_id:=md5('zomeal-test-item-'||provider_index||'-'||day_number||'-'||slot_value||'-veg')::uuid;
        seed_nonveg_item_id:=md5('zomeal-test-item-'||provider_index||'-'||day_number||'-'||slot_value||'-nonveg')::uuid;
        seed_carb_item_id:=md5('zomeal-test-item-'||provider_index||'-'||slot_value||'-carb')::uuid;
        -- Lunch and dinner share the same fixed-side catalogue item. Keeping one
        -- provider-level ID also satisfies menu_items(provider_id, name).
        seed_side_item_id:=md5('zomeal-test-item-'||provider_index||'-shared-side')::uuid;

        insert into public.menu_items(id,provider_id,name,description,category,dietary_type,status,ingredients,submitted_at,created_by)
        values(
          seed_veg_item_id,seed_provider_id,
          (case when slot_value='LUNCH' then veg_lunch[day_number] else veg_dinner[day_number] end)||' · '||provider_index,
          'Vegetarian home-style main course','MAIN_COURSE','VEG','PENDING_REVIEW','{}',now(),staff_id
        ) on conflict(id) do update set status='PENDING_REVIEW',dietary_type='VEG',reviewed_by=null,reviewed_at=null;
        insert into public.menu_items(id,provider_id,name,description,category,dietary_type,status,ingredients,submitted_at,created_by)
        values(
          seed_nonveg_item_id,seed_provider_id,
          (case when slot_value='LUNCH' then nonveg_lunch[day_number] else nonveg_dinner[day_number] end)||' · '||provider_index,
          'Non-vegetarian home-style main course','MAIN_COURSE','NON_VEG','PENDING_REVIEW','{}',now(),staff_id
        ) on conflict(id) do update set status='PENDING_REVIEW',dietary_type='NON_VEG',reviewed_by=null,reviewed_at=null;
        insert into public.menu_items(id,provider_id,name,category,dietary_type,status,submitted_at,created_by)
        values(seed_carb_item_id,seed_provider_id,case when slot_value='LUNCH' then 'Rice / Roti' else 'Roti / Paratha' end,'CARB','VEG','PENDING_REVIEW',now(),staff_id)
        on conflict(id) do update set status='PENDING_REVIEW',reviewed_by=null,reviewed_at=null;
        insert into public.menu_items(id,provider_id,name,category,dietary_type,status,submitted_at,created_by)
        values(seed_side_item_id,seed_provider_id,'Dal · seasonal bhaja · salad · achar','SIDE','VEG','PENDING_REVIEW',now(),staff_id)
        on conflict(id) do update set status='PENDING_REVIEW',reviewed_by=null,reviewed_at=null;

        insert into public.menu_day_choices(menu_day_id,menu_item_id,choice_group,is_default,is_required,is_changeable,min_select,max_select,display_order)
        values
          (seed_menu_day_id,seed_veg_item_id,'MAIN_COURSE',true,true,true,1,1,1),
          (seed_menu_day_id,seed_nonveg_item_id,'MAIN_COURSE',false,true,true,1,1,2),
          (seed_menu_day_id,seed_carb_item_id,'CARB',true,true,true,1,1,3),
          (seed_menu_day_id,seed_side_item_id,'SIDE',true,true,false,1,1,4)
        on conflict(menu_day_id,menu_item_id) do update set is_default=excluded.is_default,is_changeable=excluded.is_changeable,display_order=excluded.display_order;
      end loop;
    end loop;
  end loop;
end;
$$;
