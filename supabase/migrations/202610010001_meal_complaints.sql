create table public.meal_complaints(
 id uuid primary key default gen_random_uuid(),
 meal_id uuid not null unique references public.subscription_meals(id),
 customer_id uuid not null references public.profiles(id),
 provider_id uuid not null references public.providers(id),
 issue text not null check(issue in ('Food quality','Wrong food','Missing items','Quantity','Late or missing delivery','Food safety','Other')),
 details text not null check(length(trim(details)) between 10 and 1000),
 photo_paths text[] not null default '{}',
 status text not null default 'CALLBACK_REQUESTED' check(status in ('CALLBACK_REQUESTED','IN_REVIEW','RESOLVED','REFUNDED')),
 admin_note text not null default '',
 refund_paise bigint not null default 0 check(refund_paise>=0),
 created_at timestamptz not null default now(),updated_at timestamptz not null default now()
);
alter table public.meal_complaints enable row level security;
revoke all on public.meal_complaints from anon,authenticated;
create function public.customer_meal_complaint(target_meal uuid,target_issue text default null,target_details text default null,target_photos text[] default '{}')
returns jsonb language plpgsql security definer set search_path=public as $$
declare m subscription_meals; c meal_complaints; photo text;
begin
 select * into m from subscription_meals where id=target_meal and customer_id=auth.uid();
 if m.id is null then raise exception 'Meal not found'; end if;
 select * into c from meal_complaints where meal_id=m.id;
 if target_issue is not null and c.id is null then
  if m.service_date>(now() at time zone 'Asia/Kolkata')::date then raise exception 'Future meals cannot be reported yet'; end if;
  if cardinality(target_photos)>3 then raise exception 'Maximum three photos'; end if;
  foreach photo in array target_photos loop
   if photo not like auth.uid()::text||'/'||m.id::text||'/%' or not exists(select 1 from storage.objects where bucket_id='complaint-evidence' and name=photo) then raise exception 'Invalid evidence photo'; end if;
  end loop;
  insert into meal_complaints(meal_id,customer_id,provider_id,issue,details,photo_paths)
  values(m.id,m.customer_id,m.provider_id,target_issue,trim(target_details),target_photos)
  on conflict(meal_id) do nothing;
  select * into c from meal_complaints where meal_id=m.id;
 end if;
 return jsonb_build_object('complaint',case when c.id is null then null else to_jsonb(c)-'admin_note' end);
end $$;

insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
values('complaint-evidence','complaint-evidence',false,2097152,array['image/jpeg']) on conflict(id) do nothing;
create policy complaint_photo_upload on storage.objects for insert to authenticated with check(
 bucket_id='complaint-evidence' and (storage.foldername(name))[1]=auth.uid()::text
 and exists(select 1 from public.subscription_meals m where m.id::text=(storage.foldername(name))[2] and m.customer_id=auth.uid()));
create function public.can_read_complaint_photo(target_path text) returns boolean language sql stable security definer set search_path=public as $$
 select exists(select 1 from meal_complaints c where target_path=any(c.photo_paths) and is_provider_member(c.provider_id));
$$;
revoke all on function public.can_read_complaint_photo(text) from public,anon;
grant execute on function public.can_read_complaint_photo(text) to authenticated;
create policy complaint_photo_read on storage.objects for select to authenticated using(
 bucket_id='complaint-evidence' and (
 (storage.foldername(name))[1]=auth.uid()::text or public.can_manage_accounts()
 or public.can_read_complaint_photo(name)));

create function public.meal_feedback_feed(target_offset integer default 0) returns jsonb
language plpgsql stable security definer set search_path=public as $$
declare admin boolean:=public.can_manage_accounts();
begin
 return jsonb_build_object('items',coalesce((select jsonb_agg(to_jsonb(x)) from (
  select sm.id meal_id,p.display_name provider_name,sm.provider_id,sm.service_date,sm.meal_slot,sm.status meal_status,
   sm.meal_value_paise,coalesce(mi.name,'Meal') item_name,
   case when r.is_anonymous then 'Anonymous customer' else coalesce(pr.full_name,'Customer') end customer_name,
   case when admin then coalesce(nullif(pr.phone,''),(select u.phone from auth.users u where u.id=pr.id)) else null end customer_phone,
   r.rating,r.category_ratings,r.review_text,r.tags,
   c.id complaint_id,c.issue,c.details,c.photo_paths,c.status,c.refund_paise,
   case when admin then c.admin_note else null end admin_note,
   coalesce(c.updated_at,r.updated_at) updated_at,
   (select storage_path from provider_media pm where pm.menu_item_id=sm.selected_menu_item_id and pm.status='APPROVED' order by pm.is_primary desc,pm.created_at desc limit 1) photo_path
  from subscription_meals sm join providers p on p.id=sm.provider_id join profiles pr on pr.id=sm.customer_id
  left join menu_items mi on mi.id=sm.selected_menu_item_id
  left join customer_meal_reviews r on r.meal_id=sm.id left join meal_complaints c on c.meal_id=sm.id
  where (r.id is not null or c.id is not null) and (admin or is_provider_member(sm.provider_id))
  order by coalesce(c.updated_at,r.updated_at) desc,sm.id limit 50 offset greatest(0,target_offset)
 )x),'[]'::jsonb));
end $$;

create function public.admin_meal_complaint_action(target_id uuid,target_status text,target_note text,target_refund bigint default 0)
returns jsonb language plpgsql security definer set search_path=public as $$
declare c meal_complaints;m subscription_meals;e provider_financial_ledger; charged bigint; refunded bigint;
begin
 if not public.can_manage_accounts() then raise exception 'Administrator access required';end if;
 if nullif(trim(target_note),'') is null then raise exception 'Record the callback discussion and decision';end if;
 select * into c from meal_complaints where id=target_id for update;
 if c.id is null then raise exception 'Complaint not found';end if;
 if c.refund_paise>0 then
  if target_status='REFUNDED' and target_refund=c.refund_paise then return jsonb_build_object('saved',true,'already_refunded',true);end if;
  raise exception 'This complaint has already been refunded';
 end if;
 if target_status not in ('IN_REVIEW','RESOLVED','REFUNDED') then raise exception 'Invalid status';end if;
 if target_status='REFUNDED' then
  if not exists(select 1 from admin_staff_profiles where user_id=auth.uid() and staff_role in ('SUPER_ADMIN','ADMINISTRATOR')) then raise exception 'Refund requires a senior administrator';end if;
  select * into m from subscription_meals where id=c.meal_id for update;
  select coalesce(-sum(amount_paise),0) into charged from customer_wallet_entries
   where customer_id=c.customer_id and entry_type='SUBSCRIPTION_DEBIT' and reference_type='subscription_meal' and reference_id=m.id::text;
  select coalesce(sum(amount_paise),0) into refunded from customer_wallet_entries where customer_id=c.customer_id and entry_type='REFUND'
   and (metadata->>'meal_id'=m.id::text or (reference_type='subscription_meal' and reference_id=m.id::text));
  if target_refund is null or target_refund<=0 or target_refund>least(charged-refunded,m.meal_value_paise) then raise exception 'Refund must not exceed the remaining amount actually charged';end if;
  select * into e from provider_financial_ledger where meal_id=m.id and entry_type='MEAL_EARNING';
  if e.id is null then raise exception 'Meal earning not recorded: finance reconciliation is required before provider-funded refund';end if;
  -- Full approved refund is provider-funded; commission is not deducted again.
  insert into provider_financial_ledger(provider_id,subscription_id,meal_id,entry_type,meal_slot,service_date,package_kind,gross_paise,commission_basis_points,commission_paise,provider_net_paise,available_at,reference_entry_id,created_by,metadata)
  values(c.provider_id,m.subscription_id,m.id,'REVERSAL',m.meal_slot,m.service_date,e.package_kind,-target_refund,0,0,-target_refund,now(),e.id,auth.uid(),jsonb_build_object('complaint_id',c.id,'reason',target_note));
  insert into customer_wallet_entries(customer_id,entry_type,amount_paise,description,reference_type,reference_id,metadata)
  values(c.customer_id,'REFUND',target_refund,'Food complaint refund after support review','meal_complaint',c.id::text,jsonb_build_object('meal_id',m.id,'provider_id',c.provider_id));
  insert into customer_wallets(customer_id,balance_paise,lifetime_credit_paise) values(c.customer_id,target_refund,target_refund)
  on conflict(customer_id) do update set balance_paise=customer_wallets.balance_paise+target_refund,lifetime_credit_paise=customer_wallets.lifetime_credit_paise+target_refund,updated_at=now();
  perform post_finance_journal('meal_complaint',c.id::text,'WALLET_REFUND',now(),'Provider-funded complaint wallet refund',null,
   jsonb_build_array(jsonb_build_object('account_code','2100','debit_paise',target_refund,'provider_id',c.provider_id),jsonb_build_object('account_code','2000','credit_paise',target_refund)),jsonb_build_object('customer_id',c.customer_id,'meal_id',m.id));
 end if;
 update meal_complaints set status=target_status,admin_note=trim(target_note),refund_paise=case when target_status='REFUNDED' then target_refund else 0 end,updated_at=now() where id=c.id;
 insert into audit_logs(actor_id,action,entity_type,entity_id,after_data) values(auth.uid(),'MEAL_COMPLAINT_REVIEWED','meal_complaint',c.id::text,jsonb_build_object('status',target_status,'refund_paise',target_refund,'note',target_note));
 return jsonb_build_object('saved',true);
end $$;
revoke all on function public.customer_meal_complaint(uuid,text,text,text[]),public.meal_feedback_feed(integer),public.admin_meal_complaint_action(uuid,text,text,bigint) from public,anon;
grant execute on function public.customer_meal_complaint(uuid,text,text,text[]),public.meal_feedback_feed(integer),public.admin_meal_complaint_action(uuid,text,text,bigint) to authenticated;

alter function public.customer_meal_experience_feed(integer) rename to customer_meal_experience_feed_base;
revoke all on function public.customer_meal_experience_feed_base(integer) from public,anon,authenticated;
create function public.customer_meal_experience_feed(target_limit integer default 60) returns jsonb
language sql stable security definer set search_path=public as $$
 select jsonb_build_object('items',coalesce(jsonb_agg(item||jsonb_build_object(
  'price_paise',sm.meal_value_paise,
  'photo_path',(select storage_path from provider_media pm where pm.menu_item_id=sm.selected_menu_item_id and pm.status='APPROVED' order by pm.is_primary desc,pm.created_at desc limit 1)
 ) order by ord),'[]'::jsonb))
 from jsonb_array_elements(customer_meal_experience_feed_base(target_limit)->'items') with ordinality as rows(item,ord)
 join subscription_meals sm on sm.id=(item->>'meal_id')::uuid and sm.customer_id=auth.uid();
$$;
revoke all on function public.customer_meal_experience_feed(integer) from public,anon;
grant execute on function public.customer_meal_experience_feed(integer) to authenticated;
