-- Customer profile edits and confirmed relocation. Never accept a customer ID
-- or wallet balance from the handset.
create or replace function public.customer_profile_details()
returns jsonb language plpgsql stable security definer set search_path=public as $$
begin
  if auth.uid() is null then raise exception 'Sign in to view your profile'; end if;
  return (select jsonb_build_object('full_name',p.full_name,'phone',coalesce(u.phone,p.phone),
    'pincode',coalesce(a.pincode,p.registration_pincode),'address',to_jsonb(a))
    from public.profiles p join auth.users u on u.id=p.id
    left join lateral(select * from public.customer_addresses where customer_id=p.id order by is_default desc,updated_at desc limit 1) a on true
    where p.id=auth.uid() and p.is_active);
end; $$;

create or replace function public.customer_update_profile(target_full_name text)
returns jsonb language plpgsql security definer set search_path=public as $$
begin
  if auth.uid() is null then raise exception 'Sign in to edit your profile'; end if;
  if length(trim(coalesce(target_full_name,''))) not between 2 and 100 then raise exception 'Enter your full name (2–100 characters)'; end if;
  update public.profiles set full_name=trim(target_full_name),updated_at=now() where id=auth.uid() and is_active;
  if not found then raise exception 'Active profile not found'; end if;
  return public.customer_profile_details();
end; $$;

create or replace function public.customer_validated_address(target_address jsonb)
returns jsonb language plpgsql stable security definer set search_path=public as $$
declare pin public.pincodes; field text; result jsonb:='{}';
begin
  if auth.uid() is null then raise exception 'Sign in to edit your address'; end if;
  if coalesce(target_address->>'pincode','') !~ '^[1-9][0-9]{5}$' then raise exception 'Enter a valid six-digit pincode'; end if;
  select * into pin from public.pincodes where code=target_address->>'pincode' and is_enabled;
  if not found then raise exception 'This pincode is not serviceable'; end if;
  foreach field in array array['house','street','locality'] loop
    if length(trim(coalesce(target_address->>field,''))) not between 1 and 200 then raise exception 'Complete the % field (maximum 200 characters)',field; end if;
    result:=result||jsonb_build_object(field,trim(target_address->>field));
  end loop;
  if length(coalesce(target_address->>'landmark',''))>200 then raise exception 'Landmark must be under 200 characters'; end if;
  return result||jsonb_build_object('landmark',trim(coalesce(target_address->>'landmark','')),'pincode',pin.code,'city',pin.city,'state',pin.state);
end; $$;

create or replace function public.customer_store_address(target_address jsonb)
returns void language plpgsql security definer set search_path=public as $$
begin
  insert into public.customer_addresses(customer_id,house,street,locality,landmark,pincode,city,state,is_default)
  values(auth.uid(),target_address->>'house',target_address->>'street',target_address->>'locality',target_address->>'landmark',
    target_address->>'pincode',target_address->>'city',target_address->>'state',true)
  on conflict(customer_id) where is_default do update set house=excluded.house,street=excluded.street,locality=excluded.locality,
    landmark=excluded.landmark,pincode=excluded.pincode,city=excluded.city,state=excluded.state,updated_at=now();
  update public.profiles set registration_pincode=target_address->>'pincode',updated_at=now() where id=auth.uid();
end; $$;

create or replace function public.customer_update_delivery_address(target_address jsonb)
returns jsonb language plpgsql security definer set search_path=public as $$
declare address jsonb; current_pin text; local_now timestamp:=now() at time zone 'Asia/Kolkata';
begin
  if auth.uid() is null then raise exception 'Sign in to edit your address'; end if;
  perform 1 from public.profiles where id=auth.uid() and is_active for update;
  if not found then raise exception 'Active profile not found'; end if;
  address:=public.customer_validated_address(target_address);
  current_pin:=public.customer_profile_details()->>'pincode';
  if current_pin is distinct from address->>'pincode' then raise exception 'Use Change pincode to select providers, package and menu before moving'; end if;
  if exists(select 1 from public.customer_subscriptions s where s.customer_id=auth.uid() and s.status in('ACTIVE','PAUSED') and s.end_date>=local_now::date
    and not exists(select 1 from public.provider_service_areas a where a.provider_id=s.provider_id and a.pincode=current_pin and a.status='APPROVED'
      and (a.effective_from is null or a.effective_from<=local_now::date) and (a.effective_until is null or a.effective_until>=local_now::date))) then
    raise exception 'Your provider no longer serves this pincode. Use Change pincode to choose an available kitchen'; end if;
  perform public.customer_store_address(address);
  update public.customer_subscriptions set delivery_address=address,updated_at=now()
    where customer_id=auth.uid() and status in('ACTIVE','PAUSED') and end_date>=local_now::date;
  update public.subscription_meals set delivery_address=address,updated_at=now()
    where customer_id=auth.uid() and status in('SCHEDULED','PAUSED') and wallet_charged_at is null
    and (service_date>local_now::date or (service_date=local_now::date and local_now::time<case meal_slot when 'LUNCH' then time '08:00' else time '16:00' end));
  return public.customer_profile_details();
end; $$;

create table public.customer_relocations(
  previous_subscription_id uuid primary key references public.customer_subscriptions(id),
  new_subscription_id uuid not null references public.customer_subscriptions(id),
  customer_id uuid not null references public.profiles(id),
  created_at timestamptz not null default now()
);
alter table public.customer_relocations enable row level security;
revoke all on public.customer_relocations from anon,authenticated;

create or replace function public.customer_relocate_subscription(
  target_subscription uuid,replacement_provider uuid,replacement_package uuid,
  target_weekly_menu jsonb,target_address jsonb,target_start_date date,expected_price_paise bigint
) returns jsonb language plpgsql security definer set search_path=public as $$
declare old_sub public.customer_subscriptions; pkg public.packages; price public.package_price_versions;
  address jsonb; new_sub uuid; new_template_id uuid; slot_value public.meal_slot; day_index integer; item_id uuid;
  service_day date; item_name text; component bigint; balance bigint; first_charge bigint; paused boolean; existing uuid;
  local_today date:=(now() at time zone 'Asia/Kolkata')::date;
begin
  if auth.uid() is null then raise exception 'Sign in before changing pincode'; end if;
  perform 1 from public.profiles where id=auth.uid() and is_active for update;
  if not found then raise exception 'Active profile not found'; end if;
  select * into old_sub from public.customer_subscriptions where id=target_subscription and customer_id=auth.uid() for update;
  if not found then raise exception 'Subscription not found'; end if;
  select new_subscription_id into existing from public.customer_relocations where previous_subscription_id=old_sub.id and customer_id=auth.uid();
  if existing is not null then return jsonb_build_object('subscription_id',existing,'already_applied',true); end if;
  if old_sub.status not in('ACTIVE','PAUSED') then raise exception 'This subscription cannot be moved'; end if;
  if target_start_date is null or target_start_date<local_today+1 or target_start_date>local_today+30 then raise exception 'Choose a start date from tomorrow to 30 days ahead'; end if;
  address:=public.customer_validated_address(target_address);
  select * into pkg from public.packages where id=replacement_package and provider_id=replacement_provider and is_active and duration_days in(7,30) for share;
  if not found then raise exception 'Choose an available weekly or monthly package'; end if;
  if not exists(select 1 from public.customer_marketplace(address->>'pincode') p where p.provider_id=replacement_provider
    and exists(select 1 from jsonb_array_elements(p.packages) x where x->>'id'=replacement_package::text)) then raise exception 'This kitchen/package is no longer available at the new pincode'; end if;
  if not exists(select 1 from public.provider_service_areas a where a.provider_id=replacement_provider and a.pincode=address->>'pincode' and a.status='APPROVED'
    and (a.effective_from is null or a.effective_from<=target_start_date) and (a.effective_until is null or a.effective_until>=target_start_date+pkg.duration_days-1)) then raise exception 'Provider coverage does not include the selected period'; end if;
  select * into price from public.package_price_versions where package_id=pkg.id and status='APPROVED' and effective_from<=now()
    and (effective_until is null or effective_until>now()) order by effective_from desc,version desc limit 1 for share;
  if not found or price.total_price_paise is distinct from expected_price_paise then raise exception 'Package price changed. Reopen the package screen and confirm the latest price'; end if;
  if exists(select 1 from public.subscription_meals where subscription_id=old_sub.id and service_date>=target_start_date
    and (status in('PREPARING','PACKING','READY','OUT_FOR_DELIVERY','DELIVERED') or wallet_charged_at is not null)) then raise exception 'Some meals are already committed. Choose a later start date'; end if;
  if exists(select 1 from public.customer_subscriptions where customer_id=auth.uid() and id<>old_sub.id and status in('ACTIVE','PAUSED') and end_date>=target_start_date) then raise exception 'Another subscription already covers that period'; end if;

  update public.customer_weekly_menu_templates set is_active=false where customer_id=auth.uid() and is_active;
  insert into public.customer_weekly_menu_templates(customer_id,package_id,name,dietary_preference,is_active)
    values(auth.uid(),pkg.id,'New pincode weekly menu',pkg.dietary_type,true) returning id into new_template_id;
  for day_index in 1..7 loop
    foreach slot_value in array case pkg.kind when 'LUNCH_ONLY' then array['LUNCH'::public.meal_slot] when 'DINNER_ONLY' then array['DINNER'::public.meal_slot] else array['LUNCH'::public.meal_slot,'DINNER'::public.meal_slot] end loop
      item_name:=nullif(trim(target_weekly_menu->lower(slot_value::text)->>((day_index-1)::text)),'');
      item_id:=null;
      select i.id into item_id from public.provider_menus m join public.menu_days d on d.menu_id=m.id and d.day_of_week=day_index and d.meal_slot=slot_value and d.is_available
        join public.menu_day_choices c on c.menu_day_id=d.id and c.choice_group='MAIN_COURSE'
        join public.menu_items i on i.id=c.menu_item_id and i.status='APPROVED'
        where m.provider_id=replacement_provider and m.status='APPROVED' and m.valid_from<=target_start_date and (m.valid_until is null or m.valid_until>=target_start_date+pkg.duration_days-1)
          and lower(trim(i.name))=lower(item_name) order by m.valid_from desc limit 1;
      if item_id is null then raise exception 'Choose an approved % main course for weekday %',slot_value,day_index; end if;
      insert into public.customer_weekly_menu_selections(template_id,day_of_week,meal_slot,choice_group,menu_item_id)
        values(new_template_id,day_index,slot_value,'MAIN_COURSE',item_id);
    end loop;
  end loop;
  select balance_paise into balance from public.customer_wallets where customer_id=auth.uid() for update;
  component:=case pkg.kind when 'DINNER_ONLY' then price.dinner_value_paise else price.lunch_value_paise end;
  first_charge:=component/pkg.duration_days+case when component%pkg.duration_days>0 then 1 else 0 end;
  paused:=coalesce(balance,0)<first_charge;
  insert into public.customer_subscriptions(customer_id,provider_id,package_id,price_version_id,status,pause_reason,start_date,end_date,
    delivery_address,total_paid_paise,package_price_paise,lunch_component_paise,dinner_component_paise,commission_basis_points,activated_at)
    values(auth.uid(),replacement_provider,pkg.id,price.id,case when paused then 'PAUSED' else 'ACTIVE' end,
      case when paused then 'INSUFFICIENT_WALLET' end,target_start_date,target_start_date+pkg.duration_days-1,address,0,
      price.total_price_paise,price.lunch_value_paise,price.dinner_value_paise,public.current_provider_commission_basis_points(replacement_provider),now()) returning id into new_sub;
  for day_index in 1..pkg.duration_days loop
    service_day:=target_start_date+day_index-1;
    foreach slot_value in array case pkg.kind when 'LUNCH_ONLY' then array['LUNCH'::public.meal_slot] when 'DINNER_ONLY' then array['DINNER'::public.meal_slot] else array['LUNCH'::public.meal_slot,'DINNER'::public.meal_slot] end loop
      component:=case slot_value when 'LUNCH' then price.lunch_value_paise else price.dinner_value_paise end;
      select s.menu_item_id into item_id from public.customer_weekly_menu_selections s where s.template_id=new_template_id and s.day_of_week=extract(isodow from service_day)::integer and s.meal_slot=slot_value;
      insert into public.subscription_meals(subscription_id,provider_id,customer_id,service_date,meal_slot,selected_menu_item_id,status,meal_value_paise,delivery_address)
        values(new_sub,replacement_provider,auth.uid(),service_day,slot_value,item_id,case when paused then 'PAUSED' else 'SCHEDULED' end,
          component/pkg.duration_days+case when day_index<=component%pkg.duration_days then 1 else 0 end,address);
    end loop;
  end loop;
  update public.subscription_meals set status='CANCELLED',updated_at=now() where subscription_id=old_sub.id and service_date>=target_start_date and status in('SCHEDULED','PAUSED');
  -- Keep already-committed old meals billable under their original snapshots.
  update public.customer_subscriptions set end_date=case when start_date<target_start_date then least(end_date,target_start_date-1) else end_date end,
    status=case when start_date>=target_start_date then 'CANCELLED' else status end,updated_at=now() where id=old_sub.id;
  update public.customer_package_changes set status='CANCELLED',updated_at=now() where current_subscription_id=old_sub.id and status='SCHEDULED';
  perform public.customer_store_address(address);
  insert into public.customer_relocations values(old_sub.id,new_sub,auth.uid(),now());
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,before_data,after_data)
    values(auth.uid(),'CUSTOMER_PINCODE_RELOCATED','customer_subscription',new_sub::text,to_jsonb(old_sub),
      jsonb_build_object('previous_subscription',old_sub.id,'pincode',address->>'pincode','start_date',target_start_date,'duration_days',pkg.duration_days,'wallet_unchanged',true));
  return jsonb_build_object('subscription_id',new_sub,'start_date',target_start_date,'end_date',target_start_date+pkg.duration_days-1,'wallet_unchanged',true,'paused',paused);
end; $$;
revoke all on function public.customer_validated_address(jsonb),public.customer_store_address(jsonb) from public,anon,authenticated;
revoke all on function public.customer_profile_details(),public.customer_update_profile(text),public.customer_update_delivery_address(jsonb),
  public.customer_relocate_subscription(uuid,uuid,uuid,jsonb,jsonb,date,bigint) from public;
grant execute on function public.customer_profile_details(),public.customer_update_profile(text),public.customer_update_delivery_address(jsonb),
  public.customer_relocate_subscription(uuid,uuid,uuid,jsonb,jsonb,date,bigint) to authenticated;
notify pgrst,'reload schema';
