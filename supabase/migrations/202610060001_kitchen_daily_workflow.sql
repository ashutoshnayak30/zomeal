-- Monthly commitments recur as unpaid records. They never debit a wallet/bank.
alter table public.provider_kitchen_entries add column repeat_from_month date;
-- Start existing monthly commitments next month, without rewriting past books.
update public.provider_kitchen_entries set recurring=true,revision=revision+1,updated_at=now()
where kind='EXPENSE' and cost_type='FIXED' and source_template is null and voided_at is null
and entry_date>=date_trunc('month',now() at time zone 'Asia/Kolkata')::date;
update public.provider_kitchen_entries set repeat_from_month=(date_trunc('month',now() at time zone 'Asia/Kolkata')+interval '1 month')::date
where recurring and source_template is null;

create function public.kitchen_monthly_entry_defaults() returns trigger language plpgsql set search_path=public as $$
begin
 if new.source_template is null and new.kind='EXPENSE' and new.cost_type='FIXED' then
  if tg_op='INSERT' then new.recurring:=true;
  elsif old.cost_type<>'FIXED' then new.recurring:=true; end if;
 end if;
 if new.recurring and new.repeat_from_month is null then
  new.repeat_from_month:=greatest((date_trunc('month',new.entry_date::timestamp)+interval '1 month')::date,
   (date_trunc('month',now() at time zone 'Asia/Kolkata')+interval '1 month')::date);
 end if;
 return new;
end $$;
create trigger kitchen_monthly_defaults before insert or update on public.provider_kitchen_entries
for each row execute function public.kitchen_monthly_entry_defaults();
revoke all on function public.kitchen_monthly_entry_defaults() from public,anon,authenticated;

create function public.kitchen_ensure_month(p uuid,m date) returns void
language plpgsql security definer set search_path=public as $$
declare root provider_kitchen_entries; latest provider_kitchen_entries; new_id uuid;
begin
 if m is null or m<>date_trunc('month',m)::date or m>date_trunc('month',now() at time zone 'Asia/Kolkata')::date then raise exception 'Invalid repeat month'; end if;
 perform 1 from providers where id=p for update;
 for root in select * from provider_kitchen_entries where provider_id=p and recurring and source_template is null
  and voided_at is null and repeat_from_month<=m and entry_date<m order by id loop
  -- Includes removed copies: removing a month is an intentional skip.
  if exists(select 1 from provider_kitchen_entries where provider_id=p and source_template=root.id and entry_date>=m and entry_date<m+interval '1 month') then continue; end if;
  select * into latest from provider_kitchen_entries where provider_id=p and (id=root.id or source_template=root.id)
   and voided_at is null and entry_date<m order by entry_date desc,updated_at desc limit 1;
  insert into provider_kitchen_entries(id,provider_id,entry_date,kind,category,item,amount_paise,cost_type,quantity,unit,paid,supplier,note,source_template,created_by)
  values(gen_random_uuid(),p,m,'EXPENSE',latest.category,latest.item,latest.amount_paise,latest.cost_type,latest.quantity,latest.unit,false,latest.supplier,
   'Automatic monthly estimate — verify amount and payment',root.id,root.created_by)
  on conflict(provider_id,source_template,entry_date) do nothing returning id into new_id;
  if new_id is not null then insert into audit_logs(actor_id,action,entity_type,entity_id)
   values(auth.uid(),'KITCHEN_MONTH_AUTO_CARRIED','provider_kitchen_entries',new_id::text); end if;
 end loop;
end $$;
revoke all on function public.kitchen_ensure_month(uuid,date) from public,anon,authenticated;

create function public.provider_kitchen_repeat_setting(root_id uuid,expected_revision integer,enabled boolean) returns jsonb
language plpgsql security definer set search_path=public as $$
declare p uuid:=kitchen_book_provider(); begin
 if enabled is null then raise exception 'Choose repeat on or off'; end if;
 perform 1 from providers where id=p for update;
 update provider_kitchen_entries set recurring=enabled,revision=revision+1,updated_at=now(),
 repeat_from_month=case when enabled then (date_trunc('month',now() at time zone 'Asia/Kolkata')+interval '1 month')::date else repeat_from_month end
 where id=root_id and provider_id=p and source_template is null and kind='EXPENSE' and voided_at is null and revision=expected_revision;
 if not found then raise exception 'Monthly setting changed. Refresh and try again'; end if;
 insert into audit_logs(actor_id,action,entity_type,entity_id,after_data) values(auth.uid(),'KITCHEN_REPEAT_SETTING','provider_kitchen_entries',root_id::text,jsonb_build_object('enabled',enabled));
 return jsonb_build_object('saved',true);
end $$;
revoke all on function public.provider_kitchen_repeat_setting(uuid,integer,boolean) from public,anon;
grant execute on function public.provider_kitchen_repeat_setting(uuid,integer,boolean) to authenticated;

alter function public.provider_kitchen_statement(date) rename to kitchen_statement_snapshot;
revoke all on function public.kitchen_statement_snapshot(date) from public,anon,authenticated;
create function public.provider_kitchen_statement(target_month date) returns jsonb
language plpgsql volatile security definer set search_path=public as $$
declare p uuid:=kitchen_book_provider(); m date:=date_trunc('month',target_month)::date;
 today date:=(now() at time zone 'Asia/Kolkata')::date; book jsonb; s jsonb;
 days integer; elapsed integer; fixed bigint; allocated bigint; costs bigint; income bigint; monthly jsonb;
begin
 if m is null or m<'2020-01-01' or m>date_trunc('month',today)::date then raise exception 'Choose a valid month'; end if;
 perform kitchen_ensure_month(p,m);
 book:=kitchen_statement_snapshot(m);s:=book->'statement';
 days:=extract(day from m+interval '1 month - 1 day');
 elapsed:=case when m=date_trunc('month',today)::date then extract(day from today)::integer else days end;
 -- Ingredients are consumed using stock values, never also prorated as overhead.
 select coalesce(sum(amount_paise),0) into fixed from provider_kitchen_entries where provider_id=p and voided_at is null and kind='EXPENSE' and cost_type='FIXED'
 and category not in('Grains','Vegetables','Meat & eggs','Oil & spices','Dairy') and entry_date>=m and entry_date<m+interval '1 month';
 allocated:=round(fixed::numeric*elapsed/days);costs:=(s->>'operating_costs_paise')::bigint-fixed+allocated;
 income:=(s->>'net_paise')::bigint+coalesce((book->'trend'->-1->>'other_sales_paise')::bigint,0);
 select coalesce(jsonb_agg(to_jsonb(e)||jsonb_build_object('repeat_root',r.id,'repeat_revision',r.revision,'repeat_enabled',coalesce(r.recurring,false),'repeat_from',r.repeat_from_month) order by e.category,e.item),'[]')
 into monthly from provider_kitchen_entries e left join provider_kitchen_entries r on r.id=coalesce(e.source_template,e.id) and r.provider_id=p and r.voided_at is null
 where e.provider_id=p and e.voided_at is null and e.kind='EXPENSE' and e.cost_type='FIXED' and e.entry_date>=m and e.entry_date<m+interval '1 month';
 return book||jsonb_build_object('monthly_costs',monthly,'statement',s||jsonb_build_object(
 'days_in_month',days,'elapsed_days',elapsed,'allocated_fixed_paise',allocated,'monthly_overhead_paise',fixed,
 'fixed_per_day_paise',round(fixed::numeric/days),'costs_to_date_paise',costs,'profit_to_date_paise',income-costs,
 'is_current_month',m=date_trunc('month',today)::date));
end $$;
revoke all on function public.provider_kitchen_statement(date) from public,anon;
grant execute on function public.provider_kitchen_statement(date) to authenticated;
