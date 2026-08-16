-- Provider operations: delivery personnel, directory/readiness and admin media.

create table public.provider_delivery_personnel (
  id uuid primary key default gen_random_uuid(),
  provider_id uuid not null references public.providers(id) on delete cascade,
  full_name text,
  phone text not null,
  is_primary boolean not null default false,
  is_active boolean not null default true,
  created_by uuid not null references public.profiles(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique(provider_id,phone),
  constraint delivery_phone_valid check(phone ~ '^[6-9][0-9]{9}$')
);
create unique index one_primary_delivery_person on public.provider_delivery_personnel(provider_id) where is_primary and is_active;
create trigger provider_delivery_personnel_updated before update on public.provider_delivery_personnel for each row execute function public.set_updated_at();
alter table public.provider_delivery_personnel enable row level security;
create policy staff_delivery_people_read on public.provider_delivery_personnel for select using(public.has_role('ADMIN') or public.has_role('OPERATIONS') or public.is_provider_member(provider_id));
create policy provider_delivery_people_write on public.provider_delivery_personnel for all using(public.is_provider_member(provider_id)) with check(public.is_provider_member(provider_id));

create or replace function public.require_delivery_person_for_activation()
returns trigger language plpgsql set search_path=public as $$
begin
  if new.status='ACTIVE' and old.status is distinct from 'ACTIVE' and not exists(
    select 1 from public.provider_delivery_personnel where provider_id=new.id and is_active
  ) then raise exception 'At least one active delivery phone number is required'; end if;
  return new;
end; $$;
create trigger providers_require_delivery_person before update of status on public.providers
for each row execute function public.require_delivery_person_for_activation();

create or replace function public.admin_save_delivery_people(target_provider_id uuid,people jsonb)
returns void language plpgsql security definer set search_path=public as $$
declare person jsonb;
begin
  perform public.require_staff(array['ADMIN','OPERATIONS']::public.app_role[]);
  if jsonb_array_length(coalesce(people,'[]'::jsonb))=0 then raise exception 'At least one delivery phone number is required'; end if;
  delete from public.provider_delivery_personnel where provider_id=target_provider_id;
  for person in select value from jsonb_array_elements(people) loop
    insert into public.provider_delivery_personnel(provider_id,full_name,phone,is_primary,is_active,created_by)
    values(target_provider_id,nullif(trim(person->>'name'),''),person->>'phone',coalesce((person->>'is_primary')::boolean,false),true,auth.uid());
  end loop;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data)
  values(auth.uid(),'DELIVERY_PERSONNEL_UPDATED','providers',target_provider_id::text,people);
end; $$;

create or replace function public.admin_provider_directory(status_filter text default null,search_text text default null)
returns table(id uuid,display_name text,status text,dietary_type text,phone text,city text,pincodes text[],package_count bigint,delivery_people bigint,created_at timestamptz)
language sql stable security definer set search_path=public as $$
  select p.id,p.display_name,p.status::text,p.dietary_type::text,p.support_phone,p.business_city,
    coalesce(array_agg(distinct a.pincode) filter(where a.pincode is not null),'{}'),
    count(distinct pk.id),count(distinct dp.id),p.created_at
  from public.providers p
  left join public.provider_service_areas a on a.provider_id=p.id
  left join public.packages pk on pk.provider_id=p.id
  left join public.provider_delivery_personnel dp on dp.provider_id=p.id and dp.is_active
  where (status_filter is null or p.status::text=status_filter)
    and (search_text is null or p.display_name ilike '%'||search_text||'%' or p.support_phone like '%'||search_text||'%')
    and (public.has_role('ADMIN') or public.has_role('OPERATIONS'))
  group by p.id order by p.created_at desc;
$$;

create or replace function public.admin_register_provider_media(target_provider_id uuid,target_menu_item_id uuid,media_kind public.media_type,
  object_path text,mime text,bytes bigint,alt_text_value text,is_primary_value boolean default false)
returns uuid language plpgsql security definer set search_path=public as $$
declare media_id uuid;
begin
  perform public.require_staff(array['ADMIN','OPERATIONS']::public.app_role[]);
  insert into public.provider_media(provider_id,media_type,status,storage_bucket,storage_path,mime_type,size_bytes,alt_text,menu_item_id,is_primary,uploaded_by,submitted_at,reviewed_by,reviewed_at)
  values(target_provider_id,media_kind,'APPROVED','provider-media',object_path,mime,bytes,alt_text_value,target_menu_item_id,is_primary_value,auth.uid(),now(),auth.uid(),now()) returning id into media_id;
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data,metadata)
  values(auth.uid(),'ADMIN_MEDIA_UPLOADED','provider_media',media_id::text,jsonb_build_object('path',object_path,'type',media_kind),jsonb_build_object('provider_id',target_provider_id));
  return media_id;
end; $$;

create policy staff_provider_storage_insert on storage.objects for insert to authenticated with check(
  bucket_id='provider-media' and (public.has_role('ADMIN') or public.has_role('OPERATIONS'))
);
create policy staff_provider_storage_read on storage.objects for select to authenticated using(
  bucket_id in ('provider-media','provider-documents') and (public.has_role('ADMIN') or public.has_role('OPERATIONS'))
);

-- Extend the live workspace with delivery personnel and activation readiness.
create or replace function public.admin_provider_workspace(target_id uuid)
returns jsonb language plpgsql stable security definer set search_path=public as $$
begin
  perform public.require_staff(array['ADMIN','OPERATIONS']::public.app_role[]);
  return (select jsonb_build_object('provider',to_jsonb(p),
    'readiness',public.provider_activation_check(p.id),
    'service_areas',coalesce((select jsonb_agg(to_jsonb(a) order by a.pincode) from public.provider_service_areas a where a.provider_id=p.id),'[]'::jsonb),
    'delivery_people',coalesce((select jsonb_agg(to_jsonb(d) order by d.is_primary desc,d.created_at) from public.provider_delivery_personnel d where d.provider_id=p.id and d.is_active),'[]'::jsonb),
    'packages',coalesce((select jsonb_agg(jsonb_build_object('id',pk.id,'name',pk.name,'kind',pk.kind,'duration_days',pk.duration_days,'delivery_time',pk.description,'is_active',pk.is_active,'prices',coalesce((select jsonb_agg(to_jsonb(v) order by v.version desc) from public.package_price_versions v where v.package_id=pk.id),'[]'::jsonb))) from public.packages pk where pk.provider_id=p.id),'[]'::jsonb),
    'menus',coalesce((select jsonb_agg(jsonb_build_object('id',m.id,'name',m.name,'status',m.status,'days',coalesce((select jsonb_agg(jsonb_build_object('id',d.id,'day',d.day_of_week,'meal_slot',d.meal_slot,'choices',coalesce((select jsonb_agg(jsonb_build_object('id',c.id,'item_id',i.id,'name',i.name,'category',c.choice_group,'changeable',c.is_changeable)) from public.menu_day_choices c join public.menu_items i on i.id=c.menu_item_id where c.menu_day_id=d.id),'[]'::jsonb)) order by d.day_of_week,d.meal_slot) from public.menu_days d where d.menu_id=m.id),'[]'::jsonb))) from public.provider_menus m where m.provider_id=p.id),'[]'::jsonb),
    'media',coalesce((select jsonb_agg(to_jsonb(pm) order by pm.created_at desc) from public.provider_media pm where pm.provider_id=p.id),'[]'::jsonb)
  ) from public.providers p where p.id=target_id);
end; $$;

revoke all on function public.admin_save_delivery_people(uuid,jsonb),public.admin_provider_directory(text,text),public.admin_register_provider_media(uuid,uuid,public.media_type,text,text,bigint,text,boolean) from public;
grant execute on function public.admin_save_delivery_people(uuid,jsonb),public.admin_provider_directory(text,text),public.admin_register_provider_media(uuid,uuid,public.media_type,text,text,bigint,text,boolean) to authenticated;
