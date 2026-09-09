-- Real customer meal reviews and customer-visible delivery assignments.

create table if not exists public.customer_meal_reviews (
  id uuid primary key default gen_random_uuid(),
  meal_id uuid not null references public.subscription_meals(id) on delete cascade,
  subscription_id uuid not null references public.customer_subscriptions(id) on delete cascade,
  provider_id uuid not null references public.providers(id) on delete cascade,
  customer_id uuid not null references public.profiles(id) on delete cascade,
  menu_item_id uuid references public.menu_items(id) on delete set null,
  rating smallint not null check (rating between 1 and 5),
  category_ratings jsonb not null default '{}'::jsonb,
  tags text[] not null default '{}',
  review_text text not null default '' check (char_length(review_text) <= 500),
  is_anonymous boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique(meal_id),
  check (jsonb_typeof(category_ratings) = 'object')
);

create index if not exists customer_meal_reviews_provider_created_idx
  on public.customer_meal_reviews(provider_id, created_at desc);
create index if not exists customer_meal_reviews_customer_created_idx
  on public.customer_meal_reviews(customer_id, created_at desc);

alter table public.customer_meal_reviews enable row level security;
drop policy if exists customer_meal_reviews_owner_read on public.customer_meal_reviews;
create policy customer_meal_reviews_owner_read on public.customer_meal_reviews
  for select to authenticated using(customer_id=auth.uid());

create or replace function public.customer_meal_experience_feed(target_limit integer default 60)
returns jsonb language sql stable security definer set search_path=public as $$
  select jsonb_build_object(
    'items',coalesce(jsonb_agg(jsonb_build_object(
      'meal_id',x.meal_id,
      'subscription_id',x.subscription_id,
      'provider_id',x.provider_id,
      'provider_name',x.provider_name,
      'service_date',x.service_date,
      'meal_slot',x.meal_slot,
      'status',x.status,
      'item_id',x.item_id,
      'item_name',x.item_name,
      'description',x.description,
      'delivery_person_name',x.delivery_person_name,
      'delivery_person_phone',x.delivery_person_phone,
      'delivered_at',x.delivered_at,
      'rating',x.rating,
      'category_ratings',x.category_ratings,
      'tags',x.tags,
      'review_text',x.review_text,
      'is_anonymous',x.is_anonymous,
      'reviewed_at',x.reviewed_at
    ) order by x.service_date desc,x.meal_slot),'[]'::jsonb)
  )
  from (
    select sm.id meal_id,sm.subscription_id,sm.provider_id,p.display_name provider_name,
      sm.service_date,sm.meal_slot,sm.status,mi.id item_id,coalesce(mi.name,'') item_name,
      coalesce(mi.description,'') description,dp.full_name delivery_person_name,
      dp.phone delivery_person_phone,sm.delivered_at,r.rating,r.category_ratings,r.tags,
      r.review_text,r.is_anonymous,r.updated_at reviewed_at
    from public.subscription_meals sm
    join public.providers p on p.id=sm.provider_id
    left join public.menu_items mi on mi.id=sm.selected_menu_item_id
    left join public.provider_delivery_personnel dp on dp.id=sm.delivery_personnel_id and dp.is_active
    left join public.customer_meal_reviews r on r.meal_id=sm.id
    where sm.customer_id=auth.uid()
      and sm.service_date between current_date-30 and current_date+7
    order by sm.service_date desc,sm.meal_slot
    limit least(greatest(coalesce(target_limit,60),1),100)
  ) x;
$$;

create or replace function public.customer_submit_meal_review(
  target_meal_id uuid,
  target_rating integer,
  target_category_ratings jsonb default '{}'::jsonb,
  target_tags text[] default '{}',
  target_review_text text default '',
  target_is_anonymous boolean default false
) returns jsonb language plpgsql security definer set search_path=public as $$
declare meal_record public.subscription_meals%rowtype; saved public.customer_meal_reviews%rowtype;
begin
  if target_rating not between 1 and 5 then raise exception 'Choose a rating from 1 to 5'; end if;
  if char_length(coalesce(target_review_text,''))>500 then raise exception 'Review must be 500 characters or fewer'; end if;
  if coalesce(jsonb_typeof(target_category_ratings),'object')<>'object' then raise exception 'Category ratings must be an object'; end if;

  select * into meal_record from public.subscription_meals
    where id=target_meal_id and customer_id=auth.uid();
  if meal_record.id is null then raise exception 'Meal was not found'; end if;
  if meal_record.status<>'DELIVERED' then raise exception 'Only delivered meals can be reviewed'; end if;

  insert into public.customer_meal_reviews(
    meal_id,subscription_id,provider_id,customer_id,menu_item_id,rating,
    category_ratings,tags,review_text,is_anonymous
  ) values(
    meal_record.id,meal_record.subscription_id,meal_record.provider_id,meal_record.customer_id,
    meal_record.selected_menu_item_id,target_rating,coalesce(target_category_ratings,'{}'::jsonb),
    coalesce(target_tags,'{}'),trim(coalesce(target_review_text,'')),coalesce(target_is_anonymous,false)
  )
  on conflict(meal_id) do update set
    rating=excluded.rating,category_ratings=excluded.category_ratings,tags=excluded.tags,
    review_text=excluded.review_text,is_anonymous=excluded.is_anonymous,updated_at=now()
  returning * into saved;

  insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data)
  values(auth.uid(),'CUSTOMER_MEAL_REVIEW_SAVED','customer_meal_review',saved.id::text,
    jsonb_build_object('meal_id',saved.meal_id,'provider_id',saved.provider_id,'rating',saved.rating));
  return jsonb_build_object('saved',true,'review_id',saved.id,'meal_id',saved.meal_id,'rating',saved.rating,'updated_at',saved.updated_at);
end; $$;

create or replace function public.admin_provider_meal_reviews(target_provider uuid,target_limit integer default 100)
returns jsonb language plpgsql stable security definer set search_path=public as $$
begin
  if not exists(
    select 1 from public.admin_staff_profiles
    where user_id=auth.uid()
      and staff_role in ('SUPER_ADMIN','ADMINISTRATOR','OPERATIONS_MANAGER','CUSTOMER_SUPPORT','CATALOGUE_REVIEWER')
  ) then raise exception 'Administrator access is required'; end if;

  return jsonb_build_object(
    'summary',(select jsonb_build_object(
      'review_count',count(*),'average_rating',coalesce(round(avg(rating)::numeric,2),0),
      'five_star_count',count(*) filter(where rating=5),'one_two_star_count',count(*) filter(where rating<=2)
    ) from public.customer_meal_reviews where provider_id=target_provider),
    'items',coalesce((select jsonb_agg(jsonb_build_object(
      'id',r.id,'meal_id',r.meal_id,'rating',r.rating,'category_ratings',r.category_ratings,
      'tags',r.tags,'review_text',r.review_text,'is_anonymous',r.is_anonymous,
      'service_date',sm.service_date,'meal_slot',sm.meal_slot,'item_name',coalesce(mi.name,''),
      'customer_name',case when r.is_anonymous then 'Anonymous customer' else coalesce(pr.full_name,'Customer') end,
      'created_at',r.created_at,'updated_at',r.updated_at
    ) order by r.updated_at desc)
    from (select * from public.customer_meal_reviews where provider_id=target_provider order by updated_at desc limit least(greatest(coalesce(target_limit,100),1),200)) r
    join public.subscription_meals sm on sm.id=r.meal_id
    left join public.menu_items mi on mi.id=r.menu_item_id
    left join public.profiles pr on pr.id=r.customer_id),'[]'::jsonb)
  );
end; $$;

revoke all on function public.customer_meal_experience_feed(integer),public.customer_submit_meal_review(uuid,integer,jsonb,text[],text,boolean),public.admin_provider_meal_reviews(uuid,integer) from public,anon;
grant execute on function public.customer_meal_experience_feed(integer),public.customer_submit_meal_review(uuid,integer,jsonb,text[],text,boolean),public.admin_provider_meal_reviews(uuid,integer) to authenticated;
