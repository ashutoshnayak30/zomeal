-- Customer-controlled transition from a seven-day trial to a monthly package.
-- The monthly plan starts after the current trial, avoiding overlapping meals.

create table if not exists public.customer_package_changes (
  id uuid primary key default gen_random_uuid(),
  customer_id uuid not null references public.profiles(id) on delete cascade,
  current_subscription_id uuid not null references public.customer_subscriptions(id) on delete cascade,
  provider_id uuid not null references public.providers(id) on delete cascade,
  target_package_id uuid not null references public.packages(id),
  target_price_version_id uuid not null references public.package_price_versions(id),
  effective_date date not null,
  status text not null default 'SCHEDULED' check(status in('SCHEDULED','APPLIED','CANCELLED','FAILED')),
  failure_reason text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  applied_at timestamptz
);
create unique index if not exists one_scheduled_customer_package_change
  on public.customer_package_changes(current_subscription_id) where status='SCHEDULED';
create index if not exists customer_package_changes_due_idx
  on public.customer_package_changes(status,effective_date);
alter table public.customer_package_changes enable row level security;
drop policy if exists customer_package_changes_owner_read on public.customer_package_changes;
create policy customer_package_changes_owner_read on public.customer_package_changes for select to authenticated using(customer_id=auth.uid());
revoke all on public.customer_package_changes from anon,authenticated;
grant select on public.customer_package_changes to authenticated;

create or replace function public.customer_schedule_monthly_upgrade(target_subscription uuid,target_package uuid)
returns jsonb language plpgsql security definer set search_path=public as $$
declare current_sub public.customer_subscriptions; current_package public.packages; next_package public.packages;
  next_price public.package_price_versions; change_id uuid; starts_on date;
begin
  select * into current_sub from public.customer_subscriptions where id=target_subscription and customer_id=auth.uid() for update;
  if current_sub.id is null then raise exception 'Active subscription was not found'; end if;
  if current_sub.status not in('ACTIVE','PAUSED') then raise exception 'This subscription cannot be upgraded now'; end if;
  select * into current_package from public.packages where id=current_sub.package_id;
  if current_package.duration_days<>7 then raise exception 'Only a weekly trial can be upgraded from this screen'; end if;
  select * into next_package from public.packages where id=target_package and provider_id=current_sub.provider_id and duration_days=30 and is_active;
  if next_package.id is null then raise exception 'Choose an active monthly package from your current provider'; end if;
  select * into next_price from public.package_price_versions where package_id=target_package and status='APPROVED'
    and effective_from<=now() and(effective_until is null or effective_until>now()) order by effective_from desc,version desc limit 1;
  if next_price.id is null then raise exception 'The selected monthly price is not approved'; end if;
  starts_on:=greatest(current_sub.end_date+1,current_date+1);
  update public.customer_package_changes set status='CANCELLED',updated_at=now()
    where current_subscription_id=current_sub.id and status='SCHEDULED';
  insert into public.customer_package_changes(customer_id,current_subscription_id,provider_id,target_package_id,target_price_version_id,effective_date)
  values(auth.uid(),current_sub.id,current_sub.provider_id,next_package.id,next_price.id,starts_on) returning id into change_id;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,before_data,after_data)
  values(auth.uid(),'CUSTOMER_MONTHLY_UPGRADE_SCHEDULED','customer_subscription',current_sub.id::text,
    jsonb_build_object('package_id',current_sub.package_id,'end_date',current_sub.end_date),
    jsonb_build_object('change_id',change_id,'package_id',next_package.id,'effective_date',starts_on,'price_paise',next_price.total_price_paise));
  return jsonb_build_object('id',change_id,'status','SCHEDULED','package_id',next_package.id,'package_name',next_package.name,
    'package_kind',next_package.kind,'duration_days',30,'price_paise',next_price.total_price_paise,'effective_date',starts_on);
end; $$;

create or replace function public.customer_cancel_monthly_upgrade(target_subscription uuid)
returns jsonb language plpgsql security definer set search_path=public as $$
declare changed integer;
begin
  update public.customer_package_changes set status='CANCELLED',updated_at=now()
  where current_subscription_id=target_subscription and customer_id=auth.uid() and status='SCHEDULED';
  get diagnostics changed=row_count;
  if changed=0 then raise exception 'No scheduled monthly upgrade was found'; end if;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id) values(auth.uid(),'CUSTOMER_MONTHLY_UPGRADE_CANCELLED','customer_subscription',target_subscription::text);
  return jsonb_build_object('cancelled',true,'subscription_id',target_subscription);
end; $$;

create or replace function public.process_due_customer_package_changes(target_date date default current_date)
returns jsonb language plpgsql security definer set search_path=public as $$
declare change_record record; sub public.customer_subscriptions; pkg public.packages; price public.package_price_versions;
  new_template_id uuid; source_template uuid; day_index integer; slot_value public.meal_slot; selected_item uuid;
  service_day date; day_number integer; component_total bigint; daily_base bigint; daily_remainder bigint;
  applied integer:=0; failed integer:=0;
begin
  for change_record in select * from public.customer_package_changes where status='SCHEDULED' and effective_date<=target_date order by effective_date,created_at for update skip locked loop
    begin
      select * into sub from public.customer_subscriptions where id=change_record.current_subscription_id for update;
      select * into pkg from public.packages where id=change_record.target_package_id and provider_id=change_record.provider_id and duration_days=30 and is_active;
      select * into price from public.package_price_versions where id=change_record.target_price_version_id and package_id=pkg.id and status='APPROVED';
      if sub.id is null or pkg.id is null or price.id is null then raise exception 'Subscription or approved monthly package is no longer available'; end if;
      if sub.status in('CANCELLED','CANCEL_PENDING') then raise exception 'The weekly subscription was cancelled'; end if;

      select id into source_template from public.customer_weekly_menu_templates where customer_id=sub.customer_id and is_active order by updated_at desc limit 1;
      update public.customer_weekly_menu_templates set is_active=false where customer_id=sub.customer_id and is_active;
      insert into public.customer_weekly_menu_templates(customer_id,package_id,name,dietary_preference,is_active)
      values(sub.customer_id,pkg.id,'Monthly package menu',pkg.dietary_type,true) returning id into new_template_id;
      if source_template is not null then
        insert into public.customer_weekly_menu_selections(template_id,day_of_week,meal_slot,choice_group,menu_item_id)
        select new_template_id,selection.day_of_week,selection.meal_slot,selection.choice_group,selection.menu_item_id
        from public.customer_weekly_menu_selections selection join public.menu_items item on item.id=selection.menu_item_id
        where selection.template_id=source_template and item.provider_id=sub.provider_id and item.status='APPROVED'
          and not((pkg.kind='LUNCH_ONLY' and selection.meal_slot='DINNER')or(pkg.kind='DINNER_ONLY' and selection.meal_slot='LUNCH'))
        on conflict do nothing;
      end if;
      for day_index in 1..7 loop
        foreach slot_value in array (case pkg.kind when 'LUNCH_ONLY' then array['LUNCH'::public.meal_slot] when 'DINNER_ONLY' then array['DINNER'::public.meal_slot] else array['LUNCH'::public.meal_slot,'DINNER'::public.meal_slot] end) loop
          if not exists(select 1 from public.customer_weekly_menu_selections selection where selection.template_id=new_template_id and selection.day_of_week=day_index and selection.meal_slot=slot_value and selection.choice_group='MAIN_COURSE') then
            selected_item:=public.default_menu_item_for_day(sub.provider_id,change_record.effective_date+(day_index-1),slot_value);
            if selected_item is null then raise exception 'No approved % menu exists for weekday %',lower(slot_value::text),day_index; end if;
            insert into public.customer_weekly_menu_selections(template_id,day_of_week,meal_slot,choice_group,menu_item_id)
            values(new_template_id,day_index,slot_value,'MAIN_COURSE',selected_item);
          end if;
        end loop;
      end loop;

      update public.customer_subscriptions set package_id=pkg.id,price_version_id=price.id,start_date=change_record.effective_date,
        end_date=change_record.effective_date+29,package_price_paise=price.total_price_paise,lunch_component_paise=price.lunch_value_paise,
        dinner_component_paise=price.dinner_value_paise,commission_basis_points=public.current_provider_commission_basis_points(sub.provider_id),
        status=case when sub.pause_reason='INSUFFICIENT_WALLET' then 'PAUSED' else 'ACTIVE' end,activated_at=now(),updated_at=now()
      where id=sub.id;

      for day_number in 1..30 loop
        service_day:=change_record.effective_date+(day_number-1);
        foreach slot_value in array (case pkg.kind when 'LUNCH_ONLY' then array['LUNCH'::public.meal_slot] when 'DINNER_ONLY' then array['DINNER'::public.meal_slot] else array['LUNCH'::public.meal_slot,'DINNER'::public.meal_slot] end) loop
          component_total:=case slot_value when 'LUNCH' then price.lunch_value_paise else price.dinner_value_paise end;
          daily_base:=component_total/30;daily_remainder:=component_total%30;
          insert into public.subscription_meals(subscription_id,provider_id,customer_id,service_date,meal_slot,selected_menu_item_id,status,meal_value_paise,delivery_address)
          values(sub.id,sub.provider_id,sub.customer_id,service_day,slot_value,
            public.subscription_menu_item_for_day(sub.customer_id,pkg.id,sub.provider_id,service_day,slot_value),
            case when sub.pause_reason='INSUFFICIENT_WALLET' then 'PAUSED' else 'SCHEDULED' end,
            daily_base+case when day_number<=daily_remainder then 1 else 0 end,sub.delivery_address)
          on conflict(subscription_id,service_date,meal_slot) do update set provider_id=excluded.provider_id,selected_menu_item_id=excluded.selected_menu_item_id,
            status=excluded.status,meal_value_paise=excluded.meal_value_paise,delivery_address=excluded.delivery_address,updated_at=now();
        end loop;
      end loop;
      update public.customer_package_changes set status='APPLIED',applied_at=now(),updated_at=now() where id=change_record.id;
      insert into public.customer_notifications(customer_id,category,title,message,destination,dedupe_key)
      values(sub.customer_id,'Subscription','Your monthly plan is active',pkg.name||' has started. Meal charges will continue from your Zomeal wallet.','plan','MONTHLY_UPGRADE_'||change_record.id::text)
      on conflict(customer_id,dedupe_key) do nothing;
      insert into public.audit_logs(action,entity_type,entity_id,after_data) values('CUSTOMER_MONTHLY_UPGRADE_APPLIED','customer_subscription',sub.id::text,
        jsonb_build_object('change_id',change_record.id,'package_id',pkg.id,'start_date',change_record.effective_date,'end_date',change_record.effective_date+29));
      applied:=applied+1;
    exception when others then
      update public.customer_package_changes set status='FAILED',failure_reason=left(sqlerrm,500),updated_at=now() where id=change_record.id;
      failed:=failed+1;
    end;
  end loop;
  return jsonb_build_object('applied',applied,'failed',failed,'processed_date',target_date);
end; $$;

-- Enrich the restored active plan so Home can start image downloads immediately.
alter function public.customer_active_subscription_state() rename to customer_active_subscription_state_before_media_and_upgrades;
revoke all on function public.customer_active_subscription_state_before_media_and_upgrades() from public,anon,authenticated;
create function public.customer_active_subscription_state()
returns jsonb language plpgsql stable security definer set search_path=public as $$
declare result jsonb; active_subscription_id uuid; active_provider_id uuid; weekly jsonb; daily jsonb; scheduled jsonb;
begin
  result:=public.customer_active_subscription_state_before_media_and_upgrades();
  if not coalesce((result->>'has_active_subscription')::boolean,false) then return result; end if;
  active_subscription_id:=(result->'subscription'->>'id')::uuid;active_provider_id:=(result->'subscription'->>'provider_id')::uuid;
  select coalesce(jsonb_agg(row_value||jsonb_build_object('photo_path',(select media.storage_path from public.provider_media media
    where media.provider_id=active_provider_id and media.menu_item_id=nullif(row_value->>'item_id','')::uuid and media.status='APPROVED'
    order by media.is_primary desc,media.display_order,media.created_at desc limit 1))),'[]'::jsonb)
    into weekly from jsonb_array_elements(coalesce(result->'weekly_menu','[]'::jsonb)) row_value;
  select coalesce(jsonb_agg(row_value||jsonb_build_object('photo_path',(select media.storage_path from public.provider_media media
    where media.provider_id=active_provider_id and media.menu_item_id=nullif(row_value->>'item_id','')::uuid and media.status='APPROVED'
    order by media.is_primary desc,media.display_order,media.created_at desc limit 1))),'[]'::jsonb)
    into daily from jsonb_array_elements(coalesce(result->'daily_meals','[]'::jsonb)) row_value;
  select jsonb_build_object('id',change.id,'status',change.status,'effective_date',change.effective_date,'package_id',pkg.id,
    'package_name',pkg.name,'package_kind',pkg.kind,'duration_days',pkg.duration_days,'price_paise',price.total_price_paise)
    into scheduled from public.customer_package_changes change join public.packages pkg on pkg.id=change.target_package_id
    join public.package_price_versions price on price.id=change.target_price_version_id
    where change.current_subscription_id=active_subscription_id and change.status='SCHEDULED' order by change.created_at desc limit 1;
  return jsonb_set(jsonb_set(jsonb_set(result,'{weekly_menu}',weekly),'{daily_meals}',daily),'{subscription}',
    result->'subscription'||jsonb_build_object(
      'primary_photo_path',(select media.storage_path from public.provider_media media where media.provider_id=active_provider_id and media.status='APPROVED' and media.media_type in('PROVIDER_LOGO','OWNER_PROFILE','PACKAGE_COVER','MEAL') order by case media.media_type when 'PROVIDER_LOGO' then 1 when 'OWNER_PROFILE' then 2 when 'MEAL' then 3 else 4 end,media.is_primary desc,media.created_at desc limit 1),
      'kitchen_photo_path',(select media.storage_path from public.provider_media media where media.provider_id=active_provider_id and media.status='APPROVED' and media.media_type='KITCHEN' order by media.is_primary desc,media.created_at desc limit 1),
      'meal_photo_path',(select media.storage_path from public.provider_media media where media.provider_id=active_provider_id and media.status='APPROVED' and media.media_type in('MEAL','PACKAGE_COVER') order by media.is_primary desc,media.created_at desc limit 1),
      'scheduled_package_change',scheduled));
end; $$;

revoke all on function public.customer_schedule_monthly_upgrade(uuid,uuid),public.customer_cancel_monthly_upgrade(uuid),
  public.process_due_customer_package_changes(date),public.customer_active_subscription_state() from public,anon;
grant execute on function public.customer_schedule_monthly_upgrade(uuid,uuid),public.customer_cancel_monthly_upgrade(uuid),public.customer_active_subscription_state() to authenticated;
grant execute on function public.process_due_customer_package_changes(date) to service_role;

create extension if not exists pg_cron with schema extensions;
do $$ declare existing_job bigint; begin
  select jobid into existing_job from cron.job where jobname='zomeal-monthly-package-upgrades';
  if existing_job is not null then perform cron.unschedule(existing_job); end if;
  perform cron.schedule('zomeal-monthly-package-upgrades','*/15 * * * *','select public.process_due_customer_package_changes();');
end $$;
notify pgrst,'reload schema';
